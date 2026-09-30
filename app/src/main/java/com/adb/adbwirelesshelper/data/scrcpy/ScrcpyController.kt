package com.adb.adbwirelesshelper.data.scrcpy

import com.adb.adbwirelesshelper.domain.model.ScrcpyConfig
import com.adb.adbwirelesshelper.util.Logx
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * scrcpy 视频/控制双 socket 客户端。
 *
 * 线路（tunnel_forward=true，App 侧连 127.0.0.1:<port>，由 adb forward 转发到设备的
 * localabstract:scrcpy_<scid>）：
 *
 * ```
 * [第 1 条 socket = video]  1B dummy + 64B device meta(设备名，NUL 填充)
 *                            + 12B stream meta(codecId u32 + width u32 + height u32)
 *                            + 每帧 12B 头(8B: config(bit63) | keyframe(bit62) | PTS(bit61..0)
 *                                          + 4B: payload size u32)
 *                            + NALU payload（Annex-B，起始码 00 00 00 01）
 * [第 2 条 socket = audio]  无 dummy byte（dummy 只写给「第一条」socket，即 video）
 *                            + 4B 音频流头（大端 u32 codec id；0/1 为禁用码）
 *                            + 每个音频包 12B 头（与视频同构）+ payload
 * [第 3 条 socket = control] 只写不读，承载 ControlProtocol 产出的二进制控制包
 * ```
 *
 * ⚠️ 握手顺序必须与服务端一致（已按 scrcpy v3.3.2 `DesktopConnection.open` / `Server.scrcpy`
 * / `Streamer` / `AudioRawRecorder` 逐行核实）：
 *
 * 1. `DesktopConnection.open()` 在 tunnel_forward 模式下按 **video → audio → control** 的顺序
 *    依次 `LocalServerSocket.accept()`；`sendDummyByte` 只会写给**第一条**被 accept 的 socket，
 *    写完即置 false —— 所以 video=true 时 dummy 在 video 上，audio 与 control **都没有 dummy**。
 * 2. video accept 之后服务端就阻塞在 audio 的 `accept()` 上；`open()` 全部返回后才
 *    `sendDeviceMeta()`（写到 `getFirstSocket()`，即 video）+ 各 Streamer 写自己的流头。
 *
 * 因此**三条连接的建立顺序必须与 accept 顺序严格一致**，否则服务端会永远卡在 `accept()` 上，
 * 表现为「能连上但读不到任何数据」；更糟的是重试会把重连的那条连接当成下一条 socket 被
 * accept，服务端往已关闭的旧 socket 写数据后直接退出。
 *
 * 顺序错位的症状差异（排查用）：少连 audio 而服务端 audio=true → device meta 之后全部超时；
 * 先连 control 再连 audio → control 被当成 audio、audio 被当成 control，控制包静默失效。
 *
 * 本类只依赖 JDK socket + 协程，不引用任何 Android UI 类型。
 */
class ScrcpyController(private val cfg: ScrcpyConfig) {

    /** 回显给上层的流事件。 */
    sealed interface Event {
        /** 握手完成：拿到分辨率与 codecId。 */
        data class Ready(val w: Int, val h: Int, val codecId: Int, val deviceName: String?) : Event

        /** 一帧码流数据。 */
        data class Frame(
            val data: ByteArray,
            val pts: Long,
            val keyframe: Boolean,
            val config: Boolean
        ) : Event

        /**
         * 音频通道已就绪：已在 audio socket 上读到 4 字节 codec id。
         *
         * @param codecId 取值见 [AUDIO_CODEC_ID_RAW] / [AUDIO_CODEC_ID_AAC] / [AUDIO_CODEC_ID_OPUS]
         */
        data class AudioReady(val codecId: Int) : Event

        /**
         * 设备端未启用音频（codec id 为 0，或是不认识的编码）。
         *
         * ⚠️ 收到本事件**不是错误**：被控端 Android 11 以下根本不支持音频采集，服务端会写
         * 4 个 0 字节明确禁用该流。上层应当**继续无声投屏**，而不是把投屏判定为失败。
         *
         * @param reason 可直接展示给用户的中文原因
         */
        data class AudioDisabled(val reason: String) : Event

        /**
         * 一个音频包。
         *
         * 与视频帧共用同一种 12 字节包头（8B ptsAndFlags + 4B size），所以字段含义一致；
         * 音频没有关键帧概念，[config] 表示这是编码器配置包（Opus 为 `OpusHead`，
         * AAC 为 AudioSpecificConfig）。
         */
        data class AudioFrame(
            val data: ByteArray,
            val pts: Long,
            val config: Boolean
        ) : Event

        /** 读取或写入异常。 */
        data class Error(val throwable: Throwable) : Event

        /** 通道已关闭（含主动 stop）。 */
        data object Closed : Event
    }

    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventFlow: MutableSharedFlow<Event> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = FRAME_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @Volatile
    private var running: Boolean = false

    private var videoSocket: Socket? = null
    private var audioSocket: Socket? = null
    private var controlSocket: Socket? = null
    private var controlOutput: OutputStream? = null
    private var readerJob: Job? = null

    /** 音频 socket 的读循环协程。与 [readerJob] 分离：音频挂掉不能影响视频读循环。 */
    private var audioReaderJob: Job? = null

    /**
     * 最近一次连接失败的真实异常。
     *
     * 存在原因：[connect] 按契约只返回 Boolean（异常已被吞掉），但「
     * `ConnectException: Connection refused`（端口没人监听）」与「
     * `SocketTimeoutException`（在监听但不 accept）」的根因和修法完全不同，
     * 调用方（MirrorViewModel 的诊断）必须拿到原始异常才能区分。
     * 每次 [connect] 开始前清空，成功后为 null。
     */
    @Volatile
    var lastConnectError: Throwable? = null
        private set

    /**
     * 建立双通道并完成握手。
     *
     * 严格按 scrcpy v3.3.2 的服务端顺序：
     * ① video socket connect + 读 1B dummy（可安全重试）
     * → ② audio socket connect（开启了音频时；无 dummy，失败即抛）
     * → ③ control socket connect（失败即抛）
     * → ④ 读 64B device meta → ⑤ 读 12B stream meta。
     *
     * 音频包由握手返回后另起的 [readAudioLoop] 协程读取，不阻塞本方法
     * （设备端 `AudioRecord.start()` 的耗时会拖慢首帧出图）。
     *
     * @param host 恒为本机回环（adb forward 在本机监听）
     * @param port ScrcpyServerDeployer.StartInfo.localPort
     * @return true 表示握手完成，帧事件随后由 [frames] 下发
     */
    suspend fun connect(host: String = "127.0.0.1", port: Int): Boolean {
        lastConnectError = null
        return runCatching {
            closeSockets()

            // 一次性完成「三条 socket + 两次 meta 读取」，顺序由 connectAndHandshake 保证
            val attempt: HandshakeAttempt = connectAndHandshake(host, port)
            videoSocket = attempt.video
            attempt.audio?.let { socket -> audioSocket = socket }
            attempt.control?.let { socket -> controlSocket = socket }
            // 握手已读完，切回正常帧读超时
            attempt.video.soTimeout = READ_TIMEOUT_MS

            post(Event.Ready(attempt.width, attempt.height, attempt.codecId, attempt.deviceName))

            running = true
            readerJob = ioScope.launch { readLoop(attempt.input) }
            // 音频在**独立协程**里读：音频通道是可降级通道，它的任何异常只能变成
            // AudioDisabled，不能停掉视频读循环、也不能投递 Event.Closed。
            // 这里不预先等 4B 音频头，是为了不让「设备端起音频采集」的耗时拖慢首帧出图。
            attempt.audio?.let { socket ->
                audioReaderJob = ioScope.launch { readAudioLoop(socket) }
            }
            true
        }.getOrElse { throwable ->
            // 保留原始异常供上层诊断：只留一句「连接失败」区分不出
            // 「端口没人监听」与「在监听但不 accept」。
            lastConnectError = throwable
            Logx.e(TAG, "连接 scrcpy 失败：${describeThrowable(throwable)}")
            post(Event.Error(throwable))
            closeSockets()
            false
        }
    }

    /** 订阅流事件。可被多次 collect，事件扇出给所有订阅者。 */
    fun frames(): Flow<Event> = eventFlow.asSharedFlow()

    /**
     * 向控制通道写字节（通常是 [ControlProtocol] 的输出）。
     * 控制通道未就绪时丢弃并记录，不抛异常，避免打断 UI。
     */
    suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out: OutputStream = controlOutput ?: run {
            Logx.w(TAG, "控制通道未就绪，丢弃 ${bytes.size} 字节")
            return@withContext
        }
        runCatching {
            out.write(bytes)
            out.flush()
        }.onFailure { throwable ->
            Logx.e(TAG, "控制通道写入失败：${throwable.message}")
            post(Event.Error(throwable))
        }
    }

    /**
     * 关闭双 socket 并释放协程。
     * 注意：server 正是通过「控制通道读到 EOF」来判定客户端已断开并自行退出的，
     * 因此 close 本身就是「通知 server 退出」的动作。
     */
    suspend fun stop() {
        running = false
        val videoJob: Job? = readerJob
        val audioJob: Job? = audioReaderJob
        readerJob = null
        audioReaderJob = null
        // closeSockets 会同时关 audio socket；阻塞在 read() 上的音频协程因此抛异常退出
        closeSockets()
        withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { videoJob?.join() }
        withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { audioJob?.join() }
        post(Event.Closed)
        ioScope.cancel()
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 一次完整握手的产物：video 与 control 必须绑在一起返回。
     *
     * video 的 [input] 已读完 dummy byte，可直接继续读 device meta / stream meta / 帧数据；
     * [control] 为 null 表示 `cfg.controlEnabled=false`（服务端启动参数已同步，不会多 accept）。
     */
    private data class HandshakeAttempt(
        val video: Socket,
        val input: DataInputStream,
        val audio: Socket?,
        val control: Socket?,
        val width: Int,
        val height: Int,
        val codecId: Int,
        val deviceName: String?
    )

    /**
     * 按官方顺序建立三条通道并完成握手。顺序错了就是用户看到的 EOF 级联
     * （详见类 KDoc）：只有 video 这条可以选择安全重试，其余步骤失败一律抛
     * —— 因为服务端此刻正阻塞在下一个 `accept()` 上。
     */
    private suspend fun connectAndHandshake(host: String, port: Int): HandshakeAttempt {
        // ① video socket：connect + 读 1B dummy。此刻服务端还没 accept audio/control，重试安全。
        val video: Socket = connectFirstSocketWithRetry(host, port)
        Logx.i(TAG, "video socket 已连接并读到 dummy byte（服务端 accept 顺序 video → audio → control）")

        var audio: Socket? = null
        var control: Socket? = null
        try {
            val input: DataInputStream = DataInputStream(video.getInputStream())
            // device meta / stream meta 用单独的超时，比帧超时短，避免服务端异常时久等
            video.soTimeout = META_READ_TIMEOUT_MS

            // ② audio socket：服务端在 video accept 之后立刻阻塞在 audio 的 accept() 上。
            //    顺序绝不能与 ③ 对调 —— 服务端是按 video → audio → control 编号的，
            //    先连 control 会让它对号入座错位（控制包会被当成音频流写走）。
            audio = openAudioSocket(host, port)
            if (audio != null) {
                Logx.i(TAG, "audio socket 已连接（服务端 accept 顺序 video → audio → control，无 dummy byte）")
            }

            // ③ control socket：连不上服务端就永远不写 device meta，所以失败必须抛
            control = openControlSocket(host, port)
            if (control != null) {
                Logx.i(TAG, "control socket 已连接（服务端 accept 顺序 video → audio → control）")
            }

            // ④ 64B device meta（设备名，NUL 填充）。服务端写的是 getFirstSocket()，
            //    video=true 时即 video socket。
            val deviceMeta: ByteArray = readExactly(input, DEVICE_META_SIZE, "device meta")
            val deviceName: String? = decodeCString(deviceMeta)

            // ⑤ 12B stream meta：codecId + width + height（大端）
            val streamMeta: ByteArray = readExactly(input, STREAM_META_SIZE, "stream meta")
            val codecId: Int = readIntBE(streamMeta, 0)
            val width: Int = readIntBE(streamMeta, 4)
            val height: Int = readIntBE(streamMeta, 8)

            // ⚠️ 这里**不**读 audio socket 上那 4 字节音频流头：它由设备端音频线程在
            //    AudioRecord.start() 之后才写（见 AudioRawRecorder.record()），耗时不可控，
            //    同步等它会把首帧出图一起拖慢。改为交给 readAudioLoop 在自己协程里读。
            Logx.i(
                TAG,
                "握手完成：${width}x${height}, codec=0x${codecId.toString(16)}, device=$deviceName, " +
                    "audio=${if (audio != null) "on" else "off"}"
            )
            return HandshakeAttempt(
                video = video,
                input = input,
                audio = audio,
                control = control,
                width = width,
                height = height,
                codecId = codecId,
                deviceName = deviceName
            )
        } catch (throwable: Throwable) {
            // 半开状态不能留给上层：三条 socket 都要关掉
            runCatching { control?.close() }
            runCatching { audio?.close() }
            runCatching { video.close() }
            throw throwable
        }
    }

    /**
     * 第 1 条 socket（video）：`connect + 读 1B dummy` 整体重试。
     *
     * 为什么要重试：app_process 起来到 `LocalServerSocket.accept()` 之间有一段窗口，
     * adb server 虽已在本地监听（所以 `connect` 永远成功），但转发到设备端会失败。
     *
     * 为什么只在这里重试：服务端此刻还没进入 control 的 accept()，重连不会被它误当成
     * 第二条连接；一旦 control 连过再重试，就会破坏服务端状态机（见类 KDoc）。
     */
    private suspend fun connectFirstSocketWithRetry(host: String, port: Int): Socket {
        var lastError: Throwable? = null
        // 总时长兜底：单次最坏耗时是「读 dummy 卡到 2s 超时」，40 次叠起来会有近 90 秒，
        // UI 会长时间停在「连接中」。这里再压一道 15s 的墙钟上限，失败就尽快把错误抛给上层。
        val deadline: Long = System.currentTimeMillis() + FIRST_SOCKET_TOTAL_TIMEOUT_MS
        repeat(FIRST_SOCKET_RETRIES) { attempt ->
            // 超时检查必须在「发起尝试之前」：放到尝试之后的话，每轮都会先跑完一次
            // 完整连接（最坏 3s 连接 + 2s 读）再退出，反而比不设上限更慢。
            if (System.currentTimeMillis() >= deadline) {
                Logx.w(
                    TAG,
                    "video socket 重试总时长达上限 ${FIRST_SOCKET_TOTAL_TIMEOUT_MS}ms，停止重试"
                )
                return@repeat
            }
            var socket: Socket? = null
            val result: Result<Socket> = runCatching {
                val opened: Socket = openSocket(host, port)
                socket = opened
                opened.tcpNoDelay = true
                opened.receiveBufferSize = RECEIVE_BUFFER_SIZE
                // dummy byte 用较短的读超时：server 没 ready 时不至于每次都挂 10 秒
                opened.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
                val input: DataInputStream = DataInputStream(opened.getInputStream())
                readDummyByte(input)
                opened
            }
            result.onSuccess { return it }

            val throwable: Throwable =
                result.exceptionOrNull() ?: IOException("未知原因（端口 $port）")
            lastError = throwable
            // 半开的连接必须关掉，否则漏 socket，下一次重试也会拿到脏状态
            runCatching { socket?.close() }
            Logx.d(
                TAG,
                "第 ${attempt + 1} 次连接 video socket 失败：${describeThrowable(throwable)}"
            )
            delay(FIRST_SOCKET_RETRY_DELAY_MS)
        }
        // 带上最后一次的真实异常（类名 + message，如 java.net.ConnectException: Connection refused），
        // 便于 MirrorViewModel 的诊断文案与日志定位，而不是一句「连接失败」。
        val cause: Throwable = lastError
            ?: IOException("未知原因（端口 $port）")
        throw IOException(
            "连接 scrcpy video socket 失败：$host:$port，重试上限 $FIRST_SOCKET_RETRIES 次 / " +
                "${FIRST_SOCKET_TOTAL_TIMEOUT_MS}ms（先到者为准，间隔 " +
                "${FIRST_SOCKET_RETRY_DELAY_MS}ms，每次含读 dummy byte），" +
                "最后错误：${describeThrowable(cause)}",
            cause
        )
    }

    /** 读服务端写在 video socket 上的第 1 个字节（dummy），失败时补上下文再抛。 */
    private fun readDummyByte(input: DataInputStream) {
        val dummy = ByteArray(1)
        runCatching { input.readFully(dummy) }
            .getOrElse { throwable ->
                throw IOException(
                    "读取服务端 dummy byte 失败：" + describeThrowable(throwable),
                    throwable
                )
            }
    }

    /** 定长读取；失败时补上「读了什么、要多少字节」，避免只看到一个裸 EOFException。 */
    private fun readExactly(input: DataInputStream, size: Int, what: String): ByteArray {
        val buffer = ByteArray(size)
        runCatching { input.readFully(buffer) }
            .getOrElse { throwable ->
                throw IOException(
                    "读取 $what 失败（需要 $size 字节）：" + describeThrowable(throwable),
                    throwable
                )
            }
        return buffer
    }

    /**
     * 把异常压成「类名: message」的形式。
     * 例如 `java.net.ConnectException: Connection refused`，
     * 这是区分「端口没人监听」与「在监听不 accept」的唯一依据。
     */
    private fun describeThrowable(t: Throwable): String {
        val message: String = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return t.javaClass.name + ": " + message
    }

    private suspend fun openSocket(host: String, port: Int): Socket = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = READ_TIMEOUT_MS
        socket
    }

    /**
     * 建立第 2 条（control）socket，只 connect，不读任何字节。
     *
     * ⚠️ 失败必须抛异常，不能「打个警告继续走」：服务端此刻正阻塞在 `accept()` 上，
     * control 不连上它就永远不写 device meta，后续读取必然超时。（旧的 `getOrNull()`
     * 写法正是用户看到的「第 1 次超时、之后全是 EOF」的成因之一。）
     *
     * @return 已连接的 socket；`cfg.controlEnabled=false` 时返回 null
     *         （服务端启动参数已同步为 control=false，不会多 accept 一次）
     */
    /**
     * 建立第 2 条（audio）socket，只 connect，**不读任何字节**。
     *
     * ⚠️ 三条差别必须记住（都来自 scrcpy v3.3.2 `DesktopConnection.open`）：
     * 1. **没有 dummy byte**。`sendDummyByte` 只会写给第一条被 accept 的 socket，写完即置 false；
     *    我们恒有 video，所以 audio 与 control 都收不到那 1 个字节。按 video 的读法去读 dummy，
     *    会把音频头的第 1 个字节吃掉，后续 3 字节整体错位 → codec id 变成垃圾值。
     * 2. **失败必须抛**。服务端此刻阻塞在 audio 的 accept() 上（顺序上 audio 排在 control 前面），
     *    连不上它就永远走不到 control 的 accept()，device meta 也就永远不会写。
     * 3. soTimeout 先给 [META_READ_TIMEOUT_MS]：这 4 字节由设备端音频线程在
     *    `AudioRecord.start()` 成功/失败之后才写，需要一个有界的等待；读到之后再在
     *    [readAudioLoop] 里切回 0（无限等待），避免音频包间隔被误判成断流。
     *
     * @return 已连接的 socket；`cfg.audioEnabled=false` 时返回 null
     *         （服务端启动参数已同步为 audio=false，不会多 accept 一次）
     */
    private suspend fun openAudioSocket(host: String, port: Int): Socket? {
        if (!cfg.audioEnabled) {
            Logx.i(TAG, "cfg.audioEnabled=false，不建立音频通道（服务端 accept 次数已同步为 false）")
            return null
        }
        val socket: Socket = runCatching { openSocket(host, port) }
            .getOrElse { throwable ->
                // 不能降级跳过：跳过会让服务端永远卡在 audio 的 accept()，
                // 于是「音频连不上」表现为「整个投屏连不上」，反而更难排查。
                throw IOException(
                    "连接 scrcpy audio socket 失败：$host:$port，" +
                        describeThrowable(throwable),
                    throwable
                )
            }
        socket.tcpNoDelay = true
        socket.soTimeout = META_READ_TIMEOUT_MS
        return socket
    }

    private suspend fun openControlSocket(host: String, port: Int): Socket? {
        if (!cfg.controlEnabled) {
            Logx.i(TAG, "cfg.controlEnabled=false，不建立控制通道（服务端 accept 次数已同步）")
            return null
        }
        val socket: Socket = runCatching { openSocket(host, port) }
            .getOrElse { throwable ->
                throw IOException(
                    "连接 scrcpy control socket 失败：$host:$port，" +
                        describeThrowable(throwable),
                    throwable
                )
            }
        socket.tcpNoDelay = true
        controlOutput = socket.getOutputStream()
        return socket
    }

    private suspend fun readLoop(input: DataInputStream) {
        try {
            while (running) {
                val header = ByteArray(FRAME_HEADER_SIZE)
                input.readFully(header)

                val ptsAndFlags: Long = readLongBE(header, 0)
                val size: Int = readIntBE(header, 8)
                if (size <= 0) {
                    continue
                }
                if (size > MAX_PACKET_SIZE) {
                    throw IOException("非法帧长度：$size（上限 $MAX_PACKET_SIZE）")
                }

                val payload = ByteArray(size)
                input.readFully(payload)

                val config: Boolean = (ptsAndFlags and FLAG_CONFIG) != 0L
                val keyframe: Boolean = (ptsAndFlags and FLAG_KEYFRAME) != 0L
                val pts: Long = ptsAndFlags and PTS_MASK

                post(Event.Frame(ensureAnnexB(payload), pts, keyframe, config))
            }
        } catch (throwable: Throwable) {
            if (running) {
                // ★ 必须打**异常类型**：EOFException 的 message 恒为 null，
                //   只打 message 会得到「读取循环结束：null」，无法与超时/重置/本机关闭区分。
                Logx.w(TAG, "读取循环结束：${describeThrowable(throwable)}")
                post(Event.Error(throwable))
            }
        } finally {
            running = false
            post(Event.Closed)
        }
    }

    /**
     * 音频独立读循环：先读 4B 音频流头，再按 12B 包头循环投递 [Event.AudioFrame]。
     *
     * ⚠️ 与 [readLoop] 的三处刻意的不同（音频是**可降级通道**）：
     * 1. **不置 `running=false`、不投递 [Event.Closed]**。音频结束不代表会话结束；
     *    若在这里投 Closed，上层会把「设备端不支持音频」误判成「投屏断开」并整场重连。
     * 2. 任何失败都收敛成 [Event.AudioDisabled]（带可展示的中文原因），视频通道不受影响。
     * 3. 流头读到之后把 soTimeout 切成 0。音频包在设备端无播放时也可能停发（采集静音帧的
     *    节奏不固定），有超时反而会把正常静默误判成断流；退出只靠 `running=false` + close。
     */
    private suspend fun readAudioLoop(socket: Socket) {
        val input: DataInputStream = try {
            DataInputStream(socket.getInputStream())
        } catch (throwable: Throwable) {
            Logx.w(TAG, "音频通道取流失败：${describeThrowable(throwable)}")
            runCatching { socket.close() }
            post(Event.AudioDisabled(REASON_AUDIO_SOCKET_FAILED))
            return
        }

        // ① 4B 大端 codec id。Streamer.writeAudioHeader() 写的是 codec.getId()；
        //    若设备端采集不了，AudioRawRecorder/AudioEncoder 会改走 writeDisableStream()，
        //    写的仍是 4 字节，只是全 0（或末字节为 1）—— 所以这一步永远能读到定长 4 字节。
        val codecId: Int = try {
            readIntBE(readExactly(input, AUDIO_HEADER_SIZE, "音频流头"), 0)
        } catch (throwable: Throwable) {
            Logx.w(TAG, "读取音频流头失败，降级为无声投屏：${describeThrowable(throwable)}")
            runCatching { socket.close() }
            post(Event.AudioDisabled(REASON_AUDIO_HEADER_FAILED))
            return
        }

        when {
            // code 0：设备端明确禁用音频（最常见是 Android 11 以下不支持音频采集）。
            // 上游注释：scrcpy should continue mirroring video only —— 必须继续投屏。
            codecId == AUDIO_DISABLE_CODE -> {
                Logx.w(TAG, "设备端禁用音频（codec id = 0），降级为无声投屏")
                runCatching { socket.close() }
                post(Event.AudioDisabled(REASON_AUDIO_UNAVAILABLE))
                return
            }
            // code 1：服务端音频编码器配置失败。上游语义是「必须停止 scrcpy」。
            // 本 App 主功能是投屏、音频只是可降级增强通道，故这里**只关音频、保留画面**，
            // 并在提示里说清原因 —— 为音频配置失败而杀掉整场投屏对用户是更差的体验。
            codecId == AUDIO_ERROR_CODE -> {
                Logx.e(TAG, "设备端音频配置错误（codec id = 1），仅关闭音频通道，保留投屏")
                runCatching { socket.close() }
                post(Event.AudioDisabled(REASON_AUDIO_CONFIG_ERROR))
                return
            }
            codecId !in ScrcpyConfig.AUDIO_CODEC_IDS -> {
                Logx.w(TAG, "未知音频 codec id=0x${codecId.toString(16)}，降级为无声投屏")
                runCatching { socket.close() }
                post(Event.AudioDisabled("设备端音频编码不受支持（0x${codecId.toString(16)}）"))
                return
            }
        }

        runCatching { socket.soTimeout = 0 }
        Logx.i(TAG, "音频流就绪：codecId=0x${codecId.toString(16)}")
        post(Event.AudioReady(codecId))

        // ② 音频包与视频帧共用同一种 12B 包头，解析逻辑刻意与 readLoop 保持一致
        try {
            while (running) {
                val header = ByteArray(FRAME_HEADER_SIZE)
                input.readFully(header)

                val ptsAndFlags: Long = readLongBE(header, 0)
                val size: Int = readIntBE(header, 8)
                if (size <= 0) {
                    continue
                }
                if (size > MAX_PACKET_SIZE) {
                    throw IOException("非法音频包长度：$size（上限 $MAX_PACKET_SIZE）")
                }
                val payload = ByteArray(size)
                input.readFully(payload)

                post(
                    Event.AudioFrame(
                        // ⚠️ 原样透传，不做任何字节序转换：RAW 路径下服务端直接把 AudioRecord
                        // 的 PCM16LE 原样写出（AudioRawRecorder 无 ByteOrder 处理），
                        // AudioTrack.write 也按小端解释 byte[]，两端天然一致。
                        data = payload,
                        pts = ptsAndFlags and PTS_MASK,
                        config = (ptsAndFlags and FLAG_CONFIG) != 0L
                    )
                )
            }
        } catch (throwable: Throwable) {
            if (running) {
                Logx.w(TAG, "音频读取循环结束：${describeThrowable(throwable)}")
                post(Event.AudioDisabled(REASON_AUDIO_STREAM_ENDED))
            }
        }
    }

    private fun closeSockets() {
        runCatching { controlOutput?.flush() }
        controlOutput = null
        runCatching { controlSocket?.close() }
        controlSocket = null
        // 关音频 socket 同时是「解除 readAudioLoop 阻塞」的手段：阻塞在 read() 上的协程
        // 会因 SocketException 退出，不需要额外 interrupt。
        runCatching { audioSocket?.close() }
        audioSocket = null
        runCatching { videoSocket?.close() }
        videoSocket = null
    }

    private fun post(event: Event) {
        if (!eventFlow.tryEmit(event) && event !is Event.Frame) {
            Logx.d(TAG, "事件缓冲已满，丢弃非帧事件：$event")
        }
    }

    /** 把 NUL 结尾的定长字节数组解码成字符串；全 0 时返回 null。 */
    private fun decodeCString(bytes: ByteArray): String? {
        var end: Int = bytes.size
        for (i in bytes.indices) {
            if (bytes[i] == 0.toByte()) {
                end = i
                break
            }
        }
        val text: String = String(bytes, 0, end, Charsets.UTF_8).trim()
        return text.ifEmpty { null }
    }

    /**
     * 保证 payload 是 Annex-B（起始码 00 00 00 01）。
     * scrcpy 默认发 Annex-B，但部分版本/配置下会省略 4 字节起始码（只留 3 字节或没有），
     * 解码器要求必须是 4 字节起始码，这里统一补齐。
     */
    private fun ensureAnnexB(data: ByteArray): ByteArray {
        if (data.size >= 4 &&
            data[0] == 0.toByte() &&
            data[1] == 0.toByte() &&
            data[2] == 0.toByte() &&
            data[3] == 1.toByte()
        ) {
            return data
        }
        if (data.size >= 3 &&
            data[0] == 0.toByte() &&
            data[1] == 0.toByte() &&
            data[2] == 1.toByte()
        ) {
            // 3 字节起始码 → 补成 4 字节
            val result = ByteArray(data.size + 1)
            result[0] = 0
            System.arraycopy(data, 0, result, 1, data.size)
            return result
        }
        val result = ByteArray(data.size + ANNEX_B_PREFIX.size)
        System.arraycopy(ANNEX_B_PREFIX, 0, result, 0, ANNEX_B_PREFIX.size)
        System.arraycopy(data, 0, result, ANNEX_B_PREFIX.size, data.size)
        return result
    }

    /** 大端读 4 字节无符号 → Int。 */
    private fun readIntBE(src: ByteArray, offset: Int): Int {
        return ((src[offset].toInt() and 0xFF) shl 24) or
            ((src[offset + 1].toInt() and 0xFF) shl 16) or
            ((src[offset + 2].toInt() and 0xFF) shl 8) or
            (src[offset + 3].toInt() and 0xFF)
    }

    /** 大端读 8 字节 → Long。 */
    private fun readLongBE(src: ByteArray, offset: Int): Long {
        var value: Long = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (src[offset + i].toLong() and 0xFFL)
        }
        return value
    }

    companion object {
        private const val TAG: String = "ScrcpyController"
        private const val CONNECT_TIMEOUT_MS: Int = 3_000
        private const val READ_TIMEOUT_MS: Int = 10_000
        /** 第 1 条 socket 上读 dummy byte 的超时：服务端未 ready 时不至于每次都挂 10 秒 */
        private const val HANDSHAKE_READ_TIMEOUT_MS: Int = 2_000
        /**
         * device meta / stream meta 的读超时。
         * 这两步在 control socket 连好之后才读，服务端正常时毫秒级返回；
         * 给 5s 是留给「设备端首帧编码阻塞」的余量，再长就一定是异常。
         */
        private const val META_READ_TIMEOUT_MS: Int = 5_000
        /**
         * 第 1 条 socket（video）的重试次数与间隔。
         *
         * ⚠️ 只有这一步能安全重试：此刻服务端还没进入 control 的 accept()，重连不会被它
         * 误当成第二条连接。单次最坏耗时 = 连接 3s + 读 dummy 2s，故次数压到 40 次、
         * 间隔 150ms，兼顾「慢启动设备多等一会」与「故障时快速失败」。
         */
        private const val FIRST_SOCKET_RETRIES: Int = 40
        private const val FIRST_SOCKET_RETRY_DELAY_MS: Long = 150L
        /** 第 1 条 socket 重试的墙钟总上限：优先于 [FIRST_SOCKET_RETRIES] 生效 */
        private const val FIRST_SOCKET_TOTAL_TIMEOUT_MS: Long = 15_000L
        private const val STOP_JOIN_TIMEOUT_MS: Long = 2_000L
        /**
         * 设备名字段长度：scrcpy v3.3.2 `DesktopConnection.DEVICE_NAME_FIELD_LENGTH = 64`，
         * 服务端 `sendDeviceMeta()` 固定写 64 字节（NUL 填充）。客户端必须严格读 64 字节，
         * 少读会让后面的 12B stream meta 整体错位（width/height 读出 0 或垃圾值）。
         */
        private const val DEVICE_META_SIZE: Int = 64
        private const val STREAM_META_SIZE: Int = 12
        private const val FRAME_HEADER_SIZE: Int = 12
        /**
         * 音频流头长度：`Streamer.writeAudioHeader()` 固定写 4 字节（大端 u32 codec id）。
         * 与视频流头（12B）不同，音频头只有 codec id，没有尺寸字段。
         */
        private const val AUDIO_HEADER_SIZE: Int = 4
        /** `Streamer.writeDisableStream(false)` 的取值：设备端采集不到音频 → 继续无声投屏。 */
        private const val AUDIO_DISABLE_CODE: Int = 0
        /** `Streamer.writeDisableStream(true)` 的取值：设备端音频配置错误。 */
        private const val AUDIO_ERROR_CODE: Int = 1
        /**
         * 音频降级原因文案（会直接展示给用户）。
         * 统一放在这里而不是散在 readAudioLoop 里，便于后续做多语言与文案统一。
         */
        private const val REASON_AUDIO_UNAVAILABLE: String =
            "被控端无法采集音频（需 Android 11+），已降级为无声投屏"
        private const val REASON_AUDIO_CONFIG_ERROR: String =
            "被控端音频编码器配置失败，已降级为无声投屏"
        private const val REASON_AUDIO_HEADER_FAILED: String =
            "未收到被控端音频流头，已降级为无声投屏"
        private const val REASON_AUDIO_SOCKET_FAILED: String =
            "音频通道不可用，已降级为无声投屏"
        private const val REASON_AUDIO_STREAM_ENDED: String =
            "音频流已中断，已降级为无声投屏"
        private const val MAX_PACKET_SIZE: Int = 32 * 1024 * 1024
        private const val FRAME_BUFFER_CAPACITY: Int = 32
        private const val RECEIVE_BUFFER_SIZE: Int = 512 * 1024

        private const val FLAG_CONFIG: Long = 1L shl 63
        private const val FLAG_KEYFRAME: Long = 1L shl 62
        private const val PTS_MASK: Long = (1L shl 62) - 1L

        private val ANNEX_B_PREFIX: ByteArray = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}
