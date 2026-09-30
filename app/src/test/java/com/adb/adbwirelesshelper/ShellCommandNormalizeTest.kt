package com.adb.adbwirelesshelper

import com.adb.adbwirelesshelper.data.adb.normalizeShellCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令行页命令归一化的测试（BugFix：用户输入 `adb xxx` 导致被控端退出码 127）。
 *
 * 只覆盖纯函数 [normalizeShellCommand] —— [com.adb.adbwirelesshelper.data.adb.ShellExecutor]
 * 依赖 AdbClient，JVM 单测里造不出来，所以归一化逻辑刻意抽成了顶层 `internal` 函数。
 */
class ShellCommandNormalizeTest {

    // ---------------------------------------------------------------- 规则 1：空白

    @Test
    fun `空白输入返回空命令且不拦不提示`() {
        for (raw in listOf("", "   ", "\t\n")) {
            val plan = normalizeShellCommand(raw)
            assertEquals("", plan.command)
            assertNull(plan.strippedPrefix)
            assertNull(plan.hostOnlyHint)
        }
    }

    // ---------------------------------------------------------------- 规则 2：剥前缀

    @Test
    fun `adb shell 前缀被剥掉`() {
        val plan = normalizeShellCommand("adb shell getprop ro.product.model")
        assertEquals("getprop ro.product.model", plan.command)
        assertEquals("adb shell", plan.strippedPrefix)
        assertNull(plan.hostOnlyHint)
    }

    @Test
    fun `单独的 adb 前缀被剥掉`() {
        val plan = normalizeShellCommand("adb logcat -s MyTag")
        assertEquals("logcat -s MyTag", plan.command)
        assertEquals("adb", plan.strippedPrefix)
        assertNull(plan.hostOnlyHint)
    }

    @Test
    fun `单独的 shell 前缀被剥掉`() {
        val plan = normalizeShellCommand("shell dumpsys battery")
        assertEquals("dumpsys battery", plan.command)
        assertEquals("shell", plan.strippedPrefix)
        assertNull(plan.hostOnlyHint)
    }

    @Test
    fun `制表符分隔也能剥前缀`() {
        val plan = normalizeShellCommand("adb\tgetprop ro.serialno")
        assertEquals("getprop ro.serialno", plan.command)
        assertEquals("adb", plan.strippedPrefix)
    }

    @Test
    fun `前缀大小写敏感且必须 token 相等`() {
        // 只认小写 `adb`；`ADB` 不剥（用户真要在设备上跑一个叫 ADB 的程序时不能误剥）
        assertEquals("ADB devices", normalizeShellCommand("ADB devices").command)
        assertNull(normalizeShellCommand("ADB devices").strippedPrefix)
    }

    @Test
    fun `adb 开头的其它命令不能被误剥`() {
        // ★ 必须按 token 相等判定，不能用 startsWith：
        //   adbd / adbx / shellx 都是合法命令名，剥掉就再也执行不了了
        val adbd = normalizeShellCommand("adbd")
        assertEquals("adbd", adbd.command)
        assertNull(adbd.strippedPrefix)

        val adbx = normalizeShellCommand("adbx version")
        assertEquals("adbx version", adbx.command)
        assertNull(adbx.strippedPrefix)

        val shellx = normalizeShellCommand("shellx --help")
        assertEquals("shellx --help", shellx.command)
        assertNull(shellx.strippedPrefix)
    }

    @Test
    fun `剥前缀循环最多执行 6 次`() {
        // 上限提到 6 是为了容纳 `adb -s <serial> shell`（3 步）及叠加写法，
        // 但病态输入仍要在上限处停手，剩下的原样执行（宁可报 127 也不能死循环）
        val plan = normalizeShellCommand("adb adb adb adb adb adb adb ls")
        assertEquals("adb ls", plan.command)
        assertEquals("adb adb adb adb adb adb", plan.strippedPrefix)
    }

    // ---------------------------------------------------------------- 规则 3：只剩前缀

    @Test
    fun `只写了 adb 时给出说明而不是执行`() {
        val plan = normalizeShellCommand("adb")
        assertEquals("", plan.command)
        assertEquals("adb", plan.strippedPrefix)
        assertNotNull(plan.hostOnlyHint)
        assertTrue(plan.hostOnlyHint!!.contains("没有要执行的命令"))
    }

    @Test
    fun `只写了 adb shell 时给出说明而不是执行`() {
        val plan = normalizeShellCommand("adb shell  ")
        assertEquals("", plan.command)
        assertEquals("adb shell", plan.strippedPrefix)
        assertNotNull(plan.hostOnlyHint)
        assertTrue(plan.hostOnlyHint!!.contains("没有要执行的命令"))
    }

    // ---------------------------------------------------------------- 规则 4：主机端子命令

    @Test
    fun `adb devices 被识别为主机端子命令并给出替代做法`() {
        val plan = normalizeShellCommand("adb devices")
        assertEquals("", plan.command)
        assertEquals("adb", plan.strippedPrefix)
        assertNotNull(plan.hostOnlyHint)
        assertTrue(plan.hostOnlyHint!!.contains("已知设备"))
    }

    @Test
    fun `全部主机端子命令都被拦下`() {
        val hostOnly = listOf(
            "devices", "connect", "disconnect", "pair", "forward", "reverse",
            "push", "pull", "install", "uninstall", "root", "remount",
            "tcpip", "usb", "sideload"
        )
        for (sub in hostOnly) {
            val plan = normalizeShellCommand("adb $sub arg1 arg2")
            assertEquals("sub=$sub 应被拦下", "", plan.command)
            assertNotNull("sub=$sub 应给出提示", plan.hostOnlyHint)
        }
    }

    @Test
    fun `push 与 pull 的提示指向文件管理页`() {
        assertTrue(normalizeShellCommand("adb push a b").hostOnlyHint!!.contains("文件管理"))
        assertTrue(normalizeShellCommand("adb pull /sdcard/a").hostOnlyHint!!.contains("文件管理"))
    }

    // ---------------------------------------------------------------- 规则 5：设备端命令必须放行

    @Test
    fun `被控端自带命令不能被误判成主机端子命令`() {
        // ★ 这是本次修复要保住的能力：`adb logcat` 归一化成 `logcat` 后完全可用。
        //   这些命令都是被控端 /system/bin 下的可执行文件，必须在设备 shell 里跑。
        val deviceSide = listOf(
            "reboot", "bugreport", "logcat", "getprop", "dumpsys",
            "pm", "am", "settings", "input"
        )
        for (name in deviceSide) {
            val plan = normalizeShellCommand("adb shell $name -x")
            assertEquals("cmd=$name 不应被拦", "$name -x", plan.command)
            assertNull("cmd=$name 不应有 hostOnlyHint", plan.hostOnlyHint)
        }
    }

    @Test
    fun `adb reboot 归一化后仍是 reboot 交给危险命令流程处理`() {
        // reboot 是设备端命令（不在主机端集合里），但它是 DANGEROUS ——
        // 归一化只负责剥前缀，危险判定由 ShellExecutor 在归一化之后接手。
        val plan = normalizeShellCommand("adb reboot")
        assertEquals("reboot", plan.command)
        assertEquals("adb", plan.strippedPrefix)
        assertNull(plan.hostOnlyHint)
    }

    @Test
    fun `pm uninstall 首 token 是 pm 所以放行`() {
        // 首 token 是 `pm` 而不是 `uninstall`，不能按「包含 uninstall」误判
        val plan = normalizeShellCommand("pm uninstall com.example.app")
        assertEquals("pm uninstall com.example.app", plan.command)
        assertNull(plan.strippedPrefix)
        assertNull(plan.hostOnlyHint)
    }

    // ---------------------------------------------------------------- 常见写法

    @Test
    fun `用户报的原始命令 adb devices 不再被下发到设备 shell`() {
        // 用户原话报的 bug：`adb devices` → 实际执行 `adb -s <serial> shell adb devices`
        // → 被控端 `/system/bin/sh: adb: inaccessible or not found`，退出码 127。
        val plan = normalizeShellCommand("adb devices")
        assertEquals("", plan.command)
    }

    @Test
    fun `adb shell pm list packages 正常执行`() {
        val plan = normalizeShellCommand("adb shell pm list packages")
        assertEquals("pm list packages", plan.command)
        assertEquals("adb shell", plan.strippedPrefix)
        assertNull(plan.hostOnlyHint)
    }

    // ---------------------------------------------------------------- 目标选择参数

    @Test
    fun `adb -s serial shell 前缀被整段剥掉`() {
        val plan = normalizeShellCommand("adb -s 192.168.1.5:5555 shell getprop x")
        assertEquals("getprop x", plan.command)
        assertEquals("adb -s 192.168.1.5:5555 shell", plan.strippedPrefix)
        assertNull(plan.hostOnlyHint)
    }

    @Test
    fun `adb -s serial 不带 shell 也能剥`() {
        val plan = normalizeShellCommand("adb -s 192.168.1.5:5555 getprop x")
        assertEquals("getprop x", plan.command)
        assertEquals("adb -s 192.168.1.5:5555", plan.strippedPrefix)
        assertNull(plan.hostOnlyHint)
    }

    @Test
    fun `adb -d shell 与 adb -e shell 被剥掉`() {
        val d = normalizeShellCommand("adb -d shell getprop x")
        assertEquals("getprop x", d.command)
        assertEquals("adb -d shell", d.strippedPrefix)

        val e = normalizeShellCommand("adb -e shell getprop x")
        assertEquals("getprop x", e.command)
        assertEquals("adb -e shell", e.strippedPrefix)
    }

    @Test
    fun `adb -t transportId shell 被剥掉`() {
        val plan = normalizeShellCommand("adb -t 3 shell getprop x")
        assertEquals("getprop x", plan.command)
        assertEquals("adb -t 3 shell", plan.strippedPrefix)
        assertNull(plan.hostOnlyHint)
    }

    @Test
    fun `非引导位置的 -s 必须原样保留`() {
        // ★ 关键回归点：别把被控端命令自己的 `-s` 参数吃掉。
        //   归一化只从**头部**剥，第一轮就在 `ls` / `getprop` 上 break。
        val ls = normalizeShellCommand("ls -s 100")
        assertEquals("ls -s 100", ls.command)
        assertNull(ls.strippedPrefix)
        assertNull(ls.ignoredTarget)

        val getprop = normalizeShellCommand("getprop -s something")
        assertEquals("getprop -s something", getprop.command)
        assertNull(getprop.strippedPrefix)

        // 剥完引导段之后，命令自己的 -s 同样要保住
        val nested = normalizeShellCommand("adb -s 192.168.1.5:5555 shell ls -s 100")
        assertEquals("ls -s 100", nested.command)
        assertEquals("adb -s 192.168.1.5:5555 shell", nested.strippedPrefix)

        // shell 之后紧跟的 -s 属于被控端命令，不能被当成 adb 的目标 flag
        val afterShell = normalizeShellCommand("adb shell -s 100")
        assertEquals("-s 100", afterShell.command)
        assertEquals("adb shell", afterShell.strippedPrefix)
        assertNull(afterShell.ignoredTarget)
    }

    @Test
    fun `非引导位置的 -d 与 -e 同样保留`() {
        assertEquals("ls -d /sdcard", normalizeShellCommand("ls -d /sdcard").command)
        assertEquals("ls -e foo", normalizeShellCommand("ls -e foo").command)
    }

    @Test
    fun `-s 参数与当前设备不一致时记为被忽略`() {
        val plan = normalizeShellCommand(
            "adb -s 192.168.1.9:5555 shell getprop x",
            "192.168.1.5:5555"
        )
        assertEquals("getprop x", plan.command)
        assertEquals("-s 192.168.1.9:5555", plan.ignoredTarget)
    }

    @Test
    fun `-s 参数就是当前设备时不算被忽略`() {
        val plan = normalizeShellCommand(
            "adb -s 192.168.1.5:5555 shell getprop x",
            "192.168.1.5:5555"
        )
        assertEquals("getprop x", plan.command)
        assertNull(plan.ignoredTarget)
        assertEquals("adb -s 192.168.1.5:5555 shell", plan.strippedPrefix)
    }

    @Test
    fun `-t 给的 transportId 一律算被忽略`() {
        // 本页没有 transport id 这层概念，没法兑现，必须告知用户
        val plan = normalizeShellCommand("adb -t 3 shell getprop x", "3")
        assertEquals("-t 3", plan.ignoredTarget)
    }

    @Test
    fun `只写了 adb -s 没有后续命令时给出说明`() {
        val plan = normalizeShellCommand("adb -s 192.168.1.5:5555")
        assertEquals("", plan.command)
        assertEquals("adb -s 192.168.1.5:5555", plan.strippedPrefix)
        assertNotNull(plan.hostOnlyHint)
    }

    @Test
    fun `-s 后面没有参数时不会越界`() {
        val plan = normalizeShellCommand("adb -s")
        assertEquals("", plan.command)
        assertEquals("adb -s", plan.strippedPrefix)
        assertNull(plan.ignoredTarget)
    }

    @Test
    fun `叠多个目标参数时循环上限内剥完`() {
        // adb / -s A / -s B / shell = 4 步，在 6 的上限内
        val plan = normalizeShellCommand("adb -s A -s B shell ls")
        assertEquals("ls", plan.command)
        assertEquals("adb -s A -s B shell", plan.strippedPrefix)
    }
}
