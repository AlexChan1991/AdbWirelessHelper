package com.adb.adbwirelesshelper.data.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.adb.adbwirelesshelper.domain.model.ScrcpyConfig
import com.adb.adbwirelesshelper.util.Logx
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * H.264/HEVC 硬解码封装：MediaCodec **异步回调模式** → Surface 直出。
 *
 * 三条硬规则（来自需求文档 8.6.2）：
 * 1. 必须先从 **IDR 关键帧** 开始喂，否则黑屏/花屏 —— 首个 IDR 之前的帧一律丢弃（"黑名单帧"不入队）；
 * 2. SPS/PPS 用 `BUFFER_FLAG_CODEC_CONFIG` 送入（也可在 configure 前塞进 csd-0）；
 * 3. `releaseOutputBuffer(index, true)` 直接上屏，不做二次拷贝。
 *
 * 所有回调中的异常都被收敛成 [DecoderEvent.Error] 事件上抛，绝不把 IllegalStateException 抛给调用方。
 */
class VideoDecoder {

    /** 解码器异步事件，供上层收集后决定是否需要重启/降级。 */
    sealed interface DecoderEvent {
        data class Error(val throwable: Throwable) : DecoderEvent

        /**
         * 解码输出分辨率变化：被控端旋转 / 分辨率调整时由 MediaCodec 通过
         * `INFO_OUTPUT_FORMAT_CHANGED` 上报。
         *
         * ⚠️ 这是**唯一**能拿到旋转后新分辨率的途径：scrcpy 服务端
         * `SurfaceEncoder.streamCapture()` 里的 `headerWritten` 是一次性标志，旋转重建编码器时
         * 不会重发那 12 字节流头（已核对 v3.3.2 源码）。丢了它，投屏页的等比适配、
         * 触控坐标映射、窗口方向跟随会全部停留在旧尺寸上。
         */
        data class SizeChanged(val width: Int, val height: Int) : DecoderEvent

        data object Stopped : DecoderEvent
    }

    private val _events: MutableSharedFlow<DecoderEvent> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<DecoderEvent> = _events.asSharedFlow()

    /** 异步回调里收到的可用输入缓冲区 index。 */
    private val inputQueue: LinkedBlockingQueue<Int> = LinkedBlockingQueue()

    private var codec: MediaCodec? = null
    private var started: Boolean = false
    private var seenIdr: Boolean = false
    private var submittedFrames: Long = 0L
    private var renderedFrames: Long = 0L
    private val lock: Any = Any()

    /**
     * 当前解码器绑定的输出 Surface。
     *
     * 仅用于让上层（MirrorViewModel）判断「同一个 Surface 对象是否仍被解码器持有」——
     * 详见 [setOutputSurface] 与 `attachSurface` 的换绑逻辑。
     * 用已有的 [lock] 同步，与 [codec] 保持一致的生命周期：
     * [start] 成功后置入，[stopAndRelease] 清空。
     */
    private var outputSurface: Surface? = null

    private val callback: MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            inputQueue.offer(index)
        }

        override fun onOutputBufferAvailable(
            mc: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            // 异步模式下不能再用 dequeueOutputBuffer，只能在这里取 buffer 决定是否上屏。
            renderedFrames++
            val thrown = runCatching {
                if (info.size > 0) {
                    mc.releaseOutputBuffer(index, true)
                } else {
                    mc.releaseOutputBuffer(index, false)
                }
            }.exceptionOrNull()
            if (thrown is IllegalStateException) {
                reportError(thrown)
            }
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            Logx.e(TAG, "解码器错误：${e.message}")
            reportError(e)
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            // 格式变化就是「被控端画面尺寸变了」的信号。
            //
            // ⚠️ 这里必须取「可见画面」尺寸，不能盲信 KEY_WIDTH/KEY_HEIGHT ——
            // 因为这个尺寸会被当作控制包里的 screenW/screenH 发出去，而 scrcpy 服务端
            // `Device.getPhysicalPoint()` 会把 Position 里的 screenSize 与它自己的 videoSize
            // 做**严格相等**比较，不相等就**直接丢弃这一条触控事件**（不是偏移，是整条丢掉）。
            // 部分编解码器在输出格式里报的是**对齐后**的缓冲尺寸（例：1080 报成 1088），
            // 拿它当 screenW 会让所有触控静默失效 —— 表现就是「能看不能控」。
            // 所以优先用裁剪矩形（crop）算可见尺寸，这正是 ExoPlayer 计算 VideoSize 的做法；
            // 拿不到 crop 时才退回 KEY_WIDTH/KEY_HEIGHT，并由 MirrorViewModel 的 16px 同方向
            // 容差兜底（对齐级误差会被忽略，不会覆盖掉流头里那个正确的尺寸）。
            val cropW: Int = cropSpan(format, MediaFormat.KEY_CROP_LEFT, MediaFormat.KEY_CROP_RIGHT)
            val cropH: Int = cropSpan(format, MediaFormat.KEY_CROP_TOP, MediaFormat.KEY_CROP_BOTTOM)
            val width: Int = if (cropW > 0) cropW else readIntOrZero(format, MediaFormat.KEY_WIDTH)
            val height: Int = if (cropH > 0) cropH else readIntOrZero(format, MediaFormat.KEY_HEIGHT)
            Logx.i(TAG, "解码输出格式变化：可见 ${width}x${height}，crop ${cropW}x${cropH}，coded ${readIntOrZero(format, MediaFormat.KEY_WIDTH)}x${readIntOrZero(format, MediaFormat.KEY_HEIGHT)}")
            if (width > 0 && height > 0) {
                _events.tryEmit(DecoderEvent.SizeChanged(width, height))
            }
        }
    }

    /**
     * 创建并启动解码器。
     *
     * 注意 `mime` 有默认值，调用时请使用 **命名参数**，例如
     * `start(width = w, height = h, surface = s)`。
     *
     * @param mime    解码类型，默认 H.264
     * @param width   视频宽度（来自 stream meta）
     * @param height  视频高度
     * @param surface 渲染目标 Surface（SurfaceView 提供）
     * @param csd     可选的 SPS/PPS 数据；为空时依赖首帧 config 包携带
     * @return true 表示启动成功
     */
    fun start(
        mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
        width: Int,
        height: Int,
        surface: Surface,
        csd: ByteArray? = null
    ): Boolean {
        stopAndRelease()
        if (width <= 0 || height <= 0) {
            reportError(IllegalArgumentException("非法分辨率：${width}x${height}"))
            return false
        }
        return try {
            val format: MediaFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
                // KEY_LOW_LATENCY 自 API 30 起可用，低延迟是投屏的硬性要求
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = 实时优先级
                }
                csd?.let { bytes ->
                    if (bytes.isNotEmpty()) {
                        setByteBuffer(CS_KEY_CSD0, ByteBuffer.wrap(bytes))
                    }
                }
            }

            val decoder: MediaCodec = MediaCodec.createDecoderByType(mime).apply {
                setCallback(callback)
                configure(format, surface, null, 0)
                start()
            }

            synchronized(lock) {
                codec = decoder
                started = true
                seenIdr = csd != null && csd.isNotEmpty()
                inputQueue.clear()
                outputSurface = surface
            }
            Logx.i(TAG, "解码器已启动：$mime ${width}x${height}, csd=${csd?.size ?: 0}B")
            true
        } catch (e: Exception) {
            Logx.e(TAG, "启动解码器失败：${e.message}")
            reportError(e)
            false
        }
    }

    /**
     * 送入一帧码流。
     *
     * @param data     Annex-B 裸 NALU（含起始码）
     * @param ptsUs    呈现时间戳（微秒），直接来自帧头的 PTS 字段
     * @param keyframe 是否关键帧（IDR）
     * @param config   是否 SPS/PPS 配置包
     */
    fun submit(data: ByteArray, ptsUs: Long, keyframe: Boolean, config: Boolean) {
        if (data.isEmpty()) return

        val codecRef: MediaCodec = synchronized(lock) {
            if (!started) return
            // 黑名单帧：首个 IDR（或 config 包）之前的非关键帧直接丢弃，避免黑屏/花屏
            if (!seenIdr && !config && !keyframe) {
                droppedBeforeIdr++
                return
            }
            if (config || keyframe) {
                seenIdr = true
            }
            codec
        } ?: run {
            droppedNoCodec++
            return
        }

        val index: Int = inputQueue.poll(INPUT_WAIT_MS, TimeUnit.MILLISECONDS) ?: run {
            // 解码器忙（低延迟反压），丢弃当前帧而不是堆积队列
            droppedBackPressure++
            return
        }

        val thrown = runCatching {
            val buffer: ByteBuffer = codecRef.getInputBuffer(index)
                ?: throw IllegalStateException("输入缓冲区 $index 不可用")
            buffer.clear()
            if (data.size > buffer.remaining()) {
                // 单帧大于缓冲区：宁可丢弃这一帧也不要越界写入
                codecRef.queueInputBuffer(index, 0, 0, ptsUs, 0)
                throw IllegalStateException("单帧 ${data.size}B 超过输入缓冲区 ${buffer.remaining()}B")
            }
            buffer.put(data)
            val flags: Int = if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            codecRef.queueInputBuffer(index, 0, data.size, ptsUs, flags)
            submittedFrames++
        }.exceptionOrNull()

        if (thrown is IllegalStateException) {
            reportError(thrown)
        }
    }

    /** 停止并彻底释放解码器，可重复调用。 */
    fun stopAndRelease() {
        val decoder: MediaCodec? = synchronized(lock) {
            started = false
            seenIdr = false
            inputQueue.clear()
            val ref = codec
            codec = null
            outputSurface = null
            ref
        }
        if (decoder != null) {
            runCatching { decoder.stop() }
                .onFailure { e -> Logx.w(TAG, "stop 异常：${e.message}") }
            runCatching { decoder.release() }
                .onFailure { e -> Logx.w(TAG, "release 异常：${e.message}") }
            Logx.i(TAG, "解码器已释放：提交=${submittedFrames} 渲染=${renderedFrames}")
        }
    }

    /**
     * 返回当前解码器绑定的输出 Surface（用于上层判断 Surface 对象是否已被换掉）。
     *
     * 用 [lock] 同步读取，避免与 [stopAndRelease] / [start] 的写竞争。
     * SurfaceView 在 letterbox / 频繁 configChange 时可能不先发 `surfaceDestroyed`
     * 就直接交付一个**全新的 Surface 对象**（logcat 里 `[...]#1` 即第二个 surface、
     * `producer disconnected` 即旧 Surface 的 producer 已断）。此时解码器仍绑着旧 Surface，
     * 必须靠身份比较发现并换绑，否则新帧永远喂不进新的 BufferQueue。
     */
    fun boundSurface(): Surface? = synchronized(lock) { outputSurface }

    /**
     * 不重建解码器，直接把输出 Surface 换成 [s]（API 23+ 的 [MediaCodec.setOutputSurface]）。
     *
     * 相比「stop + start 重建」：不黑屏、不丢已解码帧、不重新握手 IDR。
     * 失败（< API 23、解码器未启动、或底层拒绝）时返回 false，由上层退回重建；
     * 这里**绝不自作主张重建**，否则会与调用方的状态机重复释放/创建。
     */
    fun setOutputSurface(s: Surface): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val mc: MediaCodec = synchronized(lock) { codec } ?: return false
        val thrown = runCatching { mc.setOutputSurface(s) }.exceptionOrNull()
        if (thrown != null) {
            Logx.w(TAG, "换绑输出 Surface 失败：${thrown.javaClass.simpleName}: ${thrown.message}")
            return false
        }
        synchronized(lock) { outputSurface = s }
        Logx.i(TAG, "解码器输出 Surface 已换绑（未重建解码器）")
        return true
    }

    /** 供 UI 展示的丢帧统计（在 IDR 之前被丢弃的帧数）。 */
    fun droppedFrames(): Long = droppedBeforeIdr + droppedNoCodec + droppedBackPressure

    /** 诊断只读快照：累计提交帧数（不改变任何现有行为）。 */
    fun submittedFrames(): Long = submittedFrames

    /** 诊断只读快照：累计渲染帧数（不改变任何现有行为）。 */
    fun renderedFrames(): Long = renderedFrames

    private fun reportError(throwable: Throwable) {
        Logx.e(TAG, "解码异常：${throwable.message}")
        _events.tryEmit(DecoderEvent.Error(throwable))
    }

    /**
     * 读 MediaFormat 里的整数字段；键不存在时返回 0 而不是抛异常。
     * （`getInteger` 对缺失键会抛 IllegalArgumentException，回调线程里不能让它逃出去。）
     */
    private fun readIntOrZero(format: MediaFormat, key: String): Int =
        runCatching { format.getInteger(key) }.getOrDefault(0)

    /** 读不到就返回 null —— 与 [readIntOrZero] 的区别是能区分「值为 0」和「键不存在」。 */
    private fun readIntOrNull(format: MediaFormat, key: String): Int? =
        runCatching { format.getInteger(key) }.getOrNull()

    /**
     * 裁剪矩形的跨度（**含端点**）：`end - start + 1`。
     *
     * 任一端的键不存在（或跨度非正）就返回 0，调用方据此回退到 `KEY_WIDTH/KEY_HEIGHT`。
     * 必须两侧都在才算数：只判断 `cropLeft == 0` 会把「键缺失」误当成「左边界是 0」，
     * 于是 `0..0` 被算成宽度 1，直接把画面宽度写成 1。
     */
    private fun cropSpan(format: MediaFormat, startKey: String, endKey: String): Int {
        val start: Int = readIntOrNull(format, startKey) ?: return 0
        val end: Int = readIntOrNull(format, endKey) ?: return 0
        val span: Int = end - start + 1
        return if (span > 0) span else 0
    }

    @Volatile
    private var droppedBeforeIdr: Long = 0L

    @Volatile
    private var droppedNoCodec: Long = 0L

    @Volatile
    private var droppedBackPressure: Long = 0L

    companion object {
        private const val TAG: String = "VideoDecoder"
        private const val CS_KEY_CSD0: String = "csd-0"
        private const val INPUT_WAIT_MS: Long = 100L
    }
}

/**
 * 音频解码与回放：scrcpy 音频通道的**控制端**。
 *
 * 两条路径，由 audio socket 流头里的 codec id 决定（常量见 [ScrcpyConfig]）：
 *
 * | codec | 处理方式 | 控制端要求 |
 * |---|---|---|
 * | `raw`（默认） | **零解码直通**：收到的就是 PCM16LE，原样写 AudioTrack | 无（全版本可用） |
 * | `aac` | MediaCodec `audio/mp4a-latm` 解码 → AudioTrack | API 16+ |
 * | `opus` | MediaCodec `audio/opus` 解码 → AudioTrack | **API 29+** |
 * | `flac` | MediaCodec `audio/flac` 解码 → AudioTrack | API 21+ |
 *
 * ## 为什么默认 `raw` 而不是 scrcpy 自己的默认 `opus`
 * 本工程 minSdk = 26，而 Opus 的**解码**要 API 29 —— 拿它做默认会让 Android 8/9 控制端
 * 「开了音频却完全没声音」。`raw` 是唯一「零解码 + 全版本可用 + 延迟最低」的路径；
 * 代价是带宽（48000Hz × 2ch × 16bit ≈ 1.5 Mbps），故仍保留 aac/opus 作为省流量选项。
 *
 * ## 关键实现约束（逐条来自 scrcpy v3.3.2 服务端源码核实）
 * 1. **采样格式双方硬编码一致**：48000Hz / 2ch / PCM16LE
 *    （`AudioConfig.SAMPLE_RATE / CHANNELS / ENCODING`）。控制端不做重采样，也不做字节序转换。
 * 2. `raw` 路径服务端**不做任何字节序转换**（`AudioRawRecorder` 把 AudioRecord 的 ByteBuffer
 *    直接写上线），而且 `AudioRecordReader.read()` 固定 `BufferInfo.set(0, r, pts, 0)`
 *    —— flags 恒为 0，**raw 流里不存在 config 包**，每个包都是纯 PCM。
 * 3. **不要按 PTS 调度播放**。scrcpy 没有 A/V 时钟同步（`audio_regulator.c` 完全不引用视频
 *    时钟），音频 PTS 是设备端单调时钟、与视频 PTS 不同源。到达即写、让 AudioTrack 用自己的
 *    时钟消费，才是延迟最低且不会累积漂移的做法。
 * 4. **[submit] 绝不能阻塞**：它与视频帧处理共用上层同一个协程，一旦在里边阻塞写 AudioTrack，
 *    视频出图会被音频缓冲拖住。故 raw 路径改为「非阻塞入队 + 专职写线程」，
 *    队列满时**丢最旧的包**（宁可丢一点音频也不要让延迟无界增长）。
 * 5. 编码路径（aac/opus/flac）**必须等第一个 config 包**才能 configure：AAC 的
 *    AudioSpecificConfig 与 Opus 的 `OpusHead` 都由服务端作为 config 包（包头 bit63）发出。
 *
 * 所有异常都收敛成 [AudioEvent.Error] 上抛，绝不把异常抛给调用方 —— 音频是可降级通道。
 */
class AudioDecoder {

    /** 音频通道事件。上层据此决定展示「音频已开」还是「降级为无声投屏」。 */
    sealed interface AudioEvent {
        /**
         * 回放通道已建立并开始出声。
         *
         * @param passthrough true 表示走的是 raw 零解码直通
         */
        data class Started(
            val sampleRate: Int,
            val channels: Int,
            val passthrough: Boolean
        ) : AudioEvent

        /** 出错。调用方应**只降级音频**，不要影响视频与投屏状态机。 */
        data class Error(val throwable: Throwable) : AudioEvent

        /** 已停止（由 [stopAndRelease] 触发）。 */
        data object Stopped : AudioEvent
    }

    /** 当前走的解码路径。 */
    private enum class Mode {
        /** [start] 尚未调用，或已 [stopAndRelease]。 */
        NONE,

        /** raw：字节直接进 AudioTrack。 */
        PASSTHROUGH,

        /** aac / opus / flac：经 MediaCodec 解码后再进 AudioTrack。 */
        CODEC
    }

    private val _events: MutableSharedFlow<AudioEvent> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<AudioEvent> = _events.asSharedFlow()

    private val lock: Any = Any()

    @Volatile
    private var running: Boolean = false

    /**
     * 当前解码路径。
     *
     * ⚠️ `@Volatile` 是必需的，不是保险：[submit] 由 socket 读协程调用，而 [start] /
     * [stopAndRelease] 可能由**另一条线程**调用（`MirrorViewModel` 收到 [AudioEvent.Error]
     * 后会在 main 上直接 `stopAndRelease()`）。普通字段在这种跨线程读写下没有 happens-before，
     * [submit] 可能读到**已经失效的旧值**，例如停止之后仍按 PASSTHROUGH 继续往已释放的
     * AudioTrack 里写。
     */
    @Volatile
    private var mode: Mode = Mode.NONE

    private var audioTrack: AudioTrack? = null
    private var codec: MediaCodec? = null

    // ---- raw 直通路径 ----
    /**
     * 待写队列。生产者是 [submit]（上层协程），消费者是 [rawWriter]。
     * 同样必须 `@Volatile`：`stopAndRelease` 会在别的线程把它置 null，而 [enqueueRaw] 要立刻看见。
     */
    @Volatile
    private var rawQueue: ArrayBlockingQueue<ByteArray>? = null
    private var rawWriter: Thread? = null
    private var rawDropped: Long = 0L

    // ---- 编码路径 ----
    /** 已 [start] 但还没收到 config 包时的目标 mime；configure 成功后置 null。 */
    private var pendingMime: String? = null

    /** 供日志/报错展示的 codec 名（"AAC" 等）。在别的线程读（`@Volatile` 只求可见性，不要求原子）。 */
    @Volatile
    private var codecLabel: String = ""

    /** 只用于「等不到 config 包」的看门狗计数，与 [codecDropped]（解码器忙）分开统计。 */
    private var unconfiguredDropped: Long = 0L

    private var codecConfigured: Boolean = false
    private var codecDrain: Thread? = null
    private var codecDropped: Long = 0L

    /** 编码路径下 [start] 记下的目标缓冲，等 config 包到了才真正建 AudioTrack。 */
    @Volatile
    private var targetBufferMs: Int = 0

    /**
     * 建立音频回放通道。
     *
     * @param codecId audio socket 流头里读到的大端 codec id
     *                （[ScrcpyConfig.AUDIO_CODEC_ID_RAW] / `_AAC` / `_OPUS` / `_FLAC`）
     * @param bufferMs 目标缓冲（毫秒），来自 [ScrcpyConfig.audioBufferMs]；会被夹到
     *                 [MIN_BUFFER_MS]..[MAX_BUFFER_MS] 之间
     * @return true 表示可以开始收包。**注意**：编码路径下这一步只是「参数可接受」，
     *         真正 configure 要等第一个 config 包（见类 KDoc 第 5 条），
     *         所以返回 true 不代表此刻已经在出声
     */
    fun start(codecId: Int, bufferMs: Int): Boolean {
        // 幂等的基础：先彻底清掉上一次的通道，避免两个 AudioTrack 抢放（表现为杂音）
        stopAndRelease()
        val safeBufferMs: Int = bufferMs.coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS)

        return when (codecId) {
            ScrcpyConfig.AUDIO_CODEC_ID_RAW -> startPassthrough(safeBufferMs)

            ScrcpyConfig.AUDIO_CODEC_ID_AAC ->
                prepareCodec(MediaFormat.MIMETYPE_AUDIO_AAC, "AAC", safeBufferMs)

            ScrcpyConfig.AUDIO_CODEC_ID_FLAC ->
                prepareCodec(MediaFormat.MIMETYPE_AUDIO_FLAC, "FLAC", safeBufferMs)

            ScrcpyConfig.AUDIO_CODEC_ID_OPUS -> {
                // 显式拦一道并给出可读原因：否则失败会沉在 MediaCodec 的
                // "Failed to create codec audio/opus" 里，用户只看到「没声音」。
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    reportError(
                        UnsupportedOperationException(
                            "控制端 Android ${Build.VERSION.RELEASE} 不支持 Opus 解码（需 Android 10+），" +
                                "请在设置里把音频编码改成「原始 PCM」"
                        )
                    )
                    return false
                }
                prepareCodec(MediaFormat.MIMETYPE_AUDIO_OPUS, "OPUS", safeBufferMs)
            }

            else -> {
                reportError(
                    UnsupportedOperationException(
                        "未知音频 codec id：0x${codecId.toString(16)}，无法解码"
                    )
                )
                false
            }
        }
    }

    /**
     * 送入一个音频包。**非阻塞**，可在视频帧协程里直接调用（见类 KDoc 第 4 条）。
     *
     * @param data   包负载（raw 路径下就是 PCM16LE 原始字节）
     * @param ptsUs  设备端单调时钟的呈现时间戳（微秒）。**仅用于日志**，
     *               不参与播放调度（见类 KDoc 第 3 条）
     * @param config 是否编码器配置包（CSD）
     */
    fun submit(data: ByteArray, ptsUs: Long, config: Boolean) {
        if (data.isEmpty() || !running) return

        when (mode) {
            Mode.PASSTHROUGH -> {
                // raw 流里不存在 config 包（类 KDoc 第 2 条）。这里仍然挡一道，
                // 是为了万一上游语义变化时不要把非 PCM 数据当 PCM 播成爆音。
                if (!config) {
                    enqueueRaw(data)
                }
            }

            Mode.CODEC -> {
                if (config) {
                    // 只在首个 config 包上 configure；后续重复的 CSD 直接忽略
                    if (!codecConfigured) {
                        configureCodec(data)
                    }
                    return
                }
                if (!codecConfigured) {
                    // 编码路径必须等 config 包（CSD）才能 configure。上游 `AudioEncoder.outputThread()`
                    // 一定先 `writeAudioHeader()` 再把编码器的首个输出（CSD，带 BUFFER_FLAG_CODEC_CONFIG）
                    // 发出来，所以正常情况下这条分支只会命中极少数几次。
                    //
                    // 但「一直等不到」时必须有结论：否则解码器永远建不起来，上层则永远停在
                    // 「音频连接中…」—— 用户既听不到声音，也看不到任何原因。所以这里放一个
                    // 按**包数**计的看门狗（不给它单独开线程/定时器，直接搭在数据流上）。
                    unconfiguredDropped++
                    if (unconfiguredDropped == UNCONFIGURED_WATCHDOG_PACKETS) {
                        reportError(
                            IllegalStateException(
                                "$codecLabel 解码器始终未收到配置包（已丢弃 $unconfiguredDropped 个音频包），" +
                                    "请在设置里改用「原始 PCM」"
                            )
                        )
                    }
                    return
                }
                feedCodec(data, ptsUs)
            }

            Mode.NONE -> Unit
        }
    }

    /**
     * 停止并彻底释放。可重复调用（未启动时是空操作）。
     *
     * 顺序刻意固定：**先置 [running]=false 并中断线程，再释放 AudioTrack / MediaCodec**
     * —— 反过来的话写线程会在释放后仍调 `write()`，抛出的 IllegalStateException 会被
     * 当成解码错误上抛，表现为「正常退出却报音频故障」。
     */
    fun stopAndRelease() {
        val wasRunning: Boolean = running
        running = false

        val track: AudioTrack?
        val decoder: MediaCodec?
        val writer: Thread?
        val drain: Thread?
        val queue: ArrayBlockingQueue<ByteArray>?
        synchronized(lock) {
            track = audioTrack
            audioTrack = null
            decoder = codec
            codec = null
            writer = rawWriter
            rawWriter = null
            drain = codecDrain
            codecDrain = null
            queue = rawQueue
            rawQueue = null
            mode = Mode.NONE
            pendingMime = null
            codecLabel = ""
            codecConfigured = false
        }

        writer?.interrupt()
        drain?.interrupt()
        queue?.clear()

        // ⚠️ 先等工作线程退干净再释放资源。interrupt 对 `WRITE_BLOCKING` / `dequeueOutputBuffer`
        // 都**无效**（它们不抛 InterruptedException），所以不能只靠 interrupt —— 但两个循环都在
        // 下一次检查 `running` 时退出，且各自的最坏等待都很短（write 随 AudioTrack 被消费返回、
        // dequeue 有 10ms 超时），因此给一个小的上限即可。加这道 join 是为了消灭
        // 「release 之后写线程仍在 write」这个窗口，而不是为了等待（超时就继续往下走）。
        joinQuietly(writer, WORKER_JOIN_TIMEOUT_MS)
        joinQuietly(drain, WORKER_JOIN_TIMEOUT_MS)

        // AudioTrack.flush() 只在 PAUSED/STOPPED 下合法，故必须 pause 在前且全程 runCatching
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.stop() }
        runCatching { track?.release() }

        if (decoder != null) {
            runCatching { decoder.stop() }
                .onFailure { e -> Logx.w(TAG, "MediaCodec.stop 异常：${e.message}") }
            runCatching { decoder.release() }
                .onFailure { e -> Logx.w(TAG, "MediaCodec.release 异常：${e.message}") }
        }

        if (wasRunning) {
            Logx.i(TAG, "音频通道已释放：raw 丢包=$rawDropped 解码丢包=$codecDropped")
            _events.tryEmit(AudioEvent.Stopped)
        }
    }

    // ------------------------------------------------------------------
    // raw 直通
    // ------------------------------------------------------------------

    private fun startPassthrough(bufferMs: Int): Boolean {
        val track: AudioTrack = try {
            createAudioTrack(bufferMs)
        } catch (throwable: Throwable) {
            reportError(
                IllegalStateException("AudioTrack 创建失败：${throwable.message}", throwable)
            )
            return false
        }

        // 队列容量由用户选的缓冲换算而来（服务端每包最大 4096B ≈ 21ms），
        // 夹到 2..8：下限保证不会一帧就断，上限避免队列本身变成延迟来源。
        val capacity: Int = (bufferMs / SERVER_PACKET_MS).coerceIn(MIN_QUEUE_CAPACITY, MAX_QUEUE_CAPACITY)
        val queue = ArrayBlockingQueue<ByteArray>(capacity)
        val writer = Thread({ writeRawLoop(track, queue) }, "audio-raw-writer").apply {
            isDaemon = true
        }

        synchronized(lock) {
            audioTrack = track
            rawQueue = queue
            rawWriter = writer
            mode = Mode.PASSTHROUGH
            rawDropped = 0L
            running = true
        }

        runCatching { track.play() }
            .onFailure { throwable ->
                reportError(IllegalStateException("AudioTrack.play 失败：${throwable.message}", throwable))
                stopAndRelease()
                return false
            }

        writer.start()
        Logx.i(
            TAG,
            "音频直通已启动：raw PCM16LE ${SAMPLE_RATE}Hz/${CHANNELS}ch，" +
                "缓冲=${bufferMs}ms(目标 ${targetBufferBytes(bufferMs)}B) 队列=$capacity 包"
        )
        _events.tryEmit(
            AudioEvent.Started(sampleRate = SAMPLE_RATE, channels = CHANNELS, passthrough = true)
        )
        return true
    }

    /**
     * raw 路径的生产者侧：只做一次入队，**绝不阻塞**。
     * 队列满时丢掉最旧的一包 —— 音频宁可丢帧也不要让延迟无界增长。
     */
    private fun enqueueRaw(data: ByteArray) {
        val queue: ArrayBlockingQueue<ByteArray> = rawQueue ?: return
        if (queue.offer(data)) return
        rawDropped++
        queue.poll()
        if (!queue.offer(data)) {
            // 与写线程竞争时的兜底：这一次没塞进去就丢掉，不值得重试
            rawDropped++
        }
    }

    private fun writeRawLoop(track: AudioTrack, queue: ArrayBlockingQueue<ByteArray>) {
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val chunk: ByteArray? = try {
                    queue.poll(WRITER_POLL_MS, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                if (chunk == null) continue

                // WRITE_BLOCKING：等 AudioTrack 消费完再返回，天然形成反压。
                // 因为用的是**专属线程**，阻塞在这里不会拖住视频帧处理。
                val written: Int = track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    // 释放过程中 write 也会返回负值，此时 running 已为 false，不该报错
                    if (running) {
                        reportError(IllegalStateException("AudioTrack.write 返回错误码 $written"))
                        return
                    }
                    return
                }
            }
        } catch (throwable: Throwable) {
            if (running) {
                reportError(throwable)
            }
        }
    }

    // ------------------------------------------------------------------
    // aac / opus / flac
    // ------------------------------------------------------------------

    /**
     * 编码路径一次成功初始化的产物。
     * 三者**要么一起生效，要么一起释放** —— 拆开就会漏 MediaCodec 或 AudioTrack。
     */
    private class BuiltCodec(
        val decoder: MediaCodec,
        val track: AudioTrack,
        val drain: Thread
    )

    /** 记住目标 mime，等 config 包到了再 configure —— 因为 CSD 只能从包里拿。 */
    private fun prepareCodec(mime: String, label: String, bufferMs: Int): Boolean {
        synchronized(lock) {
            pendingMime = mime
            codecLabel = label
            codecConfigured = false
            codecDropped = 0L
            unconfiguredDropped = 0L
            targetBufferMs = bufferMs
            mode = Mode.CODEC
            running = true
        }
        Logx.i(TAG, "音频解码待配置：$label（等待首个 config 包携带 CSD）")
        return true
    }

    /**
     * 用 config 包（CSD）configure + start MediaCodec，并建立 AudioTrack 与排水线程。
     *
     * CSD 的两种来源（都由服务端 `Streamer.writePacket` 以 bit63 标记发出，且已按编码裁成
     * 纯 extra data）：AAC = AudioSpecificConfig；Opus = `OpusHead`。
     *
     * ⚠️ 这里刻意把「建资源」放在锁外、把「提交进字段」放在锁内并**重新检查 `running`**：
     * 建 MediaCodec/AudioTrack 要几十毫秒，占着锁会卡住 [stopAndRelease]；
     * 但若不检查就提交，则「本方法建到一半时另一线程调了 stopAndRelease」会留下
     * 一对**已 `play()` 但没人写、也没人 release** 的僵尸资源。
     */
    private fun configureCodec(csd: ByteArray) {
        var label: String = ""
        var built: BuiltCodec? = null
        var failure: Throwable? = null
        var committed: Boolean = false

        synchronized(lock) {
            if (!running) {
                // stopAndRelease 已经跑过一轮：此时提交必然变成僵尸，直接丢弃
                Logx.w(TAG, "收到 config 包时音频通道已停止，丢弃本次解码器初始化")
                return
            }
            val mime: String = pendingMime ?: return
            label = codecLabel
            try {
                val product: BuiltCodec = buildCodec(mime, targetBufferMs, csd)
                codec = product.decoder
                audioTrack = product.track
                codecDrain = product.drain
                codecConfigured = true
                pendingMime = null
                built = product
                committed = true
                // ⚠️ Started 必须在**同一段临界区**里发出，不能挪到锁外：
                // 否则「提交」与「通知」之间会被 stopAndRelease 插进来（它要拿同一把锁），
                // 于是上层先收到 Stopped/Error 再收到 Started，UI 会显示成「已停止但音频仍开着」。
                // 放在锁内则顺序天然正确：要么 stopAndRelease 先跑（此处的 !running 早退，不发 Started），
                // 要么 Started 先发出去（后续的 Stopped/Error 必然排在它后面）。
                // tryEmit 对带缓冲的 SharedFlow 不会挂起也不会阻塞，锁内调用是安全的。
                _events.tryEmit(
                    AudioEvent.Started(
                        sampleRate = SAMPLE_RATE,
                        channels = CHANNELS,
                        passthrough = false
                    )
                )
            } catch (throwable: Throwable) {
                failure = throwable
                codecConfigured = false
                pendingMime = null
            }
        }

        if (!committed) {
            // 半成品就地释放。**不要**在这里调 stopAndRelease —— 它会连带清掉会话级状态，
            // 而这里只想收拾这一次失败的初始化。
            built?.let { releaseBuilt(it) }
            failure?.let { throwable ->
                Logx.e(TAG, "音频解码器配置失败：${throwable.message}")
                reportError(
                    IllegalStateException("$label 解码器配置失败：${throwable.message}", throwable)
                )
            }
            return
        }

        built?.drain?.start()
        Logx.i(TAG, "$label 解码器已启动：CSD=${csd.size}B，缓冲=${targetBufferMs}ms")
    }

    /**
     * 建 MediaCodec + AudioTrack + 排水线程。**任一步失败都要把已建好的部分释放掉**，
     * 否则会漏掉一个 MediaCodec 或一条 AudioTrack。
     *
     * 注意 `configure`/`start` 失败的 MediaCodec **不能再 `stop()`**（会抛 IllegalStateException），
     * 只能 `release()`；所以两处都套 runCatching，失败时优先保证 release 一定被调用。
     */
    private fun buildCodec(mime: String, bufferMs: Int, csd: ByteArray): BuiltCodec {
        val format: MediaFormat = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, CHANNELS).apply {
            // ⚠️ 必须 wrap 成 ByteBuffer；csd 是调用方传进来的数组，copyOf 避免外部复用同一份
            setByteBuffer(CS_KEY_CSD0, ByteBuffer.wrap(csd.copyOf()))
            // 低延迟：API 30+ 才支持 KEY_LOW_LATENCY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        val decoder: MediaCodec = MediaCodec.createDecoderByType(mime)
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
        } catch (throwable: Throwable) {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            throw throwable
        }

        val track: AudioTrack = try {
            createAudioTrack(bufferMs)
        } catch (throwable: Throwable) {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            throw throwable
        }
        try {
            track.play()
        } catch (throwable: Throwable) {
            runCatching { track.release() }
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            throw throwable
        }

        return BuiltCodec(
            decoder = decoder,
            track = track,
            drain = Thread(
                { drainCodecLoop(decoder, track) },
                "audio-codec-drain"
            ).apply { isDaemon = true }
        )
    }

    /** 释放一次**尚未提交**的构建产物（线程还没 start，只需 interrupt 兜底）。 */
    private fun releaseBuilt(product: BuiltCodec) {
        runCatching { product.drain.interrupt() }
        runCatching { product.track.pause() }
        runCatching { product.track.flush() }
        runCatching { product.track.stop() }
        runCatching { product.track.release() }
        runCatching { product.decoder.stop() }
        runCatching { product.decoder.release() }
    }

    /** 把压缩包喂给解码器；拿不到输入缓冲就丢弃当前包（与视频解码同样的反压策略）。 */
    private fun feedCodec(data: ByteArray, ptsUs: Long) {
        val decoder: MediaCodec = synchronized(lock) {
            if (!codecConfigured) return
            codec
        } ?: return

        val thrown = runCatching {
            val index: Int = decoder.dequeueInputBuffer(0L)
            if (index < 0) {
                codecDropped++
                return
            }
            val buffer: ByteBuffer = decoder.getInputBuffer(index)
                ?: throw IllegalStateException("音频输入缓冲区 $index 不可用")
            buffer.clear()
            if (data.size > buffer.remaining()) {
                decoder.queueInputBuffer(index, 0, 0, ptsUs, 0)
                throw IllegalStateException(
                    "音频包 ${data.size}B 超过输入缓冲区 ${buffer.remaining()}B"
                )
            }
            buffer.put(data)
            decoder.queueInputBuffer(index, 0, data.size, ptsUs, 0)
        }.exceptionOrNull()

        if (thrown != null && running) {
            reportError(thrown)
        }
    }

    /** 排水线程：把解码出的 PCM 写进 AudioTrack。 */
    private fun drainCodecLoop(decoder: MediaCodec, track: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val index: Int = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Logx.i(TAG, "音频输出格式变化：${describeOutputFormat(decoder)}")
                        continue
                    }

                    index >= 0 -> {
                        val buffer: ByteBuffer? = decoder.getOutputBuffer(index)
                        // CSD 不是 PCM，绝不能写进 AudioTrack（会变成一声爆音）
                        val isCsd: Boolean = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (buffer != null && info.size > 0 && !isCsd) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val written: Int =
                                track.write(buffer, buffer.remaining(), AudioTrack.WRITE_BLOCKING)
                            if (written < 0 && running) {
                                reportError(
                                    IllegalStateException("AudioTrack.write 返回错误码 $written")
                                )
                                return
                            }
                        }
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (running) {
                reportError(throwable)
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 建 AudioTrack。优先低延迟模式，失败则回退普通模式
     * （部分设备在「低延迟 + 指定缓冲」组合下 `build()` 会抛 UnsupportedOperationException）。
     */
    /**
     * 目标缓冲字节数。
     *
     * 用户选的缓冲若小于系统下限就以系统下限为准，否则会有持续 underrun 的破碎声；
     * `getMinBufferSize` 在参数不受支持时返回负值，此时一并按负值处理会让 `AudioTrack.Builder`
     * 直接抛异常，所以这里取 `maxOf` 之后由调用方兜住异常并回退。
     */
    private fun targetBufferBytes(bufferMs: Int): Int {
        val minBytes: Int = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        return maxOf(minBytes, BYTES_PER_MS * bufferMs)
    }

    private fun createAudioTrack(bufferMs: Int): AudioTrack {
        val targetBytes: Int = targetBufferBytes(bufferMs)

        return runCatching { buildAudioTrack(targetBytes, lowLatency = true) }
            .getOrElse { lowLatencyError ->
                Logx.w(TAG, "低延迟 AudioTrack 不可用，回退普通模式：${lowLatencyError.message}")
                runCatching { buildAudioTrack(targetBytes, lowLatency = false) }
                    .getOrElse { plainError ->
                        throw IllegalStateException(
                            "AudioTrack 创建失败（低延迟：${lowLatencyError.message}；" +
                                "普通：${plainError.message}）",
                            plainError
                        )
                    }
            }
    }

    private fun buildAudioTrack(bufferBytes: Int, lowLatency: Boolean): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_MASK)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply {
                if (lowLatency) {
                    // minSdk = 26，PERFORMANCE_MODE_LOW_LATENCY 自 API 26 起可用
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
            }
            .build()

    /** 把输出格式压成一行，便于排查「声音变调 / 只有单声道」这类采样参数问题。 */
    private fun describeOutputFormat(decoder: MediaCodec): String = runCatching {
        val format: MediaFormat = decoder.outputFormat
        val rate: Int = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(-1)
        val channels: Int =
            runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(-1)
        val warning: String =
            if (rate != SAMPLE_RATE || channels != CHANNELS) {
                " ⚠️ 与控制端硬编码的 ${SAMPLE_RATE}Hz/${CHANNELS}ch 不一致，声音可能变调"
            } else {
                ""
            }
        "${rate}Hz/${channels}ch$warning"
    }.getOrElse { "解析失败：${it.message}" }

    private fun reportError(throwable: Throwable) {
        Logx.e(TAG, "音频异常：${throwable.message}")
        _events.tryEmit(AudioEvent.Error(throwable))
    }

    /**
     * 有上限地等一个工作线程结束。
     *
     * 绝不无限等待：[stopAndRelease] 可能在主线程被调用（`AudioEvent.Error` → main），
     * 无限 join 会直接卡住 UI。也绝不 join 自己（从工作线程回调进来时会发生）。
     */
    private fun joinQuietly(thread: Thread?, timeoutMs: Long) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(timeoutMs)
        } catch (interrupted: InterruptedException) {
            // 调用方（很可能就是主线程）被中断：恢复中断标记并立刻返回，不吞掉这个信号
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG: String = "AudioDecoder"

        private const val CS_KEY_CSD0: String = "csd-0"

        /**
         * 采样参数**必须与服务端硬编码值一致**（scrcpy `AudioConfig`）：
         * 48000Hz / 2ch / PCM16LE。控制端不做重采样，两端对不上就是变调或噪音。
         */
        private const val SAMPLE_RATE: Int = 48_000
        private const val CHANNELS: Int = 2

        /** PCM16 小端。与 `AudioConfig.ENCODING` 对应。 */
        private val ENCODING: Int = AudioFormat.ENCODING_PCM_16BIT

        /**
         * ⚠️ **播放侧**掩码是 `CHANNEL_OUT_STEREO`，而服务端采集侧用的是
         * `CHANNEL_IN_STEREO`。两者数值不同，混用会让 AudioTrack 以错误的声道布局建轨。
         */
        private val CHANNEL_MASK: Int = AudioFormat.CHANNEL_OUT_STEREO

        /** 每秒字节数 48000 × 2ch × 2B = 192000 → 每毫秒 192 字节。 */
        private const val BYTES_PER_MS: Int = SAMPLE_RATE * CHANNELS * 2 / 1000

        /**
         * 服务端单个音频包的最大时长。
         * `AudioConfig.MAX_READ_SIZE = 1024 采样 × 2ch × 2B = 4096B` → 4096 / 192 ≈ 21ms。
         * 用它把用户选的 `bufferMs` 换算成队列容量，队列才真正跟着「音频缓冲」设置走。
         */
        private const val SERVER_PACKET_MS: Int = 21

        private const val MIN_QUEUE_CAPACITY: Int = 2
        private const val MAX_QUEUE_CAPACITY: Int = 8

        private const val MIN_BUFFER_MS: Int = 20
        private const val MAX_BUFFER_MS: Int = 1_000

        private const val WRITER_POLL_MS: Long = 100L

        /** 排水线程的取帧超时：给 10ms 就够，过长会让停止时的响应变慢。 */
        private const val DEQUEUE_TIMEOUT_US: Long = 10_000L

        /**
         * [stopAndRelease] 等两个工作线程退出的上限。
         * 只是「关掉写/取帧窗口」用的，不是真的等它们跑完，所以给得很短。
         */
        private const val WORKER_JOIN_TIMEOUT_MS: Long = 300L

        /**
         * 编码路径「等不到 config 包」的看门狗阈值（按**收到的音频包个数**计）。
         *
         * 服务端每包 ≈ 4096B ≈ 21ms，所以 64 包 ≈ 1.4 秒 —— 上游 `AudioEncoder.outputThread()`
         * 是先发 CSD 再发数据包的，正常绝不会等这么久；真等到了就说明这条流已经不可用，
         * 与其让用户看着「音频连接中…」发呆，不如明确降级并给出原因。
         */
        private const val UNCONFIGURED_WATCHDOG_PACKETS: Long = 64L
    }
}
