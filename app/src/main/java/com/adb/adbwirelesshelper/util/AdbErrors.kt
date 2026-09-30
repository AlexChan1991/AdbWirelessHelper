package com.adb.adbwirelesshelper.util

import java.util.Locale

/**
 * 错误场景。
 *
 * - [GENERAL]：默认场景，错误发生在「主控端 ↔ 被控端」这条**真实网络链路**上，
 *   例如 `adb connect <ip:port>`、设备发现 / 端口扫描。此时「同网段 / AP 隔离」是有效排查方向。
 * - [LOOPBACK]：错误发生在「App → 本机 127.0.0.1:<adb forward 端口>」这条**回环链路**上，
 *   例如 scrcpy 连 forward 端口超时。127.0.0.1 是本机回环，跟两台设备是否同网段、
 *   路由器有没有开 AP 隔离**毫无关系**；若沿用 GENERAL 规则，会把「本机端口连不上」
 *   误报成「网络不通 + AP 隔离」，用户照着去折腾路由器纯属白费功夫。
 *
 * 所有入口函数的该参数都**带默认值 [GENERAL]**，既有调用点无需改动即保持向后兼容。
 */
enum class ErrorContext {
    GENERAL,
    LOOPBACK
}

/**
 * adb / scrcpy 原始输出 → 中文人话的映射（需求 A5）。
 *
 * 判定统一使用「小写包含」，因为 adb 各版本措辞不一致（如 failed to authenticate /
 * Failed to authenticate / authentication failed）。
 */
object AdbErrors {

    /** 映射规则：关键词 → 中文文案 + 排查建议 */
    private data class Rule(
        val keys: List<String>,
        val text: String,
        val advice: String
    )

    /**
     * 通用规则（远端链路 / 未知场景）。
     *
     * ⚠️ 第 3 条「网络不通 / AP 隔离」只在 [ErrorContext.GENERAL]（连远端 IP）下才有意义；
     * [ErrorContext.LOOPBACK] 会先走 [humanizeLoopback] 拦截 timeout/refused，
     * 不会落到这条规则上。
     */
    private val RULES: List<Rule> = listOf(
        Rule(
            listOf("failed to authenticate", "authentication failed", "wrong code", "bad code"),
            "配对失败：配对码错误或已过期",
            "在被控端重新打开「无线调试 → 使用配对码配对设备」，拿到新码后 30 秒内输入"
        ),
        Rule(
            listOf("connection refused", "refused"),
            "端口不可达",
            "目标设备可能已关闭无线调试或端口已变化，请返回列表重新发现"
        ),
        Rule(
            listOf("no route to host", "network is unreachable", "timed out", "timeout", "unreachable"),
            "网络不通，请确认同网段并关闭 AP 隔离",
            "确认两台设备连同一个 Wi-Fi、勿用访客网络、关闭路由器的 AP 隔离"
        ),
        Rule(
            listOf("unauthorized", "device unauthorized"),
            "被控端未授权",
            "请查看被控端是否弹出「允许无线调试？」对话框并点击允许"
        ),
        Rule(
            listOf("cannot exec", "permission denied", "no such file or directory"),
            "内置 adb 不可用",
            "请阅读 README「二进制资源」章节放入正确的 libadb.so，或在设置页手动导入 adb"
        ),
        Rule(
            listOf("device offline", "offline"),
            "设备离线",
            "被控端可能已断网或重启，请重新发现后连接"
        ),
        Rule(
            listOf("more than one device"),
            "检测到多台设备，内部应已用 -s 指定",
            "这是程序缺陷，请把日志导出后反馈"
        ),
        Rule(
            listOf("protocol fault", "closed"),
            "连接被对端关闭",
            "被控端可能杀掉了调试会话，请重新连接"
        )
    )

    /** 回环场景下「超时」的建议操作 */
    private const val LOOPBACK_TIMEOUT_ADVICE: String =
        "返回设备列表重新连接一次；若反复出现，在命令行页执行 adb devices -l 确认设备仍在线"

    /** 回环场景下「连接被拒」的建议操作 */
    private const val LOOPBACK_REFUSED_ADVICE: String =
        "在命令行页执行 adb forward --list 确认转发规则还在；再返回设备列表重新连接一次"

    /** 回环场景未命中任何特征时的兜底建议 */
    private const val LOOPBACK_DEFAULT_ADVICE: String =
        "返回设备列表重新连接一次；若反复出现，在命令行页执行 adb devices -l 与 adb forward --list 核对"

    /** 通用场景未命中任何规则时的兜底建议 */
    private const val GENERAL_DEFAULT_ADVICE: String =
        "请确认两台设备处于同一 Wi-Fi 网段，且被控端已开启「无线调试」"

    /**
     * 回环场景专用文案（[ErrorContext.LOOPBACK] 先于通用规则匹配）。
     *
     * 这里刻意把「超时」和「拒绝」分开 —— 两者根因完全不同：
     * - `timed out` / `timeout`：端口在监听但**没有被 accept/转发**
     *   （adb server 到设备的链路卡住，或 adb server 进程被 Android 冻结）；
     * - `connection refused`：本机端口**根本没人监听**（adb forward 的本地监听没建起来）。
     *
     * @param lower 已转小写且 trim 过的原始错误文本
     * @param port  本机 forward 端口；<=0 时文案里不写具体端口号
     * @return 命中的中文文案；未命中返回 null（交给通用规则继续匹配）
     */
    private fun humanizeLoopback(lower: String, port: Int): String? {
        val portText: String = if (port > 0) "本机端口 $port" else "本机转发端口"
        return when {
            lower.contains("timed out") || lower.contains("timeout") ->
                "adb 隧道未打通：$portText 连接超时（adb 服务可能已被系统回收或转发卡住）"

            lower.contains("connection refused") || lower.contains("refused") ->
                "adb 隧道未打通：$portText 没有人监听（adb forward 监听没建起来）"

            else -> null
        }
    }

    /** [humanizeLoopback] 的配套建议；未命中返回 null。 */
    private fun adviceLoopback(lower: String): String? = when {
        lower.contains("timed out") || lower.contains("timeout") -> LOOPBACK_TIMEOUT_ADVICE
        lower.contains("connection refused") || lower.contains("refused") -> LOOPBACK_REFUSED_ADVICE
        else -> null
    }

    /**
     * 把 adb 原始输出翻译成一句中文人话。
     * 未命中任何规则时，返回裁剪后的原文（避免把一大段堆栈直接甩给用户）。
     *
     * @param raw     adb / socket 的原始错误文本
     * @param context 错误场景；连本机 forward 端口（127.0.0.1）必须传 [ErrorContext.LOOPBACK]，
     *                否则「timeout」会被误译成「网络不通 / AP 隔离」
     * @param port   相关端口号（>0 时写进文案），仅用于让提示更具体
     */
    fun humanizeAdbError(
        raw: String,
        context: ErrorContext = ErrorContext.GENERAL,
        port: Int = 0
    ): String {
        val text = raw.trim()
        if (text.isEmpty()) {
            return "未知错误（adb 没有返回任何信息）"
        }
        val lower = text.lowercase(Locale.ROOT)
        if (context == ErrorContext.LOOPBACK) {
            humanizeLoopback(lower, port)?.let { return it }
        }
        for (rule in RULES) {
            if (rule.keys.any { lower.contains(it) }) {
                return rule.text
            }
        }
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: text
        return if (firstLine.length > 120) firstLine.substring(0, 120) + "…" else firstLine
    }

    /**
     * 与 [humanizeAdbError] 配套的「下一步建议」，未命中时给出对应场景的通用建议。
     *
     * @param raw     adb / socket 的原始错误文本
     * @param context 错误场景，语义同 [humanizeAdbError]
     * @param port    相关端口号（>0 时写进文案）
     */
    fun advice(
        raw: String,
        context: ErrorContext = ErrorContext.GENERAL,
        port: Int = 0
    ): String {
        val lower = raw.lowercase(Locale.ROOT)
        if (context == ErrorContext.LOOPBACK) {
            adviceLoopback(lower)?.let { return it }
        }
        for (rule in RULES) {
            if (rule.keys.any { lower.contains(it) }) {
                return rule.advice
            }
        }
        return when (context) {
            ErrorContext.LOOPBACK ->
                if (port > 0) "本机端口 $port：$LOOPBACK_DEFAULT_ADVICE" else LOOPBACK_DEFAULT_ADVICE

            ErrorContext.GENERAL -> GENERAL_DEFAULT_ADVICE
        }
    }

    /** 一次性拿到「文案 + 建议」 */
    fun explain(
        raw: String,
        context: ErrorContext = ErrorContext.GENERAL,
        port: Int = 0
    ): Pair<String, String> = humanizeAdbError(raw, context, port) to advice(raw, context, port)
}

/**
 * 顶层便捷函数（全局契约要求）：把 adb 原始输出翻译成中文人话。
 * 内部委托给 [AdbErrors.humanizeAdbError]。
 *
 * 后两个参数**都有默认值**，既有 `humanizeAdbError(raw)` 调用点无需改动。
 */
fun humanizeAdbError(
    raw: String,
    context: ErrorContext = ErrorContext.GENERAL,
    port: Int = 0
): String = AdbErrors.humanizeAdbError(raw, context, port)
