package com.adb.adbwirelesshelper.util

import java.util.Locale

/**
 * 投屏统计数字的单位换算与展示格式化。
 *
 * 刻意从 `MirrorViewModel` / `MirrorScreen` 里抽出来做成**纯函数**：
 * 码率这块出过一次真 bug —— 计算端多除了一个 1000（单位算成「kbit 每毫秒」），
 * 显示端又整除了 1000（低码率一律显示 0）。两端分散在 ViewModel 和 Composable 里，
 * 谁也没法单独验证，合起来才暴露。集中到这里后两端都能被 JVM 单测直接覆盖。
 */

private const val TEXT_STAT_KBPS: String = "Kbps"
private const val TEXT_STAT_MBPS: String = "Mbps"

/**
 * 由「统计窗口内的字节数 + 窗口毫秒数」算实时码率。
 *
 * @return 单位 **kbit/s**
 *
 * 换算链条（别再漏项）：
 * `字节 × 8 = bit` → `bit / 1000 = kbit` → `kbit / elapsed(**毫秒**) × 1000 = kbit/s`
 * 前后两个 1000 抵消，所以最终就是 `bytes * 8 / elapsedMs`。
 *
 * ⚠️ 写成 `bytes * 8 / 1000 / elapsedMs` 得到的是「kbit 每毫秒」，比真实值小 1000 倍。
 */
internal fun bitrateKbps(windowBytes: Long, elapsedMs: Long): Int {
    if (elapsedMs <= 0L || windowBytes <= 0L) {
        return 0
    }
    // 溢出保护：elapsed 是秒级窗口（约 1000ms），windowBytes 撑死几十 MB，
    // 乘积远小于 Long.MAX；但窗口被异常拉长时仍兜个底，避免 Int 截断成负数。
    val bits: Long = windowBytes * 8L
    val value: Long = bits / elapsedMs
    return if (value > Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()
}

/**
 * 码率展示文案。
 *
 * 不能直接 `kbps / 1000` 当 Mbps：整数除法下 800 kbit/s（0.8 Mbps）会显示成 **0 Mbps**，
 * 看上去像「根本没在传数据」。所以 1000 以下原样按 Kbps 显示，以上才换成带一位小数的 Mbps。
 *
 * [Locale.US] 是必须的 —— 部分语言环境下 `String.format` 会把小数点格式化成逗号。
 */
internal fun bitrateText(kbps: Int): String {
    if (kbps <= 0) {
        return "0 $TEXT_STAT_KBPS"
    }
    if (kbps < 1000) {
        return "$kbps $TEXT_STAT_KBPS"
    }
    return String.format(Locale.US, "%.1f $TEXT_STAT_MBPS", kbps / 1000.0)
}
