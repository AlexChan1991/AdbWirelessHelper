package com.adb.adbwirelesshelper.data.repository

import com.adb.adbwirelesshelper.data.adb.AdbClient
import com.adb.adbwirelesshelper.domain.model.AdbDevice
import com.adb.adbwirelesshelper.domain.model.DeviceState
import com.adb.adbwirelesshelper.domain.model.serialToHost
import com.adb.adbwirelesshelper.util.Logx
import com.adb.adbwirelesshelper.util.humanizeAdbError
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 已连接设备的内存态仓储（需求 D7：多设备不串数据）。
 *
 * - [devices] 为唯一数据源，UI 直接订阅；
 * - [startWatch] 幂等：重复调用不会叠加多个轮询协程；
 * - 断线检出依赖 `adb devices -l` 的状态列。
 *
 * @param adb     adb 命令门面
 * @param history 历史连接记录仓储；**可为 null**（单测 / 不关心历史的场景）。
 *                非 null 时，每次 [refresh] 拉到在线设备都会顺带记一笔历史，
 *                这样无论从哪条路径连上（扫描点击 / 手动添加 / 配对回连）都不会漏记。
 */
class DeviceRepository(
    private val adb: AdbClient,
    private val history: ConnectionHistoryRepository? = null
) {

    private val _devices: MutableStateFlow<List<AdbDevice>> = MutableStateFlow(emptyList())

    /** 当前已知设备列表（含 offline / unauthorized） */
    val devices: StateFlow<List<AdbDevice>> = _devices.asStateFlow()

    @Volatile
    private var watchJob: Job? = null

    /**
     * 拉取一次 `adb devices -l`。
     * 失败时保留上一次结果，只写日志，避免网络抖动导致 UI 闪空。
     */
    suspend fun refresh() {
        // adb.devices() 本身已返回 Result，不要再套一层 runCatching，
        // 否则会变成 Result<Result<List<AdbDevice>>>，解包时类型对不上。
        val result: Result<List<AdbDevice>> = adb.devices()
        if (result.isSuccess) {
            val list: List<AdbDevice> = result.getOrThrow()
            _devices.value = list
            Logx.d(TAG, "refresh: ${list.size} 台设备")
            // 顺带记一笔「曾经连接过」：放这里而不是放在 ViewModel 的每个成功分支，
            // 是为了覆盖全部连接路径（含配对成功后的自动回连、以及 App 启动前就已经连着的）。
            // 失败不影响设备列表本身，只写日志。
            // refresh() 自身是 suspend，直接挂起调用即可（DataStore 的落盘在它自己的
            // IO 调度器上，不会把调用方钉在 Main 线程）。
            runCatching { history?.recordOnline(list) }
                .onFailure { Logx.w(TAG, "写入连接历史失败: ${it.message}") }
        } else {
            Logx.w(TAG, "refresh 失败: ${result.exceptionOrNull()?.message}")
        }
    }

    /** 按 serial 取一台设备；不存在返回 null */
    fun get(serial: String): AdbDevice? = _devices.value.firstOrNull { it.serial == serial }

    /**
     * 按 **host（IP）** 精确反查设备，供「已知 IP、但端口可能已变」的场景使用
     * （被控端重启后 adb 报告的端口与 mDNS 快照不一致时）。
     *
     * ★ 必须按 `:` 切分后**精确比对 host 段**，不能用 `serial.contains(host)`：
     *   子串匹配会让 `192.168.1.5` 命中 `192.168.1.50`，导致多设备场景串数据（需求 D7）。
     *   USB serial（不含 `:`）整体作为比较值，不会与 IP 误匹配。
     *
     * @return 命中的设备；未命中返回 null
     */
    fun findByHost(host: String): AdbDevice? {
        if (host.isBlank()) {
            return null
        }
        // ★ 走 serialToHost 而不是 substringBefore(':')：IPv6 的 serial 是 `[::1]:5555`，
        //   取第一个冒号会得到 `[`，于是所有 IPv6 设备都能和 host `[` 匹配上 ——
        //   按 IP 反查串设备，比子串匹配还糟。
        return _devices.value.firstOrNull { serialToHost(it.serial) == host }
    }

    /**
     * [findByHost] 的便捷版：直接返回 adb 报告的真实 serial。
     * 调用方拿到它再去导航/执行命令，比自己拼 `host:port` 可靠。
     */
    fun serialForHost(host: String): String? = findByHost(host)?.serial

    /** 只返回状态为 DEVICE 的设备（真正可交互的） */
    fun onlineDevices(): List<AdbDevice> =
        _devices.value.filter { it.state == DeviceState.DEVICE }

    /** 已连接设备数量（供通知文案使用） */
    fun onlineCount(): Int = onlineDevices().size

    /**
     * 断开指定设备。成功后立刻刷新一次列表。
     */
    suspend fun disconnect(serial: String): Result<String> {
        val result = adb.disconnect(serial)
        if (result.isSuccess) {
            _devices.value = _devices.value.filterNot { it.serial == serial }
            runCatching { refresh() }
            Logx.i(TAG, "已断开 $serial")
        } else {
            Logx.w(TAG, "断开失败 $serial : ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    /**
     * 发起无线连接（需求 B3：点击条目即连）。
     *
     * ★ 必须用**连接端口**，不是配对端口。
     *
     * 说明：`adb connect` 的进程退出码在「连不上」时同样是 0，因此这里**以输出文本判定成败**：
     * 输出含 `connected to` / `already connected to` 才算成功（并自动 refresh 一次），
     * 否则视为失败并用 [humanizeAdbError] 转成中文人话返回，与 [disconnect] 的错误风格一致。
     *
     * @return 成功透传 adb 原文；失败为带中文文案的 Result.failure
     */
    suspend fun connect(host: String, port: Int): Result<String> {
        if (host.isBlank() || port <= 0 || port > 65535) {
            val msg = "连接参数非法：$host:$port"
            Logx.w(TAG, msg)
            return Result.failure(IllegalArgumentException(msg))
        }
        val result = adb.connect(host, port)
        val text = result.getOrDefault("").trim()
        if (result.isSuccess && isConnectedText(text)) {
            runCatching { refresh() }
            Logx.i(TAG, "已连接 $host:$port")
            return result
        }
        val raw = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
            ?: text.ifBlank { "无法连接到 $host:$port" }
        Logx.w(TAG, "连接失败 $host:$port : $raw")
        return Result.failure(IllegalStateException(humanizeAdbError(raw)))
    }

    /**
     * 兼容已有的 serial 形式（"host:port"，如 `192.168.1.5:37123`）。
     *
     * @return 非法格式返回 Result.failure(IllegalArgumentException("serial 格式非法: $serial"))
     */
    suspend fun connectSerial(serial: String): Result<String> {
        val value = serial.trim()
        val idx = value.lastIndexOf(':')
        val host = if (idx > 0) value.substring(0, idx) else ""
        val port = if (idx >= 0 && idx < value.length - 1) {
            value.substring(idx + 1).toIntOrNull() ?: -1
        } else {
            -1
        }
        if (host.isBlank() || port <= 0 || port > 65535) {
            Logx.w(TAG, "serial 格式非法: $serial")
            return Result.failure(IllegalArgumentException("serial 格式非法: $serial"))
        }
        return connect(host, port)
    }

    /** 判定 `adb connect` 输出是否表示已连上 */
    private fun isConnectedText(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("connected to") ||
            lower.contains("already connected") ||
            lower.contains("已连接")
    }

    /**
     * 启动轮询（幂等）。
     *
     * @param scope      宿主协程作用域（通常用 ViewModelScope 或 App 级 scope）
     * @param intervalMs 轮询间隔，默认 2 秒
     */
    fun startWatch(scope: CoroutineScope, intervalMs: Long = 2_000) {
        val current = watchJob
        if (current != null && current.isActive) {
            // 已在轮询中：只更新间隔说明，不叠加协程
            Logx.d(TAG, "startWatch 已在运行，忽略重复调用")
            return
        }
        watchJob = scope.launch(Dispatchers.IO) {
            Logx.i(TAG, "启动设备轮询，间隔 ${intervalMs}ms")
            while (isActive) {
                refresh()
                delay(intervalMs)
            }
        }
    }

    /** 停止轮询 */
    fun stopWatch() {
        watchJob?.cancel()
        watchJob = null
        Logx.i(TAG, "停止设备轮询")
    }

    /** 查询轮询是否在运行 */
    fun isWatching(): Boolean = watchJob?.isActive == true

    companion object {
        private const val TAG = "DeviceRepository"
    }
}
