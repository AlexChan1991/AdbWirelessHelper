package com.adb.adbwirelesshelper.data.scrcpy

import android.content.Context
import android.util.Base64
import com.adb.adbwirelesshelper.data.adb.AdbClient
import com.adb.adbwirelesshelper.domain.model.ScrcpyConfig
import com.adb.adbwirelesshelper.util.Logx
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Random

/**
 * scrcpy-server 部署器。
 *
 * 职责：把随包发布的 `scrcpy-server.jar` 送到被控端、建立 `adb forward` 隧道、
 * 并以 `app_process`（shell 身份，uid 2000，天然具备 INJECT_EVENTS，无需 root）后台拉起服务端。
 *
 * 三步命令（与需求文档 8.6.1 完全一致，不得改动顺序与参数）：
 * 1) adb -s <serial> push <localJar> /data/local/tmp/scrcpy-server.jar
 * 2) adb -s <serial> forward tcp:<localPort> localabstract:scrcpy_<scid>
 * 3) adb -s <serial> shell "CLASSPATH=/data/local/tmp/scrcpy-server.jar nohup app_process /
 *    com.genymobile.scrcpy.Server <serverVersion> scid=<scid> log_level=info tunnel_forward=true
 *    video=true audio=<cfg.audioEnabled> audio_codec=<codec> audio_source=<source>
 *    audio_bit_rate=<bps> control=<cfg.controlEnabled> cleanup=true max_size=<maxSize>
 *    video_bit_rate=<bitrate>
 *    max_fps=<fps> send_frame_meta=true send_device_meta=true send_dummy_byte=true
 *    send_codec_meta=true >/data/local/tmp/scrcpy-server.log 2>&1 &"
 *
 * ⚠️ 第 3 步的第一个位置参数必须是 `cfg.serverVersion`（即 BuildConfig.SCRCPY_SERVER_VERSION），
 *    server 会校验它，错配会立刻退出（表现为「无画面」而非报错）。
 *
 * 本类不持有任何 Android UI 类型，可在 IO 线程调用；构造方法为 AppContainer 依赖的 (Context, AdbClient) 双参签名。
 */
class ScrcpyServerDeployer(
    private val context: Context,
    val adb: AdbClient
) {

    /** 一次部署的产物：端口号用于 App 侧连接，抽象名用于 forward 规则清理。 */
    data class StartInfo(
        val scid: String,
        val localPort: Int,
        val remoteAbstract: String
    )

    private val random: Random = Random()

    /**
     * server 启动日志在设备侧的路径。连接失败时由 MirrorViewModel 读取前若干行做诊断，
     * 这样 server 版本不匹配 / app_process 崩溃 / SELinux 限制都能留下线索。
     */
    val remoteLogPath: String get() = REMOTE_LOG_PATH

    /**
     * 标准部署路径：push jar → forward → app_process 启动。
     *
     * @param serial 目标设备序列号（无线场景形如 `192.168.1.42:37123`）
     * @param cfg    scrcpy 参数；其中的 serverVersion 必须与 jar 自身版本一致
     * @return 成功返回 [StartInfo]；jar 缺失或任一步失败返回明确的 failure，绝不静默成功
     */
    suspend fun deploy(serial: String, cfg: ScrcpyConfig): Result<StartInfo> {
        if (serial.isBlank()) {
            return Result.failure(IllegalArgumentException("设备序列号为空，无法部署 scrcpy-server"))
        }
        if (cfg.serverVersion.isBlank()) {
            return Result.failure(
                IllegalStateException("ScrcpyConfig.serverVersion 为空，server 会因版本校验失败退出")
            )
        }

        val localJar: File = ensureLocalJar()
            ?: return Result.failure(IllegalStateException(MISSING_JAR_MESSAGE))

        Logx.i(
            TAG,
            "部署 scrcpy-server：serial=$serial, jar=${localJar.length()}B, version=${cfg.serverVersion}"
        )

        val scid: String = newScid()
        val remoteAbstract: String = REMOTE_ABSTRACT_PREFIX + scid
        // localPort == 0 → 让 adb 自己挑空闲端口（adb forward tcp:0 ...），用于绕开
        // 27183 被本机其它 adb / 调试工具占用的情况。
        // StartInfo.localPort 会回填 adb 打印的真实端口，绝不留 0。
        val localPort: Int = cfg.localPort.takeIf { it >= 0 && it <= 65535 } ?: DEFAULT_LOCAL_PORT

        // ① push
        val push: Result<String> = adb.push(serial, localJar, REMOTE_JAR_PATH)
        if (push.isFailure) {
            val cause: Throwable = push.exceptionOrNull()
                ?: IllegalStateException("推送 scrcpy-server.jar 失败")
            Logx.e(TAG, "push 失败：${cause.message}")
            return Result.failure(cause)
        }
        val remoteSize: Long? = remoteJarSize(serial)
        if (remoteSize != null && remoteSize != localJar.length()) {
            Logx.w(TAG, "push 后大小不一致：local=${localJar.length()}B remote=${remoteSize}B，仍继续尝试启动")
        }

        // ② forward
        // 端口固定（默认 27183）：上一轮会话若没清理干净，这里会因「端口已占用/规则已存在」失败，
        // 而失败又会被上层误当成成功，最终表现为「连接视频通道失败」。所以建规则前先 best-effort 清一遍。
        // localPort == 0 时是「让 adb 动态分配」，没有已知端口可清，跳过。
        if (localPort > 0) {
            val preClean: Result<Unit> = adb.removeForward(serial, localPort)
            if (preClean.isFailure) {
                Logx.d(
                    TAG,
                    "预清理 forward tcp:$localPort 未成功（多数情况是无旧规则）：" +
                        preClean.exceptionOrNull()?.message
                )
            } else {
                Logx.i(TAG, "已预清理旧的 forward tcp:$localPort")
            }
        }

        val forward: Result<Int> = adb.forward(serial, localPort, remoteAbstract)
        if (forward.isFailure) {
            val cause: Throwable = forward.exceptionOrNull()
                ?: IllegalStateException("建立 forward tcp:$localPort 失败")
            Logx.e(TAG, "forward 失败：${cause.message}")
            return Result.failure(cause)
        }
        val actualPort: Int = forward.getOrDefault(localPort)

        // ③ app_process 启动
        val command: String = buildLaunchCommand(cfg, scid)
        Logx.i(TAG, "启动命令：$command")
        // 启动前清掉上一次的日志，避免读到上一轮的内容误判
        runCatching { adb.shell(serial, "rm -f $REMOTE_LOG_PATH", SIZE_TIMEOUT_MS) }
        val launch: Result<com.adb.adbwirelesshelper.domain.model.ShellResult> =
            adb.shell(serial, command, LAUNCH_TIMEOUT_MS)
        if (launch.isFailure) {
            val cause: Throwable = launch.exceptionOrNull()
                ?: IllegalStateException("启动 scrcpy-server 失败")
            Logx.e(TAG, "app_process 启动失败：${cause.message}")
            runCatching { cleanup(localPort, serial) }
            return Result.failure(cause)
        }
        val stdout: String = launch.getOrNull()?.stdout.orEmpty()
        if (stdout.isNotBlank()) {
            Logx.i(TAG, "app_process 输出：$stdout")
        }

        return Result.success(
            StartInfo(scid = scid, localPort = actualPort, remoteAbstract = remoteAbstract)
        )
    }

    /**
     * 等待设备端 server 真正 listen 上 abstract socket（连接前探活）。
     *
     * 为什么必须有这一步：`adb forward` 建好之后，adb server 就在本机端口上 listen，
     * 于是 App 的 `socket.connect()` **永远成功**（三次握手是与 adb server 完成的）。
     * 真正的失败点在后面两步 —— adb 把这条连接转发到设备的 `localabstract:scrcpy_<scid>`，
     * 以及我们随后读 dummy byte + device meta + stream meta。若此刻 app_process 还没把
     * `LocalServerSocket` 建好（dex 加载要几百毫秒到数秒），adb 转发失败会**立刻关闭**
     * 客户端连接，我们读到的就是 `EOFException`（既不是 timeout，也不是 refused）。
     *
     * 所以在 connect 之前先按 200ms 间隔轮询 `/proc/net/unix`，确认 `@scrcpy_<scid>`
     * 已存在再去连，从根上消除 EOF；最多等 10 秒，超时给出明确错误。
     *
     * @param serial 目标序列号
     * @param scid   本次部署的 scid（必须取本次 [StartInfo.scid]，不能用旧值或写死）
     * @return 命中即 success；超时返回带排查方向的 failure
     */
    suspend fun awaitServerSocket(serial: String, scid: String): Result<Unit> {
        if (serial.isBlank()) {
            return Result.failure(IllegalArgumentException("设备序列号为空，无法探活 scrcpy-server"))
        }
        val abstractName: String = REMOTE_ABSTRACT_PREFIX + scid
        val command: String = "cat /proc/net/unix 2>/dev/null | grep $abstractName"
        val deadline: Long = System.currentTimeMillis() + SERVER_SOCKET_WAIT_TIMEOUT_MS
        var probes: Int = 0
        while (true) {
            probes++
            val out: String = adb.shell(serial, command, SERVER_SOCKET_PROBE_TIMEOUT_MS)
                .getOrNull()?.stdout.orEmpty()
            if (out.contains(abstractName)) {
                Logx.i(TAG, "设备端 abstract socket 已就绪：$abstractName（第 $probes 次探活命中）")
                return Result.success(Unit)
            }
            if (System.currentTimeMillis() >= deadline) {
                Logx.w(
                    TAG,
                    "探活超时：${SERVER_SOCKET_WAIT_TIMEOUT_MS}ms 内 $abstractName 未出现（探活 $probes 次）"
                )
                return Result.failure(
                    IOException(
                        "scrcpy-server 未能在 ${SERVER_SOCKET_WAIT_TIMEOUT_MS}ms 内监听 abstract socket " +
                            "$abstractName（已探活 $probes 次）。多半是 server 版本与 " +
                            "ScrcpyConfig.serverVersion 不一致、jar 缺失或 app_process 启动失败，" +
                            "请查看设备侧 $REMOTE_LOG_PATH 与 logcat。"
                    )
                )
            }
            delay(SERVER_SOCKET_PROBE_INTERVAL_MS)
        }
    }

    /**
     * 兜底部署路径：当 `adb push` 不可用时，把 jar 做 base64 编码后经 shell 通道分块写入设备。
     * 依赖被控端存在 `base64`（toybox 通常自带）。
     *
     * 分块原因：Linux 单参数长度上限 MAX_ARG_STRLEN=128KB，必须保证每块长度 < 该值且为 4 的倍数
     * （4 的倍数才能被 `base64 -d` 独立解码，避免跨块拼接破坏 padding）。
     */
    suspend fun deployViaBase64(serial: String, cfg: ScrcpyConfig): Result<StartInfo> {
        if (serial.isBlank()) {
            return Result.failure(IllegalArgumentException("设备序列号为空，无法部署 scrcpy-server"))
        }
        if (cfg.serverVersion.isBlank()) {
            return Result.failure(
                IllegalStateException("ScrcpyConfig.serverVersion 为空，server 会因版本校验失败退出")
            )
        }
        val localJar: File = ensureLocalJar()
            ?: return Result.failure(IllegalStateException(MISSING_JAR_MESSAGE))

        val bytes: ByteArray = try {
            localJar.readBytes()
        } catch (e: IOException) {
            Logx.e(TAG, "读取本地 jar 失败：${e.message}")
            return Result.failure(e)
        }
        if (bytes.isEmpty()) {
            return Result.failure(IllegalStateException("scrcpy-server.jar 内容为空，请检查内置资源"))
        }

        val encoded: String = Base64.encodeToString(bytes, Base64.DEFAULT)
            .replace("\n", "")
            .replace("\r", "")

        val scid: String = newScid()
        val remoteAbstract: String = REMOTE_ABSTRACT_PREFIX + scid
        val localPort: Int = cfg.localPort.takeIf { it >= 0 && it <= 65535 } ?: DEFAULT_LOCAL_PORT

        // 先清空目标文件，保证后续 >> 追加从 0 开始
        adb.shell(serial, "rm -f $REMOTE_JAR_PATH", LAUNCH_TIMEOUT_MS)

        var offset: Int = 0
        var index: Int = 0
        while (offset < encoded.length) {
            val end: Int = minOf(offset + BASE64_CHUNK_SIZE, encoded.length)
            val chunk: String = encoded.substring(offset, end)
            val redirect: String = if (index == 0) ">" else ">>"
            val write: Result<com.adb.adbwirelesshelper.domain.model.ShellResult> = adb.shell(
                serial,
                "printf '%s' '$chunk' | base64 -d $redirect $REMOTE_JAR_PATH",
                BASE64_WRITE_TIMEOUT_MS
            )
            if (write.isFailure) {
                val cause: Throwable = write.exceptionOrNull()
                    ?: IllegalStateException("base64 部署第 ${index + 1} 块写入失败")
                Logx.e(TAG, "base64 部署失败：${cause.message}")
                adb.shell(serial, "rm -f $REMOTE_JAR_PATH", LAUNCH_TIMEOUT_MS)
                return Result.failure(cause)
            }
            val exitCode: Int = write.getOrNull()?.exitCode ?: 0
            if (exitCode != 0) {
                Logx.w(TAG, "base64 部署第 ${index + 1} 块退出码=$exitCode，继续")
            }
            offset = end
            index++
            if (index % 16 == 0) {
                Logx.d(TAG, "base64 部署进度：${offset}/${encoded.length}")
            }
        }

        val remoteSize: Long? = remoteJarSize(serial)
        if (remoteSize == null || remoteSize != bytes.size.toLong()) {
            val message: String =
                "base64 部署后大小校验失败：本地 ${bytes.size}B，远端 ${remoteSize ?: -1L}B"
            Logx.e(TAG, message)
            return Result.failure(IllegalStateException(message))
        }

        // 先清掉旧规则，避免端口固定（27183）时上一轮的残留导致 forward 失败。
        // localPort == 0（动态分配）时没有已知端口可清，跳过。
        if (localPort > 0) {
            runCatching { adb.removeForward(serial, localPort) }
        }

        val forward: Result<Int> = adb.forward(serial, localPort, remoteAbstract)
        if (forward.isFailure) {
            val cause: Throwable = forward.exceptionOrNull()
                ?: IllegalStateException("建立 forward tcp:$localPort 失败")
            return Result.failure(cause)
        }
        val actualPort: Int = forward.getOrDefault(localPort)

        val command: String = buildLaunchCommand(cfg, scid)
        runCatching { adb.shell(serial, "rm -f $REMOTE_LOG_PATH", SIZE_TIMEOUT_MS) }
        val launch: Result<com.adb.adbwirelesshelper.domain.model.ShellResult> =
            adb.shell(serial, command, LAUNCH_TIMEOUT_MS)
        if (launch.isFailure) {
            val cause: Throwable = launch.exceptionOrNull()
                ?: IllegalStateException("启动 scrcpy-server 失败")
            runCatching { cleanup(localPort, serial) }
            return Result.failure(cause)
        }
        Logx.i(TAG, "base64 部署成功：$REMOTE_JAR_PATH (${bytes.size}B，${index} 块)")
        return Result.success(
            StartInfo(scid = scid, localPort = actualPort, remoteAbstract = remoteAbstract)
        )
    }

    /**
     * 会话清理第三步：移除 `adb forward` 规则，并 best-effort 通知设备侧残留 server 退出。
     *
     * @param port   本机监听端口（StartInfo.localPort）
     * @param serial 目标序列号；为 null 时对当前所有在线设备尝试清理
     */
    suspend fun cleanup(port: Int, serial: String?) {
        if (port <= 0) return

        val targets: MutableList<String> = mutableListOf()
        if (!serial.isNullOrBlank()) {
            targets.add(serial)
        } else {
            adb.devices().getOrNull()?.forEach { device -> targets.add(device.serial) }
        }

        for (target: String in targets) {
            val removed: Result<Unit> = adb.removeForward(target, port)
            if (removed.isFailure) {
                Logx.w(TAG, "移除 forward tcp:$port 失败：${removed.exceptionOrNull()?.message}")
            } else {
                Logx.i(TAG, "已移除 forward tcp:$port @$target")
            }
        }

        // server 在读到控制通道 EOF 后通常会自行退出；这里再做一次兜底 kill，失败不影响调用方。
        val victim: String? = serial
        if (!victim.isNullOrBlank()) {
            val kill: Result<com.adb.adbwirelesshelper.domain.model.ShellResult> = adb.shell(
                victim,
                "for p in \$(ps -A -o PID= -o NAME= | grep com.genymobile.scrcpy.Server | awk '{print \$1}'); do kill -TERM \$p 2>/dev/null; done; true",
                LAUNCH_TIMEOUT_MS
            )
            if (kill.isFailure) {
                Logx.w(TAG, "清理设备侧残留进程失败：${kill.exceptionOrNull()?.message}")
            } else {
                Logx.i(TAG, "已请求设备侧 scrcpy-server 进程退出")
            }
        }
    }

    /**
     * 组装启动命令。除 serverVersion / scid / 视频三参数外全部按需求文档写死：
     * `tunnel_forward=true` 表示 App 侧主动连 127.0.0.1 的隧道方式。
     *
     * ⚠️ 输出重定向目标不是 /dev/null：早期版本把 stdout/stderr 丢到 /dev/null，
     * server 版本校验失败 / app_process 报错 / SELinux 限制等原因全都看不到线索，
     * 只能表现为「连接视频通道失败」。现在统一写到 [REMOTE_LOG_PATH]，
     * 连接失败时由 MirrorViewModel 读取前若干行展示给用户。
     */
    private fun buildLaunchCommand(cfg: ScrcpyConfig, scid: String): String {
        val bitRate: Int = cfg.videoBitRate.takeIf { it > 0 }
            ?: (cfg.bitrateMbps.coerceIn(1, 64) * 1_000_000)
        val maxSize: Int = cfg.maxSize.takeIf { it > 0 } ?: cfg.maxSizePreset
        val maxFps: Int = cfg.maxFps.takeIf { it > 0 } ?: cfg.fpsCap
        // 音频四参数只在开启音频时下发。
        //
        // ⚠️ audio_codec **必须显式下发**，哪怕用户选的就是 raw：
        //    Options 里 audioCodec 的默认值是 **OPUS**，不发这个参数服务端就会去编码 Opus，
        //    客户端随后读到的是 Opus 的 codec id（0x6f707573）而不是 raw 的 0x00726177 ——
        //    表现就是「选了原始 PCM 却按 Opus 解码」，声音全废且极难定位。
        //    `audio_codec=raw` 是合法取值（AudioCodec.findByName("raw") 命中 RAW）。
        // ⚠️ 不要下发 audio_buffer：服务端没有这个参数（Options.parse 会走 default 只打警告），
        //    音频缓冲是**控制端** AudioTrack 的事。
        val audioArgs: String = if (!cfg.audioEnabled) {
            "audio=false "
        } else {
            "audio=true " +
                "audio_codec=${sanitizeAudioCodec(cfg.audioCodec)} " +
                "audio_source=${sanitizeAudioSource(cfg.audioSource)} " +
                "audio_bit_rate=${sanitizeAudioBitRate(cfg.audioBitRate)} "
        }

        return "CLASSPATH=$REMOTE_JAR_PATH nohup app_process / com.genymobile.scrcpy.Server " +
            "${cfg.serverVersion} " +
            "scid=$scid " +
            "log_level=info " +
            "tunnel_forward=true " +
            "video=true " +
            audioArgs +
            // ⚠️ 必须与 ScrcpyController 建立的 socket 数量严格一致：
            // 服务端 DesktopConnection.open() 按 video → audio → control 的顺序 accept，
            // 这里传 true 而客户端不连对应那条，服务端就会永远卡在 accept() 上不写 device meta。
            "control=${cfg.controlEnabled} " +
            "cleanup=true " +
            "max_size=$maxSize " +
            "video_bit_rate=$bitRate " +
            "max_fps=$maxFps " +
            "send_frame_meta=true " +
            "send_device_meta=true " +
            "send_dummy_byte=true " +
            // 显式写出来而不是依赖默认值：客户端的握手**无条件**读 12B 视频流头与 4B 音频流头，
            // 一旦这里被改成 false，客户端就会把首帧数据当成流头解析（画面全花 + 音频错位）。
            "send_codec_meta=true " +
            ">$REMOTE_LOG_PATH 2>&1 &"
    }

    /**
     * 音频编码名白名单。
     *
     * ⚠️ 这是**命令注入防线**，不是简单的容错：`audio_codec` 取自 DataStore 里的字符串，
     * 而整条命令会被拼进 `adb shell`。若直接插值，被篡改的偏好值（如 `raw; rm -rf /sdcard`）
     * 就能在被控端以 shell 身份执行任意命令。
     * 因此只允许输出本文件里写死的常量，任何无法识别的输入一律回落到最安全的 `raw`。
     */
    private fun sanitizeAudioCodec(value: String): String = when (value) {
        ScrcpyConfig.AUDIO_CODEC_AAC -> ScrcpyConfig.AUDIO_CODEC_AAC
        ScrcpyConfig.AUDIO_CODEC_OPUS -> ScrcpyConfig.AUDIO_CODEC_OPUS
        else -> ScrcpyConfig.AUDIO_CODEC_RAW
    }

    /** 音频来源白名单，理由同 [sanitizeAudioCodec]。 */
    private fun sanitizeAudioSource(value: String): String = when (value) {
        ScrcpyConfig.AUDIO_SOURCE_PLAYBACK -> ScrcpyConfig.AUDIO_SOURCE_PLAYBACK
        ScrcpyConfig.AUDIO_SOURCE_MIC -> ScrcpyConfig.AUDIO_SOURCE_MIC
        else -> ScrcpyConfig.AUDIO_SOURCE_OUTPUT
    }

    /**
     * 音频码率收敛到 8k~512k bps。
     *
     * 越界值会让编码器 `configure()` 直接抛异常，服务端随即 `writeDisableStream(true)`
     * —— 用户只看到「音频没了」，日志里却是一句 MediaCodec 报错。这里提前夹住，
     * 把问题挡在发命令之前。
     */
    private fun sanitizeAudioBitRate(value: Int): Int =
        if (value <= 0) DEFAULT_AUDIO_BIT_RATE else value.coerceIn(8_000, 512_000)

    /** scid：31-bit 随机数（恒为正）的十六进制表示，补零到 8 位。手写补零避免 Locale 影响。 */
    private fun newScid(): String {
        val value: Int = random.nextInt(1 shl (SCID_BITS - 1))
        return value.toString(16).padStart(8, '0')
    }

    /**
     * 解析可用于 push 的本地 jar 文件。三级来源：
     * ① assets/scrcpy-server.jar（释放到 filesDir 后返回）
     * ② filesDir 下已存在的同名缓存
     * ③ 经 context.openFileInput 读到的历史缓存
     * 都没有时返回 null（调用方据此抛出带明确指引的异常，不做网络下载）。
     */
    private fun ensureLocalJar(): File? {
        val cacheFile: File = File(context.filesDir, CACHE_JAR_NAME)

        val fromAssets: File? = runCatching {
            context.assets.open(ASSET_JAR_NAME).use { input ->
                context.openFileOutput(CACHE_JAR_NAME, Context.MODE_PRIVATE).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read: Int = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            cacheFile
        }.getOrNull()
        if (fromAssets != null && fromAssets.exists() && fromAssets.length() > 0L) {
            return fromAssets
        }

        if (cacheFile.exists() && cacheFile.length() > 0L) {
            return cacheFile
        }

        return readCachedJar()
    }

    /** 通过 openFileInput 读取历史缓存，并落到 cacheDir 供 adb push 使用。 */
    private fun readCachedJar(): File? = runCatching {
        context.openFileInput(CACHE_JAR_NAME).use { input ->
            val tmp: File = File(context.cacheDir, CACHE_JAR_NAME)
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val read: Int = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
            tmp.takeIf { it.length() > 0L }
        }
    }.getOrNull()

    /** 读取设备侧 jar 大小（best-effort，失败返回 null）。 */
    private suspend fun remoteJarSize(serial: String): Long? {
        val result: Result<com.adb.adbwirelesshelper.domain.model.ShellResult> =
            adb.shell(serial, "stat -c %s $REMOTE_JAR_PATH", SIZE_TIMEOUT_MS)
        val raw: String = result.getOrNull()?.stdout?.trim().orEmpty()
        return raw.toLongOrNull()
    }

    companion object {
        private const val TAG: String = "ScrcpyDeployer"
        private const val REMOTE_JAR_PATH: String = "/data/local/tmp/scrcpy-server.jar"
        /** server 启动日志落盘路径：连接失败时由 MirrorViewModel 读取前若干行做诊断 */
        private const val REMOTE_LOG_PATH: String = "/data/local/tmp/scrcpy-server.log"
        private const val REMOTE_ABSTRACT_PREFIX: String = "scrcpy_"
        private const val ASSET_JAR_NAME: String = "scrcpy-server.jar"
        private const val CACHE_JAR_NAME: String = "scrcpy-server.jar"
        private const val COPY_BUFFER_SIZE: Int = 64 * 1024
        private const val BASE64_CHUNK_SIZE: Int = 60 * 1024
        private const val SCID_BITS: Int = 31
        private const val DEFAULT_LOCAL_PORT: Int = 27_183
        /** 探活间隔：每 200ms 查一次设备端 abstract socket */
        private const val SERVER_SOCKET_PROBE_INTERVAL_MS: Long = 200L
        /** 探活总上限：最多等 10 秒 */
        private const val SERVER_SOCKET_WAIT_TIMEOUT_MS: Long = 10_000L
        /** 单次探活命令的超时（adb shell 往返），必须小于总上限 */
        private const val SERVER_SOCKET_PROBE_TIMEOUT_MS: Long = 3_000L
        private const val LAUNCH_TIMEOUT_MS: Long = 15_000L
        private const val BASE64_WRITE_TIMEOUT_MS: Long = 30_000L
        private const val SIZE_TIMEOUT_MS: Long = 5_000L
        private const val MISSING_JAR_MESSAGE: String =
            "缺少 scrcpy-server.jar，请把 JAR 放到 app/src/main/assets/scrcpy-server.jar，" +
                "且版本号需与 BuildConfig.SCRCPY_SERVER_VERSION 一致（详见 README「二进制资源」）"

        /** 音频码率兜底值，与 scrcpy `Options.audioBitRate` 的默认值一致。 */
        private const val DEFAULT_AUDIO_BIT_RATE: Int = 128_000
    }
}
