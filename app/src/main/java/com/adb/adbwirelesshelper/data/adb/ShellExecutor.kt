package com.adb.adbwirelesshelper.data.adb

import com.adb.adbwirelesshelper.domain.model.ShellChunk
import com.adb.adbwirelesshelper.domain.model.ShellResult
import com.adb.adbwirelesshelper.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 自定义 shell 执行器（需求 F1–F6）。
 *
 * - 单条命令默认超时 10 秒，超时强杀；
 * - 流式输出通过 [Flow]<[ShellChunk]> 增量下发；
 * - 不做伪终端分配（交互式命令如 `top -i`、`su` 交互不支持）；
 * - 危险命令按 **token** 判定（禁止字符串包含匹配，否则易被绕过）；
 * - 同一时刻仅允许 1 条命令运行（[Mutex]）。
 *
 * ★ 本页命令的**执行位置**：`adb -s <serial> shell <command>` 已经包了一层，
 *   所以用户输入的内容是在**被控端的 shell** 里跑的。用户按 adb 的习惯写下
 *   `adb devices` 时，实际执行的是 `adb -s <serial> shell adb devices`，
 *   被控端 `/system/bin/sh` 找不到 `adb` → 退出码 127。
 *   因此执行前一律先过 [normalizeShellCommand] 归一化：
 *   - 剥掉 `adb` / `adb shell` 前缀，以及 adb 的目标选择参数
 *     （`-s <serial>` / `-t <id>` / `-d` / `-e` —— 目标由本页固定，用户切不了）；
 *   - 拦下只能在主机端跑的 adb 子命令（`devices` / `push` / `install` …）。
 */
class ShellExecutor(private val adb: AdbClient) {

    /** 保证同一时刻只有一条命令在跑 */
    private val mutex = Mutex()

    /**
     * 流式执行一条命令。
     *
     * 每产生一行就下发 [ShellChunk.Line]，结束时下发 [ShellChunk.Finished]。
     *
     * @param confirmed 高危命令的二次确认结果。**false 时命中 [DANGEROUS] 的命令会被拦截**，
     *                  只下发一行警告 + 退出码 -1 的 Finished，不会真正执行；
     *                  置 true 表示 UI 已弹出确认框且用户确认，此时会先下发一行警告再执行。
     *                  （需求 F5：命中 100% 弹确认，禁止批量绕过 —— 因此默认 false。）
     */
    fun run(
        serial: String,
        cmd: String,
        timeoutMs: Long = 10_000,
        confirmed: Boolean = false
    ): Flow<ShellChunk> = channelFlow {
        if (cmd.isBlank()) {
            send(ShellChunk.Line("（空命令，已忽略）"))
            send(ShellChunk.Finished(ShellResult(-1, "", "命令为空")))
            return@channelFlow
        }

        // ★ 归一化必须在危险判定**之前**：
        //   否则 `adb shell rm -rf /` 这类写法可能绕过 token 级危险判定 ——
        //   那是一刀开在安全底线上，不能留。
        // 传 serial 进去：用户写的 `-s <serial>` 到底是不是本页正在操作的那台，要在这里比
        val plan: ShellCommandPlan = normalizeShellCommand(cmd, serial)
        val command: String = plan.command

        // 主机端子命令 / 只写了前缀：不执行，只给提示。
        // ★ 必须排在「已去掉前缀 / 已忽略 -s」那两条**之前**：
        //   否则 `adb -s 9.9.9.9:5555 devices` 会先说「已改为在当前设备上执行后面的命令」、
        //   紧接着又说「本页跑不了，已停止执行」—— 两句自相矛盾。
        if (plan.hostOnlyHint != null) {
            send(ShellChunk.Line(plan.hostOnlyHint))
            send(ShellChunk.Finished(ShellResult(-1, "", plan.hostOnlyHint)))
            return@channelFlow
        }

        // 走到这里才说明**真的要执行**，此时才发「改了什么」的说明
        if (plan.ignoredTarget != null) {
            // 目标选择参数被忽略（用户想切到另一台设备 / 用了 -t）→ 单独说清楚
            send(
                ShellChunk.Line(
                    "ℹ 已忽略 ${plan.ignoredTarget}：" +
                        "命令行页固定对当前已连接的设备执行，无法切换目标；" +
                        "已改为在当前设备上执行后面的命令"
                )
            )
        } else if (plan.strippedPrefix != null) {
            // 剥掉前缀时给一行说明，让用户知道发生了什么（也顺带教会正确写法）
            send(
                ShellChunk.Line(
                    "ℹ 已自动去掉「${plan.strippedPrefix}」前缀：" +
                        "本页命令直接在被控端 shell 中执行，无需再写 adb"
                )
            )
        }

        if (command.isBlank()) {
            send(ShellChunk.Line("（空命令，已忽略）"))
            send(ShellChunk.Finished(ShellResult(-1, "", "命令为空")))
            return@channelFlow
        }

        val hit = dangerReason(command)
        if (hit != null && !confirmed) {
            val warning = "⚠ 危险命令已被拦截（命中：${hit}）。如确需执行，请确认后重试。"
            send(ShellChunk.Line(warning))
            send(ShellChunk.Finished(ShellResult(-1, "", warning)))
            return@channelFlow
        }
        if (hit != null) {
            send(ShellChunk.Line("⚠ 已确认执行高危命令（命中：${hit}）：${command}"))
        }

        val bridge = Channel<String>(Channel.UNLIMITED)
        // 逐行转发：adb 回调在 IO 线程，这里用 Channel 桥接，避免跨协程 emit
        val forwarder = launch {
            for (line in bridge) {
                send(ShellChunk.Line(line))
            }
        }

        val result: Result<ShellResult> = mutex.withLock {
            withContext(Dispatchers.IO) {
                adb.shellStream(serial, command, timeoutMs) { line -> bridge.trySend(line) }
            }
        }
        bridge.close()
        forwarder.join()

        val shellResult = result.getOrElse { error ->
            ShellResult(-1, "", error.message.orEmpty())
        }
        if (shellResult.exitCode == 124) {
            send(ShellChunk.Line("⏱ 命令未在 ${timeoutMs}ms 内返回，已终止"))
        }
        if (shellResult.exitCode == 127) {
            // 127 = 被控端 shell 里根本没有这个命令。最常见的原因就是手写了 adb 前缀，
            // 其次是被控端没这个 toybox/busybox 命令，把两种可能一起说清楚。
            send(
                ShellChunk.Line(
                    "ℹ 退出码 127：被控端 shell 中找不到该命令。" +
                        "请确认命令名拼写，或该命令在被控端系统上是否存在；" +
                        "adb / adb shell 前缀现在会自动去掉，不需要手写。"
                )
            )
        }
        send(ShellChunk.Finished(shellResult))
    }

    /**
     * 一次性执行并返回结果（不流式）。危险命令语义同 [run]。
     *
     * 与 [run] 一样会先做 [normalizeShellCommand] 归一化（同样在危险判定之前）。
     * 差异：本函数只返回一个 [ShellResult]，**发不出提示行**，所以「已剥掉前缀」
     * 这类说明只写进 Logcat；而 [ShellCommandPlan.hostOnlyHint] 会放进
     * [ShellResult.stderr] 并返回退出码 -1 —— 调用方（设备信息采集 / 文件管理）
     * 取的是 stdout，不会被这行说明污染。
     */
    suspend fun runOnce(
        serial: String,
        cmd: String,
        timeoutMs: Long = 10_000,
        confirmed: Boolean = false
    ): ShellResult =
        withContext(Dispatchers.IO) {
            if (cmd.isBlank()) {
                return@withContext ShellResult(-1, "", "命令为空")
            }
            val plan: ShellCommandPlan = normalizeShellCommand(cmd, serial)
            if (plan.ignoredTarget != null) {
                Logx.d(TAG, "runOnce 已忽略目标参数 ${plan.ignoredTarget}：$cmd -> ${plan.command}")
            } else if (plan.strippedPrefix != null) {
                Logx.d(TAG, "runOnce 已剥掉前缀「${plan.strippedPrefix}」：$cmd -> ${plan.command}")
            }
            if (plan.hostOnlyHint != null) {
                return@withContext ShellResult(-1, "", plan.hostOnlyHint)
            }
            val command: String = plan.command
            if (command.isBlank()) {
                return@withContext ShellResult(-1, "", "命令为空")
            }
            val hit = dangerReason(command)
            if (hit != null && !confirmed) {
                return@withContext ShellResult(-1, "", "危险命令已被拦截（命中：${hit}）")
            }
            mutex.withLock {
                adb.shell(serial, command, timeoutMs).getOrElse { error ->
                    ShellResult(-1, "", error.message.orEmpty())
                }
            }
        }

    /**
     * 批量顺序执行多条命令。
     *
     * ★ **故意不做 [normalizeShellCommand] 归一化**：本函数的输入全部是 App 自己拼的
     *   采集命令（`DeviceInfoCollector` 用，形如 `getprop xxx` / `dumpsys yyy`），
     *   永远不会带 `adb` 前缀，归一化是纯浪费；更重要的是，一旦将来有人在采集命令里
     *   用了 `devices` / `install` 这类词当首个 token，归一化会把它们误判成
     *   「主机端子命令」而拒绝执行 —— 那是给内部链路埋雷。用户输入只走 [run] / [runOnce]。
     *
     * @return 命令 → 输出(stdout 优先，无输出时取 stderr) 的映射；顺序与输入一致。
     *         危险命令对应值的开头会带拦截说明。
     */
    suspend fun batch(
        serial: String,
        commands: List<String>,
        confirmed: Boolean = false
    ): Map<String, String> =
        withContext(Dispatchers.IO) {
            val result = LinkedHashMap<String, String>(commands.size)
            mutex.withLock {
                for (cmd in commands) {
                    val hit = dangerReason(cmd)
                    if (hit != null && !confirmed) {
                        result[cmd] = "⚠ 已拦截（命中危险指令：$hit）"
                        continue
                    }
                    val shellResult = adb.shell(serial, cmd, DEFAULT_BATCH_TIMEOUT_MS)
                        .getOrElse { error -> ShellResult(-1, "", error.message.orEmpty()) }
                    result[cmd] = if (shellResult.stdout.isNotBlank()) {
                        shellResult.stdout
                    } else {
                        shellResult.stderr
                    }
                    Logx.d(TAG, "batch 完成: $cmd -> exit=${shellResult.exitCode}")
                }
            }
            result
        }

    /** 把 ShellChunk 流收敛成一次结果（供不需要流式的调用方使用） */
    suspend fun collect(
        serial: String,
        cmd: String,
        timeoutMs: Long = 10_000,
        confirmed: Boolean = false
    ): ShellResult {
        var last: ShellResult = ShellResult(-1, "", "")
        run(serial, cmd, timeoutMs, confirmed).collect { chunk ->
            if (chunk is ShellChunk.Finished) {
                last = chunk.result
            }
        }
        return last
    }

    companion object {
        private const val TAG = "ShellExecutor"
        private const val DEFAULT_BATCH_TIMEOUT_MS = 10_000L

        /**
         * 高危指令词表（需求 F5）。
         *
         * 判定方式：把命令按 shell 元字符切分成 token，逐个比较；
         * **不是** `cmd.contains("rm")` —— 那样 `rmdir` / `format_disk_report` 之类会被误判，
         * 而 `  rm` / `$(rm)` 之类又可能漏判。
         */
        val DANGEROUS: Set<String> = setOf(
            "reboot", "rm", "su", "wipe", "dd", "format", "mkfs", "poweroff", "magisk"
        )

        /** 额外的高危「双词组合」，例如 `pm uninstall`、`am force-stop` */
        private val DANGEROUS_PAIRS: Set<String> = setOf(
            "pm uninstall",
            "pm uninstall -k",
            "am force-stop",
            "reboot -p",
            "rm -rf"
        )

        /** shell 元字符：用于把整条命令切成 token */
        private val SPLIT_REGEX = Regex("[\\s;|&`$()<>\"'\\\\]+")

        /** 按 token 判定是否危险 */
        fun isDangerous(cmd: String): Boolean = dangerReason(cmd) != null

        /**
         * 返回命中的危险 token，未命中返回 null。
         *
         * 例：`rm -rf /sdcard` → "rm"；`echo rm` → "rm"（保守起见仍然拦截）；
         *     `rmdir foo` → null（token 是 rmdir，不等于 rm）。
         */
        fun dangerReason(cmd: String): String? {
            if (cmd.isBlank()) {
                return null
            }
            val lower = cmd.lowercase(Locale.ROOT)
            for (pair in DANGEROUS_PAIRS) {
                if (lower.contains(pair)) {
                    return pair
                }
            }
            val tokens = lower.split(SPLIT_REGEX).filter { it.isNotEmpty() }
            for (token in tokens) {
                // 去掉可能的前导路径，只取最后一段：/system/bin/rm → rm
                val name = token.substringAfterLast('/')
                if (name in DANGEROUS) {
                    return name
                }
            }
            return null
        }
    }
}

// ---------------------------------------------------------------- 命令归一化
//
// 命令行页输入的任何内容，最终都是 `adb -s <serial> shell <command>` 里那个 <command>，
// 也就是**在被控端的 shell 里执行**。用户按 adb 的习惯写 `adb devices`，实际跑的是
// `adb -s <serial> shell adb devices` → 被控端 sh 找不到 adb → 退出码 127。
//
// 用户这么写很自然（他就是照着 adb 的思维在写），所以要在代码里吸收掉前缀，
// 而不是让用户去改习惯。

/**
 * 归一化结果。
 *
 * @property command      归一化后真正要执行的命令；**为空表示不要执行**
 * @property strippedPrefix 被剥掉的前缀原文（`adb` / `adb shell` / `shell`）；没剥为 null
 * @property hostOnlyHint 非 null = 这条命令只能在 adb **主机端**跑，本页执行不了，
 *                        里面是一句可直接展示给用户（以及写进文档）的说明
 */
internal data class ShellCommandPlan(
    val command: String,
    val strippedPrefix: String?,
    val hostOnlyHint: String?,
    /**
     * 被忽略的**目标选择参数原文**（含 flag，如 `-s 192.168.1.9:5555` / `-t 3`）。
     *
     * 只有「本页兑现不了这个目标」时才非 null：
     * - `-s` 的参数不等于当前 serial → 用户想切到另一台设备，本页做不到；
     * - `-t` 给的是 adb transport id，本页根本没有这层概念 → 一律算被忽略。
     *
     * 为 null（`-s` 参数正好就是当前 serial，或压根没写目标参数）时，
     * 走普通的「已剥掉前缀」提示即可，不必额外强调。
     */
    val ignoredTarget: String? = null,
)

/**
 * 剥前缀的循环上限。
 *
 * 上限从 4 提到 **6** 是为了容纳带目标选择参数的写法：
 * `adb -s <serial> shell cmd` 需要 3 步（adb / `-s <serial>` / shell），
 * `adb -s <serial> -d shell cmd` 这类叠法要 4 步，再留 2 步余量。
 * 同时也是防病态输入（`adb adb adb …`）不至于把时间耗光。
 */
private const val MAX_SHELL_PREFIX_STRIP: Int = 6

/** 可被剥掉的引导 token。必须 **token 完全相等**，不能用 startsWith。 */
private const val TOKEN_ADB: String = "adb"
private const val TOKEN_SHELL: String = "shell"

/** adb 的目标选择 flag：`-s <serial>` / `-t <transportId>` 带参数，`-d` / `-e` 不带。 */
private const val FLAG_SERIAL: String = "-s"
private const val FLAG_TRANSPORT: String = "-t"
private const val FLAG_DEVICE: String = "-d"
private const val FLAG_EMULATOR: String = "-e"

/**
 * 只能在 **adb 主机端**跑的子命令：它们走的是 adb 自己的协议，不是设备上的可执行文件，
 * 塞进 `adb shell` 里必然 127。
 *
 * ⚠️ 这张表**不能**放进 `reboot` / `bugreport` / `logcat` / `getprop` / `dumpsys` /
 *    `pm` / `am` / `settings` / `input`，也**不能**放进 `sync`：
 *    它们是被控端 `/system/bin` 下**真实存在**的可执行文件，
 *    `adb shell sync` / `adb shell bugreport` 现在是能正常跑的，
 *    加进来会把本来可用的命令拦死。
 *    `adb logcat` 归一化成 `logcat` 后完全可用 —— 这正是本次修复要保住的能力。
 */
private val HOST_ONLY_ADB_SUBCOMMANDS: Set<String> = setOf(
    // —— 设备/连接管理 ——
    "devices",
    "connect",
    "disconnect",
    "pair",
    // —— 端口转发 ——
    "forward",
    "reverse",
    // —— 文件与应用的主机端通道 ——
    "push",
    "pull",
    "install",
    "uninstall",
    "sideload",
    // —— 提权 / 分区 ——
    "root",
    "remount",
    // —— 连接模式切换 ——
    "tcpip",
    "usb",
    // —— 主机端进程与元信息（★ QA 实测仍有 127 的那一批）——
    "exec-out",
    "exec-in",
    "version",
    "get-serialno",
    "wait-for-device",
    "backup",
    "restore",
    "emu",
    "kill-server",
    "start-server",
)

/**
 * 主机端子命令的替代做法。措辞会同步进 README 的「命令行页」章节，所以写成完整句子。
 *
 * 只写本 App 确实具备的能力（文件管理页 / 首页已知设备 / 首页手动添加 / 配对引导 /
 * 设备端 `pm` 命令），不编造没有的入口。
 *
 * ⚠️ **不要求**每个子命令都有映射：查不到时 `HOST_ONLY_ALTERNATIVES[head] ?: "本页不支持该操作"`
 *    会兜底成一句安全的通用说明。与其编一个不存在的入口，不如老实说「本页不支持」。
 */
private val HOST_ONLY_ALTERNATIVES: Map<String, String> = mapOf(
    "devices" to "查看设备请回到首页的「已知设备」分组",
    "connect" to "连接设备请在首页点选候选设备，或用「手动添加 IP:端口」",
    "disconnect" to "断开请在首页「已知设备」分组里点该设备的「断开」",
    "pair" to "配对请在首页点未配对的设备，按引导输入被控端显示的 6 位配对码",
    "push" to "传文件请用「文件管理」页",
    "pull" to "取文件请用「文件管理」页",
    "install" to "安装应用请先用「文件管理」页把 apk 传到设备上，再在本页执行 pm install <apk 路径>",
    "uninstall" to "卸载应用可在本页执行 pm uninstall <包名>（属高危命令，会弹二次确认）",
    "root" to "本页不提供提权；请先确认被控端已 root，再直接执行需要的命令",
    "remount" to "本页不支持重新挂载分区",
    "tcpip" to "切换无线调试请在被控端「开发者选项 → 无线调试」里操作",
    "usb" to "切回 USB 调试请在被控端「开发者选项」里关闭无线调试",
    "sideload" to "卡刷请在被控端进入 recovery 后用官方工具操作，本页不支持",
    "forward" to "端口转发由本 App 的投屏 / 文件能力自动完成，无需手动设置",
    "reverse" to "反向端口转发本页不支持",
    "exec-out" to "取原始输出可在本页跑对应的设备端命令（如 screencap -p > /sdcard/a.png），再用「文件管理」页取回",
    // ★ 下面这几条是「本页确实有等价做法」的，别让它们掉进通用兜底：
    //   兜底文案只有一句「本页不支持该操作」，而这些其实做得到。
    "get-serialno" to "查看序列号可在本页执行 getprop ro.serialno",
    "wait-for-device" to "本页只对已连接的设备开放，设备在线时这里才可用，无需另等",
    "kill-server" to "本 App 自行管理 adb 连接，无需手动停止",
    "start-server" to "本 App 自行管理 adb 连接，无需手动启动",
    //
    // 剩下 exec-in / version / backup / restore / emu 确实没有等价入口，
    // 走 `?: "本页不支持该操作"` 兜底 —— 与其编一个不存在的入口，不如老实说不支持。
)

/** 取第一个空白分隔的 token（没有空白则整个串就是一个 token）。 */
private fun firstToken(text: String): String {
    val idx: Int = text.indexOfFirst { it.isWhitespace() }
    return if (idx < 0) text else text.substring(0, idx)
}

/** 去掉第一个 token 及其后的连续空白，返回剩余部分（已 trim）。 */
private fun dropFirstToken(text: String): String {
    val idx: Int = text.indexOfFirst { it.isWhitespace() }
    if (idx < 0) {
        return ""
    }
    var end: Int = idx
    while (end < text.length && text[end].isWhitespace()) {
        end++
    }
    return text.substring(end).trim()
}

/**
 * 归一化用户在命令行页输入的一条命令。
 *
 * 规则：
 * 1. 先 trim；空白 → `command = ""`（调用方已有空命令分支，行为不变）。
 * 2. 最多循环 [MAX_SHELL_PREFIX_STRIP] 次**从头部**剥引导 token，逐个 token **完全相等**才剥
 *    （大小写敏感）：
 *    - `adb` / `shell` → 剥这个 token；
 *    - `-s` / `-t` → 剥这个 token **连同它后面那一个参数**；
 *    - `-d` / `-e` → 剥这个 token（不带参数）；
 *    后两者**只在 `shell` 之前**才认（adb 的目标 flag 本来就只能写在子命令前）。
 *    ★ 两道保险确保不会吃掉被控端命令自己的 `-s` / `-d`：
 *      ① 一旦头部 token 不认识就**立刻停手** —— `ls -s 100` 第一轮就在 `ls` 上 break；
 *      ② `shell` 已被剥掉之后不再认任何目标 flag —— `adb shell -s 100` 的 `-s` 留给设备。
 * 3. 剥完为空 → `command = ""` + [ShellCommandPlan.hostOnlyHint] 说明「只写了前缀」。
 * 4. 剩余命令的首个 token 命中 [HOST_ONLY_ADB_SUBCOMMANDS] → 不执行，
 *    [ShellCommandPlan.command] 留空 + hostOnlyHint 给出原因与替代做法。
 * 5. 其余情况（如 `adb shell getprop ro.product.model` → `getprop ro.product.model`）
 *    → 返回归一化后的命令，正常执行。
 *
 * @param raw           用户原始输入
 * @param currentSerial 命令行页当前正在操作的设备 serial（用于判断 `-s` 参数是否是同一台）
 */
internal fun normalizeShellCommand(raw: String, currentSerial: String = ""): ShellCommandPlan {
    var rest: String = raw.trim()
    if (rest.isEmpty()) {
        return ShellCommandPlan(command = "", strippedPrefix = null, hostOnlyHint = null)
    }

    // 2. 剥引导前缀（含 adb 的目标选择参数）
    val stripped: ArrayList<String> = ArrayList()
    var ignored: String? = null
    // ★ 用独立的布尔量记录「`shell` 子命令是否已被剥掉」，不要用
    //   `stripped.contains("shell")` 去反推：`-s` 的实参万一恰好是字符串 `shell`
    //   （`adb -s shell -d ls`），后者会把实参误当成子命令，判定就错了。
    var shellSeen: Boolean = false
    var guard: Int = 0
    while (guard < MAX_SHELL_PREFIX_STRIP) {
        guard++
        val token: String = firstToken(rest)
        // ★ adb 的目标选择 flag 只在 `shell` 子命令**之前**合法。
        //   一旦 `shell` 已被剥掉，后面全是发给被控端的命令，
        //   里面的 `-s` / `-d` 之类是被控端命令自己的参数，一律不许碰。
        val beforeShell: Boolean = !shellSeen
        when {
            // 不带参数的引导 token
            token == TOKEN_ADB || token == TOKEN_SHELL ||
                (beforeShell && (token == FLAG_DEVICE || token == FLAG_EMULATOR)) -> {
                if (token == TOKEN_SHELL) {
                    shellSeen = true
                }
                stripped.add(token)
                rest = dropFirstToken(rest)
                continue
            }

            // 带参数的引导 token：连参数一起剥（同样只在 shell 之前）
            //
            // ⚠️ 这里**无条件**把后面那一个 token 当实参吃掉，不校验它长得像不像 serial。
            //    于是 `adb -s -weird shell ls` 会把 `-weird` 当 `-s` 的实参吃掉，
            //    剩下 `shell ls` → 再剥 shell → `ls` 正常执行，**不会** 127。
            //    反过来若去校验「实参不能以 `-` 开头」而拒绝剥，`-s -weird shell ls`
            //    会整串下发到被控端，那才真的 127。
            //    （`-weird` 本身不是合法 serial，所以「吃掉它」是更可取的行为；
            //     不要照「反正都会 127」的错误理由把这里改成拒绝剥。）
            beforeShell && (token == FLAG_SERIAL || token == FLAG_TRANSPORT) -> {
                val afterFlag: String = dropFirstToken(rest)
                val argument: String = firstToken(afterFlag)
                stripped.add(token)
                if (argument.isNotEmpty()) {
                    stripped.add(argument)
                }
                rest = dropFirstToken(afterFlag)
                if (argument.isNotEmpty()) {
                    // -s 的参数是 serial/host，可以和当前设备比；
                    // -t 给的是 adb transport id，本页没有这层概念，一律算被忽略。
                    val sameTarget: Boolean = token == FLAG_SERIAL &&
                        argument.equals(currentSerial.trim(), ignoreCase = true)
                    if (!sameTarget) {
                        ignored = "$token $argument"
                    }
                }
                continue
            }

            // 头部不认识 → 停手（保证不会吃掉被控端命令自己的 -s 之类参数）
            else -> break
        }
    }
    val strippedPrefix: String? = if (stripped.isEmpty()) null else stripped.joinToString(" ")

    // 3. 只剩前缀，没有实际命令
    if (rest.isEmpty()) {
        return ShellCommandPlan(
            command = "",
            strippedPrefix = strippedPrefix,
            hostOnlyHint = "只输入了「${strippedPrefix}」前缀，后面没有要执行的命令。" +
                "本页命令直接在被控端 shell 中执行，无需再写 adb；" +
                "请直接输入命令本身，例如 getprop ro.product.model",
            ignoredTarget = ignored,
        )
    }

    // 4. 只能在主机端跑的 adb 子命令
    val head: String = firstToken(rest)
    if (head in HOST_ONLY_ADB_SUBCOMMANDS) {
        val alternative: String = HOST_ONLY_ALTERNATIVES[head] ?: "本页不支持该操作"
        return ShellCommandPlan(
            command = "",
            strippedPrefix = strippedPrefix,
            hostOnlyHint = "${head} 是 adb 主机端的子命令（走的是 adb 自身协议，不是被控端的可执行文件），" +
                "本页跑不了，已停止执行。替代做法：${alternative}。",
            ignoredTarget = ignored,
        )
    }

    // 5. 正常命令
    return ShellCommandPlan(
        command = rest,
        strippedPrefix = strippedPrefix,
        hostOnlyHint = null,
        ignoredTarget = ignored,
    )
}
