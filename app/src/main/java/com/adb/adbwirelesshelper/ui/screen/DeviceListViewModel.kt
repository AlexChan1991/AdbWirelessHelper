package com.adb.adbwirelesshelper.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adb.adbwirelesshelper.data.adb.PairingManager
import com.adb.adbwirelesshelper.data.discovery.DiscoveryRepository
import com.adb.adbwirelesshelper.data.repository.ConnectionHistoryRepository
import com.adb.adbwirelesshelper.data.repository.DeviceAliasRepository
import com.adb.adbwirelesshelper.data.repository.DeviceRepository
import com.adb.adbwirelesshelper.domain.model.AdbDevice
import com.adb.adbwirelesshelper.domain.model.Capability
import com.adb.adbwirelesshelper.domain.model.DeviceState
import com.adb.adbwirelesshelper.domain.model.DiscoveredService
import com.adb.adbwirelesshelper.domain.model.HistoryDevice
import com.adb.adbwirelesshelper.domain.model.Source
import com.adb.adbwirelesshelper.domain.model.serialToHost
import com.adb.adbwirelesshelper.util.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- 文案常量

private const val TAG = "DeviceListViewModel"

private const val TXT_PAIR_REQUIRED = "该设备尚未配对，请先完成配对"
private const val TXT_PAIRING = "正在配对…"
private const val TXT_PAIR_OK = "配对成功，已自动连接"
private const val TXT_PAIR_NEED_RESCAN = "配对成功，但未获取到连接端口，请返回列表重新扫描"
private const val TXT_CONNECT_OK = "已连接"
private const val TXT_BAD_FORMAT = "格式不正确，应为 IP:端口，例如 192.168.1.42:39621"
private const val TXT_CODE_LEN = "请输入 6 位配对码"
private const val TXT_BUSY = "正在处理上一个连接请求，请稍候"

    private const val TXT_HISTORY_GONE = "该设备当前不可达。请确认被控端已开启「无线调试」、与本机在同一 Wi-Fi"
    private const val TXT_KNOWN_GONE = "这台设备当前不是在线状态。可先点「断开」再重连，或确认被控端已开启无线调试"
private const val TXT_HISTORY_DELETED = "已删除该历史记录"
private const val TXT_HISTORY_CLEARED = "已清空历史设备"

// 设备别名（长按条目 → 编辑别名）
private const val TXT_ALIAS_SAVED = "别名已保存，下次这台设备出现时会自动显示"
private const val TXT_ALIAS_REMOVED = "已移除别名"
private const val TXT_ALIAS_FAILED = "别名保存失败，请重试"

private const val TXT_ERR_EXPIRED = "配对码已过期（约 1 分钟内有效）。请在被控端重新点一次「使用配对码配对设备」后重试"
private const val TXT_ERR_WRONG = "配对码错误。请核对被控端显示的 6 位数字后重新输入"
private const val TXT_ERR_PORT = "该端口不是配对端口。配对端口与连接端口不同，请在被控端重新点开配对码界面"
private const val TXT_ERR_UNREACHABLE = "无法连接到该设备。请确认两端在同一 Wi-Fi、已关闭 AP 隔离、未使用访客网络"
private const val TXT_ERR_TIMEOUT = "配对超时（10 秒）。请检查被控端配对码是否仍然有效后重试"
private const val TXT_ERR_ROM = "被控端系统不支持无线配对，请升级系统或改用有线 adb tcpip"
private const val TXT_ERR_UNKNOWN = "配对失败"

// ---------------------------------------------------------------- UI 状态

/**
 * 设备发现列表页的 UI 状态（单向数据流）。
 *
 * @property candidates    mDNS / 端口扫描 / 手动添加的候选设备。
 * @property knownDevices  已连接过的设备（来自 DeviceRepository）。
 * @property historyDevices 曾经连接过的设备（来自 ConnectionHistoryRepository，持久化）。
 * @property aliases       设备别名表：key = `deviceAliasKey(serial)`，value = 别名。
 *                         同一台设备（无线设备换端口也算同一台）下次出现时自动套用。
 * @property scanning      是否正在扫描。
 * @property mode          当前发现模式（mDNS / 端口扫描 / 已停止）。
 * @property connectingHost 正在握手的主机 IP（同一时刻仅允许一个）。
 * @property pairTarget    需要配对的目标（非 null 时弹出配对对话框）。
 * @property message       一次性提示（Snackbar）。
 * @property error         错误文案（红色横幅）。
 * @property navigateTo    导航目标序列号（被消费后清空）。
 */
data class DeviceListUiState(
    val candidates: List<DiscoveredService> = emptyList(),
    val knownDevices: List<AdbDevice> = emptyList(),
    val historyDevices: List<HistoryDevice> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
    val scanning: Boolean = false,
    val mode: String = "",
    val connectingHost: String? = null,
    val pairTarget: DiscoveredService? = null,
    val message: String? = null,
    val error: String? = null,
    val navigateTo: String? = null,
)

/**
 * 设备发现列表页 ViewModel。
 *
 * 职责：发现（启停）、连接、配对、手动添加、历史设备（点击直达 / 删除 / 清空）、
 * 设备别名（长按条目编辑，按设备标识持久化）；把结果聚合到 [uiState]。
 */
class DeviceListViewModel(
    private val discovery: DiscoveryRepository,
    private val pairing: PairingManager,
    private val repo: DeviceRepository,
    private val history: ConnectionHistoryRepository,
    private val aliases: DeviceAliasRepository,
) : ViewModel() {

    private val _uiState: MutableStateFlow<DeviceListUiState> = MutableStateFlow(DeviceListUiState())
    val uiState: StateFlow<DeviceListUiState> = _uiState.asStateFlow()

    init {
        // 聚合发现结果
        viewModelScope.launch {
            discovery.candidates.collect { list ->
                _uiState.update { it.copy(candidates = list) }
            }
        }
        viewModelScope.launch {
            discovery.scanning.collect { value ->
                _uiState.update { it.copy(scanning = value) }
            }
        }
        viewModelScope.launch {
            discovery.mode.collect { value ->
                _uiState.update { it.copy(mode = value) }
            }
        }
        // 已知设备
        viewModelScope.launch {
            repo.devices.collect { list ->
                _uiState.update { it.copy(knownDevices = list) }
            }
        }
        // 历史设备（持久化，App 冷启动即有）
        viewModelScope.launch {
            history.history.collect { list ->
                _uiState.update { it.copy(historyDevices = list) }
            }
        }
        // 设备别名（持久化，与历史分开存；key = deviceAliasKey(serial)）
        viewModelScope.launch {
            aliases.aliases.collect { map ->
                _uiState.update { it.copy(aliases = map) }
            }
        }
        // 配对状态机
        viewModelScope.launch {
            pairing.state.collect { state ->
                when (state) {
                    is PairingManager.State.Pairing -> {
                        _uiState.update { it.copy(message = TXT_PAIRING) }
                    }

                    is PairingManager.State.Success -> {
                        Logx.i(TAG, "配对成功：${state.host}:${state.connectPort}")
                        // ⚠️ 必须在开协程之前取值：下面的 reset() 会清掉 PairingManager 的登记值
                        val hasKnownPort: Boolean =
                            runCatching { pairing.hasKnownConnectPort() }.getOrDefault(false)

                        viewModelScope.launch {
                            runCatching { repo.refresh() }

                            // 优先用 adb 报告的真实 serial：被控端重启后端口可能已变，
                            // 比自己拼 host:port 可靠（需求 C3：配对后自动进详情）
                            val realSerial: String? = findSerialByHost(state.host)

                            when {
                                realSerial != null -> {
                                    _uiState.update {
                                        it.copy(
                                            connectingHost = null,
                                            pairTarget = null,
                                            message = TXT_PAIR_OK,
                                            navigateTo = realSerial,
                                        )
                                    }
                                }

                                hasKnownPort -> {
                                    // 端口可信，兜底拼一个（adb 还没来得及刷新出该设备）
                                    _uiState.update {
                                        it.copy(
                                            connectingHost = null,
                                            pairTarget = null,
                                            message = TXT_PAIR_OK,
                                            navigateTo = "${state.host}:${state.connectPort}",
                                        )
                                    }
                                }

                                else -> {
                                    // 只发现了 pairing 服务（连接服务还没被解析到）：
                                    // 此时 Success.connectPort 只是配对端口的兜底值，
                                    // 拼出来的 serial 与 adb devices 里的不一致，跳过去会永远加载中。
                                    Logx.w(TAG, "配对成功但未登记连接端口，提示用户重新扫描")
                                    _uiState.update {
                                        it.copy(
                                            connectingHost = null,
                                            pairTarget = null,
                                            message = TXT_PAIR_NEED_RESCAN,
                                            error = null,
                                        )
                                    }
                                    pairing.reset()
                                }
                            }
                        }
                    }

                    is PairingManager.State.Failure -> {
                        val code = runCatching { state.reason.name }.getOrDefault("UNKNOWN")
                        val detail = runCatching { state.detail }.getOrNull()
                        _uiState.update {
                            it.copy(
                                connectingHost = null,
                                error = pairErrorText(code, detail),
                            )
                        }
                    }

                    is PairingManager.State.Idle -> {
                        // 空闲态：无需处理
                    }
                }
            }
        }
        // 首帧拉一次已连接设备
        viewModelScope.launch { repo.refresh() }
    }

    // ---------------------------------------------------------------- 发现

    /** 开始扫描（mDNS 优先，8 秒无结果自动叠加端口扫描）。 */
    fun startScan() {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            discovery.start()
        }
    }

    /** 停止扫描。 */
    fun stopScan() {
        discovery.stop()
    }

    // ---------------------------------------------------------------- 连接与配对

    /**
     * 点击一个候选设备。
     *
     * 判定顺序：① 已在 `adb devices` 列表 → 直接进详情（无需重复握手）；
     * ② 有连接端口 → `adb connect` 直连，成功即跳详情（需求 B3，已配对设备 ≤2 秒）；
     * ③ 直连失败（未授权 / 未配对）→ 弹配对对话框，配对成功后引擎层自动回连（需求 C1 + C3）。
     *
     * 同一时刻只允许一个握手进行（[DeviceListUiState.connectingHost] 非空即拒绝新请求）。
     */
    fun onDeviceClick(svc: DiscoveredService) {
        if (_uiState.value.connectingHost != null) {
            _uiState.update { it.copy(message = TXT_BUSY) }
            return
        }
        val host = svc.host
        val connectPort: Int? = svc.connectPort
            ?: svc.port.takeIf { svc.capability == Capability.CONNECT }

        viewModelScope.launch {
            _uiState.update { it.copy(connectingHost = host, error = null) }

            // ① 已经在 adb devices 列表里（含 offline / unauthorized）→ 直接进详情页
            val currentSerial: String? = findSerialByHost(host)
            if (currentSerial != null) {
                _uiState.update {
                    it.copy(
                        connectingHost = null,
                        message = TXT_CONNECT_OK,
                        navigateTo = currentSerial,
                    )
                }
                return@launch
            }
            runCatching { repo.refresh() }
            val refreshedSerial: String? = findSerialByHost(host)
            if (refreshedSerial != null) {
                _uiState.update {
                    it.copy(
                        connectingHost = null,
                        message = TXT_CONNECT_OK,
                        navigateTo = refreshedSerial,
                    )
                }
                return@launch
            }

            // ② 有连接端口 → 直接 `adb connect`（已配对设备应当 ≤2 秒完成，需求 B3）。
            //    DeviceRepository.connect 内部已按输出文本判定成败并自动 refresh()，
            //    「连不上」会以 Result.failure 返回中文人话，可以放心直接展示。
            if (connectPort != null) {
                val serial = "$host:$connectPort"
                val result: Result<*> = runCatching { repo.connect(host, connectPort) }
                    .getOrElse { Result.failure(it) }
                if (result.isSuccess) {
                    // DeviceRepository.connect 内部已 refresh；优先取 adb 报告的真 serial
                    val realSerial: String = findSerialByHost(host) ?: serial
                    _uiState.update {
                        it.copy(
                            connectingHost = null,
                            message = "$TXT_CONNECT_OK $realSerial",
                            navigateTo = realSerial,
                        )
                    }
                    return@launch
                }
                Logx.w(TAG, "直连失败 $serial：${result.exceptionOrNull()?.message}")
            }

            // ③ 直连失败（未授权 / 未配对）→ 弹配对对话框，配对成功后自动回连（需求 C1 + C3）
            openPairDialog(svc)
        }
    }

    /**
     * 弹出配对对话框。
     *
     * 必须**每次**都调用 [PairingManager.setConnectPort]：
     * A 的实现里 `cancel()` / `reset()` 会清空 pendingConnectPort，
     * 只在页面初始化时设一次会导致后续配对丢失回连端口。
     */
    private fun openPairDialog(svc: DiscoveredService) {
        runCatching { pairing.setConnectPort(svc.connectPort) }
            .onFailure { error -> Logx.w(TAG, "设置回连端口失败：${error.message}") }
        _uiState.update {
            it.copy(
                connectingHost = null,
                pairTarget = svc,
                message = TXT_PAIR_REQUIRED,
            )
        }
    }

    /**
     * 在已连接设备里按 **host 段精确匹配** 反查 adb 报告的真实 serial。
     *
     * 委托给 [DeviceRepository.serialForHost]，不在 UI 层再写一份匹配逻辑：
     * 「按 IP 反查 serial」的唯一正确实现收敛在引擎层，避免上层各写一份、
     * 其中某份退化成 `serial.contains(host)` 子串匹配。
     *
     * ⚠️ 子串匹配的后果：`"192.168.1.50:41234".contains("192.168.1.5")` 为 **true**，
     * 会把 5 号机误判成 50 号机，详情页就会打开另一台设备的数据
     * （需求 D7 明确要求「多设备同时连接不串数据」）；
     * `192.168.1.1` 与 `192.168.1.10` / `192.168.1.100` 同理，在 /24 网段内很常见。
     */
    private fun findSerialByHost(host: String): String? = repo.serialForHost(host)

    /**
     * 执行配对：`adb pair <host>:<pairPort> <code>`。
     *
     * @param port **配对端口**（不是连接端口）。连接端口已由 [openPairDialog] 通过
     *             [PairingManager.setConnectPort] 预先注入，配对成功后引擎层会自动
     *             `adb connect` 回连，只有回连成功才 emit `State.Success`（需求 C3）。
     */
    fun onPair(host: String, port: Int, code: String) {
        val trimmed = code.trim()
        if (trimmed.length != 6 || trimmed.any { !it.isDigit() }) {
            _uiState.update { it.copy(error = TXT_CODE_LEN) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(connectingHost = host, error = null) }

            // 防御：确保回连端口已注入（对话框可能由外部直接触发 onPair 而没走 openPairDialog）
            val target = _uiState.value.pairTarget
            if (target != null && target.host == host) {
                runCatching { pairing.setConnectPort(target.connectPort) }
                    .onFailure { error -> Logx.w(TAG, "设置回连端口失败：${error.message}") }
            }

            val result: Result<*> = runCatching { pairing.pair(host, port, trimmed) }
                .getOrElse { Result.failure(it) }

            if (result.isFailure) {
                val raw = result.exceptionOrNull()?.message
                _uiState.update {
                    it.copy(
                        connectingHost = null,
                        error = if (raw.isNullOrBlank()) TXT_ERR_UNKNOWN else raw,
                    )
                }
            }
            // 成功路径交给 PairingManager.State.Success 统一处理（含自动回连与跳转）
        }
    }

    /**
     * 关闭配对对话框。
     * 必须调用 [PairingManager.reset]，否则正在握手的协程与 adb 子进程不会被释放（需求 C5）。
     */
    fun dismissPairDialog() {
        pairing.reset()
        _uiState.update { it.copy(pairTarget = null, connectingHost = null) }
    }

    /** 手动输入 `IP:端口` 直连，绕过 mDNS（组播被过滤时的刚需）。 */
    fun onManualAdd(hostPort: String) {
        val input = hostPort.trim().replace("：", ":")
        val splitAt = input.lastIndexOf(':')
        if (splitAt <= 0 || splitAt == input.length - 1) {
            _uiState.update { it.copy(error = TXT_BAD_FORMAT) }
            return
        }
        val host = input.substring(0, splitAt).trim()
        val port = input.substring(splitAt + 1).trim().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            _uiState.update { it.copy(error = TXT_BAD_FORMAT) }
            return
        }

        val manual = DiscoveredService(
            instanceName = "manual-$host:$port",
            serviceType = "manual",
            host = host,
            port = port,
            source = Source.MANUAL,
            capability = Capability.CONNECT,
            pairPort = null,
            connectPort = port,
        )
        onDeviceClick(manual)
    }

    // ---------------------------------------------------------------- 历史设备

    /**
     * 点击「已知设备」分组里的一台 → 进详情页。
     *
     * - 状态为 [DeviceState.DEVICE] → 直接跳转，不重复握手；
     * - offline / unauthorized → 先重连一次（`adb connect <serial>`），成功才跳；
     *   仍失败就给可读错误，而不是跳到一个加载不出来的详情页。
     */
    fun onKnownDeviceClick(serial: String) {
        if (_uiState.value.connectingHost != null) {
            _uiState.update { it.copy(message = TXT_BUSY) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(connectingHost = serial, error = null) }

            if (repo.get(serial)?.state == DeviceState.DEVICE) {
                navigateTo(serial)
                return@launch
            }
            runCatching { repo.refresh() }
            if (repo.get(serial)?.state == DeviceState.DEVICE) {
                navigateTo(serial)
                return@launch
            }

            // offline / unauthorized：这条 serial 本身带着 host:port，可以直接重连
            val result: Result<*> = runCatching { repo.connectSerial(serial) }
                .getOrElse { Result.failure(it) }
            if (result.isSuccess) {
                val real: String = repo.serialForHost(serialToHost(serial)) ?: serial
                navigateTo(real, message = "$TXT_CONNECT_OK $real")
                return@launch
            }
            Logx.w(TAG, "已知设备重连失败 $serial：${result.exceptionOrNull()?.message}")
            _uiState.update { it.copy(connectingHost = null, error = TXT_KNOWN_GONE) }
        }
    }

    /**
     * 点击一条历史设备 → **直接跳转**（尽力连接，连不上才报错）。
     *
     * 三级判定：
     * ① adb 里已经有这台（按最后一次 serial，或按 host 反查）→ 直接进详情，不重复握手；
     * ② 有端口 → `adb connect host:port`，成功即跳转；
     * ③ 都失败 → 说明被控端当前不在线（关机 / 换了网段 / 关了无线调试），给可读错误。
     *
     * ★ 历史设备**一定已经配对过**，所以这里不会走配对流程 —— 走配对流程只会让用户
     *   在「设备根本没开机」时白白等一个永远弹不出来的配对码。
     */
    fun onHistoryClick(entry: HistoryDevice) {
        if (_uiState.value.connectingHost != null) {
            _uiState.update { it.copy(message = TXT_BUSY) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(connectingHost = entry.host, error = null) }

            // ① 已在 adb devices 列表里 → 直接进详情
            val cached: String? = repo.get(entry.serial)?.serial ?: repo.serialForHost(entry.host)
            if (cached != null) {
                navigateTo(cached)
                return@launch
            }
            runCatching { repo.refresh() }
            val refreshed: String? = repo.get(entry.serial)?.serial ?: repo.serialForHost(entry.host)
            if (refreshed != null) {
                navigateTo(refreshed)
                return@launch
            }

            // ② 有端口 → 直连（被控端重启后端口变了会失败，落到 ③）
            if (entry.isWireless) {
                val result: Result<*> = runCatching { repo.connect(entry.host, entry.port) }
                    .getOrElse { Result.failure(it) }
                if (result.isSuccess) {
                    val realSerial: String = repo.serialForHost(entry.host)
                        ?: "${entry.host}:${entry.port}"
                    navigateTo(realSerial, message = "$TXT_CONNECT_OK $realSerial")
                    return@launch
                }
                Logx.w(TAG, "历史设备直连失败 ${entry.hostPort}：${result.exceptionOrNull()?.message}")
            }

            // ③ 都失败
            _uiState.update { it.copy(connectingHost = null, error = TXT_HISTORY_GONE) }
        }
    }

    /** 删除一条历史记录（只删记录，不断开已建立的连接）。 */
    fun removeHistory(entry: HistoryDevice) {
        viewModelScope.launch {
            runCatching { history.remove(entry.host) }
                .onFailure { Logx.w(TAG, "删除历史失败：${it.message}") }
            _uiState.update { it.copy(message = TXT_HISTORY_DELETED) }
        }
    }

    /** 清空全部历史记录。 */
    fun clearHistory() {
        viewModelScope.launch {
            runCatching { history.clear() }
                .onFailure { Logx.w(TAG, "清空历史失败：${it.message}") }
            _uiState.update { it.copy(message = TXT_HISTORY_CLEARED) }
        }
    }

    // ---------------------------------------------------------------- 设备别名

    /**
     * 保存设备别名。
     *
     * @param key   别名主键（UI 侧用 `deviceAliasKey(serial)` / `HistoryDevice.host` 算好）
     * @param alias 别名原文；**trim 后为空则走清除路径**，不会存一个空串进去。
     */
    fun setDeviceAlias(key: String, alias: String?) {
        val value: String = alias?.trim().orEmpty()
        if (value.isEmpty()) {
            clearDeviceAlias(key)
            return
        }
        viewModelScope.launch {
            runCatching { aliases.set(key, value) }
                .onSuccess { _uiState.update { it.copy(message = TXT_ALIAS_SAVED) } }
                .onFailure { error ->
                    // ★ 取消不是失败：ViewModel 销毁 / 页面离开时协程被取消是正常流程，
                    //   不能把它当成「保存失败」弹红字。原样抛出，保持结构化并发。
                    if (error is CancellationException) {
                        throw error
                    }
                    Logx.w(TAG, "保存别名失败 $key：${error.message}")
                    _uiState.update { it.copy(error = TXT_ALIAS_FAILED) }
                }
        }
    }

    /** 移除某台设备的别名（只清名字，不动连接与历史记录）。 */
    fun clearDeviceAlias(key: String) {
        viewModelScope.launch {
            runCatching { aliases.remove(key) }
                .onSuccess { _uiState.update { it.copy(message = TXT_ALIAS_REMOVED) } }
                .onFailure { error ->
                    // 同上：取消不是失败，别弹「保存失败」
                    if (error is CancellationException) {
                        throw error
                    }
                    Logx.w(TAG, "移除别名失败 $key：${error.message}")
                    _uiState.update { it.copy(error = TXT_ALIAS_FAILED) }
                }
        }
    }

    /** 跳转详情页；[message] 为空则不弹提示。 */
    private fun navigateTo(serial: String, message: String = TXT_CONNECT_OK) {
        _uiState.update {
            it.copy(connectingHost = null, message = message, navigateTo = serial)
        }
    }

    // ---------------------------------------------------------------- 状态清理

    /** 清除错误与一次性提示。 */
    fun clearError() {
        _uiState.update { it.copy(error = null, message = null) }
    }

    /** 消费导航事件（避免重组时重复跳转）。 */
    fun consumeNavigation() {
        _uiState.update { it.copy(navigateTo = null) }
    }

    /** 断开一台已连接设备。 */
    fun disconnect(serial: String) {
        viewModelScope.launch {
            val result: Result<*> = runCatching { repo.disconnect(serial) }
                .getOrElse { Result.failure(it) }
            if (result.isSuccess) {
                repo.refresh()
                _uiState.update { it.copy(message = "已断开 $serial") }
            } else {
                _uiState.update {
                    it.copy(error = result.exceptionOrNull()?.message ?: "断开失败")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        discovery.stop()
    }

    // ---------------------------------------------------------------- 内部

    /**
     * PairError → 中文文案。用 `reason.name` 匹配，**不 import PairError 类型**，
     * 以免与 A 的实际包名耦合。
     */
    private fun pairErrorText(code: String, detail: String?): String {
        val suffix = if (detail.isNullOrBlank()) "" else "（$detail）"
        return when (code) {
            "CODE_EXPIRED" -> TXT_ERR_EXPIRED + suffix
            "WRONG_CODE" -> TXT_ERR_WRONG + suffix
            "PORT_NOT_PAIRING" -> TXT_ERR_PORT + suffix
            "HOST_UNREACHABLE" -> TXT_ERR_UNREACHABLE + suffix
            "TIMEOUT" -> TXT_ERR_TIMEOUT + suffix
            "UNSUPPORTED_ROM" -> TXT_ERR_ROM + suffix
            "UNKNOWN" -> "$TXT_ERR_UNKNOWN$suffix"
            else -> "$TXT_ERR_UNKNOWN：$code$suffix"
        }
    }
}
