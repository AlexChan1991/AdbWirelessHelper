package com.adb.adbwirelesshelper.data.discovery

import com.adb.adbwirelesshelper.domain.model.Capability
import com.adb.adbwirelesshelper.domain.model.DiscoveredService
import com.adb.adbwirelesshelper.domain.model.Source
import com.adb.adbwirelesshelper.util.Logx
import com.adb.adbwirelesshelper.util.NetUtils
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 端口扫描兜底引擎。
 *
 * 当 mDNS 被路由器/系统过滤（AP 隔离、访客网络、组播被拦）时，用它做同网段 TCP 探活。
 *
 * **提速的关键：先做一次「主机探活」再决定要不要扫端口。**（2026-09-28 重写）
 * 旧实现是直接 `254 台主机 × 200 个端口 = 5 万次` connect。其中绝大多数来自**不存在的 IP** ——
 * 对不存在的主机，connect 只能干等到超时（每次 timeoutMs）；而对在线主机，即便端口没开，
 * 对方也会立刻回 RST（毫秒级）。于是耗时几乎全部浪费在「给空气发 SYN」上，实测要几分钟。
 *
 * 现在分两步：
 * 1. **主机探活**：每台主机只探一个端口（默认 5555）。凡是拿到 OPEN 或 REFUSED 的都算在线
 *    —— REFUSED 恰恰是「对方在线且响应极快」的证据（[NetUtils.ProbeResult.REFUSED]）；
 *      拿到 UNREACHABLE 的说明这台 IP 没人应答，直接出局；
 * 2. **端口抽样**：只对第 1 步活下来的主机做动态高位端口抽样，不再扫 254 台全量主机。
 *
 * 典型家庭网段在线设备一般 10~30 台，探测量从 ~5 万降到 ~4 千，且绝大多数是毫秒级 RST。
 *
 * 最坏情况也不会退化回旧实现：
 * - 若整段只有一两台在线 —— 正是本次优化的主场景（提速几十倍）；
 * - 若碰到「路由器对任何 IP 都直接回 RST」的网络（部分运营商 NAT 会这么做），
 *   第一阶段会把 254 台全判成在线，但那种网络里第二阶段的每个探测同样是毫秒级 RST，
 *   5 万次快速失败仍是秒级（比原来 5 万次超时便宜得多）。
 *   即 **第一步把「在线」判宽了代价可控，判严了（漏设备）才是真事故**。
 *
 * 结果是增量发射的：命中一个就发一个，UI 可以边扫边出结果；整体运行在 `Dispatchers.IO`。
 */
class PortScanEngine {

    companion object {
        private const val TAG = "PortScan"

        /** 动态端口区间的抽样数量（全量 7000 个端口 × 254 台主机不可接受）。 */
        private const val DYNAMIC_SAMPLE_COUNT: Int = 200
    }

    /**
     * @param subnetPrefix  网段前缀，形如 `"192.168.1."`；传 null 时自动取本机网段。
     * @param parallelism   并发连接数。
     * @param timeoutMs     单个连接的超时时间（主机探活与端口抽样共用）。
     * @param dynamicRange  无线调试随机高位端口区间。
     * @param extraPorts    额外固定探测端口（默认 5555），同时也是主机探活用的端口。
     * @param scanAllWhenNoAlive 探活一台都没探到时的兜底：true 则退回全量主机扫描。
     *        正常网段里「一台都不在线」几乎必然意味着探活失真（比如整段被防火墙静默丢包），
     *        此时退回旧行为，保证覆盖率不至于变差。
     */
    fun scan(
        subnetPrefix: String? = null,
        parallelism: Int = 128,
        timeoutMs: Int = 250,
        dynamicRange: IntRange = 37_000..44_000,
        extraPorts: List<Int> = listOf(5555),
        scanAllWhenNoAlive: Boolean = true,
    ): Flow<DiscoveredService> = channelFlow {
        val prefix: String? = subnetPrefix?.trim()?.takeIf { it.isNotEmpty() }
            ?: NetUtils.localSubnetPrefix()?.trim()?.takeIf { it.isNotEmpty() }

        if (prefix == null) {
            Logx.w(TAG, "无法确定本机网段，端口扫描已跳过")
            return@channelFlow
        }

        val hosts: List<String> = NetUtils.subnetHosts(prefix)
        if (hosts.isEmpty()) {
            Logx.w(TAG, "网段 ${prefix}* 下没有可扫描的主机")
            return@channelFlow
        }

        val limit = Semaphore(parallelism.coerceIn(1, 512))
        val hitCount = AtomicInteger(0)
        val startedAt: Long = SystemClock.elapsedRealtime()
        Logx.i(TAG, "开始端口扫描：${prefix}*，共 ${hosts.size} 台主机")

        // ---- 阶段一：主机探活（每台主机只探一次），顺带完成固定端口扫描 ----
        // 复用 extraPorts 的第一次探活结果：既能直接产出 5555 的命中，又能判定主机是否在线。
        val aliveHosts: MutableList<String> = Collections.synchronizedList(ArrayList(hosts.size))
        val discoveryJobs: List<Job> = hosts.map { host ->
            launch {
                for (port in extraPorts) {
                    when (probe(limit, host, port, timeoutMs)) {
                        NetUtils.ProbeResult.OPEN -> {
                            // 开了 5555的传统 TCP 模式：既是命中，当然也算在线
                            hitCount.incrementAndGet()
                            aliveHosts.add(host)
                            send(newResult(host, port))
                        }

                        NetUtils.ProbeResult.REFUSED -> aliveHosts.add(host)
                        NetUtils.ProbeResult.UNREACHABLE -> Unit // 这台 IP 没人应答，直接出局
                    }
                }
            }
        }
        discoveryJobs.joinAll()

        val elapsed: Long = SystemClock.elapsedRealtime() - startedAt
        Logx.i(TAG, "主机探活完成：${aliveHosts.size}/${hosts.size} 台在线，耗时 ${elapsed}ms")

        val targets: List<String> = if (aliveHosts.isNotEmpty()) {
            aliveHosts.distinct().sorted()
        } else if (scanAllWhenNoAlive) {
            Logx.w(TAG, "探活未发现任何在线主机，退回全量扫描（会慢，网络可能在静默丢包）")
            hosts
        } else {
            Logx.w(TAG, "探活未发现任何在线主机，已跳过端口抽样")
            emptyList()
        }

        if (targets.isEmpty()) {
            Logx.i(TAG, "端口扫描结束（无目标主机），共命中 ${hitCount.get()} 个开放端口")
            return@channelFlow
        }

        // ---- 阶段二：动态高位端口等距抽样（Android 11+ 无线调试的真实端口）----
        // 只跑阶段一活下来的主机：典型场景一次十几到三十台，而不是 254 台。
        val samples: List<Int> = samplePorts(dynamicRange, DYNAMIC_SAMPLE_COUNT)
        val dynamicJobs: List<Job> = targets.map { host ->
            launch {
                for (port in samples) {
                    if (probe(limit, host, port, timeoutMs) == NetUtils.ProbeResult.OPEN) {
                        hitCount.incrementAndGet()
                        send(newResult(host, port))
                    }
                }
            }
        }
        dynamicJobs.joinAll()

        Logx.i(
            TAG,
            "端口扫描结束，共命中 ${hitCount.get()} 个开放端口，" +
                "总耗时 ${SystemClock.elapsedRealtime() - startedAt}ms",
        )
    }.flowOn(Dispatchers.IO)

    /**
     * 受限并发地探测一个 TCP 端口，返回三态结果。
     */
    private suspend fun probe(
        semaphore: Semaphore,
        host: String,
        port: Int,
        timeoutMs: Int,
    ): NetUtils.ProbeResult {
        semaphore.acquire()
        return try {
            NetUtils.probeTcp(host, port, timeoutMs)
        } finally {
            semaphore.release()
        }
    }

    /**
     * 在 [range] 内等距抽取至多 [count] 个端口，保证样本铺满整个区间。
     * `internal` 是为了让单测直接验证取样规则（改动取样数会直接影响扫描耗时）。
     */
    internal fun samplePorts(range: IntRange, count: Int): List<Int> {
        val first = range.first
        val last = range.last
        if (last <= first) return listOf(first)
        val span = last - first + 1
        val n = count.coerceAtLeast(1)
        if (span <= n) return range.toList()
        val step = span / n
        return (0 until n).map { index -> first + index * step }
    }

    private fun newResult(host: String, port: Int): DiscoveredService = DiscoveredService(
        instanceName = "portscan-$host:$port",
        serviceType = "portscan",
        host = host,
        port = port,
        source = Source.PORT_SCAN,
        capability = Capability.UNKNOWN,
        pairPort = null,
        connectPort = null,
    )
}
