package com.adb.adbwirelesshelper.util

import com.adb.adbwirelesshelper.domain.model.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 进程执行封装（adb / app_process 等外部命令统一走这里）。
 *
 * 关键约束（来自需求 8.1）：
 * 1. stdout 与 stderr **必须并发泵流**，否则管道缓冲区写满会死锁；
 * 2. 统一超时，超时后先 destroy、再 destroyForcibly；
 * 3. 全程在 Dispatchers.IO。
 */
object ProcessRunner {

    private const val TAG = "ProcessRunner"
    private const val DEFAULT_TIMEOUT_MS = 15_000L
    private const val POLL_INTERVAL_MS = 40L
    private const val GRACE_DELAY_MS = 200L
    private const val PUMP_JOIN_TIMEOUT_MS = 3_000L

    /** 超时退出码（沿用 GNU timeout 的约定） */
    const val EXIT_TIMEOUT = 124

    /**
     * 执行命令并收集文本输出。
     *
     * @param cmd        命令与参数列表（第一个元素必须是可执行文件路径）
     * @param env        追加的环境变量（如 HOME / TMPDIR / ANDROID_ADB_SERVER_PORT）
     * @param workDir    工作目录，null 表示继承当前进程
     * @param timeoutMs  超时毫秒；超时返回 [EXIT_TIMEOUT]
     * @param onLine     逐行回调（stdout 与 stderr 都会回调），可为 null
     */
    suspend fun exec(
        cmd: List<String>,
        env: Map<String, String> = emptyMap(),
        workDir: File? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onLine: ((String) -> Unit)? = null
    ): ShellResult = withContext(Dispatchers.IO) {
        require(cmd.isNotEmpty()) { "ProcessRunner.exec: 命令列表为空" }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode = -1
        var process: Process? = null

        try {
            val p = start(cmd, env, workDir)
            process = p
            var timedOut = false

            coroutineScope {
                val pumpOut = launch { pump(p.inputStream, stdout, onLine) }
                val pumpErr = launch { pump(p.errorStream, stderr, onLine) }

                val deadline = System.currentTimeMillis() + timeoutMs
                while (isActive) {
                    if (!p.isAlive) {
                        break
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        timedOut = true
                        break
                    }
                    delay(POLL_INTERVAL_MS)
                }

                if (timedOut) {
                    runCatching { p.destroy() }
                    delay(GRACE_DELAY_MS)
                    if (p.isAlive) {
                        runCatching { p.destroyForcibly() }
                    }
                    Logx.w(TAG, "命令超时(${timeoutMs}ms)已强杀: ${cmd.firstOrNull()}")
                }

                // 等泵流协程收尾，避免输出丢失（最多等 3 秒）
                withTimeoutOrNull(PUMP_JOIN_TIMEOUT_MS) {
                    pumpOut.join()
                    pumpErr.join()
                }
            }

            exitCode = if (timedOut) {
                EXIT_TIMEOUT
            } else {
                runCatching { p.exitValue() }.getOrDefault(-1)
            }
        } catch (ce: CancellationException) {
            // 取消必须向上传播，绝不能吞掉（否则停止 logcat 会失效）
            throw ce
        } catch (ioe: IOException) {
            stderr.append("\n进程启动失败: ").append(ioe.message.orEmpty())
            Logx.e(TAG, "进程启动失败 cmd=${cmd.firstOrNull()} : ${ioe.message}")
        } catch (t: Throwable) {
            stderr.append("\n进程执行异常: ").append(t.message.orEmpty())
            Logx.e(TAG, "进程执行异常 cmd=${cmd.firstOrNull()} : ${t.message}")
        } finally {
            process?.let { p ->
                runCatching { p.inputStream.close() }
                runCatching { p.errorStream.close() }
                runCatching { p.outputStream.close() }
                if (p.isAlive) {
                    runCatching { p.destroyForcibly() }
                }
            }
        }

        ShellResult(exitCode, stdout.toString(), stderr.toString())
    }

    /**
     * 执行命令并以原始字节收集输出（用于 `adb exec-out`，如截图的 PNG 二进制流）。
     *
     * @return Pair(exitCode, 输出字节)。超时时 exitCode 为 [EXIT_TIMEOUT]。
     */
    suspend fun execBytes(
        cmd: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        require(cmd.isNotEmpty()) { "ProcessRunner.execBytes: 命令列表为空" }

        var exitCode = -1
        var bytes = ByteArray(0)
        var process: Process? = null

        try {
            val p = start(cmd, env, null)
            process = p
            var timedOut = false

            coroutineScope {
                val reader = launch {
                    bytes = runCatching { p.inputStream.readBytes() }.getOrDefault(ByteArray(0))
                }
                val drainer = launch {
                    // stderr 也要排空，否则缓冲区满会阻塞子进程
                    runCatching { p.errorStream.bufferedReader().use { it.readText() } }
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                while (isActive) {
                    if (!p.isAlive) {
                        break
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        timedOut = true
                        break
                    }
                    delay(POLL_INTERVAL_MS)
                }

                if (timedOut) {
                    runCatching { p.destroy() }
                    delay(GRACE_DELAY_MS)
                    if (p.isAlive) {
                        runCatching { p.destroyForcibly() }
                    }
                    Logx.w(TAG, "命令超时(${timeoutMs}ms)已强杀: ${cmd.firstOrNull()}")
                }

                withTimeoutOrNull(PUMP_JOIN_TIMEOUT_MS) {
                    reader.join()
                    drainer.join()
                }
            }

            exitCode = if (timedOut) {
                EXIT_TIMEOUT
            } else {
                runCatching { p.exitValue() }.getOrDefault(-1)
            }
        } catch (ce: CancellationException) {
            // 取消必须向上传播，绝不能吞掉
            throw ce
        } catch (ioe: IOException) {
            Logx.e(TAG, "进程启动失败 cmd=${cmd.firstOrNull()} : ${ioe.message}")
        } catch (t: Throwable) {
            Logx.e(TAG, "进程执行异常 cmd=${cmd.firstOrNull()} : ${t.message}")
        } finally {
            process?.let { p ->
                runCatching { p.inputStream.close() }
                runCatching { p.errorStream.close() }
                runCatching { p.outputStream.close() }
                if (p.isAlive) {
                    runCatching { p.destroyForcibly() }
                }
            }
        }

        exitCode to bytes
    }

    /**
     * 启动进程。
     * 注意：不合并 stderr（redirectErrorStream = false），因为 UI 需要区分 stdout/stderr 着色。
     */
    private fun start(cmd: List<String>, env: Map<String, String>, workDir: File?): Process {
        val builder = ProcessBuilder(cmd)
        if (workDir != null) {
            builder.directory(workDir)
        }
        if (env.isNotEmpty()) {
            builder.environment().putAll(env)
        }
        builder.redirectErrorStream(false)
        Logx.d(TAG, "exec: ${cmd.joinToString(" ")}")
        return builder.start()
    }

    /** 逐行读取流：追加到 sink，并回调 onLine。 */
    private suspend fun pump(stream: InputStream, sink: StringBuilder, onLine: ((String) -> Unit)?) =
        withContext(Dispatchers.IO) {
            runCatching {
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        sink.append(line).append('\n')
                        if (onLine != null) {
                            runCatching { onLine.invoke(line) }
                        }
                    }
                }
            }.onFailure {
                Logx.d(TAG, "泵流结束（可能进程已被强杀）: ${it.message}")
            }
        }

    /** 供外部复用：安全关闭输出流（写 stdin 场景）。 */
    fun closeQuietly(stream: OutputStream?) {
        if (stream == null) return
        runCatching { stream.close() }
    }
}
