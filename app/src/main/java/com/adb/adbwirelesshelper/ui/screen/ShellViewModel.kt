package com.adb.adbwirelesshelper.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adb.adbwirelesshelper.data.adb.ShellExecutor
import com.adb.adbwirelesshelper.data.repository.DeviceRepository
import com.adb.adbwirelesshelper.data.settings.AppSettings
import com.adb.adbwirelesshelper.domain.model.ShellChunk
import com.adb.adbwirelesshelper.domain.model.ShellResult
import com.adb.adbwirelesshelper.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 终端输出行。 */
data class ShellLine(
    val text: String,
    val isError: Boolean = false,
    val isCommand: Boolean = false
)

/** 预设命令。 */
data class Preset(
    val label: String,
    val command: String,
    val danger: Boolean = false
)

/**
 * 命令行页 ViewModel。
 *
 * 关键点：
 * - 输出行数硬上限 [MAX_OUTPUT_LINES]，超出丢弃最旧的，避免 `logcat` 类长输出撑爆内存（需求 F1）；
 * - 同一时刻只允许一条命令在跑（需求 F2 / 8.5）；
 * - 高危命令先进入 [pendingDanger] 等待二次确认（需求 F5），禁止批量绕过。
 */
class ShellViewModel(
    private val executor: ShellExecutor,
    private val repo: DeviceRepository,
    private val settings: AppSettings
) : ViewModel() {

    private val _output: MutableStateFlow<List<ShellLine>> = MutableStateFlow(emptyList())
    val output: StateFlow<List<ShellLine>> = _output.asStateFlow()

    private val _running: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastResult: MutableStateFlow<String?> = MutableStateFlow(null)
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    private val _pendingDanger: MutableStateFlow<String?> = MutableStateFlow(null)
    val pendingDanger: StateFlow<String?> = _pendingDanger.asStateFlow()

    private val _history: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private val _timeoutMs: MutableStateFlow<Long> = MutableStateFlow(DEFAULT_TIMEOUT_MS)
    val timeoutMs: StateFlow<Long> = _timeoutMs.asStateFlow()

    /** ≥ 12 条常用命令，横向 Chip 直接预填。 */
    val presets: List<Preset> = listOf(
        Preset("设备信息", "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release"),
        Preset("序列号", "getprop ro.serialno"),
        Preset("Android 版本", "getprop ro.build.version.release; getprop ro.build.version.sdk"),
        Preset("IP 地址", "ip -f inet addr show wlan0"),
        Preset("内存", "cat /proc/meminfo | head -n 3"),
        Preset("磁盘", "df /data"),
        Preset("CPU 核心数", "nproc --all"),
        Preset("CPU 频率", "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"),
        Preset("电量", "dumpsys battery | grep -E 'level|status|scale'"),
        Preset("顶层 Activity", "dumpsys activity activities | grep mResumedActivity"),
        Preset("应用列表", "pm list packages | head -n 30"),
        Preset("运行进程", "ps -A -o PID,NAME | head -n 20"),
        Preset("屏幕状态", "dumpsys power | grep -E 'mWakefulness|mScreenOn'"),
        Preset("清空日志", "logcat -c", danger = true),
        Preset("卸载示例", "pm uninstall com.example.demo", danger = true),
        Preset("重启", "reboot", danger = true)
    )

    private var boundSerial: String = ""
    private var job: Job? = null
    private var dangerConfirmEnabled: Boolean = true

    init {
        viewModelScope.launch {
            settings.dangerConfirm.collect { enabled -> dangerConfirmEnabled = enabled }
        }
        append(ShellLine(TEXT_WELCOME_1))
        append(ShellLine(TEXT_WELCOME_2))
    }

    /** 绑定目标设备（幂等）。 */
    fun bind(serial: String) {
        if (serial.isBlank() || boundSerial == serial) return
        boundSerial = serial
        val device = repo.get(serial)
        append(ShellLine(TEXT_BOUND_PREFIX + (device?.model ?: serial) + "（$serial）", isCommand = true))
        Logx.i(TAG, "ShellViewModel 绑定：$serial")
    }

    /** 执行一条命令。命中黑名单时先弹二次确认。 */
    fun run(cmd: String) {
        val command: String = cmd.trim()
        if (command.isEmpty()) return

        val serial: String = boundSerial
        if (serial.isBlank()) {
            append(ShellLine(TEXT_NO_DEVICE, isError = true))
            return
        }
        if (job?.isActive == true) {
            append(ShellLine(TEXT_BUSY, isError = true))
            return
        }
        if (ShellExecutor.isDangerous(command)) {
            val reason: String = ShellExecutor.dangerReason(command) ?: TEXT_DANGER_DEFAULT_REASON
            if (!dangerConfirmEnabled) {
                Logx.w(TAG, "设置已关闭危险命令确认，仍会拦截：危险命令必须逐条确认")
            }
            append(ShellLine(TEXT_DANGER_BLOCKED + reason, isError = true))
            _pendingDanger.value = command
            return
        }
        execute(command)
    }

    /** 用户在二次确认框点了「确认执行」。 */
    fun confirmDanger() {
        val command: String = _pendingDanger.value ?: return
        _pendingDanger.value = null
        execute(command, confirmed = true)
    }

    /** 用户在二次确认框点了「取消」。 */
    fun cancelDanger() {
        val command: String = _pendingDanger.value ?: return
        _pendingDanger.value = null
        append(ShellLine(TEXT_DANGER_CANCELLED, isError = true))
        Logx.i(TAG, "用户取消高危命令：$command")
    }

    /** 手动停止当前命令。 */
    fun stop() {
        if (job?.isActive != true) {
            append(ShellLine(TEXT_NOT_RUNNING))
            return
        }
        job?.cancel()
        job = null
        _running.value = false
        append(ShellLine(TEXT_STOPPED, isError = true))
        _lastResult.value = TEXT_STOPPED_RESULT
        Logx.i(TAG, "用户手动停止命令")
    }

    /** 清空输出区。 */
    fun clear() {
        _output.value = emptyList()
    }

    /** 切换超时档位：10s / 30s / 无限制。 */
    fun setTimeout(ms: Long) {
        _timeoutMs.value = if (ms <= 0L) Long.MAX_VALUE else ms
        append(ShellLine(TEXT_TIMEOUT_PREFIX + describeTimeout(_timeoutMs.value)))
    }

    /** 超时档位的可读文案。 */
    fun describeTimeout(ms: Long): String = when {
        ms == Long.MAX_VALUE -> TEXT_TIMEOUT_UNLIMITED
        ms >= 30_000L -> TEXT_TIMEOUT_30S
        else -> TEXT_TIMEOUT_10S
    }

    /**
     * 实际下发命令。
     *
     * @param confirmed 是否已完成高危命令二次确认。来自 [confirmDanger] 时必须传 true，
     *                  否则 [ShellExecutor.run] 会再次把命令拦截掉（弹了确认框却永远执行不了）。
     */
    private fun execute(command: String, confirmed: Boolean = false) {
        val serial: String = boundSerial
        if (serial.isBlank()) return

        pushHistory(command)
        append(ShellLine("$ $command", isCommand = true))
        _running.value = true
        _lastResult.value = null

        val timeout: Long = _timeoutMs.value
        val startedAt: Long = System.currentTimeMillis()

        job = viewModelScope.launch(Dispatchers.IO) {
            var lineCount: Int = 0
            try {
                executor.run(serial, command, timeout, confirmed = confirmed).collect { chunk ->
                    when (chunk) {
                        is ShellChunk.Line -> {
                            val text: String = chunk.text
                            if (text.isNotBlank()) {
                                lineCount++
                                append(ShellLine(text, isError = looksLikeError(text)))
                            }
                        }

                        is ShellChunk.Finished -> {
                            val result: ShellResult = chunk.result
                            val costMs: Long = System.currentTimeMillis() - startedAt
                            emitStderrIfMissed(result.stderr, lineCount)
                            if (result.stdout.isNotBlank() && lineCount == 0) {
                                result.stdout.lineSequence().forEach { line ->
                                    if (line.isNotBlank()) {
                                        append(ShellLine(line, isError = looksLikeError(line)))
                                    }
                                }
                            }
                            val tail: String =
                                TEXT_EXIT_PREFIX + result.exitCode + TEXT_COST_INFIX + costMs + TEXT_COST_SUFFIX
                            append(ShellLine(tail, isError = result.exitCode != 0))
                            _lastResult.value = tail
                        }
                    }
                }
            } catch (t: Throwable) {
                val costMs: Long = System.currentTimeMillis() - startedAt
                if (t is kotlinx.coroutines.CancellationException) {
                    _lastResult.value = TEXT_STOPPED_RESULT + "（${costMs}ms）"
                } else {
                    append(
                        ShellLine(
                            TEXT_EXEC_FAILED + (t.message ?: t.javaClass.simpleName),
                            isError = true
                        )
                    )
                    _lastResult.value = TEXT_EXEC_FAILED_SHORT
                }
                Logx.w(TAG, "命令执行异常：${t.message}")
            } finally {
                _running.value = false
                job = null
            }
        }
    }

    /**
     * stderr 启发式判定：
     * `ShellExecutor.run` 只提供一条流式输出，stdout 与 stderr 无法按来源区分，
     * 这里只能按常见错误前缀做启发式标记（红色显示）。这不是严格判定，仅用于可读性。
     */
    private fun looksLikeError(line: String): Boolean {
        val trimmed: String = line.trimStart()
        for (prefix in ERROR_PREFIXES) {
            if (trimmed.startsWith(prefix, ignoreCase = true)) return true
        }
        val lower: String = trimmed.lowercase()
        return lower.contains("permission denied") ||
            lower.contains("no such file or directory") ||
            lower.contains("operation not permitted") ||
            lower.contains("exception") ||
            lower.contains("fatal") ||
            lower.contains("not found") && !lower.startsWith("#")
    }

    /** Finished 里附带的 stderr：若流式期间没回显过，则补记一次，避免用户丢掉错误信息。 */
    private fun emitStderrIfMissed(stderr: String, streamedLines: Int) {
        if (stderr.isBlank()) return
        if (streamedLines > 0) return
        stderr.lineSequence()
            .filter { it.isNotBlank() }
            .take(20)
            .forEach { append(ShellLine(it, isError = true)) }
    }

    private fun pushHistory(command: String) {
        val previous: List<String> = _history.value
        if (previous.firstOrNull() == command) return
        val next: MutableList<String> = mutableListOf(command)
        previous.forEach { item ->
            if (item != command && next.size < HISTORY_LIMIT) next.add(item)
        }
        _history.value = next.take(HISTORY_LIMIT)
    }

    /**
     * 追加一行输出。必须同步执行：输出顺序是有语义的，异步追加会串序。
     * 超过 [MAX_OUTPUT_LINES] 时丢弃最旧的行（起点裁剪一次性完成，不做逐条 removeAt）。
     */
    @Synchronized
    private fun append(line: ShellLine) {
        val current: List<ShellLine> = _output.value
        val next: MutableList<ShellLine> = ArrayList(current.size + 1)
        next.addAll(current)
        next.add(line)
        _output.value = if (next.size > MAX_OUTPUT_LINES) {
            next.drop(next.size - MAX_OUTPUT_LINES)
        } else {
            next
        }
    }

    /** 顶部副标题用到的设备名（优先型号，其次产品名，最后回退序列号）。 */
    fun deviceLabel(): String {
        val serial: String = boundSerial
        if (serial.isBlank()) return ""
        val device = repo.get(serial)
        return device?.model ?: device?.product ?: serial
    }

    /** 可选：把当前「$ 命令」行之外的 CLRF 处理统一，导出用途保留给 UI。 */
    fun exportText(): String = _output.value.joinToString(separator = "\n") { it.text }

    override fun onCleared() {
        super.onCleared()
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG: String = "ShellViewModel"
        private const val MAX_OUTPUT_LINES: Int = 5000
        private const val HISTORY_LIMIT: Int = 50
        private const val DEFAULT_TIMEOUT_MS: Long = 10_000L

        private val ERROR_PREFIXES: List<String> = listOf(
            "rm:",
            "grep:",
            "cat:",
            "ls:",
            "cp:",
            "mv:",
            "mkdir:",
            "chmod:",
            "kill:",
            "pm:",
            "am:",
            "cmd:",
            "Permission denied",
            "error",
            "Error",
            "Exception",
            "Usage:",
            "usage:"
        )

        private const val TEXT_WELCOME_1: String = "已就绪：命令由 adb -s <serial> shell 下发，不做伪终端，交互型命令（top -i / su 交互）不支持。"
        private const val TEXT_WELCOME_2: String = "提示：单次执行默认超时 10 秒，可在右上角菜单切换 10s / 30s / 无限制。"
        private const val TEXT_BOUND_PREFIX: String = "# 已连接 "
        private const val TEXT_NO_DEVICE: String = "尚未绑定设备，无法执行命令"
        private const val TEXT_BUSY: String = "已有命令正在执行，请先停止或等待其结束"
        private const val TEXT_DANGER_BLOCKED: String = "高危命令已拦截："
        private const val TEXT_DANGER_DEFAULT_REASON: String = "该命令可能造成不可逆影响"
        private const val TEXT_DANGER_CANCELLED: String = "已取消，命令未执行"
        private const val TEXT_STOPPED: String = "已手动停止命令"
        private const val TEXT_STOPPED_RESULT: String = "命令已停止"
        private const val TEXT_NOT_RUNNING: String = "当前没有正在执行的命令"
        private const val TEXT_EXEC_FAILED: String = "执行失败："
        private const val TEXT_EXEC_FAILED_SHORT: String = "执行失败"
        private const val TEXT_EXIT_PREFIX: String = "退出码 "
        private const val TEXT_COST_INFIX: String = " · 耗时 "
        private const val TEXT_COST_SUFFIX: String = "ms"
        private const val TEXT_TIMEOUT_PREFIX: String = "# 超时档位已切换为 "
        private const val TEXT_TIMEOUT_10S: String = "10 秒"
        private const val TEXT_TIMEOUT_30S: String = "30 秒"
        private const val TEXT_TIMEOUT_UNLIMITED: String = "无限制"
    }
}
