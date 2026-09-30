package com.adb.adbwirelesshelper

import com.adb.adbwirelesshelper.data.discovery.PortScanEngine
import com.adb.adbwirelesshelper.data.discovery.mergeDiscovered
import com.adb.adbwirelesshelper.domain.model.Capability
import com.adb.adbwirelesshelper.domain.model.DiscoveredService
import com.adb.adbwirelesshelper.domain.model.Source
import com.adb.adbwirelesshelper.util.NetUtils
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备发现「提速」改造的两个前置逻辑。
 *
 * 存在的理由：这两处是本次提速的全部依据，而它们**没法靠 Android 真机点到头顶取证** ——
 * 一个是 socket 异常的语义判定，一个是端口抽样规则。改坏了的表现是「扫描又快又扫不到东西」，
 * 日志看上去一切正常（超时不报错）。所以必须在这里把它钉死：
 *
 * 1. `classifyFailure`：`REFUSED`（RST）= 主机在线；`UNREACHABLE` = 这台 IP 没人应答，
 *    后续所有端口都不必再扫。判错一个方向，提速就变成「设备消失」。
 * 2. `samplePorts`：抽样数直接决定扫描耗时（254 主机 × 抽样数），规则不能被无意改动。
 */
class DiscoveryScanTest {

    // ------------------------------------------------------- 探活失败原因 → 三态

    @Test
    fun `连接被拒应判定为主机在线(REFUSED)`() {
        // 对方 TCP 栈回了 RST：端口没开，但主机存在且响应极快 —— 这是「在线」的证据
        assertEquals(NetUtils.ProbeResult.REFUSED, NetUtils.classifyFailure(ConnectException("Connection refused")))
    }

    @Test
    fun `连接超时应判定为主机不可达(UNREACHABLE)`() {
        // 干等到超时：SYN 石沉大海，再扫这台主机的其它端口纯属浪费
        assertEquals(
            NetUtils.ProbeResult.UNREACHABLE,
            NetUtils.classifyFailure(SocketTimeoutException("timeout")),
        )
    }

    @Test
    fun `不可达与解析失败都不算在线`() {
        assertEquals(
            NetUtils.ProbeResult.UNREACHABLE,
            NetUtils.classifyFailure(NoRouteToHostException("no route")),
        )
        assertEquals(
            NetUtils.ProbeResult.UNREACHABLE,
            NetUtils.classifyFailure(UnknownHostException("bogus")),
        )
    }

    @Test
    fun `只有 ConnectException 才被当作在线`() {
        val total = NetUtils.ProbeResult.values().size
        assertEquals(3, total)
    }

    // ------------------------------------------------------------ 动态端口抽样

    @Test
    fun `抽样数量应等于请求数量`() {
        val samples = PortScanEngine().samplePorts(37_000..44_000, 200)
        assertEquals(200, samples.size)
    }

    @Test
    fun `抽样应铺满整个区间且严格递增`() {
        val range = 37_000..44_000
        val samples = PortScanEngine().samplePorts(range, 200)
        assertEquals(range.first, samples.first())
        assertTrue("起点不能落在区间外", samples.first() >= range.first)
        assertTrue("终点不能落在区间外", samples.last() <= range.last)
        assertEquals("不能有重复端口", samples.size, samples.distinct().size)
        assertEquals("必须递增", samples.sorted(), samples)
    }

    @Test
    fun `区间比抽样数还小时返回全部端口`() {
        val samples = PortScanEngine().samplePorts(37_000..37_049, 200)
        assertEquals(50, samples.size)
        assertEquals(37_000, samples.first())
        assertEquals(37_049, samples.last())
    }

    @Test
    fun `退化成单个端口时返回单个端口`() {
        assertEquals(listOf(5555), PortScanEngine().samplePorts(5555..5555, 200))
    }

    @Test
    fun `抽样数为 0 时不会崩且至少返回一个端口`() {
        val samples = PortScanEngine().samplePorts(37_000..44_000, 0)
        assertEquals(1, samples.size)
    }

    // -------------------------------------------------- 候选设备合并（不限个数）

    private fun svc(host: String, port: Int, capability: Capability = Capability.UNKNOWN): DiscoveredService =
        DiscoveredService(
            instanceName = "adb-$host-$port",
            serviceType = "_adb-tls-connect._tcp.",
            host = host,
            port = port,
            source = Source.PORT_SCAN,
            capability = capability,
        )

    @Test
    fun `局域网里有多少台就应列出多少台(不设上限)`() {
        var list: List<DiscoveredService> = emptyList()
        for (i in 1..60) {
            list = mergeDiscovered(list, svc("192.168.1.$i", 5555))
        }
        assertEquals("scan 到 60 台就应显示 60 台", 60, list.size)
        assertEquals(60, list.map { it.host }.distinct().size)
    }

    @Test
    fun `已经连过的设备再次被扫到不应消失也不应重复`() {
        val once = mergeDiscovered(emptyList(), svc("192.168.1.7", 4444))
        val twice = mergeDiscovered(once, svc("192.168.1.7", 4444))
        assertEquals(1, twice.size)
        assertEquals("192.168.1.7", twice.first().host)
    }

    @Test
    fun `同一台主机的配对端口与连接端口应合并成一张卡`() {
        val pairing = DiscoveredService(
            instanceName = "adb-1111",
            serviceType = "_adb-tls-pairing._tcp.",
            host = "192.168.1.9",
            port = 39123,
            source = Source.MDNS,
            capability = Capability.PAIRING_ONLY,
        )
        val connect = DiscoveredService(
            instanceName = "adb-2222",
            serviceType = "_adb-tls-connect._tcp.",
            host = "192.168.1.9",
            port = 41234,
            source = Source.MDNS,
            capability = Capability.CONNECT,
        )
        val merged = mergeDiscovered(mergeDiscovered(emptyList(), pairing), connect)

        assertEquals(1, merged.size)
        assertEquals("两个端口都要落位", 39123, merged.first().pairPort)
        assertEquals(41234, merged.first().connectPort)
        assertEquals("有连接端口就应判定为可直接连接", Capability.CONNECT, merged.first().capability)
    }

    @Test
    fun `非法结果应被忽略且不产生副作用`() {
        val list = mergeDiscovered(emptyList(), svc("192.168.1.5", 5555))
        assertEquals("返回同一个对象引用，调用方可跳过写 StateFlow", true, list === mergeDiscovered(list, svc("", 5555)))
        assertEquals(1, mergeDiscovered(list, svc("192.168.1.6", 0)).size)
    }
}
