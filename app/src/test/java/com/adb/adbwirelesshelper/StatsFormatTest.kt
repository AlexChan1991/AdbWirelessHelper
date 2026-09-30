package com.adb.adbwirelesshelper

import com.adb.adbwirelesshelper.util.bitrateKbps
import com.adb.adbwirelesshelper.util.bitrateText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 投屏统计里码率的换算与展示。
 *
 * 存在的理由：这里出过一次真 bug —— 计算端写成 `bytes * 8 / 1000 / elapsed`
 * （算成「kbit 每毫秒」，小 1000 倍），显示端又 `kbps / 1000` 整除，
 * 结果 8 Mbps 的流在界面上恒显示 **0 Mbps**。两个错误分别藏在 ViewModel 和 Composable 里，
 * 谁都测不到，只有端到端看数字才发现。抽成纯函数后两端都能被这里钉死。
 */
class StatsFormatTest {

    // ---------------------------------------------------------------- bitrateKbps

    @Test
    fun `8 Mbps 的流在 1 秒窗口内应算成 8000 kbps`() {
        // 8 Mbps = 1,000,000 字节/秒
        assertEquals(8000, bitrateKbps(windowBytes = 1_000_000L, elapsedMs = 1_000L))
    }

    @Test
    fun `码率与时间窗口成反比`() {
        // 同样 500,000 字节：1 秒窗口 → 4000 kbps；2 秒窗口 → 2000 kbps
        assertEquals(4000, bitrateKbps(500_000L, 1_000L))
        assertEquals(2000, bitrateKbps(500_000L, 2_000L))
    }

    @Test
    fun `非法窗口不会除零或产生负数`() {
        assertEquals(0, bitrateKbps(1_000L, 0L))
        assertEquals(0, bitrateKbps(0L, 1_000L))
        assertEquals(0, bitrateKbps(-1L, 1_000L))
    }

    @Test
    fun `极大窗口不会溢出成负数`() {
        // 极端值只做兜底，不追求精确；关键是必须为正、不能因 Int 截断变成负数
        val v: Int = bitrateKbps(windowBytes = Long.MAX_VALUE / 8L, elapsedMs = 1L)
        assertEquals(Int.MAX_VALUE, v)
    }

    // ---------------------------------------------------------------- bitrateText

    @Test
    fun `低于 1 Mbps 时按 Kbps 显示而不是被整除成 0`() {
        // 这是原来最明显的症状：800 kbps 被 `kbps / 1000` 整除显示成 "0 Mbps"
        assertEquals("800 Kbps", bitrateText(800))
        assertEquals("1 Kbps", bitrateText(1))
        assertEquals("999 Kbps", bitrateText(999))
    }

    @Test
    fun `达到 1 Mbps 后换成带一位小数的 Mbps`() {
        assertEquals("1.0 Mbps", bitrateText(1000))
        assertEquals("8.0 Mbps", bitrateText(8000))
        assertEquals("2.5 Mbps", bitrateText(2500))
    }

    @Test
    fun `零码率显示 0 Kbps 而不是负数或空串`() {
        assertEquals("0 Kbps", bitrateText(0))
        assertEquals("0 Kbps", bitrateText(-5))
    }

    // ---------------------------------------------------------------- 端到端串起来

    @Test
    fun `8 Mbps 的流经换算再格式化后应显示 8_0 Mbps`() {
        // 把真实链路整串起来校验：这才是用户最终在统计叠层里看到的东西
        val kbps: Int = bitrateKbps(windowBytes = 1_000_000L, elapsedMs = 1_000L)
        assertEquals("8.0 Mbps", bitrateText(kbps))
    }

    @Test
    fun `小数点不受系统语言环境影响`() {
        // 某些 Locale 下 String.format 会把 "8.0" 写成 "8,0"
        val defaultLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("8.0 Mbps", bitrateText(8000))
        } finally {
            java.util.Locale.setDefault(defaultLocale)
        }
    }
}
