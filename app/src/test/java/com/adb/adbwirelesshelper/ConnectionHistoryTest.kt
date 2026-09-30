package com.adb.adbwirelesshelper

import com.adb.adbwirelesshelper.data.repository.decodeHistory
import com.adb.adbwirelesshelper.data.repository.encodeHistory
import com.adb.adbwirelesshelper.domain.model.HistoryDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史设备记录的序列化 / 反序列化测试。
 *
 * 只覆盖纯函数 [encodeHistory] / [decodeHistory] —— 仓储本身依赖 DataStore + Context，
 * 在 JVM 单测里跑不了（本工程未引入 Robolectric）。持久化真正容易出错的部分
 * （分隔符冲突、脏数据、去重、排序、容量上限）全在这两个函数里，所以覆盖它们就够。
 */
class ConnectionHistoryTest {

    private fun e(
        host: String,
        port: Int,
        serial: String = if (port > 0) "$host:$port" else host,
        label: String? = null,
        at: Long = 1_700_000_000_000L
    ) = HistoryDevice(host = host, port = port, serial = serial, label = label, lastConnectedAt = at)

    // ---------------------------------------------------------------- 往返

    @Test
    fun `encode 后 decode 应完全还原字段`() {
        val list = listOf(
            e("192.168.1.5", 37123, label = "Pixel 7"),
            e("192.168.1.50", 41207, label = "SM-S918B"),
            e("ABCDEFG12345", 0, label = null)
        )

        val decoded: List<HistoryDevice> = decodeHistory(encodeHistory(list))

        // decode 按 lastConnectedAt 倒序，三条时间戳相同 → 保持原顺序
        assertEquals(3, decoded.size)
        assertEquals("192.168.1.5", decoded[0].host)
        assertEquals(37123, decoded[0].port)
        assertEquals("192.168.1.5:37123", decoded[0].serial)
        assertEquals("Pixel 7", decoded[0].label)
        assertEquals("192.168.1.50", decoded[1].host)
        assertEquals(0, decoded[2].port)
        assertNull(decoded[2].label)
    }

    @Test
    fun `空列表编解码后仍为空`() {
        assertEquals(emptyList<HistoryDevice>(), decodeHistory(encodeHistory(emptyList())))
        assertEquals(emptyList<HistoryDevice>(), decodeHistory(""))
        assertEquals(emptyList<HistoryDevice>(), decodeHistory("   "))
    }

    // ---------------------------------------------------------------- 分隔符冲突

    @Test
    fun `型号名里含分隔符或换行时不应把记录解析错位`() {
        // 这是用 Base64 编码文本字段的唯一理由：裸拼字符串时，一个 `|` 就会多出字段，
        // 一个换行就会把一条记录劈成两条，整份历史随之错位。
        val nasty = listOf(
            e("192.168.1.5", 37123, label = "A|B\nC=D"),
            e("192.168.1.6", 37123, label = "中文 型号 · 空格")
        )

        val decoded: List<HistoryDevice> = decodeHistory(encodeHistory(nasty))

        assertEquals(2, decoded.size)
        assertEquals("A|B\nC=D", decoded[0].label)
        assertEquals("中文 型号 · 空格", decoded[1].label)
        assertEquals("192.168.1.5", decoded[0].host)
        assertEquals("192.168.1.6", decoded[1].host)
    }

    // ---------------------------------------------------------------- 脏数据容错

    @Test
    fun `单条记录损坏时应只跳过该条而不是丢掉整份历史`() {
        // 每种脏数据都独立成行，**且字段数都是 5** —— 字段数不对会先被字段数检查拦掉，
        // 就轮不到下面这些分支了；要让断言真的在测「Base64 损坏 / 端口越界」，
        // 必须让它们走到各自那一行去。
        val good = encodeHistory(listOf(e("192.168.1.5", 37123)))
        val broken = listOf(
            "a|b|c",                                     // 字段数 3 → 字段数不符
            "!!!|c2VyaWFs|5555|bGFiZWw=|1700000000000",  // host 不是合法 Base64
            "aGVsbG8=|!!!|5555|bGFiZWw=|1700000000000",  // serial 不是合法 Base64
            "aGVsbG8=|c2VyaWFs|notANumber|bGFiZWw=|1700000000000", // 端口非数字
            "aGVsbG8=|c2VyaWFs|99999|bGFiZWw=|1700000000000",      // 端口越界
            "aGVsbG8=|c2VyaWFs|5555|bGFiZWw=|notALong",            // 时间戳非数字
            good
        ).joinToString("\n")

        val decoded: List<HistoryDevice> = decodeHistory(broken)

        assertEquals(1, decoded.size)
        assertEquals("192.168.1.5", decoded[0].host)
    }

    // ---------------------------------------------------------------- 去重 / 排序 / 上限

    @Test
    fun `同一 host 重复出现时只保留第一条`() {
        val raw = listOf(
            encodeHistory(listOf(e("192.168.1.5", 37123, at = 3_000L))),
            encodeHistory(listOf(e("192.168.1.5", 41207, at = 1_000L)))
        ).joinToString("\n")

        val decoded: List<HistoryDevice> = decodeHistory(raw)

        assertEquals(1, decoded.size)
        assertEquals(37123, decoded[0].port)
    }

    @Test
    fun `结果按最近连接倒序`() {
        val list = listOf(
            e("10.0.0.1", 1, at = 1_000L),
            e("10.0.0.2", 2, at = 9_000L),
            e("10.0.0.3", 3, at = 5_000L)
        )

        val decoded: List<HistoryDevice> = decodeHistory(encodeHistory(list))

        assertEquals(listOf("10.0.0.2", "10.0.0.3", "10.0.0.1"), decoded.map { it.host })
    }

    @Test
    fun `超出容量上限时只保留最近的连接`() {
        // MAX_ENTRIES = 50（定义在 ConnectionHistoryRepository.kt，文件私有，这里用字面量）
        val list = (1..60).map { e("10.0.0.$it", it, at = it.toLong()) }

        val decoded: List<HistoryDevice> = decodeHistory(encodeHistory(list))

        assertEquals(50, decoded.size)
        // 时间戳最大的 60 号在最前
        assertEquals("10.0.0.60", decoded.first().host)
        assertEquals("10.0.0.11", decoded.last().host)
    }

    // ---------------------------------------------------------------- 模型派生属性

    @Test
    fun `isWireless 与 hostPort 的取值`() {
        val wifi = e("192.168.1.5", 37123)
        val usb = e("ABCDEFG", 0)

        assertTrue(wifi.isWireless)
        assertEquals("192.168.1.5:37123", wifi.hostPort)
        assertTrue(!usb.isWireless)
        assertEquals("ABCDEFG", usb.hostPort)
    }
}
