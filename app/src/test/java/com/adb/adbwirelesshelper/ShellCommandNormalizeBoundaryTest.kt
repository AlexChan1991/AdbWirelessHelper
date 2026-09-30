package com.adb.adbwirelesshelper

import com.adb.adbwirelesshelper.data.adb.ShellExecutor
import com.adb.adbwirelesshelper.data.adb.normalizeShellCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令行页归一化的**边界**用例（QA 独立补，覆盖 [ShellCommandNormalizeTest] 没碰的输入形状）。
 *
 * ★ 这里的每一条断言写的都是「正确行为」，不是「当前行为」。
 *   如果某条变红，那是候选缺陷，不是放宽断言的理由。
 *
 * 关注点：
 * - 引号 / 管道 / 重定向 / 连续空白这些**shell 语法**不能被归一化破坏；
 * - 只从外层头部剥，不解析引号内部；
 * - `-s` / `-t` 的实参位置出现任何奇怪的串（含以 `-` 开头、含缺失）都不能越界/崩溃；
 * - 危险判定必须发生在归一化**之后**（`adb shell rm -rf /` 要能命中）。
 */
class ShellCommandNormalizeBoundaryTest {

    private fun cmd(raw: String): String = normalizeShellCommand(raw).command

    // ---------------------------------------------------------------- 引号

    @Test
    fun `引号必须原样保留`() {
        val plan = normalizeShellCommand("adb shell \"pm list packages\"")
        assertEquals("\"pm list packages\"", plan.command)
        assertEquals("adb shell", plan.strippedPrefix)
        assertNull(plan.hostOnlyHint)
    }

    @Test
    fun `引号里的 adb 不能被当成前缀再剥一次`() {
        // 归一化只从**外层头部**剥，不做引号解析：引号内是发给被控端的一整个字符串
        val plan = normalizeShellCommand("adb shell \"adb devices\"")
        assertEquals("\"adb devices\"", plan.command)
        assertEquals("adb shell", plan.strippedPrefix)
        assertNull(plan.hostOnlyHint)
    }

    // ---------------------------------------------------------------- 空白

    @Test
    fun `多个空格与制表符都被折叠`() {
        val spaces = normalizeShellCommand("adb    shell   getprop x")
        assertEquals("getprop x", spaces.command)
        assertEquals("adb shell", spaces.strippedPrefix)

        val tabs = normalizeShellCommand("adb\t\tshell\tgetprop x")
        assertEquals("getprop x", tabs.command)
        assertEquals("adb shell", tabs.strippedPrefix)
    }

    @Test
    fun `命令内部的连续空格必须原样保留`() {
        // trim 只能吃掉两端，不能把 `getprop    x` 压成 `getprop x`
        assertEquals("getprop    x", cmd("adb shell getprop    x"))
    }

    @Test
    fun `首尾空白被 trim 掉`() {
        assertEquals("getprop x", cmd("  adb shell getprop x  "))
    }

    @Test
    fun `全角空白也按分隔符处理`() {
        // U+3000 是 Unicode 空白，Kotlin 的 Char.isWhitespace() 认它
        assertEquals("getprop x", cmd("adb　shell getprop x"))
    }

    // ---------------------------------------------------------------- shell 语法

    @Test
    fun `管道不能被破坏`() {
        assertEquals("ps | grep adbd", cmd("adb shell ps | grep adbd"))
    }

    @Test
    fun `重定向不能被破坏`() {
        assertEquals("ls > /sdcard/a.txt", cmd("adb shell ls > /sdcard/a.txt"))
    }

    @Test
    fun `逻辑与不能被破坏`() {
        assertEquals("cat a && echo ok", cmd("adb shell cat a && echo ok"))
    }

    // ---------------------------------------------------------------- 空 / 只有前缀

    @Test
    fun `空串与纯空白返回空命令且不给提示`() {
        for (raw in listOf("", "   ", "\t\n", "\n")) {
            val plan = normalizeShellCommand(raw)
            assertEquals("raw=$raw", "", plan.command)
            assertNull("raw=$raw", plan.strippedPrefix)
            assertNull("raw=$raw", plan.hostOnlyHint)
            assertNull("raw=$raw", plan.ignoredTarget)
        }
    }

    @Test
    fun `只写 shell 也要给出说明`() {
        val plan = normalizeShellCommand("shell")
        assertEquals("", plan.command)
        assertEquals("shell", plan.strippedPrefix)
        assertNotNull(plan.hostOnlyHint)
        assertTrue(plan.hostOnlyHint!!.contains("没有要执行的命令"))
    }

    @Test
    fun `有 -s 但没有命令时给出说明并记录被忽略的目标`() {
        val plan = normalizeShellCommand("adb -s 1.2.3.4:5555")
        assertEquals("", plan.command)
        assertEquals("adb -s 1.2.3.4:5555", plan.strippedPrefix)
        assertNotNull(plan.hostOnlyHint)
        assertEquals("-s 1.2.3.4:5555", plan.ignoredTarget)
    }

    // ---------------------------------------------------------------- 越界 / 病态输入

    @Test
    fun `-s 的实参以短横线开头时不越界且命令照常执行`() {
        // 工程师点名的残留风险：`adb -s -weird shell ls`（serial 位置是个 `-` 开头的串）
        // 期望：不抛异常；`-weird` 被当成 -s 的实参吃掉；剩下的 `ls` 正常执行
        val plan = normalizeShellCommand("adb -s -weird shell ls")
        assertEquals("ls", plan.command)
        assertEquals("-s -weird", plan.ignoredTarget)
        assertEquals("adb -s -weird shell", plan.strippedPrefix)
        assertNull(plan.hostOnlyHint)
    }

    @Test
    fun `-t 是最后一个 token 时不越界`() {
        val plan = normalizeShellCommand("adb -t")
        assertEquals("", plan.command)
        assertEquals("adb -t", plan.strippedPrefix)
        assertNull(plan.ignoredTarget)
        assertNotNull(plan.hostOnlyHint)
    }

    @Test
    fun `shell 之后 -s 是最后一个 token 时不越界且原样下发`() {
        val plan = normalizeShellCommand("adb shell -s")
        assertEquals("-s", plan.command)
        assertEquals("adb shell", plan.strippedPrefix)
        assertNull(plan.ignoredTarget)
        assertNull(plan.hostOnlyHint)
    }

    @Test
    fun `连续 10 个 adb 前缀在上限处停手且不崩溃`() {
        // 病态输入：只剥 6 次就停，剩下的原样下发（宁可 127 也不能死循环）
        val plan = normalizeShellCommand("adb adb adb adb adb adb adb adb adb adb ls")
        assertEquals("adb adb adb adb ls", plan.command)
    }

    @Test
    fun `叠写 adb shell adb shell 也能剥干净`() {
        assertEquals("ls", cmd("adb shell adb shell ls"))
    }

    @Test
    fun `adb -d -e shell 叠加被剥干净`() {
        val plan = normalizeShellCommand("adb -d -e shell ls")
        assertEquals("ls", plan.command)
        assertEquals("adb -d -e shell", plan.strippedPrefix)
    }

    // ---------------------------------------------------------------- 危险判定的时机

    @Test
    fun `危险判定发生在归一化之后`() {
        // ★ 安全底线：`adb shell rm -rf /sdcard` 剥完前缀后必须仍然命中危险词表，
        //   不能因为多了 `adb shell` 就绕过 token 判定
        val plan = normalizeShellCommand("adb shell rm -rf /sdcard")
        assertEquals("rm -rf /sdcard", plan.command)
        assertEquals("rm -rf", ShellExecutor.dangerReason(plan.command))
    }

    @Test
    fun `shell 之后的高危命令同样要能命中`() {
        val plan = normalizeShellCommand("adb shell su -c \"id\"")
        assertEquals("su -c \"id\"", plan.command)
        assertNotNull(ShellExecutor.dangerReason(plan.command))
    }

    @Test
    fun `adb -s 实参恰好是危险词时不会污染危险判定`() {
        // `-s rm` 的 rm 是 serial 位置，剥掉之后剩下的是 ls，不该被判危险
        val plan = normalizeShellCommand("adb -s rm shell ls")
        assertEquals("ls", plan.command)
        assertNull(ShellExecutor.dangerReason(plan.command))
    }

    // ================================================================ round-3 新增语义
    //
    // round-3 三件事：① `stripped.contains("shell")` → 独立的 shellSeen 布尔量；
    //                ② 补 10 个宿主侧子命令进拦截集合；
    //                ③ run() 里 hostOnlyHint 分支提到「已剥前缀 / 已忽略 -s」之前。
    // 下面逐条验证，断言写的都是**正确行为**。

    // ---------------------------------------------------------------- ① shellSeen

    @Test
    fun `-s 的实参字面就是 shell 时后续目标 flag 仍要认`() {
        // ★ round-3 的核心回归点。round-2 实测这里剥出的是 `-d ls`（因为
        //   `stripped.contains("shell")` 把 `-s` 的实参 `shell` 误当成了子命令），
        //   现在必须剥到只剩 `ls`。
        val plan = normalizeShellCommand("adb -s shell -d ls")
        assertEquals("ls", plan.command)
        assertEquals("-s shell", plan.ignoredTarget)
        assertNull(plan.hostOnlyHint)
    }

    @Test
    fun `shellSeen 改法没有让普通剥壳逻辑退化`() {
        // 未退化对照组
        assertEquals("ls", normalizeShellCommand("adb -s shell ls").command)
        assertEquals("ls", normalizeShellCommand("adb -s -weird shell ls").command)
        assertEquals("-s -weird", normalizeShellCommand("adb -s -weird shell ls").ignoredTarget)
        // shell 之后的目标 flag 依旧不许碰
        val afterShell = normalizeShellCommand("adb shell -s 100")
        assertEquals("-s 100", afterShell.command)
        assertNull(afterShell.ignoredTarget)
        // 被控端命令自己的 -s 依旧不许碰
        assertEquals("ls -s 100", normalizeShellCommand("adb -s 1.2.3.4:5555 shell ls -s 100").command)
        // 叠写 shell 也能剥干净
        assertEquals("ls", normalizeShellCommand("adb -s shell shell ls").command)
    }

    // ---------------------------------------------------------------- ② 新增的宿主侧子命令

    @Test
    fun `round-3 新增的宿主侧子命令全部走拦截而不是下发设备`() {
        // 上一轮我实测这批写法剥完前缀后会被当设备命令下发 → 127；
        // round-3 把它们收进 HOST_ONLY_ADB_SUBCOMMANDS，现在必须是「不执行 + 给替代做法」。
        val added = listOf(
            "exec-out", "exec-in", "version", "get-serialno", "wait-for-device",
            "backup", "restore", "emu", "kill-server", "start-server",
        )
        for (sub in added) {
            val plan = normalizeShellCommand("adb $sub arg1 arg2")
            assertEquals("sub=$sub 不该下发到设备", "", plan.command)
            assertNotNull("sub=$sub 应给出拦截说明", plan.hostOnlyHint)
            assertTrue(
                "sub=$sub 的说明应点名该子命令",
                plan.hostOnlyHint!!.contains(sub)
            )
        }
    }

    @Test
    fun `exec-out 的提示给出的是拿得出来的替代做法`() {
        // 只写本 App 真有的能力：设备端跑命令 + 文件管理页取回
        val hint: String = normalizeShellCommand("adb exec-out screencap -p").hostOnlyHint!!
        assertTrue(hint.contains("文件管理"))
    }

    @Test
    fun `wait-for-device 后面跟 shell 也要被拦下`() {
        // round-2 实测 `adb wait-for-device shell getprop x` 会整串下发 → 127
        val plan = normalizeShellCommand("adb wait-for-device shell getprop x")
        assertEquals("", plan.command)
        assertNotNull(plan.hostOnlyHint)
    }

    // ---------------------------------------------------------------- ③ 反向保护：不得误拦

    @Test
    fun `被控端真实存在的命令一个都不许被拦`() {
        // ★ round-3 点名强调的反向保护：sync / bugreport / reboot / logcat / getprop /
        //   dumpsys / pm / am / settings / input 都在被控端 /system/bin 下真实存在，
        //   加进拦截集合就是把本来能跑的命令拦死。
        val deviceSide = listOf(
            "reboot", "bugreport", "logcat", "getprop", "dumpsys",
            "pm", "am", "settings", "input", "sync",
        )
        for (name in deviceSide) {
            val plan = normalizeShellCommand("adb shell $name -x")
            assertEquals("cmd=$name 应照常下发", "$name -x", plan.command)
            assertNull("cmd=$name 不应被拦", plan.hostOnlyHint)
        }
    }

    @Test
    fun `adb logcat 与 adb reboot 照常下发到设备`() {
        val logcat = normalizeShellCommand("adb logcat -s MyTag")
        assertEquals("logcat -s MyTag", logcat.command)
        assertNull(logcat.hostOnlyHint)

        val reboot = normalizeShellCommand("adb reboot")
        assertEquals("reboot", reboot.command)
        assertNull(reboot.hostOnlyHint)
        // reboot 不在拦截集合，但要交给危险命令流程
        assertEquals("reboot", ShellExecutor.dangerReason(reboot.command))
    }

    @Test
    fun `adb sync 不能被拦`() {
        // 被控端自带 /system/bin/sync，拦了就是回归
        val plan = normalizeShellCommand("adb sync /sdcard")
        assertEquals("sync /sdcard", plan.command)
        assertNull(plan.hostOnlyHint)
    }

    // ---------------------------------------------------------------- 危险判定仍在归一化之后

    @Test
    fun `adb shell rm -rf 根路径在归一化后仍被危险判定命中`() {
        val plan = normalizeShellCommand("adb shell rm -rf /")
        assertEquals("rm -rf /", plan.command)
        assertNull(plan.hostOnlyHint)
        assertEquals("rm -rf", ShellExecutor.dangerReason(plan.command))
    }

    @Test
    fun `两参调用形态仍然可用`() {
        // 团队提到 normalizeShellCommand 是两参 (raw, currentSerial)，第二参有默认值；
        // 这里显式传两参，确认签名没变、且 -s 命中当前设备时不算被忽略
        val same = normalizeShellCommand("adb -s 1.2.3.4:5555 shell ls", "1.2.3.4:5555")
        assertEquals("ls", same.command)
        assertNull(same.ignoredTarget)

        val other = normalizeShellCommand("adb -s 9.9.9.9:5555 shell ls", "1.2.3.4:5555")
        assertEquals("ls", other.command)
        assertEquals("-s 9.9.9.9:5555", other.ignoredTarget)
    }

    // ================================================================ round-4 新增映射
    //
    // HOST_ONLY_ALTERNATIVES 16 → 20：get-serialno / wait-for-device /
    // kill-server / start-server 各补了一条真实等价做法。
    // 要验证的不是「hint 非 null」，而是**取到的是新映射、不是通用兜底**。

    /** 通用兜底文案（`HOST_ONLY_ALTERNATIVES[head] ?: "本页不支持该操作"`）。 */
    private val fallbackText: String = "本页不支持该操作"

    @Test
    fun `round-4 新增的 4 条替代做法映射必须被取到而不是掉进兜底`() {
        val cases: Map<String, String> = mapOf(
            "get-serialno" to "getprop ro.serialno",
            "wait-for-device" to "无需另等",
            "kill-server" to "无需手动停止",
            "start-server" to "无需手动启动",
        )
        for ((sub, needle) in cases) {
            val plan = normalizeShellCommand("adb $sub")
            assertEquals("sub=$sub 不该下发到设备", "", plan.command)
            val hint: String = plan.hostOnlyHint
                ?: throw AssertionError("sub=$sub 竟然没有 hostOnlyHint")
            assertTrue("sub=$sub 应取到新映射（含「$needle」），实际：$hint", hint.contains(needle))
            assertFalse("sub=$sub 不该掉进通用兜底，实际：$hint", hint.contains(fallbackText))
        }
    }

    @Test
    fun `exec-out 的映射没有被这轮改动挤掉`() {
        // 回归护栏：round-4 只应新增，不应动到已有映射
        val hint: String = normalizeShellCommand("adb exec-out screencap -p").hostOnlyHint!!
        assertTrue(hint.contains("文件管理"))
        assertFalse(hint.contains(fallbackText))
    }

    @Test
    fun `确实没有等价入口的 5 个子命令仍要走本页不支持兜底`() {
        // ★ 反向确认：加了 4 条映射不能把兜底路径搞坏。
        //   exec-in / version / backup / restore / emu 仍然没有映射，
        //   必须继续命中 `?: "本页不支持该操作"`。
        val noAlternative = listOf("exec-in", "version", "backup", "restore", "emu")
        for (sub in noAlternative) {
            val plan = normalizeShellCommand("adb $sub arg1")
            assertEquals("sub=$sub 不该下发到设备", "", plan.command)
            val hint: String = plan.hostOnlyHint
                ?: throw AssertionError("sub=$sub 竟然没有 hostOnlyHint")
            assertTrue("sub=$sub 应仍走兜底，实际：$hint", hint.contains(fallbackText))
        }
    }
}
