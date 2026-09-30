package com.adb.adbwirelesshelper.ui.screen

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adb.adbwirelesshelper.data.media.AudioDecoder
import com.adb.adbwirelesshelper.data.media.VideoDecoder
import com.adb.adbwirelesshelper.data.repository.DeviceRepository
import com.adb.adbwirelesshelper.data.scrcpy.ControlProtocol
import com.adb.adbwirelesshelper.data.scrcpy.ScrcpyController
import com.adb.adbwirelesshelper.data.scrcpy.ScrcpyServerDeployer
import com.adb.adbwirelesshelper.data.settings.AppSettings
import com.adb.adbwirelesshelper.domain.model.ScrcpyConfig
import com.adb.adbwirelesshelper.util.AdbErrors
import com.adb.adbwirelesshelper.util.ErrorContext
import com.adb.adbwirelesshelper.util.Logx
import com.adb.adbwirelesshelper.util.bitrateKbps
import com.adb.adbwirelesshelper.util.humanizeAdbError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 投屏页状态（单向数据流，UI 只读 StateFlow）。
 */
sealed interface MirrorState {
    /** 尚未开始 / 已停止 */
    data object Idle : MirrorState

    /** 正在 push server + forward + app_process 启动 */
    data object Deploying : MirrorState

    /** 转发已通，正在握手等待首帧 */
    data object Connecting : MirrorState

    /** 出图中。 */
    data class Streaming(
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
        val kbps: Int = 0,
        val latencyMs: Int = 0,
        val dropped: Int = 0,
        /**
         * 音频通道是否已真正出声。
         *
         * ⚠️ 音频是**可降级**通道：它只影响这个字段与 [audioNote]，绝不参与「投屏是否可用」
         * 的判定 —— 音频层任何失败都不得把状态置为 [Failed]。
         */
        val audioActive: Boolean = false,
        /** 音频的提示语（连接中 / 已降级原因）；空串表示无需提示。 */
        val audioNote: String = ""
    ) : MirrorState

    /**
     * 投屏中途断流，正在自动重连。
     *
     * 独立成一个状态而不是复用 [Connecting]：用户此刻看到的不是「第一次连不上」，
     * 而是「刚才还好好的，突然断了」。这两种情况该给的反馈完全不同 ——
     * 复用 Connecting 会让用户以为 App 卡在起步阶段，从而反复退出重进。
     *
     * @param attempt     当前是第几次重连（从 1 开始）
     * @param maxAttempt  最多会尝试几次
     */
    data class Reconnecting(val attempt: Int, val maxAttempt: Int, val reason: String) : MirrorState

    /** 失败，携带可读文案 */
    data class Failed(val message: String) : MirrorState
}

/** 手势类型，UI 采集后翻译成控制包。 */
enum class TouchKind {
    DOWN,
    MOVE,
    UP,
    CLICK,
    DOUBLE_CLICK,
    LONG_PRESS
}

/**
 * UI 上报的原始触点。
 *
 * @param kind        只使用 [TouchKind.DOWN] / [TouchKind.MOVE] / [TouchKind.UP]：
 *                    点击、双击、长按、拖动全部由**被控端自己判定**（本地不做手势合成）。
 *                    这是「回控手感对」的前提 —— 旧实现把长按合成为「按下 400ms 再抬起」，
 *                    拖动则根本收不到，所以用户会觉得「控制不灵」。
 * @param x,y         画面矩形内的坐标（px），左上角为原点
 * @param canvasWidth,canvasHeight 画面矩形的实际尺寸（**已扣除等比适配留出的黑边**），
 *                    所以到远端分辨率是纯线性映射，不存在留白偏移
 * @param pointerId   触点 id；多指时各指不同，直接透传给 scrcpy 的 INJECT_TOUCH_EVENT
 */
data class RawTouch(
    val kind: TouchKind,
    val x: Float,
    val y: Float,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val pointerId: Long = ControlProtocol.DEFAULT_POINTER_ID
)

/**
 * 被控端画面尺寸的**单一可信来源**。
 *
 * 两个来源按时间先后覆盖同一个对象：
 * ① 握手 [ScrcpyController.Event.Ready] —— 初始值（来自 12 字节 stream meta）；
 * ② 解码器 [VideoDecoder.DecoderEvent.SizeChanged] —— 被控端旋转 / 分辨率调整之后。
 *
 * ⚠️ 为什么必须有 ②：scrcpy 服务端 `SurfaceEncoder.streamCapture()` 里的 `headerWritten`
 * 是**一次性**标志 —— 旋转导致编码器重建、分辨率变化时，它**不会**重发那 12 字节流头
 * （已核对 v3.3.2 源码）。新分辨率只能从 MediaCodec 的 `INFO_OUTPUT_FORMAT_CHANGED` 取，
 * 否则是过期值：表现为旋转后画面比例错了、触控点全偏、窗口方向也不跟着转。
 *
 * 等比适配、触控映射、窗口方向三者都读这一份尺寸，保证始终一致。
 */
data class RemoteSize(val width: Int = 0, val height: Int = 0) {
    val isValid: Boolean get() = width > 0 && height > 0

    /** 宽 >= 高视为横屏（正方形按横屏处理，更符合「铺满」的预期）。 */
    val isLandscape: Boolean get() = width >= height

    /** 宽高比；尺寸非法时返回 0，调用方据此退化为「铺满」。 */
    val aspect: Float get() = if (isValid) width.toFloat() / height.toFloat() else 0f
}

/**
 * 控制端窗口方向策略。
 *
 * - [FOLLOW_REMOTE]：默认。被控端横屏则控制端横屏、竖屏则竖屏 ——
 *   即「按被控设备的尺寸改变控制端方向」；
 * - [LANDSCAPE] / [PORTRAIT]：用户手动锁定，之后被控端再旋转也不跟随。
 */
enum class OrientationMode { FOLLOW_REMOTE, LANDSCAPE, PORTRAIT }

/**
 * 投屏生命周期管家。
 *
 * 对外只有「一点进出」：[start] 建立会话，[stop] 完成 **清理四件套**
 * （关双 socket → release 解码器 → forward --remove → 通知 server 退出）。
 * 所有耗时操作都在 [Dispatchers.IO]，UI 线程只有 StateFlow 赋值。
 *
 * **音频是可降级通道**：它独立于视频，只有 [ScrcpyController.Event.AudioReady] /
 * `AudioFrame` 会驱动它，任何音频异常（设备端不支持、解码器起不来、AudioTrack 出错）
 * 都只体现为 [MirrorState.Streaming.audioNote] 与 `audioActive=false`，
 * **绝不参与投屏状态机**，更不允许把投屏页置为 [MirrorState.Failed]。
 */
class MirrorViewModel(
    private val deployer: ScrcpyServerDeployer,
    private val repo: DeviceRepository,
    private val settings: AppSettings
) : ViewModel() {

    private val _state: MutableStateFlow<MirrorState> = MutableStateFlow(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    /** 当前会话的目标设备序列号。 */
    private var boundSerial: String = ""

    private var sessionJob: Job? = null
    private var framesJob: Job? = null
    private var decoderEventsJob: Job? = null

    /** 会话代号：stop / 清理后自增，用于让在飞的旧会话在下一个挂起点自行退出。 */
    private var sessionGeneration: Long = 0L

    /**
     * 会话闸门：为 true 表示「本轮绑定的这一路会话还没释放」。
     * 只有 [stop] / [releaseResources] 会把它复位，用于拦截旋转或组合重建导致的重复 deploy。
     */
    @Volatile
    private var sessionAlive: Boolean = false

    private var controller: ScrcpyController? = null
    /**
     * 视频解码器。
     *
     * ⚠️ `@Volatile` 是必需的：`attachSurface` 跑在 UI 线程（SurfaceHolder 回调），
     * 而 [ensureDecoder] / [VideoDecoder.start] 跑在 IO 线程（framesJob），两侧共享此字段
     * 却无锁。不加 volatile 会有两个问题：
     * ① `start()` 成功后才把 codec 赋给本字段，若此时 UI 线程读到的仍是 null，会误判
     *    「换绑失败」→ 触发一次多余的解码器重建；
     * ② 理论上两个 MediaCodec 实例先后写同一字段，前一个可能泄漏。
     * 这里只做最小改动（加 volatile），不改成单线程收口，避免引入回归风险。
     */
    @Volatile
    private var decoder: VideoDecoder? = null

    /**
     * 解码器重建保护：防止 MTK 等 `setOutputSurface` 支持差的平台陷入「换绑失败 → 重建 →
     * 又失败」的死循环（每次重建都会弹「解码器初始化失败」并不断重连）。三道闸：
     * 冷却 / 防重入 / 熔断，详见 [attachSurface] 与 [ensureDecoder]。
     * 这些字段在 UI 线程（attachSurface）与 IO 线程（ensureDecoder / onFrame）间共享，故加 @Volatile。
     */
    @Volatile
    private var lastDecoderRebuildMs: Long = 0L
    @Volatile
    private var rebuildingDecoder: Boolean = false
    @Volatile
    private var decoderRebuildCount: Int = 0
    /** 冷却期内被跳过的换绑，待冷却结束后再补一次（见 [onFrame] 的节流检查）。 */
    @Volatile
    private var pendingRebind: Boolean = false
    /** onFrame 里补做换绑的低频节流时间点（1 秒一次）。 */
    @Volatile
    private var lastRebindCheckMs: Long = 0L

    /**
     * Surface 换绑 / 重建诊断计数器：在 [attachSurface] 各分支自增，[releaseResources] 归零。
     * 仅用于断流时 [logClientRenderSnapshot] 输出，不参与任何控制流，故加 @Volatile 保证跨线程可见。
     */
    @Volatile
    private var surfaceAttachCount: Int = 0
    @Volatile
    private var surfaceNullCount: Int = 0
    @Volatile
    private var surfaceSameCount: Int = 0
    /**
     * `attachSurface` 时解码器**尚未绑定**任何 Surface 的次数（创建中窗口期，见该函数注释）。
     * 这属于正常现象、不是故障：数值 > 0 且 `rebindFail=0` 表示「Surface 压根没换过」，
     * 用于把「真的换绑失败」与「创建期空窗」区分开——两者在旧日志里都表现为 bound 为空。
     */
    @Volatile
    private var surfaceUnboundCount: Int = 0
    @Volatile
    private var surfaceRebindOkCount: Int = 0
    @Volatile
    private var surfaceRebindFailCount: Int = 0
    @Volatile
    private var surfaceRebuiltCount: Int = 0

    /**
     * 本会话内 [VideoDecoder.setOutputSurface] 是否已确认不可用（MTK / 部分 ROM 的 codec2 不实现该能力）。
     *
     * 一旦为 true，后续 Surface 变化**直接走重建**，不再重复调用那个必然失败的换绑接口
     * （每次都会抛异常再被接住，纯浪费）。
     *
     * 这不是我们独创的绕法：ExoPlayer 源码里就有一张名为 `codecNeedsSetOutputSurfaceWorkaround`
     * 的硬编码机型名单专门收录这类设备，NewPipe 也为此加了「强制兼容」开关。
     * 详见 ExoPlayer issue #4468（`OMX.MTK.VIDEO.DECODER.AVC` 换 Surface 抛
     * IllegalArgumentException / CodecException）与 androidx/media issue #2711
     * （`c2.mtk.hevc.decoder` connectToSurface 后立刻 disconnectFromSurface → 黑屏）。
     *
     * [releaseResources] 里复位：每个会话重新探测一次，代价仅一次调用，但能兼容「系统升级后能力变好」。
     */
    @Volatile
    private var setOutputSurfaceUnsupported: Boolean = false

    /** 音频解码器（可降级通道）；`null` 表示本会话没有音频。 */
    private var audioDecoder: AudioDecoder? = null
    private var audioEventsJob: Job? = null

    private var startInfo: ScrcpyServerDeployer.StartInfo? = null
    private var currentConfig: ScrcpyConfig? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var remoteWidth: Int = 0

    @Volatile
    private var remoteHeight: Int = 0

    /** 画面尺寸的对外投影：投屏页据此做等比适配 + 方向跟随。 */
    private val _remoteSize: MutableStateFlow<RemoteSize> = MutableStateFlow(RemoteSize())
    val remoteSize: StateFlow<RemoteSize> = _remoteSize.asStateFlow()

    /** 窗口方向策略。默认跟随被控端。 */
    private val _orientationMode: MutableStateFlow<OrientationMode> =
        MutableStateFlow(OrientationMode.FOLLOW_REMOTE)
    val orientationMode: StateFlow<OrientationMode> = _orientationMode.asStateFlow()

    @Volatile
    private var paused: Boolean = false

    @Volatile
    private var intentionalStop: Boolean = false

    /**
     * 本界面上一次发出的屏幕状态。见 [toggleDisplayPower] 为何不用真实状态。
     * 只在主线程（按钮回调）读写，加 @Volatile 仅为跨协程读取时可见。
     */
    @Volatile
    private var remoteScreenOn: Boolean = true

    /**
     * 设置项「自动重连」的快照。在 [start] 的会话协程开头读一次 ——
     * 断流后要不要重连得立刻能判断，不能在崩溃路径上再去读 DataStore。
     */
    @Volatile
    private var autoReconnect: Boolean = true

    /** 已经自动重连了几次。出图成功（[MirrorState.Streaming]）后归零。 */
    @Volatile
    private var reconnectAttempt: Int = 0

    /**
     * 控制包发送队列（**单消费者**）。
     *
     * 为什么不直接 `launch { sendNow(bytes) }`：`Dispatchers.IO` 是多线程池，
     * `postTouch` 的 DOWN / MOVE / UP 各自 launch 一个协程后，**到达 socket 的先后顺序
     * 不再有保证**（UP 可能先于 MOVE 写出去），被控端就会收到乱序手势 —— 表现是
     * 「点得动，但拖动很卡 / 偶发失灵」。同时多次并发写同一个 `OutputStream` 也不安全。
     * 用「无界队列 + 唯一消费者」把顺序和互斥一次解决。
     *
     * 用 UNLIMITED 而不是带丢弃的策略：丢 MOVE 无所谓，但**丢掉 UP 会让被控端永久卡在按下状态**。
     */
    private val controlQueue: Channel<ByteArray> = Channel(capacity = Channel.UNLIMITED)

    /** 队列的唯一消费者。随会话启动、随会话清理取消。 */
    private var controlWriterJob: Job? = null

    // ---------------- 统计 ----------------
    private var windowStartMs: Long = 0L
    private var windowFrames: Int = 0
    private var windowBytes: Long = 0L
    private var lastFps: Int = 0
    private var lastKbps: Int = 0
    private var estimatedLatencyMs: Int = 0
    private var droppedFrames: Int = 0
    private var firstFrameWallMs: Long = 0L
    private var firstFramePtsUs: Long = 0L

    init {
        Logx.d(TAG, "MirrorViewModel 已创建")
    }

    /**
     * 绑定目标设备。幂等：同一 serial 重复调用不会重触发投屏。
     */
    fun bind(serial: String) {
        if (serial.isBlank()) return
        if (boundSerial == serial) {
            Logx.d(TAG, "bind 幂等，忽略重复：$serial")
            return
        }
        boundSerial = serial
        Logx.d(TAG, "已绑定设备：$serial")
    }

    /**
     * 开始投屏（未 start 或已失败时可再次调用，相当于重连一次）。
     *
     * 重入保护（需求 C / E6）：投屏页在旋转、配置变更、返回栈重建时会重新进入组合，
     * `LaunchedEffect` 会再调一次 [start]；若此时上一路会话的 controller / 解码器还活着，
     * 就会 deploy 出第二个 app_process（设备侧出现两个 PID）。因此这里用 [sessionAlive]
     * 做「一次绑定只起一路会话」的闸门：真正想重启必须先 [stop]（它会把闸门复位）。
     */
    fun start() {
        val serial: String = boundSerial
        if (serial.isBlank()) {
            _state.value = MirrorState.Failed(TEXT_NO_DEVICE)
            return
        }
        if (sessionJob?.isActive == true) {
            Logx.w(TAG, "已有会话在跑，忽略重复 start")
            return
        }
        if (sessionAlive) {
            // 会话资源尚未释放（controller / 解码器仍在），重复 start 会造成双开
            Logx.w(TAG, "上一路会话仍未释放（controller=${controller != null}），忽略重复 start")
            return
        }
        intentionalStop = false
        sessionAlive = true
        startControlWriter()
        val generation: Long = ++sessionGeneration
        sessionJob = viewModelScope.launch(Dispatchers.IO) {
            // 会话一开始就取一次「自动重连」开关：断流路径上可能已经来不及读 DataStore
            autoReconnect = runCatching { settings.autoReconnect.first() }
                .onFailure { Logx.w(TAG, "读取自动重连开关失败，按开启处理：${it.message}") }
                .getOrDefault(true)
            runCatching { runSession(serial, generation) }
                .onFailure { throwable ->
                    if (throwable is java.util.concurrent.CancellationException) {
                        Logx.w(TAG, "会话被取消")
                        return@onFailure
                    }
                    val message: String = humanizeAdbError(
                        throwable.message ?: throwable.javaClass.simpleName
                    )
                    Logx.e(TAG, "投屏会话失败：$message")
                    _state.value = MirrorState.Failed(message)
                    releaseResources()
                }
        }
    }

    /** 停止投屏并执行清理四件套。可重复调用。 */
    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        intentionalStop = true
        // 同步释放闸门，保证紧接着的 start()/restart() 不会被重入保护挡掉
        sessionAlive = false
        // 释放完成后才置 Idle；若期间用户又发起新会话（start() 会自增 generation），
        // 则不能把新会话的 Deploying 覆盖回 Idle，所以只认「generation 没变」这一种情况。
        val expected: Long = sessionGeneration
        viewModelScope.launch(Dispatchers.IO) {
            releaseResources()
            if (sessionGeneration == expected) {
                _state.value = MirrorState.Idle
            }
        }
    }

    /**
     * 重启会话：先完整释放旧会话（关 socket → 释放解码器 → 移除 forward），再重新部署。
     *
     * 「重试」按钮必须走这里而不是 `stop(); start()` —— 后者是两个并发协程，
     * 旧的清理协程可能晚于新会话启动，把新会话的 controller/解码器一起关掉。
     */
    fun restart() {
        viewModelScope.launch(Dispatchers.IO) {
            // 用户主动点的「重试」：把自动重连计数也归零，重新给满 3 次机会
            reconnectAttempt = 0
            restartInternal()
        }
    }

    /**
     * [restart] 的同步实现（调用方需已在 Dispatchers.IO）。
     *
     * ⚠️ 这里是**完整重连**：重新 push server → 重建 adb forward → 重新握手。
     * 之所以不做成「只重建 socket」：断流最常见的原因是控制端的 adb forward
     * 隧道失效，只重连 socket 会因为本地端口根本没有映射而再次失败。
     */
    private suspend fun restartInternal() {
        val serial: String = boundSerial
        if (serial.isBlank()) {
            _state.value = MirrorState.Failed(TEXT_NO_DEVICE)
            return
        }
        sessionJob?.cancel()
        sessionJob = null
        intentionalStop = true
        sessionAlive = false
        releaseResources()
        _state.value = MirrorState.Idle
        start()
    }

    /**
     * Surface 回调：画布创建/变化时传入非空 Surface，销毁时传 null。
     * 旋转屏幕会走 surfaceChanged → 这里只重建解码器，**不重启投屏会话**（需求 E6）。
     *
     * 关键修复：是否复用解码器不再只看 `decoder != null`，而是比对 **Surface 对象身份**
     * （[VideoDecoder.boundSurface] 返回的解码器当前 Surface vs 入参 [s]，引用相等 `===`）。
     * 因为小米平板在 letterbox / 频繁 configChange 时，SurfaceView 可能**不先发
     * `surfaceDestroyed`** 就直接交付一个全新的 Surface 对象（logcat：`[...]#1` 第二个
     * surface、`producer disconnected` 旧 Surface 的 producer 已断）。若仍按旧逻辑
     * `decoder != null → return`，解码器会继续往已 disconnected 的旧 Surface 渲染，
     * BufferQueue 堵死 → 必现「视频流读取异常」。
     * 此时应优先 [VideoDecoder.setOutputSurface] 换绑（不黑屏不丢帧），失败再重建。
     */
    fun attachSurface(s: Surface?) {
        surfaceAttachCount++
        surface = s
        if (s == null) {
            surfaceNullCount++
            decoder?.stopAndRelease()
            decoder = null
            decoderEventsJob?.cancel()
            decoderEventsJob = null
            pendingRebind = false
            Logx.d(TAG, "Surface 已销毁，解码器释放，连接保持")
            return
        }
        // 已有解码器：比对 Surface 身份决定复用 / 换绑 / 重建
        val dec = decoder
        if (dec != null) {
            val bound: Surface? = dec.boundSurface()
            if (bound == null) {
                // ⚠️ `bound == null` 不是「Surface 被换掉」，而是「还没绑上」。
                //
                // decoder 字段在 [ensureDecoder] 里是**先发布、后 start()**（`decoder = next`
                // 在 `next.start(...)` 之前），而 [VideoDecoder.boundSurface] 的底层字段
                // `outputSurface` 只在 configure+start **成功之后**才赋值。两者之间存在一个
                // 窗口期：`decoder != null` 但尚未绑定任何 Surface。
                //
                // 此时 UI 线程送进来的 Surface 甚至经常是**同一个对象**（TextureView 因视频
                // 尺寸确定而 relayout 就会触发 onSurfaceTextureSizeChanged → attachSurface），
                // 却会因为 bound == null 被误判成「换绑」。实测真机日志正落在这个窗口：
                //     Surface 已被换绑：新=30587484 旧=0   ← 旧=0 即 identityHashCode(null)
                // 后果是每个会话刚起步就白判一次「换绑失败」，在 MTK 上还会被记成
                // 「本设备不支持 setOutputSurface」并白白消耗一次重建冷却额度。
                //
                // 注意此时 [VideoDecoder.setOutputSurface] 也只会因 `codec == null` 直接短路
                // 返回 false——它**根本没有调用过 MediaCodec**，所以这个 false 不能作为
                // 「本平台不支持该能力」的证据。
                //
                // 正确处理：不动解码器，只挂一个「稍后复核」标记（由 [onFrame] 的低频节流补做）。
                // 正在创建的解码器会在 start() 时读取最新的 [surface] 完成绑定，复核时
                // `bound === s` 即走复用分支，自然清掉该标记。
                surfaceUnboundCount++
                pendingRebind = true
                Logx.d(TAG, "解码器尚未绑定 Surface（创建中），本次 attach 延后复核")
                return
            }
            if (bound === s) {
                // 确实是同一个 Surface：控制端转向 / 留白尺寸变化 / 远端分辨率变化，
                // 这些情况都不需要动解码器（重建只会黑屏一下并丢一片帧）。
                surfaceSameCount++
                Logx.d(TAG, "Surface 未变，复用解码器（远端 ${remoteWidth}x${remoteHeight}）")
                pendingRebind = false
                return
            }
            // Surface 被换掉了：先试不重建的换绑，成功则不黑屏不丢帧
            Logx.w(
                TAG,
                "Surface 已被换绑：新=${System.identityHashCode(s)} 旧=${System.identityHashCode(bound)}，" +
                    "尝试 setOutputSurface"
            )
            // 本设备已确认不支持换绑（MTK / 部分 ROM 的 codec2 不实现该接口）：跳过调用直接重建。
            // 不做这一步的话，每次 Surface 抖动都要白抛一次异常再接住，纯浪费——这正是 ExoPlayer
            // 用 codecNeedsSetOutputSurfaceWorkaround 机型名单规避的行为（见字段 KDoc 的 issue 链接）。
            if (!setOutputSurfaceUnsupported) {
                if (dec.setOutputSurface(s)) {
                    surfaceRebindOkCount++
                    pendingRebind = false
                    return
                }
                // 第一次确认不可用：记下来，本会话内不再重复尝试。
                // surfaceRebindFailCount 只统计「真正调用过并失败」的次数，所以每会话最多加 1；
                // 后续因已确认不支持而跳过的路径不再计数（跳过 ≠ 失败）。
                setOutputSurfaceUnsupported = true
                surfaceRebindFailCount++
                Logx.w(TAG, "该设备 setOutputSurface 不可用，本会话内不再尝试换绑，后续直接重建")
            } else {
                Logx.d(TAG, "本设备已确认不支持 setOutputSurface，跳过换绑直接重建")
            }
            // ⚠️ 但重建比原 bug 更糟——原 bug 只是画面堵死，重建失败会弹「解码器初始化失败」并
            // 不断重连。必须用三道闸兜底（见 ensureDecoder 的 goto 注释），绝不允许修复比原 bug 更糟。
            Logx.w(TAG, "setOutputSurface 失败，准备重建解码器")

            // 闸③熔断：本轮会话重建失败累计达上限，停止尝试。最坏退化为改动前行为
            // （画面可能卡住），但连接保持、不再弹窗、不再无限重建。
            if (decoderRebuildCount >= MAX_DECODER_REBUILD) {
                Logx.w(
                    TAG,
                    "已达重建上限($MAX_DECODER_REBUILD)，保持旧解码器（画面可能卡住，但不再崩溃）"
                )
                pendingRebind = false
                return
            }

            // 闸①冷却：布局抖动会连发多次 surfaceChanged，每次都是新 Surface。若距上次重建
            // 不足冷却时长，不立即重建，只记 pendingRebind 等冷却结束后再补（见 onFrame 检查）。
            val sinceLast = System.currentTimeMillis() - lastDecoderRebuildMs
            if (sinceLast < DECODER_REBUILD_COOLDOWN_MS) {
                pendingRebind = true
                Logx.w(TAG, "换绑失败，重建冷却中（${sinceLast}ms < ${DECODER_REBUILD_COOLDOWN_MS}ms），已延后")
                return
            }

            // 闸②防重入：ensureDecoder 内部会真正重建；若已有重建在途则跳过本次，避免叠加。
            if (rebuildingDecoder) {
                pendingRebind = true
                Logx.w(TAG, "解码器重建进行中，本次换绑延后")
                return
            }

            // 真正重建：清掉旧解码器，交给 ensureDecoder 新建（会重置 lastDecoderRebuildMs /
            // rebuildingDecoder / decoderRebuildCount 的语义）。
            surfaceRebuiltCount++
            dec.stopAndRelease()
            decoder = null
            decoderEventsJob?.cancel()
            decoderEventsJob = null
        } else {
            // decoder 为 null（含熔断后清出的僵尸）：若已熔断则不再尝试重建，保持连接不断。
            if (decoderRebuildCount >= MAX_DECODER_REBUILD) {
                Logx.w(
                    TAG,
                    "已达重建上限($MAX_DECODER_REBUILD)，不再重建解码器，连接保持"
                )
                pendingRebind = false
                return
            }
        }
        if (remoteWidth > 0 && remoteHeight > 0) {
            ensureDecoder()
        }
    }

    /** 手势注入入口：把画布坐标换算成远端坐标后发送控制包。 */
    fun onTouch(raw: RawTouch) {
        val cfg: ScrcpyConfig = currentConfig ?: return
        if (!cfg.controlEnabled) return
        val remoteW: Int = remoteWidth
        val remoteH: Int = remoteHeight
        if (remoteW <= 0 || remoteH <= 0) return
        if (raw.canvasWidth <= 0 || raw.canvasHeight <= 0) return

        // canvas 就是画面在屏幕上的实际矩形（投屏页把 pointerInput 挂在等比适配后的画布上，
        // 黑边已在外面扣掉），所以这里是纯线性映射，不需要再补偿留白偏移。
        val x: Int = (raw.x / raw.canvasWidth * remoteW).toInt().coerceIn(0, remoteW - 1)
        val y: Int = (raw.y / raw.canvasHeight * remoteH).toInt().coerceIn(0, remoteH - 1)

        when (raw.kind) {
            TouchKind.DOWN ->
                postTouch(raw.pointerId, ControlProtocol.TOUCH_ACTION_DOWN, x, y, ControlProtocol.PRESSURE_MAX)

            TouchKind.MOVE ->
                postTouch(raw.pointerId, ControlProtocol.TOUCH_ACTION_MOVE, x, y, ControlProtocol.PRESSURE_MAX)

            TouchKind.UP ->
                postTouch(raw.pointerId, ControlProtocol.TOUCH_ACTION_UP, x, y, 0)

            // 以下三种是「本地合成手势」的旧路径。投屏页已改为直接透传 DOWN/MOVE/UP，
            // 由被控端判定点击/双击/长按（更准，且拖动不再丢失）。分支保留以兼容既有调用方。
            TouchKind.CLICK -> tap(x, y, times = 1)
            TouchKind.DOUBLE_CLICK -> tap(x, y, times = 2)
            TouchKind.LONG_PRESS -> longPress(x, y)
        }
    }

    /**
     * 按键注入。习惯用法：先 [ControlProtocol.KEY_ACTION_DOWN]，再 [ControlProtocol.KEY_ACTION_UP]。
     */
    fun injectKey(action: Int, keycode: Int) {
        val bytes: ByteArray = ControlProtocol.injectKeycode(action, keycode, 0, 0)
        sendAsync(bytes)
    }

    /** 便捷方法：一次完整的按下 + 抬起。走队列，保证 DOWN 一定先于 UP 到达。 */
    fun injectKeyTap(keycode: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            sendAsync(ControlProtocol.injectKeycode(ControlProtocol.KEY_ACTION_DOWN, keycode, 0, 0))
            delay(KEY_HOLD_MS)
            sendAsync(ControlProtocol.injectKeycode(ControlProtocol.KEY_ACTION_UP, keycode, 0, 0))
        }
    }

    /** 文本注入（不经软键盘，支持中文）。 */
    fun injectText(text: String) {
        if (text.isEmpty()) return
        val bytes: ByteArray = ControlProtocol.injectText(text)
        sendAsync(bytes)
    }

    /** 剪贴板写入远端。 */
    fun setClipboard(text: String) {
        val bytes: ByteArray = ControlProtocol.setClipboard(System.currentTimeMillis(), text)
        sendAsync(bytes)
    }

    /** 通知栏展开/收起。 */
    fun toggleNotificationPanel(expand: Boolean) {
        val bytes: ByteArray = if (expand) {
            ControlProtocol.expandNotificationPanel()
        } else {
            ControlProtocol.collapseNotificationPanel()
        }
        sendAsync(bytes)
    }

    /**
     * 远端屏幕开关：true = 正常亮屏，false = 息屏。
     *
     * 走 scrcpy 的 `SET_DISPLAY_POWER` 控制消息而不是注入 POWER 键事件 ——
     * 注入按键在多数 ROM 上会被系统吞掉（那是系统级按键，普通 inject 无效果），
     * 表现出来就是「点了电源键没反应」。
     */
    fun setDisplayPower(on: Boolean) {
        val bytes: ByteArray = ControlProtocol.setDisplayPower(on)
        sendAsync(bytes)
    }

    /**
     * 电源键：**切换**远端亮屏 / 息屏。
     *
     * 用本地记录的 [remoteScreenOn] 做翻转。它无法反映「被控端自己按电源键」的情况，
     * 所以以首次连接默认为亮屏、之后按本界面的操作翻转为准 —— 比读不到真实状态时
     * 一律发 true（点了没反应）更符合直觉。
     */
    fun toggleDisplayPower() {
        remoteScreenOn = !remoteScreenOn
        Logx.i(TAG, "切换远端屏幕: ${if (remoteScreenOn) "亮屏" else "息屏"}")
        setDisplayPower(remoteScreenOn)
    }

    /** 旋转远端设备。 */
    fun rotateDevice() {
        sendAsync(ControlProtocol.rotateDevice())
    }

    /** 前台恢复：恢复送解码（此前后台期间丢弃的帧计入丢帧）。 */
    fun onResume() {
        paused = false
        Logx.d(TAG, "恢复解码")
    }

    /** 进入后台：保留连接但暂停解码，降低 CPU / 电量消耗（需求 D6 + E6）。 */
    fun onPause() {
        paused = true
        Logx.d(TAG, "暂停解码")
    }

    /** 当前设备显示名（用于日志/提示）。 */
    fun deviceLabel(): String {
        val serial: String = boundSerial
        if (serial.isBlank()) return ""
        val device = repo.get(serial)
        return device?.model ?: device?.product ?: serial
    }

    // ------------------------------------------------------------------
    // 会话主流程
    // ------------------------------------------------------------------

    private suspend fun runSession(serial: String, generation: Long) {
        val device = repo.get(serial)
        Logx.i(TAG, "开始投屏：$serial (${device?.model ?: "未知型号"})")

        val cfg: ScrcpyConfig = settings.scrcpyConfig.first()
        currentConfig = cfg

        // ① 部署 + 建立隧道
        _state.value = MirrorState.Deploying
        val info: ScrcpyServerDeployer.StartInfo = deployer.deploy(serial, cfg).getOrThrow()
        startInfo = info
        if (generation != sessionGeneration) {
            Logx.w(TAG, "会话已在部署期间被取消，跳过后续步骤")
            return
        }

        // app_process 刚 exec 时连 /proc/net/unix 里的条目都还没有，先给一小段缓冲；
        // 真正的保障是下面的 [ScrcpyServerDeployer.awaitServerSocket] 探活（200ms 一次，上限 10s），
        // 它确定性保证「server 已 listen 才去连」，从而消除「connect 成功但读握手 EOF」。
        delay(SERVER_BOOT_DELAY_MS)
        if (generation != sessionGeneration) {
            Logx.w(TAG, "会话已在等待期间被取消，跳过握手")
            return
        }
        // ★ 连接前探活：adb forward 建好后，本机端口由 adb server 监听，socket.connect()
        //   永远成功；server 没 listen 好时失败发生在转发/读握手阶段，表现为 EOFException。
        val awaited: Result<Unit> = deployer.awaitServerSocket(serial, info.scid)
        if (awaited.isFailure) {
            // 不走 humanizeAdbError：它是多行取证式文案，会被截成 120 字丢掉排查方向
            val cause: Throwable =
                awaited.exceptionOrNull() ?: IllegalStateException("未知原因（探活未返回异常）")
            Logx.e(TAG, "server 探活失败：${cause.message}")
            _state.value = MirrorState.Failed(
                TEXT_SERVER_NOT_LISTENING + "\n" + (cause.message ?: cause.javaClass.simpleName)
            )
            releaseResources()
            return
        }
        if (generation != sessionGeneration) {
            Logx.w(TAG, "会话已在探活期间被取消，跳过握手")
            return
        }

        // ② 握手 + 起流
        _state.value = MirrorState.Connecting
        var usedInfo: ScrcpyServerDeployer.StartInfo = info
        var attempt: ConnectAttempt = connectAndBind(cfg, info)

        // 固定端口连不上时，改让 adb 动态分配端口重试一次：
        // 27183 一旦被主控端上的其它 adb / 调试工具占用，换端口即可绕开。
        // 只在首轮用的是固定端口（>0）时才重试，避免无限 deploy 出多个 app_process。
        if (!attempt.connected && info.localPort > 0) {
            Logx.w(
                TAG,
                "固定端口 ${info.localPort} 连接失败（${describeThrowableChain(attempt.error)}），" +
                    "改用 adb 动态端口重试一次"
            )
            runCatching { deployer.cleanup(info.localPort, serial) }
            val dynamic: ScrcpyServerDeployer.StartInfo? =
                deployer.deploy(serial, cfg.copy(localPort = DYNAMIC_PORT)).getOrNull()
            if (dynamic != null && dynamic.localPort > 0) {
                startInfo = dynamic
                usedInfo = dynamic
                // 同样先探活再连：换端口不解决「server 还没 listen」的问题
                val ready: Boolean = deployer.awaitServerSocket(serial, dynamic.scid).isSuccess
                if (generation != sessionGeneration) {
                    Logx.w(TAG, "会话已取消，跳过动态端口重试")
                    return
                }
                if (ready) {
                    attempt = connectAndBind(cfg, dynamic)
                } else {
                    Logx.w(TAG, "动态端口部署后 server 仍未就绪（${dynamic.scid}），放弃本次重试")
                }
            }
        }

        if (!attempt.connected) {
            // 诊断文案是「多行取证 + 结论」，不能过 humanizeAdbError（它只保留首行且会命中
            // refused/closed 之类的通用规则把结论抹掉），所以这里直接落到 Failed 状态。
            val diagnosis: String =
                diagnoseConnectFailure(serial, usedInfo, describeThrowableChain(attempt.error))
            Logx.e(TAG, "视频通道连接失败：$diagnosis")
            _state.value = MirrorState.Failed(diagnosis)
            releaseResources()
            return
        }
        Logx.i(TAG, "投屏会话建立完成：scid=${usedInfo.scid}, port=${usedInfo.localPort}")
    }

    /** 一次「建 controller + 订阅帧事件 + 握手」的结果。 */
    private data class ConnectAttempt(
        val connected: Boolean,
        val error: Throwable?
    )

    /**
     * 建立 controller 并握手。
     *
     * 注意：先订阅 [ScrcpyController.frames] 再 connect —— 握手期间发出的 [ScrcpyController.Event.Ready]
     * 才能被收到（事件流是 replay=0 的 SharedFlow）。
     */
    private suspend fun connectAndBind(
        cfg: ScrcpyConfig,
        info: ScrcpyServerDeployer.StartInfo
    ): ConnectAttempt {
        val ctrl: ScrcpyController = ScrcpyController(cfg)
        controller = ctrl

        framesJob?.cancel()
        framesJob = viewModelScope.launch(Dispatchers.IO) {
            ctrl.frames().collect { event -> handleEvent(event) }
        }

        return runCatching {
            val ok: Boolean = ctrl.connect(port = info.localPort)
            // connect 只回 Boolean，真实异常要从 lastConnectError 取
            ConnectAttempt(ok, if (ok) null else ctrl.lastConnectError)
        }.getOrElse { throwable ->
            ConnectAttempt(false, throwable)
        }
    }

    /**
     * 按 socket 异常类型给出根因提示。
     *
     * - `ConnectException`（多为 Connection refused）：本机端口**根本没人监听**
     *   → adb server 的本地监听没起来 / 已死 / 端口被别的进程占用；
     * - `SocketTimeoutException`：端口在监听但**不 accept**
     *   → adb server 进程被冻结，或转发到设备端时卡住；
     * 两者修法完全不同，必须分开提示。
     */
    private fun hintForCause(cause: String): String {
        val lower: String = cause.lowercase()
        return when {
            lower.contains("connectexception") ->
                "含义：本机该端口根本没人监听（adb 本地监听未建立/已死，或端口被其它进程占用）。"

            lower.contains("sockettimeoutexception") || lower.contains("timeoutexception") ->
                "含义：端口在监听但不 accept（adb server 进程被冻结，或转发到设备端卡住）。"

            lower.contains("noroutetohost") || lower.contains("ehostunreach") ->
                "含义：网络层不可达，检查主控端网络状态。"

            lower.contains("eacces") || lower.contains("permission denied") ->
                "含义：权限被拒，检查主控端是否限制了本机 socket。"

            else ->
                "含义：未知类型，请看⓪的类名与 message 原文。"
        }
    }

    /**
     * 把异常链压成「根因类名: message ｜ 上下文」的形式。
     *
     * 例：`java.net.ConnectException: Connection refused ｜ 上下文：IOException: 连接 scrcpy 视频通道失败…`。
     * 走到最深层是为了让⓪ 直接暴露 socket 层的类名 —— 包在外层的 IOException 只提供上下文。
     */
    private fun describeThrowableChain(t: Throwable?): String {
        if (t == null) return "未知（控制器未给出异常）"
        var root: Throwable = t
        while (root.cause != null && root.cause !== root) {
            root = root.cause ?: break
        }
        val rootText: String = describeThrowable(root)
        return if (root === t) {
            rootText
        } else {
            rootText + " ｜ 上下文：" + t.javaClass.simpleName + ": " +
                (t.message?.takeIf { it.isNotBlank() } ?: "（无 message）")
        }
    }

    /**
     * 把异常压成「类名: message」，例如 `java.net.ConnectException: Connection refused`。
     * `ConnectException`（端口没人监听）与 `SocketTimeoutException`（在监听但不 accept）
     * 的根因完全不同，诊断文案必须能看到类名。
     */
    private fun describeThrowable(t: Throwable?): String {
        if (t == null) return "未知（控制器未给出异常）"
        val message: String = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return t.javaClass.name + ": " + message
    }

    /**
     * 连接失败诊断：把「server 没起来」与「forward / 端口有问题」区分开。
     *
     * 取证步骤（每步失败只降级为「未知」，不中断后续取证）：
     * ⓪ 视频 socket 的最后错误文本（由调用方传入，多为 Connection refused / timeout）；
     * ① `adb forward --list` —— 转发规则是否真的建立；
     * ② `cat <server stdout 日志>` —— [ScrcpyServerDeployer.remoteLogPath] 留下的
     *    app_process / dalvik 层线索；
     * ③ `logcat -d -v brief | grep -i -E 'scrcpy|app_process|AndroidRuntime' | tail -60`
     *    —— server 自身崩溃线索。⚠️ scrcpy 的 Ln 走 `android.util.Log`（tag: scrcpy），
     *    且 `internalMain` 会调用 `Ln.disableSystemStreams()` 关掉 System.out/err，
     *    所以「版本号不匹配」这类最典型的错误**只进 logcat、不进 ②**。
     *    logcat 必须带 `-d`（dump 完立即退出，否则会一直阻塞并撞上超时）；
     *    先 grep 再 tail，避免整份 logcat 缓冲撑爆内存与 UI；超时单独给 6 秒；
     * ④ `cat /proc/net/unix | grep scrcpy` —— 设备端抽象 socket 是否存在
     *    （存在＝server 起来了，问题在 forward/端口；不存在＝server 没起来，
     *     问题在 push / 版本号不匹配 / app_process 启动失败）。
     *
     * @param serial 目标设备序列号
     * @param info   本次部署结果（端口 / scid）
     * @param cause  视频 socket 连接阶段的最后错误文本（可能为空）
     */
    private suspend fun diagnoseConnectFailure(
        serial: String,
        info: ScrcpyServerDeployer.StartInfo,
        cause: String
    ): String {
        val builder = StringBuilder()
        builder.append(TEXT_CONNECT_FAILED).append("（端口 ").append(info.localPort).append("）\n")
        if (cause.isNotBlank()) {
            builder.append("⓪ socket 错误：").append(cause.trim()).append("\n")
            builder.append("   ").append(hintForCause(cause)).append("\n")
            // 回环场景的人话翻译 + 建议：这里绝不能走默认场景，否则 timeout 会被
            // 译成「网络不通 / AP 隔离」——127.0.0.1 跟路由器设置没有任何关系。
            builder.append("   人话：")
                .append(humanizeAdbError(cause, ErrorContext.LOOPBACK, info.localPort))
                .append("\n")
            builder.append("   建议：")
                .append(AdbErrors.advice(cause, ErrorContext.LOOPBACK, info.localPort))
                .append("\n")
        }

        // ① 转发规则
        val forwardText: String = deployer.adb.forwardList(serial)
            .getOrDefault(emptyList())
            .joinToString(" | ")
            .trim()
        val forwardOk: Boolean = forwardText.contains("tcp:${info.localPort}")
        builder.append(
            if (forwardOk) {
                "① 端口转发已建立：$forwardText\n"
            } else {
                "① 端口转发缺失（forward --list 里没有 tcp:${info.localPort}）" +
                    if (forwardText.isBlank()) "\n" else "，当前规则：$forwardText\n"
            }
        )

        // ② server stdout 日志（app_process / dalvik 层错误）
        val logText: String = deployer.adb.shell(
            serial,
            "cat ${deployer.remoteLogPath} 2>/dev/null | head -n 20",
            DIAGNOSIS_TIMEOUT_MS
        ).getOrNull()?.stdout.orEmpty().trim()
        if (logText.isBlank()) {
            builder.append("② server stdout 日志为空（未产生任何输出）\n")
        } else {
            builder.append("② server stdout 日志：$logText\n")
        }

        // ③ 设备 logcat（server 自身的 android.util.Log，含最常见的「版本不匹配」）
        //    -d 必须保留：不加会一直阻塞，必然撞超时；grep 后再 tail 防止整份缓冲撑爆。
        val logcatText: String = deployer.adb.shell(
            serial,
            "logcat -d -v brief | grep -i -E 'scrcpy|app_process|AndroidRuntime' | tail -60",
            LOGCAT_DIAGNOSIS_TIMEOUT_MS
        ).getOrNull()?.stdout.orEmpty().trim()
        if (logcatText.isBlank()) {
            builder.append(
                "③ 设备 logcat 中未发现 scrcpy 相关日志" +
                    "（说明 server 进程压根没起来，连 logcat 都没写）\n"
            )
        } else {
            builder.append("③ 设备 logcat（scrcpy/app_process/AndroidRuntime）：\n")
            logcatText.lineSequence()
                .filter { it.isNotBlank() }
                .take(LOGCAT_MAX_LINES)
                .forEach { line -> builder.append("    ").append(line.trim()).append("\n") }
        }

        // ④ 设备端抽象 socket
        val unixText: String = deployer.adb.shell(
            serial,
            "cat /proc/net/unix 2>/dev/null | grep scrcpy",
            DIAGNOSIS_TIMEOUT_MS
        ).getOrNull()?.stdout.orEmpty().trim()
        val serverAlive: Boolean = unixText.contains("scrcpy")
        if (serverAlive) {
            builder.append("④ 设备端 abstract socket 已存在 —— server 已启动，问题多半在「端口转发/本机端口」\n")
        } else {
            builder.append("④ 设备端 abstract socket 不存在 —— server 没起来，问题在「push / 版本号不匹配 / app_process 启动」\n")
        }

        // 结论
        // 回环链路的人话文案：cause 为空时给一个不含端口猜测的中性说法，避免出现「未知错误」这种废话。
        val loopbackText: String = if (cause.isBlank()) {
            "本机端口 ${info.localPort} 连接失败（socket 未给出具体原因）"
        } else {
            humanizeAdbError(cause, ErrorContext.LOOPBACK, info.localPort)
        }
        val conclusion: String = when {
            !forwardOk && !serverAlive ->
                "结论：server 未启动且转发规则缺失 —— 先看③设备 logcat（版本串错配会秒退且只写 logcat），再重试投屏。"

            !forwardOk && serverAlive ->
                "结论：server 已启动但转发规则丢失 —— 重新投屏会自动先清理再建规则；若反复出现请检查是否有其它 adb 占用端口 ${info.localPort}。"

            forwardOk && !serverAlive ->
                "结论：转发已建但 server 退出 —— 基本是 server 版本与 ScrcpyConfig.serverVersion 不一致，或设备不支持该参数组合（见③设备 logcat）。"

            else ->
                // ★ 关键：server 已启动、抽象 socket 在、转发规则也在，
                // 那么断点只可能是「App 自己连本机 127.0.0.1:${info.localPort} 连不上」，
                // 与分辨率/码率无关（旧文案误导，已修正）。若首轮用的是固定端口 27183，
                // 上面已自动用 adb 动态端口重试过一次，仍失败则是本机 adb server 的问题。
                "结论：server 与转发都正常，断点在「App 连本机 127.0.0.1:${info.localPort}」——" +
                    loopbackText +
                    "。这属于本机 adb 监听问题（与分辨率/码率无关，也不是同网段/AP 隔离问题）：" +
                    AdbErrors.advice(cause, ErrorContext.LOOPBACK, info.localPort) +
                    "；必要时重启本 App 或执行 adb kill-server 后重试。"
        }
        builder.append(conclusion)
        return builder.toString()
    }

    private suspend fun handleEvent(event: ScrcpyController.Event) {
        when (event) {
            is ScrcpyController.Event.Ready -> {
                applyRemoteSize(event.w, event.h, "握手 stream meta")
                resetStats()
                // 已经重新出图：自动重连成功，计数归零（下次断流重新给满次数）
                reconnectAttempt = 0
                Logx.i(TAG, "流就绪：${event.w}x${event.h} name=${event.deviceName}")
                // 视频先出图；音频随后由 AudioReady / AudioDisabled 事件补上状态。
                // 开了音频就先挂一句「连接中…」，避免 UI 上音频开关看起来没反应。
                val note: String =
                    if (currentConfig?.audioEnabled == true) TEXT_AUDIO_CONNECTING else ""
                _state.value = MirrorState.Streaming(
                    width = event.w,
                    height = event.h,
                    audioNote = note
                )
                ensureDecoder()
            }

            is ScrcpyController.Event.Frame -> onFrame(event)

            is ScrcpyController.Event.AudioReady -> {
                Logx.i(TAG, "音频流就绪：codecId=0x${event.codecId.toString(16)}")
                startAudio(codecId = event.codecId)
            }

            is ScrcpyController.Event.AudioDisabled -> {
                // 被控端 Android 11 以下本来就不支持音频采集，这里必须**降级为无声投屏**：
                // 绝不能置 Failed —— 用户要的是「至少能看到画面」。
                Logx.w(TAG, "设备端未启用音频，降级为无声投屏：${event.reason}")
                updateAudioState(active = false, note = event.reason.ifBlank { TEXT_AUDIO_DEGRADED })
            }

            is ScrcpyController.Event.AudioFrame -> {
                // 解码器还没建好就直接丢弃。**不要**在这里建：AudioReady 与 AudioFrame 是两条
                // 并发投递的路径，在这里建会和 startAudio 抢同一个解码器。
                audioDecoder?.submit(event.data, event.pts, event.config)
            }

            is ScrcpyController.Event.Error -> {
                if (intentionalStop) return
                // ★ 断流原因必须落到日志里带**异常类型**。
                // 只打 `throwable.message` 是不够的：EOFException 的 message 恒为 null，
                // 于是日志上只看到「读取循环结束：null」，无法区分
                // 「对端关闭（EOF）」「超时」「连接被重置」「本机已 close」这四种完全不同的故障。
                val detail: String = describeStreamError(event.throwable)
                Logx.e(TAG, "流异常：$detail")
                // 流异常发生在「App → 本机 127.0.0.1:<forward 端口>」这条回环链路上，
                // 必须传 LOOPBACK：否则 message 里的 timeout 会被误译成「网络不通 / AP 隔离」。
                val message: String = humanizeAdbError(
                    event.throwable.message ?: TEXT_STREAM_ERROR,
                    ErrorContext.LOOPBACK,
                    startInfo?.localPort ?: 0
                )
                onStreamLost("$message（$detail）")
            }

            ScrcpyController.Event.Closed -> {
                Logx.i(TAG, "通道关闭事件")
                if (intentionalStop) return
                val current = _state.value
                if (current is MirrorState.Streaming || current is MirrorState.Connecting) {
                    onStreamLost(TEXT_DISCONNECTED)
                }
            }
        }
    }

    /**
     * 断流当下抓一次 `adb forward --list` 快照并写日志，**返回隧道是否仍在**。
     *
     * 这是区分两类故障的决定性证据：
     * - 列表里**没有** `tcp:<port>` → 隧道被回收（adb server 重启 / 系统杀后台进程）。
     *   这类重连能救，因为 [restartInternal] 会完整重跑一遍 forward。
     * - 列表里**还有** `tcp:<port>` → 隧道是好的，说明是被控端 scrcpy server 自己退出了
     *   （编码器崩溃 / 被系统杀 / 收到不支持的消息），重连多半无效，得去看 server 日志。
     *
     * @return 隧道是否仍在（列表里含 `tcp:$port`）；无法定位设备（serial 空 / port<=0）也返回 false。
     */
    private suspend fun logTunnelSnapshot(): Boolean {
        val serial: String = boundSerial
        val port: Int = startInfo?.localPort ?: 0
        if (serial.isBlank() || port <= 0) {
            Logx.w(TAG, "断流诊断：无法定位隧道（serial=${serial.ifBlank { "<空>" }}, port=$port）")
            return false
        }
        val rules: List<String> = runCatching { deployer.adb.forwardList(serial).getOrDefault(emptyList()) }
            .onFailure { Logx.w(TAG, "断流诊断：读取 forward 列表失败：${it.message}") }
            .getOrDefault(emptyList())
        val alive: Boolean = rules.any { it.contains("tcp:$port") }
        if (alive) {
            Logx.w(
                TAG,
                "断流诊断：隧道仍在（tcp:$port 在列表里）→ 被控端 server 主动退出可能性大；" +
                    "当前规则=${rules.ifEmpty { listOf("<空>") }}"
            )
        } else {
            Logx.w(
                TAG,
                "断流诊断：隧道已消失（tcp:$port 不在列表里）→ adb 隧道被回收，重连可重建；" +
                    "当前规则=${rules.ifEmpty { listOf("<空>") }}"
            )
        }
        return alive
    }

    /**
     * 断流且「隧道仍在」时，抓取被控端 scrcpy server 侧的退出证据。分两档：
     *
     * - `full = false`（精简档，每次断流都跑）：只 2 条快命令 —— ① `ps -A | grep scrcpy`
     *   看进程是否还在、② `logcat -d -s scrcpy`（空则宽过滤）看 server 自己的日志。
     *   整体 [withTimeoutOrNull] 8s 兜底，正好落在重连的退避窗口里，不阻塞重连。
     * - `full = true`（完整档，仅在放弃重连时跑）：在精简档基础上再加 ③ nohup 重定向的
     *   stderr 文件（`deployer.remoteLogPath`，含版本不匹配 / FATAL 原文）、④ `logcat -b events`
     *   看是否被系统 LMK / 低内存杀。整体 [withTimeoutOrNull] 25s 兜底。
     *
     * 调用方已确认隧道是好的，那断流就只可能是被控端 server 主动关了 video socket。
     * 约束：每条命令 `timeoutMs=6_000`，`-d` 必须保留（否则阻塞撞超时），`-t` 限行数降负载，
     * 输出截断（logcat 类 2000、stderr 类 1500 字符），每条 `getOrNull` 包住互不拖累。
     * 本函数必须在独立协程里跑，不要在调用方同步 await。
     *
     * @param port 仅用于签名保留（当前无独立用途），调用方已用 [logTunnelSnapshot] 确认隧道在。
     */
    private suspend fun diagnoseServerExit(serial: String, port: Int, full: Boolean = false) {
        val cut: (String, Int) -> String = { s, n -> if (s.length > n) s.takeLast(n) else s }
        val shell: suspend (String) -> String = { cmd ->
            deployer.adb.shell(serial, cmd, 6_000)
                .getOrNull()?.stdout.orEmpty().trim()
        }
        val prefix: String = if (full) "server 取证" else "server 取证(简)"
        val timeout: Long = if (full) 25_000L else 8_000L

        val result: String? = withTimeoutOrNull(timeout) {
            // ① server 进程是否还活着
            val ps = shell("ps -A 2>/dev/null | grep -i scrcpy")
            val alive = ps.isNotBlank()
            Logx.w(TAG, "$prefix①进程：${if (alive) "仍在 → $ps" else "已退出（进程不在）"}")

            // ② server 自己的 logcat（tag=scrcpy）；为空回退宽过滤
            // ⚠️ 不要给 logcat 加 `-t <n>`：toybox 的 `-t` 是「取整个缓冲区最后 n 行」，
            // 设备日志一忙，scrcpy 那几行早被挤出窗口，按 tag 过滤后恒为空 ——
            // 真机上连续 14+ 次取证输出为空就是这个原因。改为先过滤、再用 tail 截断。
            val lcNarrow = shell("logcat -d -v brief -s scrcpy 2>/dev/null | tail -40")
            val lc = if (lcNarrow.isNotBlank()) {
                lcNarrow
            } else {
                shell(
                    "logcat -d -v brief | grep -i -E 'scrcpy|app_process|AndroidRuntime|FATAL|OutOfMemory' | tail -40"
                )
            }
            Logx.w(TAG, "$prefix②logcat：\n" + cut(lc, 2000))

            // ③ 两条都跑（不再只在完整档跑）：`cat` 是纯文件读取、毫秒级返回，不会拖慢断流诊断；
            // 而「轻档」（每次断流都跑）恰恰是我们最常拿到日志的场景，之前只在放弃重连时才
            // cat，导致绝大多数断流拿不到这份最直接的证据。
            val rl = deployer.remoteLogPath.let { path ->
                shell("cat $path 2>/dev/null | tail -n 30").also {
                    Logx.w(TAG, "$prefix③stderr 文件：\n" + cut(it, 1500))
                }
            }
            // ④ 仅完整档（扫 events 缓冲比上面几条重，轻档跑在重连退避窗口里不能拖太久）
            val ev = if (full) {
                shell(
                    // 同样的坑：`-t 200` 会在过滤前截断，events 缓冲里真正想要的
                    // am_kill 行常常不在最后 200 行里。改为先 grep 再 tail。
                    "logcat -d -b events | grep -i -E 'am_kill|am_low_memory|am_anr|onTrimMemory' | tail -20"
                ).also { Logx.w(TAG, "$prefix④LMK/低内存事件：${if (it.isBlank()) "无" else it}") }
            } else ""

            // 聚合结论（轻档只有 ps + logcat，ev/rl 为空，按可用数据判读）
            val lowerLc = lc.lowercase()
            val lowerRl = rl.lowercase()
            val killedBySystem = ev.contains("am_kill") || ev.contains("am_low_memory")
            val versionOrFatal = lowerLc.contains("version") || lowerLc.contains("fatal") ||
                lowerRl.contains("version") || lowerRl.contains("fatal")
            val conclusion: String = when {
                !alive && versionOrFatal ->
                    "server 因版本/参数错误自行退出（见②/③）"
                !alive && killedBySystem ->
                    "server 被系统杀（LMK / 后台限制，见④）"
                alive ->
                    "video socket 已断但 server 进程仍在，问题在链路或客户端侧"
                else ->
                    "原因未定，见上方原始输出"
            }
            // 注意必须用 ${prefix} 加大括号：Kotlin 的标识符允许 CJK 字母，
            // `$prefix结论` 会被当成一个标识符 `prefix结论` 解析而报 Unresolved reference。
            "${prefix}结论：$conclusion"
        }

        if (result == null) {
            Logx.w(TAG, "$prefix：诊断超时（${timeout}ms）已放弃")
        } else {
            Logx.w(TAG, result)
        }
    }

    /**
     * 投屏中途断流：先尝试自动重连，次数用尽才置 [MirrorState.Failed]。
     *
     * 为什么必须自动重连：**断流最常见的成因是控制端的 adb forward 隧道失效**
     * （adb server 被系统回收 / 重启后，之前 `adb forward` 建的映射就没了），
     * 此时服务端与网络其实都还好好的。`restart()` 会完整重跑一遍
     * 「push server → adb forward → 启动 → 握手」，正好能重建隧道。
     * 在平板上这尤其常见：大屏设备对后台子进程更激进，隧道更容易被回收。
     *
     * 受设置项「自动重连」控制（[AppSettings.autoReconnect]，默认开）。
     */
    private suspend fun onStreamLost(reason: String) {
        // ⚠️ 快照必须抓在 releaseResources() **之前**：清理流程会顺手把 forward 规则删掉，
        // 之后再查就恒为「缺失」，也就分不清「隧道本来就没了」和「是我们自己删的」。
        val serial: String = boundSerial
        val port: Int = startInfo?.localPort ?: 0
        val tunnelAlive: Boolean = logTunnelSnapshot()
        // 客户端渲染侧快照（纯读内存字段，同步、零开销）：说明解码器有没有在出图 / Surface 对不对
        logClientRenderSnapshot()

        if (!autoReconnect || reconnectAttempt >= MAX_AUTO_RECONNECT) {
            _state.value = MirrorState.Failed(reason)
            // 放弃重连：跑完整档（含 remoteLogPath stderr + LMK 事件），定位 server 退出根因。
            if (tunnelAlive && serial.isNotBlank() && port > 0) {
                viewModelScope.launch(Dispatchers.IO) { diagnoseServerExit(serial, port, full = true) }
            }
            releaseResources()
            return
        }
        reconnectAttempt++
        Logx.w(TAG, "投屏中断，自动重连 $reconnectAttempt/$MAX_AUTO_RECONNECT：$reason")
        _state.value = MirrorState.Reconnecting(
            attempt = reconnectAttempt,
            maxAttempt = MAX_AUTO_RECONNECT,
            reason = reason
        )
        // 每次断流都跑精简档（ps + logcat），落在下面 delay 的重连退避窗口里（首次 1200ms），
        // 不阻塞 releaseResources 与重连；ps/logcat 走独立 adb 进程，不与重连的 push/forward 抢通道。
        // 仍只在隧道仍在时跑：隧道没了就是 adb 回收，抓 server 没意义。
        if (tunnelAlive && serial.isNotBlank() && port > 0) {
            viewModelScope.launch(Dispatchers.IO) { diagnoseServerExit(serial, port, full = false) }
        }
        releaseResources()
        viewModelScope.launch(Dispatchers.IO) {
            // 线性退避：隧道刚失效时立刻重试大概率还是失败，给系统一点恢复时间
            delay(RECONNECT_BACKOFF_MS * reconnectAttempt)
            if (intentionalStop) return@launch
            restartInternal()
        }
    }

    /**
     * 断流时同步打一次**客户端渲染侧**快照（纯读内存字段，无 adb、开销可忽略）。
     *
     * 与 [diagnoseServerExit] 互补：server 侧证据说明「server 为什么退」，这里说明
     * 「客户端解码器到底有没有在出图 / Surface 身份对不对」，用来区分两类故障：
     * - [surfaceRebindFailCount] 大且 [surfaceAttachCount] 大 → Surface 高频抖动 + 本平台
     *   `setOutputSurface` 不可用（MTK 等）；
     * - 渲染计数在断流前完全没增长 → 解码器从未出图，问题在服务端或握手；
     * - 渲染计数正常增长但 Surface 身份（`surface` vs `decoder?.boundSurface()`）不一致 →
     *   渲染到了错误的 Surface 上。
     */
    private fun logClientRenderSnapshot() {
        val dec: VideoDecoder? = decoder
        val submitted: Long = dec?.submittedFrames() ?: 0L
        val rendered: Long = dec?.renderedFrames() ?: 0L
        val dropped: Long = dec?.droppedFrames() ?: 0L

        val surf: Surface? = surface
        val bound: Surface? = dec?.boundSurface()
        val surfHash: String = "0x" + Integer.toHexString(System.identityHashCode(surf))
        val boundHash: String = "0x" + Integer.toHexString(System.identityHashCode(bound))
        val same: Boolean = (surf != null && bound != null && surf === bound)

        val w: Int = remoteWidth
        val h: Int = remoteHeight

        val sb = StringBuilder()
        sb.append("断流诊断(客户端)：")
            .append("attach=").append(surfaceAttachCount)
            .append(" null=").append(surfaceNullCount)
            .append(" same=").append(surfaceSameCount)
            .append(" unbound=").append(surfaceUnboundCount)
            .append(" rebindOk=").append(surfaceRebindOkCount)
            .append(" rebindFail=").append(surfaceRebindFailCount)
            .append(" rebuilt=").append(surfaceRebuiltCount)
            // supp：本设备是否已被判定为「不支持 setOutputSurface」。
            // rebindFail 每会话最多为 1（真正调用过并失败），所以判读要靠 supp 而不是次数。
            .append(" supp=").append(setOutputSurfaceUnsupported)
            .append(" | decoder 提交=").append(submitted)
            .append(" 渲染=").append(rendered)
            .append(" 丢帧=").append(dropped)
            .append(" | surface=").append(surfHash)
            .append(" bound=").append(boundHash)
            .append(" 相同=").append(same)
        if (w > 0 && h > 0) {
            sb.append(" | 远端 ").append(w).append("x").append(h)
        }

        // 聚合判读（优先级从高到低）
        val verdict: String = when {
            surfaceUnboundCount > 0 && !setOutputSurfaceUnsupported && surfaceRebindFailCount == 0 ->
                "Surface 从未换绑（unbound 是解码器创建期空窗，属正常）"
            setOutputSurfaceUnsupported && surfaceAttachCount >= 3 ->
                "Surface 高频抖动，且本设备 setOutputSurface 不可用（已跳过换绑改走重建）"
            surfaceRebindFailCount >= 3 && surfaceAttachCount >= 3 ->
                "Surface 高频抖动，且本平台 setOutputSurface 不可用"
            rendered == 0L ->
                "解码器从未出图，问题在服务端或握手"
            !same && surf != null && bound != null ->
                "渲染到了错误的 Surface 上"
            else -> ""
        }
        if (verdict.isNotBlank()) {
            sb.append(" ｜ ").append(verdict)
        }
        Logx.w(TAG, sb.toString())
    }

    /** 把断流异常压成「类型: message」，保证日志里能看到异常类名（EOFException 等）。 */
    private fun describeStreamError(t: Throwable): String {
        val name: String = t.javaClass.simpleName
        val msg: String = t.message?.takeIf { it.isNotBlank() } ?: "无附加信息"
        return "$name: $msg"
    }

    private fun onFrame(frame: ScrcpyController.Event.Frame) {
        val now: Long = System.currentTimeMillis()
        val size: Int = frame.data.size

        // 冷却期内被跳过的 Surface 换绑，在这里补做（保证最终一致，避免冷却/熔断吞掉修复）。
        // 节流到 1 秒一次：重新走一遍 attachSurface 的换绑/重建判定（冷却/防重入/熔断会再次生效），
        // 因此不会绕开三道闸。条件已在 attachSurface 内复核，这里只负责「触发 + 节流」。
        if (pendingRebind && now - lastRebindCheckMs >= REBIND_CHECK_MS) {
            lastRebindCheckMs = now
            val s: Surface? = surface
            if (s != null) {
                attachSurface(s)
            }
        }

        if (windowStartMs == 0L) {
            windowStartMs = now
        }

        windowFrames++
        windowBytes += size

        val elapsed: Long = now - windowStartMs
        if (elapsed >= STATS_WINDOW_MS) {
            lastFps = (windowFrames * 1000L / elapsed).toInt()
            // 单位换算集中在 util/StatsFormat.kt（那边有单测守着）：
            // 之前写成 `bytes * 8 / 1000 / elapsed`，算出来是「kbit 每毫秒」，
            // 比真实值小 1000 倍，再经显示端一除，8 Mbps 的流会恒显示 0 Mbps。
            lastKbps = bitrateKbps(windowBytes, elapsed)
            windowStartMs = now
            windowFrames = 0
            windowBytes = 0L
        }

        // 延迟估计：实际到达时刻 - 按 PTS 推算的应到时刻（指数平滑）。
        // ⚠️ 参考点只能用「数据帧」建立，且必须跳过 config 包：
        // 流里第一个包是 config 包（SPS/PPS），scrcpy 给 config 包的 PTS 位恒为 0
        // （ptsAndFlags 高 2 位被 FLAG_CONFIG 占满，见 ScrcpyController 解包逻辑），
        // 而数据帧的 PTS 是被控端 System.nanoTime 量级的「开机时长微秒数」——
        // 设备开机几小时就能到 1e10。若把 config 包的 0 当参考，
        // expectedMs 会被推到数小时之后，(now - expectedMs) 恒为负、被钳到 0
        // → 延迟永远显示 0。pts 倒退（异常防御）时也重新建立参考，避免被旧参考卡死。
        if (!frame.config) {
            if (firstFramePtsUs == 0L || frame.pts < firstFramePtsUs) {
                firstFrameWallMs = now
                firstFramePtsUs = frame.pts
            }
            val expectedMs: Long = firstFrameWallMs + (frame.pts - firstFramePtsUs) / 1000L
            val instant: Long = (now - expectedMs).coerceIn(0L, MAX_LATENCY_MS)
            estimatedLatencyMs = ((estimatedLatencyMs.toLong() * 3L + instant) / 4L).toInt()
        }

        if (paused) {
            // 后台：保留连接但不解码（相当于降帧保护）
            droppedFrames++
            publishStats()
            return
        }

        val decoderRef: VideoDecoder? = decoder
        if (decoderRef == null) {
            droppedFrames++
            publishStats()
            return
        }
        decoderRef.submit(frame.data, frame.pts, frame.keyframe, frame.config)
        publishStats()
    }

    private fun publishStats() {
        val current = _state.value
        if (current !is MirrorState.Streaming) return
        // 丢帧 = 本会话累计「收到却没能渲染」的帧数，两部分都要算：
        // ① ViewModel 层丢弃的（后台暂停降帧保护 / 解码器还没建好）—— droppedFrames；
        // ② VideoDecoder 内部丢弃的（IDR 之前的非关键帧 / 解码器未启动 / 输入队列反压超限）。
        // 之前只接第①部分，正常投屏时丢帧恒显示 0，等于这个统计没生效。
        val decoderDropped: Long = decoder?.droppedFrames() ?: 0L
        val totalDropped: Long = droppedFrames.toLong() + decoderDropped
        _state.value = current.copy(
            fps = lastFps,
            kbps = lastKbps,
            latencyMs = estimatedLatencyMs,
            dropped = totalDropped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
    }

    private fun resetStats() {
        windowStartMs = 0L
        windowFrames = 0
        windowBytes = 0L
        lastFps = 0
        lastKbps = 0
        estimatedLatencyMs = 0
        droppedFrames = 0
        firstFrameWallMs = 0L
        firstFramePtsUs = 0L
        // 新一轮出图：重新武装「首次注入后取证」，否则重连后不会再抓
        injectionDiagPulled = false
    }

    /**
     * 更新被控端画面尺寸，并同步对外投影。
     *
     * @param reason 日志用，标明这次尺寸是哪来的（握手 / 解码格式变化）
     */
    private fun applyRemoteSize(width: Int, height: Int, reason: String) {
        if (width <= 0 || height <= 0) return
        if (remoteWidth == width && remoteHeight == height) return

        // 编码器会把宽高对齐到 16 的倍数（例：1080 编出来报 1088），这种同方向内的
        // 对齐级抖动不值得重建解码器、抖一下画面。但「横竖切换」必须无条件认账 ——
        // 那正是本功能要跟的东西。
        //
        // ⚠️ 这个闸门同时**意外地保护了触控注入**，改动前务必读懂：
        // 服务端 `PositionMapper.map()` 拿我们发的 screenW/screenH 与它自己的 videoSize
        // 做**严格相等**比较，不等就丢弃整条触摸事件（见 ControlProtocol.injectTouch 的
        // KDoc）。而解码器上报的尺寸**不一定等于** videoSize —— 编码器若写了 H.264 的
        // 16 对齐 padding 却**没写 crop 矩形**，解码器就会报 pad 后的尺寸（1080→1088）。
        // 此时闸门把这个 8px 差异吞掉，我们继续用握手拿到的真实 videoSize，恰好是对的。
        // 曾尝试「无条件采纳解码器最新值」，会在上述设备上主动制造触控全丢，已撤回。
        // 真机 140 次会话验证：本闸门从未被「解码格式变化」触发（握手值==最终填值）。
        if (remoteWidth > 0 && remoteHeight > 0) {
            val sameOrientation: Boolean = (remoteWidth >= remoteHeight) == (width >= height)
            val widthDelta: Int = abs(width - remoteWidth)
            val heightDelta: Int = abs(height - remoteHeight)
            if (sameOrientation && widthDelta <= SIZE_TOLERANCE_PX && heightDelta <= SIZE_TOLERANCE_PX) {
                return
            }
        }
        remoteWidth = width
        remoteHeight = height
        _remoteSize.value = RemoteSize(width, height)
        Logx.i(
            TAG,
            "远端尺寸更新（$reason）：${width}x${height}，" +
                "方向=${if (width >= height) "横屏" else "竖屏"}"
        )
    }

    /**
     * 轮换窗口方向策略：跟随 → 竖屏 → 横屏 → 跟随。
     * 投屏页据此设置 `Activity.requestedOrientation`。
     */
    fun cycleOrientationMode() {
        val next: OrientationMode = when (_orientationMode.value) {
            OrientationMode.FOLLOW_REMOTE -> OrientationMode.PORTRAIT
            OrientationMode.PORTRAIT -> OrientationMode.LANDSCAPE
            OrientationMode.LANDSCAPE -> OrientationMode.FOLLOW_REMOTE
        }
        _orientationMode.value = next
        Logx.i(TAG, "窗口方向策略切换为 $next（远端 ${remoteWidth}x${remoteHeight}）")
    }

    private fun ensureDecoder() {
        // 闸②防重入：attachSurface（UI 线程）与 onFrame（IO 线程）都可能触发重建，
        // 若已有重建在途则直接忽略本次，避免叠加出多个 MediaCodec 实例。
        if (rebuildingDecoder) {
            Logx.w(TAG, "ensureDecoder 重入保护：已有重建在途，忽略本次")
            return
        }
        val target: Surface = surface ?: run {
            Logx.w(TAG, "Surface 尚未就绪，暂不创建解码器")
            return
        }
        val width: Int = remoteWidth
        val height: Int = remoteHeight
        if (width <= 0 || height <= 0) {
            return
        }

        rebuildingDecoder = true
        try {
            // 记录本次重建时间点，供 attachSurface 冷却判定（布局抖动连发 surfaceChanged 时
            // 避免秒级「建→拆→建」把平台同时存在的 MediaCodec 实例数撑爆）。
            lastDecoderRebuildMs = System.currentTimeMillis()

            decoder?.stopAndRelease()
            decoder = null
            decoderEventsJob?.cancel()

            val next: VideoDecoder = VideoDecoder()
            decoder = next
            decoderEventsJob = next.events
                .onEach { event ->
                    when (event) {
                        is VideoDecoder.DecoderEvent.Error -> {
                            if (intentionalStop) return@onEach
                            val message: String =
                                TEXT_DECODER_FAILED + "（${event.throwable.message ?: "未知原因"}）"
                            Logx.e(TAG, message)
                            _state.value = MirrorState.Failed(message)
                        }

                        is VideoDecoder.DecoderEvent.SizeChanged -> {
                            if (intentionalStop) return@onEach
                            // 被控端旋转：服务端不重发流头，新尺寸只能从 MediaCodec 的
                            // 格式变化里拿到。更新尺寸即可 —— 绝不在这里重建解码器：
                            // ① `INFO_OUTPUT_FORMAT_CHANGED` 本来就是 MediaCodec 表达「分辨率变了、
                            //    继续解码」的机制，重建纯属多余；
                            // ② 解码器 start() 之后会**立刻**上报一次初始格式（尺寸与当前相同），
                            //    若此处无条件 ensureDecoder()，就会「建 → 拆 → 再建」无限循环，
                            //    每次都在 configure 半途被 release，抛出 message 为空的
                            //    IllegalStateException（真机上表现为「解码器启动失败」+ 日志里出现
                            //    两个 MediaCodec 实例）。
                            // 画面尺寸变化由 Compose 侧重新布局 SurfaceView 承接，SurfaceView 自己
                            // 会把新分辨率的画面缩放到新视图尺寸，不需要动解码器。
                            applyRemoteSize(event.width, event.height, "解码格式变化")
                            val current: MirrorState = _state.value
                            if (current is MirrorState.Streaming) {
                                _state.value = current.copy(width = event.width, height = event.height)
                            }
                        }

                        VideoDecoder.DecoderEvent.Stopped -> Unit
                    }
                }
                .launchIn(viewModelScope)

            val ok: Boolean = next.start(width = width, height = height, surface = target)
            if (!ok) {
                // 清掉僵尸解码器：start 失败时 next 的 outputSurface / codec 均为 null，
                // 若不把 decoder 置回 null，下次 attachSurface 会拿到「dec != null 但
                // boundSurface() == null」的僵尸 → 走「换绑失败 → 又重建」死循环。
                next.stopAndRelease()
                decoder = null
                decoderEventsJob?.cancel()
                decoderEventsJob = null
                // 闸③熔断计数：一轮会话内重建失败累计达上限即停止再尝试（见 attachSurface）。
                decoderRebuildCount++
                Logx.e(TAG, "解码器启动失败（重建 ${decoderRebuildCount}/$MAX_DECODER_REBUILD）")
                _state.value = MirrorState.Failed(TEXT_DECODER_FAILED)
            } else {
                // 重建成功：重置熔断计数与挂起标记
                decoderRebuildCount = 0
                pendingRebind = false
            }
        } finally {
            rebuildingDecoder = false
        }
    }

    /**
     * 启动音频解码（收到 [ScrcpyController.Event.AudioReady] 后调用）。
     *
     * 幂等：同一会话内只允许存在一个 [AudioDecoder]，重复事件直接忽略 —— 否则会出现
     * 两个 AudioTrack 抢放、或解码器在 configure 半途被 release（真机表现为「音频杂音」）。
     *
     * 音频是**可降级通道**：任何失败都只写日志与提示，不影响视频与投屏状态机。
     *
     * @param codecId 服务端音频流头里的大端 codec id（RAW / OPUS / AAC / FLAC）
     */
    private fun startAudio(codecId: Int) {
        if (audioEventsJob?.isActive == true || audioDecoder != null) {
            Logx.d(TAG, "音频解码器已存在，忽略重复的 AudioReady")
            return
        }
        // 优先用当前会话的配置，避免为了一个 int 再读一次磁盘
        val bufferMs: Int = currentConfig?.audioBufferMs ?: DEFAULT_AUDIO_BUFFER_MS

        val next: AudioDecoder = AudioDecoder()
        audioDecoder = next
        audioEventsJob = next.events
            .onEach { event ->
                when (event) {
                    is AudioDecoder.AudioEvent.Started -> {
                        Logx.i(
                            TAG,
                            "音频解码已启动：${event.sampleRate}Hz/${event.channels}ch，" +
                                "passthrough=${event.passthrough}"
                        )
                        updateAudioState(active = true, note = "")
                    }

                    is AudioDecoder.AudioEvent.Error -> {
                        // 音频故障绝不能拖垮投屏：只降级为无声 + 释放半死的解码器
                        Logx.e(
                            TAG,
                            "音频解码异常（已降级为无声投屏）：" +
                                (event.throwable.message ?: event.throwable.javaClass.simpleName)
                        )
                        updateAudioState(active = false, note = TEXT_AUDIO_DEGRADED)
                        audioDecoder?.stopAndRelease()
                    }

                    AudioDecoder.AudioEvent.Stopped -> Logx.d(TAG, "音频解码已停止")
                }
            }
            .launchIn(viewModelScope)

        val ok: Boolean = next.start(codecId = codecId, bufferMs = bufferMs)
        if (!ok) {
            Logx.w(
                TAG,
                "音频解码器启动失败（codecId=0x${codecId.toString(16)}，buffer=${bufferMs}ms），降级为无声投屏"
            )
            updateAudioState(active = false, note = TEXT_AUDIO_START_FAILED)
        }
    }

    /**
     * 更新音频相关的展示位。
     *
     * 只在 [MirrorState.Streaming] 下生效（音频事件必然晚于视频 [ScrcpyController.Event.Ready]）；
     * 传 null 表示该字段保持不变。
     */
    private fun updateAudioState(active: Boolean? = null, note: String? = null) {
        val current: MirrorState = _state.value
        if (current !is MirrorState.Streaming) return
        val nextActive: Boolean = active ?: current.audioActive
        val nextNote: String = note ?: current.audioNote
        if (nextActive == current.audioActive && nextNote == current.audioNote) return
        _state.value = current.copy(audioActive = nextActive, audioNote = nextNote)
    }

    /**
     * 清理四件套，顺序固定：
     * ① 关双 socket（同时是通知 server 退出的动作：server 读到 EOF 自行退出）
     * ② release 视频/音频解码器
     * ③ forward --remove
     * ④ 收尾：取消帧协程、重置状态
     */
    private suspend fun releaseResources() {
        Logx.i(TAG, "开始清理会话")
        runCatching { controller?.stop() }
            .onFailure { e -> Logx.w(TAG, "关闭 socket 异常：${e.message}") }
        controller = null

        decoder?.stopAndRelease()
        decoder = null
        decoderEventsJob?.cancel()
        decoderEventsJob = null
        // 重置解码器重建三道闸，避免上一轮会话的熔断/冷却泄漏到新会话
        decoderRebuildCount = 0
        rebuildingDecoder = false
        pendingRebind = false
        lastDecoderRebuildMs = 0L
        // 重置 Surface 诊断计数器，避免上一轮会话的计数泄漏到新会话
        surfaceAttachCount = 0
        surfaceNullCount = 0
        surfaceSameCount = 0
        surfaceUnboundCount = 0
        surfaceRebindOkCount = 0
        surfaceRebindFailCount = 0
        surfaceRebuiltCount = 0
        // 换绑能力探测复位：每个会话只付出一次「调用并失败」的代价（见字段 KDoc），
        // 而不是把判定永久钉死在 App 生命周期上——系统升级 / ROM 更新后能力可能变好。
        setOutputSurfaceUnsupported = false

        // 音频解码器与视频解码器相邻释放：同属「② 释放解码器」这一步，
        // 必须早于 ③ 移除 forward（否则 AudioTrack 还在写已断的音频流）。
        audioDecoder?.stopAndRelease()
        audioDecoder = null
        audioEventsJob?.cancel()
        audioEventsJob = null

        val info: ScrcpyServerDeployer.StartInfo? = startInfo
        if (info != null) {
            val serial: String? = boundSerial.ifBlank { null }
            runCatching { deployer.cleanup(info.localPort, serial) }
                .onFailure { e -> Logx.w(TAG, "清理 forward 异常：${e.message}") }
        }
        startInfo = null

        framesJob?.cancel()
        framesJob = null
        // 先取消控制发送者，再让 socket 关掉：否则消费者可能在 socket 关闭后还在写，
        // 每次都会记一条「控制通道写入失败」的噪音。
        controlWriterJob?.cancel()
        controlWriterJob = null
        sessionGeneration++
        sessionJob = null
        sessionAlive = false

        remoteWidth = 0
        remoteHeight = 0
        _remoteSize.value = RemoteSize()
        currentConfig = null
        resetStats()
        Logx.i(TAG, "会话清理完成")
    }

    // ------------------------------------------------------------------
    // 输入注入
    // ------------------------------------------------------------------

    private fun postTouch(pointerId: Long, action: Int, x: Int, y: Int, pressure: Int) {
        val bytes: ByteArray = ControlProtocol.injectTouch(
            action = action,
            pointerId = pointerId,
            x = x,
            y = y,
            // ★ 必须传「与视频一致」的分辨率：服务端 PositionMapper.map() 会拿它和
            // 自身 videoSize 做严格相等比较，不等就把整条触控事件丢掉（完全没反应）。
            // 详情见 ControlProtocol.injectTouch 与 README §3.4。
            screenW = remoteWidth,
            screenH = remoteHeight,
            pressure = pressure
        )
        sendAsync(bytes)
    }

    private fun tap(x: Int, y: Int, times: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repeat(times) { index ->
                if (index > 0) delay(DOUBLE_TAP_GAP_MS)
                touchNow(ControlProtocol.TOUCH_ACTION_DOWN, x, y, ControlProtocol.PRESSURE_MAX)
                delay(TAP_HOLD_MS)
                touchNow(ControlProtocol.TOUCH_ACTION_UP, x, y, 0)
            }
        }
    }

    private fun longPress(x: Int, y: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            touchNow(ControlProtocol.TOUCH_ACTION_DOWN, x, y, ControlProtocol.PRESSURE_MAX)
            delay(LONG_PRESS_HOLD_MS)
            touchNow(ControlProtocol.TOUCH_ACTION_UP, x, y, 0)
        }
    }

    private fun touchNow(action: Int, x: Int, y: Int, pressure: Int) {
        val bytes: ByteArray = ControlProtocol.injectTouch(
            action = action,
            pointerId = ControlProtocol.DEFAULT_POINTER_ID,
            x = x,
            y = y,
            screenW = remoteWidth,
            screenH = remoteHeight,
            pressure = pressure
        )
        sendAsync(bytes)
    }

    /**
     * 把一包控制命令排进 [controlQueue]（非阻塞，可在 UI 线程调用）。
     *
     * ⚠️ 不要改回 `viewModelScope.launch(Dispatchers.IO) { sendNow(bytes) }`：
     * 那样每包一个协程，DOWN/MOVE/UP 的到达顺序就不再保证（见 [controlQueue] 注释）。
     * 写入由队列的唯一消费者 [startControlWriter] 串行执行。
     */
    /** 每轮会话只拉一次「注入后服务端取证」，避免每帧都跑 adb。 */
    @Volatile
    private var injectionDiagPulled: Boolean = false

    /** 控制包发送计数，仅供注入侧采样日志使用（见 [sendAsync]）。 */
    @Volatile
    private var injectedPackets: Int = 0

    /**
     * 「画面正常但控制静默失效」专用取证。
     *
     * 控制通道是**单向只写**的（`ScrcpyController` 只取 `getOutputStream()`），
     * 服务端注入失败时不会回任何错误字节，客户端结构上感知不到。而服务端的报错
     * （典型：小米 HyperOS 未开「USB 调试（安全设置）」→ `INJECT_EVENTS` 权限异常）
     * 只写在**被控端**的 logcat 里——我们平时抓的是控制端日志，物理上不可能包含它。
     * 于是这类故障表现为「点了没反应，日志全绿」，连续多轮无法定位。
     *
     * 做法：首次注入后延迟一小段再拉一次被控端 logcat（`scrcpy` tag，ERROR 级在
     * 默认 INFO 阈值下即可见，无需 `log_level=verbose`），顺带带上 server stderr 文件。
     * 全程 IO 线程、超时兜底、失败只降级，绝不干扰正常注入。
     */
    private fun scheduleInjectionDiagnostics() {
        if (injectionDiagPulled) return
        injectionDiagPulled = true
        val serial: String = boundSerial
        if (serial.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                delay(INJECTION_DIAG_DELAY_MS)
                val lc: String = deployer.adb.shell(
                    serial,
                    // 同上：不能用 `-t <n>`，先过滤再 tail
                    "logcat -d -v brief -s scrcpy 2>/dev/null | tail -30",
                    DIAGNOSIS_TIMEOUT_MS
                ).getOrNull()?.stdout.orEmpty().trim()
                val rl: String = deployer.adb.shell(
                    serial,
                    "cat ${deployer.remoteLogPath} 2>/dev/null | tail -n 20",
                    DIAGNOSIS_TIMEOUT_MS
                ).getOrNull()?.stdout.orEmpty().trim()
                Logx.w(
                    TAG,
                    "注入后服务端取证：\n【被控端 logcat -s scrcpy】\n" +
                        lc.ifBlank { "(空)" } +
                        "\n【被控端 server stderr 文件】\n" +
                        rl.ifBlank { "(空)" }
                )
            }.onFailure { e ->
                Logx.w(TAG, "注入后服务端取证失败：${e.message}")
            }
        }
    }

    private fun sendAsync(bytes: ByteArray) {
        // 首次注入时触发一次服务端侧取证（内部自带一次性闸门，后续调用是空判断）。
        scheduleInjectionDiagnostics()
        // 注入侧采样：成功路径此前**完全没有日志** —— 用户到底点没点、点了几下，
        // 日志里根本看不出来，只能靠猜。这里第 1 条必打、之后每 [INJECTION_LOG_EVERY]
        // 条打一次，既确认「用户确实操作过」，也确认「控制包确实进了发送队列」。
        // 与服务端侧取证互补：这里证明"发了"，那边证明"服务端收到了什么"。
        val n: Int = ++injectedPackets
        if (n == 1 || n % INJECTION_LOG_EVERY == 0) {
            Logx.i(
                TAG,
                "控制注入采样：第 $n 条，type=${bytes.firstOrNull()?.toInt()} 长度=${bytes.size}"
            )
        }
        if (controlQueue.trySend(bytes).isFailure) {
            Logx.w(TAG, "控制发送队列不可用，丢弃 ${bytes.size} 字节")
        }
    }

    /**
     * 启动控制包发送队列的唯一消费者。幂等：已在跑就不重复启动。
     *
     * 循环本身是挂起的：`for (bytes in controlQueue)` 会一直等到有新包或协程被取消。
     */
    private fun startControlWriter() {
        if (controlWriterJob?.isActive == true) return
        controlWriterJob = viewModelScope.launch(Dispatchers.IO) {
            for (bytes in controlQueue) {
                sendNow(bytes)
            }
        }
    }

    private suspend fun sendNow(bytes: ByteArray) {
        val ctrl: ScrcpyController? = controller
        if (ctrl == null) {
            Logx.w(TAG, "控制通道不存在，丢弃 ${bytes.size} 字节")
            return
        }
        runCatching { ctrl.send(bytes) }
            .onFailure { e -> Logx.w(TAG, "注入失败：${e.message}") }
    }

    override fun onCleared() {
        super.onCleared()
        intentionalStop = true
        viewModelScope.launch(Dispatchers.IO) { releaseResources() }
        Logx.i(TAG, "MirrorViewModel 已销毁")
    }

    companion object {
        private const val TAG: String = "MirrorViewModel"
        private const val STATS_WINDOW_MS: Long = 1_000L
        /**
         * 探活前的小缓冲（app_process 刚 exec 时进程都还没起来）。
         * 真正的保障是 [ScrcpyServerDeployer.awaitServerSocket]，所以它只需覆盖「exec 启动」
         * 这一小段，不再承担「等 server listen」的职责。
         */
        private const val SERVER_BOOT_DELAY_MS: Long = 800L
        private const val TAP_HOLD_MS: Long = 30L
        private const val DOUBLE_TAP_GAP_MS: Long = 80L

        /** 断流后最多自动重连几次。次数用尽才把状态置为 Failed。 */
        private const val MAX_AUTO_RECONNECT: Int = 3

        /**
         * 每次重连前的退避基数（实际等待 = 该值 × 第几次重连）。
         * 隧道刚失效时立刻重试大概率还是失败（adb server 还在重启），给系统一点恢复时间。
         */
        private const val RECONNECT_BACKOFF_MS: Long = 1_200L
        private const val LONG_PRESS_HOLD_MS: Long = 400L
        private const val KEY_HOLD_MS: Long = 30L
        /**
         * 判定「尺寸没变」的像素容差：编码器会把宽高对齐到 16 的倍数，
         * 同方向内 16px 以内的差异视为抖动，不重建解码器。横竖切换不受此限制。
         */
        private const val SIZE_TOLERANCE_PX: Int = 16
        private const val MAX_LATENCY_MS: Long = 5_000L

        /**
         * 解码器重建冷却：SurfaceView 布局抖动会在极短时间内连发多次 `surfaceChanged`，
         * 每次都是新 Surface 对象。换绑失败转重建时若不加冷却，会瞬间「建→拆→建」把 MTK 等
         * 平台的同时 MediaCodec 实例数撑爆（createCodecByName 失败）→ 死循环弹「解码器初始化失败」。
         */
        private const val DECODER_REBUILD_COOLDOWN_MS: Long = 800L
        /** 一轮会话内重建失败累计达到此值即熔断，不再尝试重建（退化为改动前行为，不再弹窗）。 */
        private const val MAX_DECODER_REBUILD: Int = 3
        /** onFrame 里补做换绑的低频节流间隔（避免每帧都查）。 */
        private const val REBIND_CHECK_MS: Long = 1_000L
        /** 让 adb 动态分配本地端口（adb forward tcp:0 ...）。固定端口被占用时用它重试。 */
        private const val DYNAMIC_PORT: Int = 0
        /** 诊断取证（读 server stdout 日志 / 读 /proc/net/unix）的 adb shell 超时 */
        private const val DIAGNOSIS_TIMEOUT_MS: Long = 6_000L
        /** 首次注入后，等多久再拉被控端服务端日志（给服务端留出产生报错的时间）。 */
        private const val INJECTION_DIAG_DELAY_MS: Long = 1_500L
        /** 控制包日志采样间隔：第 1 条必打，之后每 N 条打一条（触摸是高频流，逐条打会淹掉日志）。 */
        private const val INJECTION_LOG_EVERY: Int = 50
        /** logcat 取证超时：单独给短一点，-d 是 dump 后立即退出，6 秒足够 */
        private const val LOGCAT_DIAGNOSIS_TIMEOUT_MS: Long = 6_000L
        /** logcat 取证最多拼进文案的行数，避免把整份缓冲甩给 UI */
        private const val LOGCAT_MAX_LINES: Int = 20
        /** 音频缓冲默认值（ms）：与 ScrcpyConfig.audioBufferMs 的默认值保持一致 */
        private const val DEFAULT_AUDIO_BUFFER_MS: Int = 50

        private const val TEXT_NO_DEVICE: String = "尚未指定投屏设备"
        private const val TEXT_CONNECT_FAILED: String = "连接 scrcpy 视频通道失败"
        private const val TEXT_STREAM_ERROR: String = "视频流读取异常"
        /** 探活失败标题：server 一直没在设备端 listen 上 abstract socket */
        private const val TEXT_SERVER_NOT_LISTENING: String =
            "scrcpy-server 未就绪：未能在规定时间内监听设备端端口"
        private const val TEXT_DISCONNECTED: String = "投屏通道已断开"
        private const val TEXT_DECODER_FAILED: String = "解码器初始化失败，设备可能不支持硬解该分辨率"
        /** 音频提示语：等 AudioReady / AudioDisabled 事件回来之前先显示它 */
        private const val TEXT_AUDIO_CONNECTING: String = "音频连接中…"
        /** 音频降级提示（解码异常 / AudioTrack 失败），此时投屏仍正常 */
        private const val TEXT_AUDIO_DEGRADED: String = "音频已降级为无声投屏"
        /** 音频解码器启动失败时的提示 */
        private const val TEXT_AUDIO_START_FAILED: String = "音频解码器启动失败，已降级为无声投屏"
    }
}
