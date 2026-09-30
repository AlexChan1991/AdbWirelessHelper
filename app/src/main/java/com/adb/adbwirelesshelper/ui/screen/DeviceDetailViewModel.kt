package com.adb.adbwirelesshelper.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adb.adbwirelesshelper.data.deviceinfo.DeviceInfoCollector
import com.adb.adbwirelesshelper.data.repository.DeviceRepository
import com.adb.adbwirelesshelper.data.settings.AppSettings
import com.adb.adbwirelesshelper.domain.model.AdbDevice
import com.adb.adbwirelesshelper.domain.model.DeviceInfo
import com.adb.adbwirelesshelper.util.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "DeviceDetailViewModel"

/** 轻量轮询的默认间隔（AppSettings 未就绪时的兜底值）。 */
private const val DEFAULT_POLL_MS: Long = 1_500L

/** 含 Top 进程的重量采集间隔（Top 进程降频到 3 秒，避免每轮全量 dumpsys 发热）。 */
private const val HEAVY_POLL_MS: Long = 3_000L

/** 轮询间隔下限，防止设置页被填成 0 导致打满 CPU。 */
private const val MIN_POLL_MS: Long = 500L

/**
 * 设备详情页 ViewModel。
 *
 * 起两个错开的轮询循环：
 * - 轻量循环：`pollIntervalMs`（默认 1.5s），`withHeavy=false`；
 * - 重量循环：3s，`withHeavy=true`（含 Top 进程）。
 *
 * 页面进入后台时调用 [pause]，回到前台调用 [resume]。
 */
class DeviceDetailViewModel(
    private val repo: DeviceRepository,
    private val collector: DeviceInfoCollector,
    private val settings: AppSettings,
) : ViewModel() {

    private val _info: MutableStateFlow<DeviceInfo?> = MutableStateFlow(null)

    /** 最近一次采集到的设备状态；未采集到时为 null。 */
    val info: StateFlow<DeviceInfo?> = _info.asStateFlow()

    private val _device: MutableStateFlow<AdbDevice?> = MutableStateFlow(null)

    /** 当前设备在 `adb devices -l` 中的条目（含连接状态）。 */
    val device: StateFlow<AdbDevice?> = _device.asStateFlow()

    private val _message: MutableStateFlow<String?> = MutableStateFlow(null)

    /** 一次性提示（Snackbar）。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _refreshing: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** 手动刷新中。 */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _pollIntervalMs: MutableStateFlow<Long> = MutableStateFlow(DEFAULT_POLL_MS)

    /** 当前生效的轻量轮询间隔。 */
    val pollIntervalMs: StateFlow<Long> = _pollIntervalMs.asStateFlow()

    @Volatile
    private var paused: Boolean = false

    private var boundSerial: String? = null
    private var lightJob: Job? = null
    private var heavyJob: Job? = null

    init {
        // 跟随设置页的轮询间隔
        viewModelScope.launch {
            runCatching {
                settings.pollIntervalMs.collect { value ->
                    _pollIntervalMs.value = value.coerceAtLeast(MIN_POLL_MS)
                }
            }.onFailure { error ->
                Logx.w(TAG, "读取轮询间隔失败，使用默认值：${error.message}")
                _pollIntervalMs.value = DEFAULT_POLL_MS
            }
        }
        // 跟随已连接设备列表
        viewModelScope.launch {
            repo.devices.collect { list ->
                val serial = boundSerial ?: return@collect
                val matched = list.firstOrNull { it.serial == serial }
                _device.value = matched ?: runCatching { repo.get(serial) }.getOrNull()
            }
        }
    }

    /**
     * 绑定设备序列号。**幂等**：切换 serial 时才重启轮询。
     */
    fun bind(serial: String) {
        if (serial.isBlank()) return
        if (boundSerial == serial && lightJob?.isActive == true) return
        boundSerial = serial
        paused = false
        _info.value = null
        _device.value = runCatching { repo.get(serial) }.getOrNull()
        startLoops()
    }

    /** 立即采集一次（含 Top 进程）。 */
    fun refresh() {
        val serial = boundSerial ?: return
        viewModelScope.launch {
            _refreshing.value = true
            runCatching {
                collector.collect(serial, withHeavy = true)
            }.onSuccess { collected ->
                _info.value = collected
            }.onFailure { error ->
                Logx.w(TAG, "手动刷新失败：${error.message}")
                _message.value = "刷新失败：${error.message ?: "未知错误"}"
            }
            runCatching { repo.refresh() }
            _device.value = runCatching { repo.get(serial) }.getOrNull()
            _refreshing.value = false
        }
    }

    /** 页面进入后台：暂停轮询。 */
    fun pause() {
        paused = true
    }

    /** 页面回到前台：恢复轮询。 */
    fun resume() {
        paused = false
    }

    /** 断开当前设备连接。 */
    fun disconnect() {
        val serial = boundSerial ?: return
        viewModelScope.launch {
            val result: Result<*> = runCatching { repo.disconnect(serial) }
                .getOrElse { Result.failure(it) }
            if (result.isSuccess) {
                runCatching { repo.refresh() }
                _message.value = "已断开 $serial"
            } else {
                _message.value = "断开失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
            }
        }
    }

    /** 消费一次性提示。 */
    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        super.onCleared()
        lightJob?.cancel()
        heavyJob?.cancel()
        lightJob = null
        heavyJob = null
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 轻量采集结果回填前，把上一轮的 Top 进程列表补回去（修 Top 进程区块反复闪烁）。
     *
     * 成因：轻量循环（1.5s，`withHeavy = false`）不含 Top 进程，
     * `DeviceInfoCollector.collect` 在 `withHeavy = false` 时直接返回
     * `topProcesses = emptyList()`（DeviceInfoCollector.kt:97-101），于是
     * 「轻量结果清成空表 → 3s 后的重量结果再填回来」循环往复；UI 侧
     * `ResourceSection` 用 `if (info.topProcesses.isNotEmpty())` 决定是否渲染该区块，
     * 表现就是区块以 1.5s / 3s 的节奏出现 → 消失 → 出现。
     *
     * 这里**只给轻量结果做合并**：新结果为空时沿用上一轮的非空列表。
     * 重量循环（3s）与手动刷新仍原样写入，所以设备确实没有进程数据时，
     * 最多 3s 内区块就会正常消失，不会永久挂着上一次的旧数据。
     *
     * 可见性是 `internal` 而不是 `private`：逻辑本体在同文件的纯函数
     * [mergeTopProcessesInternal]（只依赖两个入参，可直接写 JVM 单测），
     * 这里只负责把 [_info] 的当前值喂进去，不必为了测它去构造采集器 / 启协程。
     *
     * @param fresh 本轮刚采集到的结果（轻量）。
     * @return 可直接写入 [_info] 的结果。
     */
    internal fun mergeTopProcesses(fresh: DeviceInfo): DeviceInfo =
        mergeTopProcessesInternal(previous = _info.value, fresh = fresh)

    private fun startLoops() {
        lightJob?.cancel()
        heavyJob?.cancel()
        val serial = boundSerial ?: return

        // 轻量循环：1.5s
        lightJob = viewModelScope.launch {
            delay(150L)
            while (isActive) {
                if (!paused) {
                    runCatching { collector.collect(serial, withHeavy = false) }
                        .onSuccess { collected ->
                            ensureActive()
                            if (boundSerial == serial) {
                                _info.value = mergeTopProcesses(collected)
                            }
                        }
                        .onFailure { error ->
                            // 取消不是失败：runCatching 会把 CancellationException 一并吞掉，
                            // 原样抛出让协程正常结束，避免打一条误导性的「采集失败」日志。
                            if (error is CancellationException) throw error
                            Logx.w(TAG, "轻量采集失败：${error.message}")
                        }
                }
                delay(_pollIntervalMs.value)
            }
        }

        // 重量循环：3s，含 Top 进程，与轻量循环错开避免撞在一起
        heavyJob = viewModelScope.launch {
            delay(900L)
            while (isActive) {
                if (!paused) {
                    runCatching { collector.collect(serial, withHeavy = true) }
                        .onSuccess { collected ->
                            ensureActive()
                            if (boundSerial == serial) {
                                _info.value = collected
                            }
                        }
                        .onFailure { error ->
                            // 同上：取消不是失败。
                            if (error is CancellationException) throw error
                            Logx.w(TAG, "完整采集失败：${error.message}")
                        }
                }
                delay(HEAVY_POLL_MS)
            }
        }
    }
}

// ---------------------------------------------------------------- 顶层纯函数

/**
 * [DeviceDetailViewModel.mergeTopProcesses] 的纯函数实现（修 Top 进程区块反复闪烁）。
 *
 * 抽成顶层纯函数的唯一理由：ViewModel 依赖 `Context` / DataStore / `viewModelScope`，
 * 纯 JVM 单测（app/src/test）构造不出来；而这段判空回填的逻辑本身只依赖两个入参，
 * 单独拎出来就能零成本钉死 4 个分支。调用行为与内联在 ViewModel 里时完全一致。
 *
 * @param previous 上一轮已写入 [_info] 的结果；`null` 表示还一次都没采集成功过。
 * @param fresh    本轮刚采集到的结果（轻量循环，`topProcesses` 恒为空）。
 * @return 可直接写入 [_info] 的结果。
 */
internal fun mergeTopProcessesInternal(previous: DeviceInfo?, fresh: DeviceInfo): DeviceInfo {
    if (fresh.topProcesses.isNotEmpty()) return fresh
    val prev: DeviceInfo = previous ?: return fresh
    if (prev.topProcesses.isEmpty()) return fresh
    return fresh.copy(topProcesses = prev.topProcesses)
}
