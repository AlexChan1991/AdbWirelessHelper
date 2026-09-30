package com.adb.adbwirelesshelper.data.adb

import com.adb.adbwirelesshelper.domain.model.AdbDevice
import com.adb.adbwirelesshelper.domain.model.DeviceState
import com.adb.adbwirelesshelper.domain.model.ShellResult
import com.adb.adbwirelesshelper.util.Logx
import com.adb.adbwirelesshelper.util.ProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * ADB 门面接口。所有实现都必须保证：
 * 1. 全部方法为 suspend，内部切换到 Dispatchers.IO；
 * 2. 不抛异常，统一用 [Result] 表达失败；
 * 3. 不引用任何 Android UI 类型。
 */
interface AdbClient {

    /** `adb version` 输出，用于自检 */
    suspend fun version(): Result<String>

    /** `adb devices -l` */
    suspend fun devices(): Result<List<AdbDevice>>

    /** `adb pair <host>:<port> <code>` —— 注意必须用**配对端口** */
    suspend fun pair(host: String, port: Int, code: String): Result<String>

    /** `adb connect <host>:<port>` —— 注意必须用**连接端口** */
    suspend fun connect(host: String, port: Int): Result<String>

    /** `adb disconnect <serial>` */
    suspend fun disconnect(serial: String): Result<String>

    /**
     * `adb -s <serial> forward tcp:<localPort> localabstract:<remoteAbstract>`
     * @return 实际监听的本地端口（localPort 传 0 时由 adb 分配，从输出解析）
     */
    suspend fun forward(serial: String, localPort: Int, remoteAbstract: String): Result<Int>

    /** `adb -s <serial> forward --remove tcp:<localPort>` */
    suspend fun removeForward(serial: String, localPort: Int): Result<Unit>

    /** `adb -s <serial> forward --list` */
    suspend fun forwardList(serial: String): Result<List<String>>

    /** `adb -s <serial> push <localFile> <remotePath>` */
    suspend fun push(serial: String, localFile: File, remotePath: String): Result<String>

    /**
     * `adb -s <serial> pull <remotePath> <localFile>`
     *
     * 用途：文件管理页播放**被控端视频**。视频动辄几百 MB，不可能一次性读进内存
     * （[execOut] 会把整个文件读成 ByteArray），必须先落到控制端本地文件，
     * 再交给 MediaPlayer 播放。图片则走 [execOut]，不需要落盘。
     */
    suspend fun pull(serial: String, remotePath: String, localFile: File): Result<String>

    /** `adb -s <serial> shell <command>` */
    suspend fun shell(serial: String, command: String, timeoutMs: Long = 15_000): Result<ShellResult>

    /** 同上，但逐行流式回调（用于 logcat 等长任务） */
    suspend fun shellStream(
        serial: String,
        command: String,
        timeoutMs: Long = 15_000,
        onLine: suspend (String) -> Unit
    ): Result<ShellResult>

    /**
     * `adb -s <serial> exec-out <command>` —— 原始字节输出（截图等）。
     *
     * ⚠️ 两个必须知道的 exec-out 特性：
     * 1. adbd 把子进程的 **stderr 并进同一条字节流**（不区分 stdout / stderr），
     *    所以调用方拼的命令**必须自带 `2>/dev/null`**，否则命令报错时
     *    错误文本会被当成业务字节返回（真机实测：`tail` 打不开文件时返回
     *    43 字节的 `Permission denied`，上层当成文件数据收下了）。
     *    例外是「要看输出 / 退出码」的探测类命令，那种不该被 execOut 承载；
     * 2. 成功判据**只看退出码**：`exitCode != 0` 一律失败。
     *    曾经写成 `exitCode != 0 && bytes.isEmpty()`，于是「部分字节 + 非零退出码」
     *    被当成成功 —— 超时强杀（[ProcessRunner.EXIT_TIMEOUT]）、管道被截断时
     *    adb 会先吐出半截数据再退出，这个半截数据被判成功，
     *    上层拿到短块后会把文件中途当成 EOF（播放静默结束，且没有任何报错）。
     *    宁可判失败让上层重试 / 报错，也不要把截断数据当成功。
     *    注：**空字节 + 退出码 0 仍然是合法的 EOF**（如 `tail -c +N` 越过文件尾），不算失败。
     */
    suspend fun execOut(serial: String, command: String, timeoutMs: Long = 15_000): Result<ByteArray>

    /** `adb kill-server` */
    suspend fun killServer()
}

/**
 * 基于内置原生 adb 二进制 + [ProcessRunner] 的实现。
 *
 * 每次调用前都会 [AdbRuntime.prepare] 自检，二进制缺失时返回明确的中文错误。
 */
class NativeAdbClient(private val runtime: AdbRuntime) : AdbClient {

    override suspend fun version(): Result<String> = withContext(Dispatchers.IO) {
        runAdb { bin -> listOf(bin, "version") }
            .map { it.stdout.ifBlank { it.stderr }.trim() }
    }

    override suspend fun devices(): Result<List<AdbDevice>> = withContext(Dispatchers.IO) {
        // devices -l 的合法输出里会含 unauthorized / offline，那是设备状态不是命令失败，
        // 因此这里只判退出码（Judge.EXIT_ONLY）。
        runAdb(judge = Judge.EXIT_ONLY) { bin -> listOf(bin, "devices", "-l") }
            .map { parseDevices(it.stdout) }
    }

    override suspend fun pair(host: String, port: Int, code: String): Result<String> =
        withContext(Dispatchers.IO) {
            runAdb(timeoutMs = PAIR_TIMEOUT_MS) { bin ->
                listOf(bin, "pair", "$host:$port", code)
            }.map { (it.stdout + it.stderr).trim() }
        }

    override suspend fun connect(host: String, port: Int): Result<String> =
        withContext(Dispatchers.IO) {
            runAdb(timeoutMs = CONNECT_TIMEOUT_MS) { bin ->
                listOf(bin, "connect", "$host:$port")
            }.map { (it.stdout + it.stderr).trim() }
        }

    override suspend fun disconnect(serial: String): Result<String> = withContext(Dispatchers.IO) {
        runAdb { bin -> listOf(bin, "disconnect", serial) }
            .map { (it.stdout + it.stderr).trim() }
    }

    override suspend fun forward(
        serial: String,
        localPort: Int,
        remoteAbstract: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        runAdb { bin ->
            listOf(bin, "-s", serial, "forward", "tcp:$localPort", "localabstract:$remoteAbstract")
        }.mapCatching { result ->
            val text = (result.stdout + result.stderr).trim()
            // adb 成功时输出就是实际监听的端口号；解析不出来说明 forward 很可能没建成。
            // ⚠️ 历史缺陷：这里曾用 `parsed ?: localPort` 兜底，把 adb 的错误输出当成成功，
            //    导致 forward 失败后仍继续启动 server，最终表现为「连接视频通道失败」。
            val parsed: Int? = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.toIntOrNull()
            if (parsed != null) {
                parsed
            } else if (localPort > 0) {
                // 少数 adb 版本成功时不打印端口：用 forward --list 复核，避免把成功误判成失败
                val listed: List<String> = forwardList(serial).getOrDefault(emptyList())
                val matched: String? = listed.firstOrNull { it.contains("tcp:$localPort") }
                if (matched != null) {
                    Logx.i(TAG, "adb forward 未打印端口，但 --list 已确认规则存在：$matched")
                    localPort
                } else {
                    throw IllegalStateException(
                        "adb forward tcp:$localPort -> localabstract:$remoteAbstract 失败：" +
                            text.ifBlank { "（adb 无输出，且 forward --list 中也不存在该规则）" }
                    )
                }
            } else {
                // tcp:0 = 让 adb 动态分配。adb 通常会直接打印分配到的端口号；
                // 少数版本不打印，此时按 localabstract 名在 forward --list 里反查，
                // 拿到 tcp:NNNN 的真实端口。两者都拿不到就必须报错 ——
                // 否则上层会拿 0 去 connect，必然连不上。
                val listed: List<String> = forwardList(serial).getOrDefault(emptyList())
                val matched: String? = listed.firstOrNull { it.contains("localabstract:$remoteAbstract") }
                val fromList: Int? = matched?.let { line ->
                    val raw: String = line.substringAfter("tcp:", "").takeWhile { ch -> ch.isDigit() }
                    raw.toIntOrNull()?.takeIf { port -> port > 0 }
                }
                if (fromList != null) {
                    Logx.i(TAG, "adb forward tcp:0 未打印端口，由 --list 反查到 $fromList（$matched）")
                    fromList
                } else {
                    throw IllegalStateException(
                        "adb forward tcp:0 -> localabstract:$remoteAbstract 未返回实际端口号：" +
                            text.ifBlank { "（adb 无输出，且 forward --list 中也查不到该 abstract 名）" }
                    )
                }
            }
        }
    }

    override suspend fun removeForward(serial: String, localPort: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runAdb { bin ->
                listOf(bin, "-s", serial, "forward", "--remove", "tcp:$localPort")
            }.map { Unit }
        }

    override suspend fun forwardList(serial: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runAdb { bin -> listOf(bin, "-s", serial, "forward", "--list") }
                .map { result ->
                    result.stdout.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
                }
        }

    override suspend fun push(serial: String, localFile: File, remotePath: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (!localFile.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("本地文件不存在: ${localFile.absolutePath}")
                )
            }
            runAdb(timeoutMs = PUSH_TIMEOUT_MS) { bin ->
                listOf(bin, "-s", serial, "push", localFile.absolutePath, remotePath)
            }.map { (it.stdout + it.stderr).trim() }
        }

    override suspend fun pull(
        serial: String,
        remotePath: String,
        localFile: File
    ): Result<String> = withContext(Dispatchers.IO) {
        // pull 的目标目录由调用方保证存在；这里额外兜一次，避免 adb 报含义不明的错误
        localFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return@withContext Result.failure(
                    IllegalStateException("无法创建本地目录: ${parent.absolutePath}")
                )
            }
        }
        runAdb(timeoutMs = PULL_TIMEOUT_MS) { bin ->
            listOf(bin, "-s", serial, "pull", remotePath, localFile.absolutePath)
        }.mapCatching { result ->
            val text: String = (result.stdout + result.stderr).trim()
            // adb pull 成功时通常打印传输速率，但少数版本静默；
            // 以「本地文件真的存在且非空」作为成功判据，比看文本可靠。
            if (localFile.exists() && localFile.length() > 0L) {
                text
            } else {
                throw IllegalStateException(
                    "adb pull 未产生有效文件：$remotePath -> ${localFile.absolutePath}" +
                        text.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()
                )
            }
        }
    }

    override suspend fun shell(
        serial: String,
        command: String,
        timeoutMs: Long
    ): Result<ShellResult> = withContext(Dispatchers.IO) {
        // shell 的退出码是「远端命令自己的业务结果」（grep 无匹配=1、stat 失败=1……），
        // 不能当 adb 失败处理，否则 DeviceInfoCollector / 命令行页会全线误报。
        // 只在退出码非零且 stderr 含 adb 传输层错误时才判失败。
        runAdb(timeoutMs = timeoutMs, judge = Judge.TRANSPORT) { bin ->
            listOf(bin, "-s", serial, "shell", command)
        }
    }

    override suspend fun shellStream(
        serial: String,
        command: String,
        timeoutMs: Long,
        onLine: suspend (String) -> Unit
    ): Result<ShellResult> = withContext(Dispatchers.IO) {
        val prepared = runtime.prepare()
        if (prepared.isFailure) {
            return@withContext Result.failure(
                prepared.exceptionOrNull() ?: IllegalStateException("adb 运行时不可用")
            )
        }
        val cmd = listOf(runtime.binaryPath(), "-s", serial, "shell", command)
        // 用 Channel 把「非挂起回调」桥接到「挂起回调」：生产者投递，消费者挂起转发。
        val channel = Channel<String>(Channel.UNLIMITED)
        val consumer = launch {
            for (line in channel) {
                onLine(line)
            }
        }
        val result = try {
            ProcessRunner.exec(cmd, runtime.env(), null, timeoutMs) { line ->
                channel.trySend(line)
            }
        } finally {
            channel.close()
            consumer.join()
        }
        Result.success(result)
    }

    override suspend fun execOut(
        serial: String,
        command: String,
        timeoutMs: Long
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val prepared = runtime.prepare()
        if (prepared.isFailure) {
            return@withContext Result.failure(
                prepared.exceptionOrNull() ?: IllegalStateException("adb 运行时不可用")
            )
        }
        val cmd = listOf(runtime.binaryPath(), "-s", serial, "exec-out", command)
        val (exitCode, bytes) = ProcessRunner.execBytes(cmd, runtime.env(), timeoutMs)
        // 只看退出码：非零一律失败，绝不接受「部分字节 + 非零退出码」（见接口 KDoc）。
        // 超时强杀后 adb 往往已经吐出半截数据，那半截数据比「明确失败」更危险。
        if (exitCode != 0) {
            Logx.w(
                TAG,
                "exec-out 失败，退出码 $exitCode，丢弃 ${bytes.size} 字节不完整输出：" +
                    cmd.joinToString(" ")
            )
            return@withContext Result.failure(
                IllegalStateException("exec-out 失败，退出码 $exitCode")
            )
        }
        Result.success(bytes)
    }

    override suspend fun killServer() = withContext(Dispatchers.IO) {
        runCatching {
            val cmd = listOf(runtime.binaryPath(), "kill-server")
            ProcessRunner.exec(cmd, runtime.env(), null, 5_000)
        }
        Logx.i(TAG, "adb kill-server 已执行")
    }

    /**
     * 统一的「准备 + 执行 + **失败判定**」封装。
     *
     * ⚠️ 历史缺陷：这里过去无条件 `return Result.success(result)`，exitCode 只用于打日志，
     * 于是 `adb push` / `forward` / `app_process` 启动任意一步真实失败时，调用方（
     * [com.adb.adbwirelesshelper.data.scrcpy.ScrcpyServerDeployer]）都拿到 success，
     * 一路走到「连接 scrcpy 视频通道失败（端口 27183）」这个无信息量的报错。
     * 现在统一判定：
     * 1. `exitCode != 0` → failure；
     * 2. 输出文本命中失败标记 → failure（adb 很多失败退出码仍然是 0）。
     *
     * 例外（由 [Judge] 控制）：
     * - [Judge.EXIT_ONLY]：`adb devices -l` 合法输出里含 `unauthorized` / `offline`，那是设备状态；
     * - [Judge.TRANSPORT]：`adb shell` 的退出码是远端命令的业务结果，只在传输层报错时才判失败。
     *
     * failure 的 message 一律带上 **退出码 + 原始输出**，UI 才能显示真实原因。
     *
     * @param timeoutMs 超时时间
     * @param judge     判定模式，默认 [Judge.STRICT]
     * @param builder   由二进制路径拼出完整命令
     */
    private suspend fun runAdb(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        judge: Judge = Judge.STRICT,
        builder: (String) -> List<String>
    ): Result<ShellResult> {
        val prepared = runtime.prepare()
        if (prepared.isFailure) {
            return Result.failure(
                prepared.exceptionOrNull() ?: IllegalStateException("adb 运行时不可用")
            )
        }
        val cmd = builder(runtime.binaryPath())
        val result = ProcessRunner.exec(cmd, runtime.env(), null, timeoutMs)
        Logx.d(TAG, "cmd=${cmd.joinToString(" ")} exit=${result.exitCode}")
        val failure: String? = judgeFailure(result, judge)
        if (failure != null) {
            Logx.w(TAG, "adb 命令判定失败：cmd=${cmd.joinToString(" ")} | $failure")
            return Result.failure(IllegalStateException(failure))
        }
        return Result.success(result)
    }

    /**
     * 判定一次 adb 执行是否失败；成功返回 null，失败返回可直接展示给用户的中文原因。
     */
    private fun judgeFailure(result: ShellResult, judge: Judge): String? {
        val combined: String = (result.stdout + "\n" + result.stderr).trim()

        if (judge == Judge.TRANSPORT) {
            // shell：远端命令的非零退出码属于正常业务结果，只在 adb 传输层报错时才失败
            if (result.exitCode == 0) return null
            val stderr = result.stderr.lowercase(Locale.ROOT)
            val marker = TRANSPORT_ERROR_MARKERS.firstOrNull { stderr.contains(it) }
                ?: return null
            return "adb 传输层错误（$marker，退出码 ${result.exitCode}）：" +
                combined.ifBlank { "（无输出）" }
        }

        if (result.exitCode != 0) {
            return "adb 退出码 ${result.exitCode}：" + combined.ifBlank { "（无输出）" }
        }
        if (judge == Judge.EXIT_ONLY) {
            return null
        }
        val lower = combined.lowercase(Locale.ROOT)
        val marker = FAILURE_MARKERS.firstOrNull { lower.contains(it) }
            ?: return null
        return "adb 退出码为 0 但输出含失败标记「$marker」：" + combined.ifBlank { "（无输出）" }
    }

    /**
     * 解析 `adb devices -l` 输出。
     * 形如：
     *   List of devices attached
     *   192.168.1.5:37123    device product:sunfish model:Pixel_4a device:sunfish transport_id:3
     */
    internal fun parseDevices(stdout: String): List<AdbDevice> {
        val list = ArrayList<AdbDevice>()
        var started = false
        for (raw in stdout.lineSequence()) {
            val line = raw.trim()
            if (!started) {
                if (line.startsWith("List of devices attached", ignoreCase = true)) {
                    started = true
                }
                continue
            }
            if (line.isBlank()) {
                continue
            }
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) {
                continue
            }
            val serial = parts[0]
            val state = parseState(parts[1])
            var model: String? = null
            var product: String? = null
            var deviceCode: String? = null
            for (token in parts.drop(2)) {
                when {
                    token.startsWith("model:") -> model = token.removePrefix("model:")
                    token.startsWith("product:") -> product = token.removePrefix("product:")
                    token.startsWith("device:") -> deviceCode = token.removePrefix("device:")
                }
            }
            list.add(AdbDevice(serial, state, model, product, deviceCode))
        }
        return list
    }

    private fun parseState(raw: String): DeviceState = when (raw.lowercase(Locale.ROOT)) {
        "device" -> DeviceState.DEVICE
        "offline" -> DeviceState.OFFLINE
        "unauthorized" -> DeviceState.UNAUTHORIZED
        "no permissions" -> DeviceState.UNAUTHORIZED
        else -> DeviceState.UNKNOWN
    }

    /**
     * 失败判定模式。
     *
     * - [STRICT]：退出码 + 输出文本双判定（push / forward / pair / connect / version 等）；
     * - [EXIT_ONLY]：只判退出码（devices，其合法输出含 unauthorized/offline）；
     * - [TRANSPORT]：只判 adb 传输层错误（shell，退出码是远端命令的业务结果）。
     */
    private enum class Judge {
        STRICT,
        EXIT_ONLY,
        TRANSPORT
    }

    companion object {
        private const val TAG = "NativeAdbClient"
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val PAIR_TIMEOUT_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val PUSH_TIMEOUT_MS = 60_000L
        // 视频可能几百 MB，Wi-Fi 下传输较慢，给足 5 分钟
        private const val PULL_TIMEOUT_MS = 300_000L

        /**
         * adb 输出的失败标记（小写包含匹配）。
         * adb 各版本措辞不一致，且不少失败路径退出码仍是 0，只能看文本。
         * 注意：`connect` / `pair` 的成功文本判定在上层
         * （`DeviceRepository.isConnectedText` / `PairingManager`），这里只负责识别失败。
         */
        private val FAILURE_MARKERS: List<String> = listOf(
            "error:",
            "failed",
            "cannot",
            "no devices",
            "device offline",
            "unauthorized",
            "device not found"
        )

        /** adb 传输层错误标记：用于 [Judge.TRANSPORT]，避免把远端命令的报错误判成 adb 失败 */
        private val TRANSPORT_ERROR_MARKERS: List<String> = listOf(
            "no devices",
            "device offline",
            "device not found",
            "unauthorized",
            "cannot connect",
            "adb: error",
            "error: closed"
        )
    }
}
