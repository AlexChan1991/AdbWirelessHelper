package com.adb.adbwirelesshelper.data.discovery

import android.content.Context
import com.adb.adbwirelesshelper.domain.model.Capability
import com.adb.adbwirelesshelper.domain.model.DiscoveredService
import com.adb.adbwirelesshelper.domain.model.Source
import com.adb.adbwirelesshelper.util.Logx
import com.adb.adbwirelesshelper.util.NetUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 纯函数：把一条发现结果并入候选表。
 *
 * **这里刻意不设上限** —— 需求是「局域网里有多少台就列多少台」。
 * 表**只按 `host` 去重**（同一台机器的 pairing / connect 两条服务合并成一张卡），
 * 不会因为数量多、也不会因为「这台以前连过」而丢弃任何一台。
 *
 * 抽成顶层纯函数是为了让单测能直接钉住这些规则（[DiscoveryRepository] 需要 Context/NsdManager，
 * 在 JVM 单元测试里无法实例化）。
 *
 * @return 合并后的新列表；若 [incoming] 应当被忽略（host 为空 / 端口非法），
 *         返回的就是入参 [current] 本身（引用相等，调用方可据此跳过写 StateFlow）。
 */
internal fun mergeDiscovered(
    current: List<DiscoveredService>,
    incoming: DiscoveredService,
): List<DiscoveredService> {
    if (incoming.host.isBlank() || incoming.port <= 0) return current

    val list = current.toMutableList()
    val index = list.indexOfFirst { it.host == incoming.host }

    if (index < 0) {
        list.add(withPorts(incoming))
        return list
    }

    val old = list[index]
    val mergedPairPort: Int? = incoming.pairPort
        ?: incoming.port.takeIf { incoming.capability == Capability.PAIRING_ONLY }
        ?: old.pairPort
    val mergedConnectPort: Int? = incoming.connectPort
        ?: incoming.port.takeIf { incoming.capability == Capability.CONNECT }
        ?: old.connectPort

    val mergedCapability: Capability = when {
        mergedConnectPort != null -> Capability.CONNECT
        mergedPairPort != null -> Capability.PAIRING_ONLY
        else -> old.capability
    }
    val mergedSource: Source = if (incoming.source == Source.MDNS || old.source == Source.MDNS) {
        Source.MDNS
    } else if (old.source == Source.MANUAL || incoming.source == Source.MANUAL) {
        Source.MANUAL
    } else {
        old.source
    }
    // mDNS 的实例名（形如 adb-XXXXXX-XXXX）比 portscan-xxx / manual-xxx 更有信息量
    val mergedName: String = when {
        old.source == Source.MDNS -> old.instanceName
        incoming.source == Source.MDNS -> incoming.instanceName
        else -> incoming.instanceName
    }
    val mergedType: String = if (incoming.source == Source.MDNS) incoming.serviceType else old.serviceType
    val mergedPort: Int = mergedConnectPort ?: mergedPairPort ?: old.port

    list[index] = old.copy(
        instanceName = mergedName,
        serviceType = mergedType,
        port = mergedPort,
        source = mergedSource,
        capability = mergedCapability,
        pairPort = mergedPairPort,
        connectPort = mergedConnectPort,
    )
    return list
}

/**
 * 把引擎返回的裸结果（pairPort / connectPort 为 null）补齐端口语义。
 */
private fun withPorts(service: DiscoveredService): DiscoveredService = when (service.capability) {
    Capability.PAIRING_ONLY -> service.copy(
        pairPort = service.pairPort ?: service.port,
        connectPort = service.connectPort,
    )

    Capability.CONNECT -> service.copy(
        connectPort = service.connectPort ?: service.port,
        pairPort = service.pairPort,
    )

    Capability.UNKNOWN -> service
}

/**
 * 设备发现结果聚合仓储。
 *
 * 合并 [NsdDiscoveryEngine]（mDNS）与 [PortScanEngine]（端口扫描）的结果：
 * - 按 `host` 聚合：同一台主机的 pairing / connect 两条服务合并成一张卡，
 *   并分别填充 [DiscoveredService.pairPort] 与 [DiscoveredService.connectPort]；
 * - **两条路径无条件并行**（2026-09-28 改）：见 [start] 的说明；
 * - **结果不设上限**：局域网里有多少台就列多少台，从不因为「已经连过 / 已经找到过一台」而截断
 *   （`_candidates` 只按 host 去重累加，见 [mergeDiscovered]）。
 *
 * 注意：构造函数是**单参**，AppContainer 依赖此签名。
 */
class DiscoveryRepository(private val context: Context) {

    companion object {
        private const val TAG = "DiscoveryRepository"

        /**
         * mDNS 阶段的预算。给引擎自身的周期：一条结果都没有时用它决定何时收流。
         *
         * ⚠️ 它**不再**用来决定「等多久才启动端口扫描」——以前 `start()` 要等
         *    `MDNS_TIMEOUT_MS + 500`（8.5 秒）才肯兜底，现在两条路径并行（见 [start]）。
         */
        const val MDNS_TIMEOUT_MS: Long = 8_000L

        /** [mode] 取值：正在做 mDNS 发现。 */
        const val MODE_MDNS: String = "mDNS"

        /** [mode] 取值：正在做端口扫描。 */
        const val MODE_PORT_SCAN: String = "端口扫描"

        /** [mode] 取值：两条路径同时跑。 */
        const val MODE_BOTH: String = "mDNS + 端口扫描"

        /** [mode] 取值：已停止。 */
        const val MODE_STOPPED: String = "已停止"
    }

    private val appContext: Context = context.applicationContext
    private val nsdEngine: NsdDiscoveryEngine = NsdDiscoveryEngine(appContext)
    private val portScanEngine: PortScanEngine = PortScanEngine()
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _candidates: MutableStateFlow<List<DiscoveredService>> =
        MutableStateFlow(emptyList())

    /** 去重、按 host 聚合后的候选设备列表。 */
    val candidates: StateFlow<List<DiscoveredService>> = _candidates.asStateFlow()

    private val _scanning: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** 是否正在扫描。 */
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _mode: MutableStateFlow<String> = MutableStateFlow(MODE_STOPPED)

    /** 当前发现模式，取值见 [MODE_MDNS] / [MODE_PORT_SCAN] / [MODE_STOPPED]。 */
    val mode: StateFlow<String> = _mode.asStateFlow()

    private var mdnsJob: Job? = null
    private var portScanJob: Job? = null

    /**
     * 启动发现：**mDNS 与端口扫描无条件并行**。
     *
     * 该方法是 suspend 的（内部发送的就是一次性启动），请在协程中调用。
     *
     * ### 为什么不再做「mDNS 没结果才兜底」
     * 旧实现是 `等 8.5 秒 → 若候选列表仍为空才启动端口扫描`。它有个很隐蔽的副作用：
     * **只要 mDNS 找到过任何一台设备，端口扫描就永远不跑**。
     * 而现实里最容易留在 mDNS 服务列表里的，恰恰是**这台 App 以前连过的那台** ——
     * 于是用户看到的现象是「首页只显示我连过的那台，局域网里其它开了无线调试的都搜不到」，
     * 而且重扫几次都一样（每次都被同一个「非空就跳过」的条件挡住）。
     *
     * 两条路径能抓到的设备集合本来就不同：
     * - mDNS 只能抓「正在广播 `_adb-tls-*`」的设备；
     * - 端口扫描还能抓 `adb tcpip 5555` 的老式明文设备 —— 它们**根本不广播 mDNS**，
     *   在旧逻辑下出现概率是 **0**。
     *
     * 所以现在并行跑，谁先回来就先出条目（[mergeDiscovered] 按 host 去重合并），
     * 结果**不设上限、也不因为「曾经连过」而跳过任何一台**。
     * 端口扫描已经做了「主机探活先行」优化（秒级完成），并行启动的代价可以接受。
     */
    suspend fun start() {
        stopInternal()
        _scanning.value = true
        Logx.i(TAG, "开始发现：mDNS 与端口扫描并行")

        mdnsJob = scope.launch {
            runCatching {
                nsdEngine.discover(MDNS_TIMEOUT_MS).collect { service -> merge(service) }
            }.onFailure { error ->
                Logx.w(TAG, "mDNS 发现异常：${error.message}")
            }
            refreshScanState()
        }

        portScanJob = scope.launch {
            runCatching {
                portScanEngine.scan(NetUtils.localSubnetPrefix()).collect { service -> merge(service) }
            }.onFailure { error ->
                Logx.w(TAG, "端口扫描异常：${error.message}")
            }
            refreshScanState()
        }

        refreshScanState()
    }

    /** 停止全部发现并释放组播锁。 */
    fun stop() {
        stopInternal()
        _scanning.value = false
        _mode.value = MODE_STOPPED
    }

    private fun stopInternal() {
        mdnsJob?.cancel()
        mdnsJob = null
        portScanJob?.cancel()
        portScanJob = null
        nsdEngine.stop()
    }

    /**
     * 依据两条路径的存活情况刷新 `mode` / `scanning`。
     *
     * 并行之后必须按「谁还没结束」来算：否则会出现「端口扫描扫完就把 scanning 置 false，
     * 而 mDNS 还在跑、状态条却已经显示停止」的错位。
     */
    private fun refreshScanState() {
        val mdnsActive: Boolean = mdnsJob?.isActive == true
        val scanActive: Boolean = portScanJob?.isActive == true
        val nextMode: String = when {
            mdnsActive && scanActive -> MODE_BOTH
            mdnsActive -> MODE_MDNS
            scanActive -> MODE_PORT_SCAN
            else -> MODE_STOPPED
        }
        _mode.value = nextMode
        if (nextMode == MODE_STOPPED) {
            _scanning.value = false
            Logx.i(TAG, "发现结束，共 ${_candidates.value.size} 台候选设备")
        }
    }

    /**
     * 合并一条发现结果：同一 host 只保留一个条目，pairing / connect 端口分别落位。
     * 纯合并逻辑在 [mergeDiscovered]，这里只负责写回 StateFlow。
     */
    private fun merge(incoming: DiscoveredService) {
        val merged: List<DiscoveredService> = mergeDiscovered(_candidates.value, incoming)
        if (merged !== _candidates.value) {
            _candidates.value = merged
        }
    }
}
