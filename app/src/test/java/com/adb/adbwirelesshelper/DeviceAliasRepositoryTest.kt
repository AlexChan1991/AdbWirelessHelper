package com.adb.adbwirelesshelper

import com.adb.adbwirelesshelper.data.repository.decodeAliases
import com.adb.adbwirelesshelper.data.repository.encodeAliases
import com.adb.adbwirelesshelper.data.repository.toHistoryEntry
import com.adb.adbwirelesshelper.domain.model.AdbDevice
import com.adb.adbwirelesshelper.domain.model.DeviceState
import com.adb.adbwirelesshelper.domain.model.deviceAliasKey
import com.adb.adbwirelesshelper.domain.model.serialToHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备别名表的序列化 / 反序列化测试。
 *
 * 与 ConnectionHistoryTest 同样的取舍：**只覆盖纯函数** [encodeAliases] / [decodeAliases]。
 * 仓储本身依赖 DataStore + Context，在 JVM 单测里跑不了（本工程未引入 Robolectric）。
 * 持久化真正容易出错的部分（分隔符冲突、脏数据、重复 key）全在这两个函数里。
 */
class DeviceAliasRepositoryTest {

    /** 落盘串允许出现的字符：URL-safe Base64 的字表 + 两个分隔符。 */
    private val allowedChars: Regex = Regex("[A-Za-z0-9_\\-|\\n]*")

    // ---------------------------------------------------------------- 别名主键切分
    //
    // ★ 这一组是整个功能的地基：别名键必须与历史记录的主键 HistoryDevice.host 是
    //   **同一个值**，否则「已知设备里设的别名」在历史列表里查不到，需求直接失效。
    //   历史上这里出过事：别名键用 substringBefore(':')（首个冒号）、历史键用
    //   lastIndexOf(':')（末个冒号），IPv6 下两者不同。现在两边都委托 serialToHost。

    @Test
    fun `deviceAliasKey 去掉端口段`() {
        assertEquals("192.168.1.5", deviceAliasKey("192.168.1.5:37123"))
        assertEquals("10.0.0.7", deviceAliasKey("10.0.0.7:5555"))
    }

    @Test
    fun `换端口后别名键不变`() {
        // 这条就是「下次同一个设备 id 自动套用别名」的全部含义
        assertEquals(
            deviceAliasKey("192.168.1.5:37123"),
            deviceAliasKey("192.168.1.5:41207")
        )
        assertEquals("192.168.1.5", deviceAliasKey("192.168.1.5:41207"))
    }

    @Test
    fun `USB 设备没有冒号时整个 serial 当键`() {
        assertEquals("ABCDEFG12345", deviceAliasKey("ABCDEFG12345"))
    }

    @Test
    fun `IPv6 设备切出带方括号的主机段而不是塌缩成一个左括号`() {
        // 曾经的 bug：substringBefore(':') 会把 `[::1]:5555` 切成 `[`，
        // 于是**所有** IPv6 设备的别名键都变成同一个 `[`，A 的别名显示在 B 上。
        assertEquals("[::1]", deviceAliasKey("[::1]:5555"))
        assertEquals("[2001:db8::1]", deviceAliasKey("[2001:db8::1]:5555"))
        assertEquals("[fe80::1%wlan0]", deviceAliasKey("[fe80::1%wlan0]:37123"))

        // 两台不同的 IPv6 设备必须得到两个不同的键
        assertNotEquals(deviceAliasKey("[::1]:5555"), deviceAliasKey("[2001:db8::1]:5555"))
    }

    @Test
    fun `别名键与历史记录主键必须逐字相同`() {
        // ★ 核心断言：不走「两边规则碰巧一样」的假设，直接比历史库实际切出来的 host。
        //   toHistoryEntry 现在是 internal，就是为了能在单测里做这个交叉验证。
        val serials = listOf(
            "192.168.1.5:37123",
            "192.168.1.5:41207",
            "192.168.1.50:41207",
            "ABCDEFG12345",
            "[::1]:5555",
            "[2001:db8::1]:5555",
            "[fe80::1%wlan0]:37123"
        )

        for (serial in serials) {
            val fromHistory: String =
                AdbDevice(serial = serial, state = DeviceState.DEVICE).toHistoryEntry().host
            assertEquals("serial=$serial", fromHistory, deviceAliasKey(serial))
            assertEquals("serial=$serial", fromHistory, serialToHost(serial))
        }
    }

    @Test
    fun `历史条目的端口不带进 host`() {
        assertEquals(
            37123,
            AdbDevice(serial = "192.168.1.5:37123", state = DeviceState.DEVICE)
                .toHistoryEntry().port
        )
        assertEquals(
            5555,
            AdbDevice(serial = "[::1]:5555", state = DeviceState.DEVICE)
                .toHistoryEntry().port
        )
        // USB：无端口
        assertEquals(
            0,
            AdbDevice(serial = "ABCDEFG12345", state = DeviceState.DEVICE)
                .toHistoryEntry().port
        )
    }

    @Test
    fun `IPv6 的两台设备在别名表里互不覆盖`() {
        val map = mapOf(
            deviceAliasKey("[::1]:5555") to "本机回环",
            deviceAliasKey("[2001:db8::1]:5555") to "实验室样机"
        )
        val decoded: Map<String, String> = decodeAliases(encodeAliases(map))

        assertEquals(2, decoded.size)
        assertEquals("本机回环", decoded["[::1]"])
        assertEquals("实验室样机", decoded["[2001:db8::1]"])
    }

    // ---------------------------------------------------------------- 空表

    @Test
    fun `空表编解码后仍为空`() {
        assertEquals("", encodeAliases(emptyMap()))
        assertEquals(emptyMap<String, String>(), decodeAliases(encodeAliases(emptyMap())))
        assertEquals(emptyMap<String, String>(), decodeAliases(""))
        assertEquals(emptyMap<String, String>(), decodeAliases("   "))
        assertEquals(emptyMap<String, String>(), decodeAliases("\n\n"))
    }

    // ---------------------------------------------------------------- 往返

    @Test
    fun `正常往返应完全还原`() {
        val map = mapOf(
            "192.168.1.5" to "客厅的小米",
            "192.168.1.50" to "卧室的 Pixel",
            "ABCDEFG12345" to "桌上的样机"
        )

        val decoded: Map<String, String> = decodeAliases(encodeAliases(map))

        assertEquals(3, decoded.size)
        assertEquals("客厅的小米", decoded["192.168.1.5"])
        assertEquals("卧室的 Pixel", decoded["192.168.1.50"])
        assertEquals("桌上的样机", decoded["ABCDEFG12345"])
    }

    @Test
    fun `中文别名往返不乱码`() {
        // 中文走 UTF-8 → Base64，回来必须逐字相同（不能出现 U+FFFD 替换符）
        val alias = "小米 14 Ultra · 测试机"
        val decoded: Map<String, String> = decodeAliases(encodeAliases(mapOf("10.0.0.7" to alias)))

        assertEquals(alias, decoded["10.0.0.7"])
        // 用转义写替换符，免得这个测试文件自己带一个 U+FFFD 字面量
        assertTrue("解码后出现替换符 U+FFFD", decoded["10.0.0.7"]!!.none { it.code == 0xFFFD })
    }

    // ---------------------------------------------------------------- 分隔符冲突

    @Test
    fun `别名含分隔符与换行时不会把记录解析错位`() {
        // 这是字段走 Base64 的唯一理由：裸拼字符串时，一个 `|` 就多出字段、
        // 一个换行就把一条记录劈成两条，整份别名表随之错位。
        val nasty = mapOf(
            "192.168.1.5" to "客厅|小米\n备用机",
            "192.168.1.6" to "aGVsbG8=|!!!", // 看起来像一条合法记录的别名
            "192.168.1.7" to "= _ - 都试一遍"
        )

        val decoded: Map<String, String> = decodeAliases(encodeAliases(nasty))

        assertEquals(3, decoded.size)
        assertEquals("客厅|小米\n备用机", decoded["192.168.1.5"])
        assertEquals("aGVsbG8=|!!!", decoded["192.168.1.6"])
        assertEquals("= _ - 都试一遍", decoded["192.168.1.7"])
    }

    @Test
    fun `key 含分隔符与换行时也能正确往返`() {
        // 正常 adb serial 不含这些字符，但仓储不该假设调用方永远规规矩矩
        val map = mapOf("host|with\nsep" to "别名 A", "192.168.1.9" to "别名 B")

        val decoded: Map<String, String> = decodeAliases(encodeAliases(map))

        assertEquals(2, decoded.size)
        assertEquals("别名 A", decoded["host|with\nsep"])
        assertEquals("别名 B", decoded["192.168.1.9"])
    }

    @Test
    fun `落盘串不含分隔符且是 URL-safe 字表`() {
        // 断言的是**不变量本身**：编码结果里除了分隔符，不能出现会破坏定长字段解析的字符。
        // 同时杜绝标准 Base64 的 `+` / `/`（URL-safe 编码器才会避免它们）。
        val raw: String = encodeAliases(
            mapOf("192.168.1.5" to "客厅|小米\n备用机", "10.0.0.1" to "~!@#$%^&*()")
        )

        assertTrue("落盘串出现了非法字符：$raw", allowedChars.matches(raw))
        assertEquals(2, raw.split('\n').size)
        assertTrue(raw.split('\n').all { it.split('|').size == 2 })
    }

    // ---------------------------------------------------------------- 脏数据容错

    @Test
    fun `单条记录损坏时应只跳过该条而不是丢掉整份别名表`() {
        // ★ 最重要的一条。每种脏数据都独立成行，**且字段数都是 2** ——
        // 字段数不对会先被字段数检查拦掉，就轮不到下面这些分支了。
        val good: String = encodeAliases(mapOf("192.168.1.5" to "客厅的小米"))
        val broken: String = listOf(
            "a|b|c",                 // 字段数 3 → 字段数不符
            "a|",                    // 字段数 2，但别名是空串 → 空别名
            "|Y2hlbmpp",             // key 为空
            "!!!|Y2hlbmpp",          // key 不是合法 Base64
            "MTkyLjE2OC4xLjU=|!!!",  // 别名不是合法 Base64
            "MTkyLjE2OC4xLjU=|",     // 别名缺失（空串）
            good
        ).joinToString("\n")

        val decoded: Map<String, String> = decodeAliases(broken)

        assertEquals(1, decoded.size)
        assertEquals("客厅的小米", decoded["192.168.1.5"])
    }

    @Test
    fun `脏数据夹在中间时前后的正常条目都要保住`() {
        val first: String = encodeAliases(mapOf("10.0.0.1" to "一号机"))
        val last: String = encodeAliases(mapOf("10.0.0.2" to "二号机"))
        val raw: String = listOf(first, "!!!|!!!", "a|b|c", "", "   ", last).joinToString("\n")

        val decoded: Map<String, String> = decodeAliases(raw)

        assertEquals(2, decoded.size)
        assertEquals("一号机", decoded["10.0.0.1"])
        assertEquals("二号机", decoded["10.0.0.2"])
    }

    // ---------------------------------------------------------------- 空别名 / 重复

    @Test
    fun `空别名与空白 key 不会被写入`() {
        val raw: String = encodeAliases(
            mapOf(
                "192.168.1.5" to "有名字",
                "192.168.1.6" to "",
                "192.168.1.7" to "   ",
                "" to "孤儿别名",
                "   " to "另一个孤儿"
            )
        )

        assertEquals(1, raw.split('\n').size)
        val decoded: Map<String, String> = decodeAliases(raw)
        assertEquals(1, decoded.size)
        assertEquals("有名字", decoded["192.168.1.5"])
        assertTrue(decoded["192.168.1.6"].isNullOrEmpty())
        assertTrue(decoded["192.168.1.7"].isNullOrEmpty())
        assertTrue(decoded[""].isNullOrEmpty())
    }

    @Test
    fun `同一 key 重复出现时保留第一条`() {
        // 编码侧永远不会产出重复 key，但手改 / 将来合并旧数据时可能遇到；
        // 与 decodeHistory 保持一致，取第一条。
        val first: String = encodeAliases(mapOf("192.168.1.5" to "先写的"))
        val second: String = encodeAliases(mapOf("192.168.1.5" to "后写的"))

        val decoded: Map<String, String> = decodeAliases("$first\n$second")

        assertEquals(1, decoded.size)
        assertEquals("先写的", decoded["192.168.1.5"])
    }
}
