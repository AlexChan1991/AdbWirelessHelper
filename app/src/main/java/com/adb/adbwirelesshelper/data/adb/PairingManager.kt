package com.adb.adbwirelesshelper.data.adb

import com.adb.adbwirelesshelper.util.Logx
import com.adb.adbwirelesshelper.util.humanizeAdbError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.Locale

/**
 * 配对失败原因分类（需求 C4：三类错误各自文案与重试入口）。
 */
enum class PairError {
    /** 配对码过期（超过约 60 秒） */
    CODE_EXPIRED,

    /** 配对码错误 */
    WRONG_CODE,

    /** 端口不是 pairing 端口 / 端口不可达 */
    PORT_NOT_PAIRING,

    /** 主机不可达 */
    HOST_UNREACHABLE,

    /** 超时（默认 10 秒） */
    TIMEOUT,

    /** ROM 不支持无线调试配对 */
    UNSUPPORTED_ROM,

    /** 其它 */
    UNKNOWN
}

/**
 * 纯函数：按 adb 输出关键词映射为 [PairError]。
 *
 * 判定顺序有意安排：先判「拒绝/不可达」类（更具体），再判认证失败，最后超时。
 */
fun classifyPairError(raw: String): PairError {
    val text = raw.lowercase(Locale.ROOT)
    if (text.isBlank()) {
        return PairError.UNKNOWN
    }
    return when {
        text.contains("cannot exec") || text.contains("permission denied") ||
            text.contains("not found") || text.contains("no such file") ||
            text.contains("unsupported") ->
            PairError.UNSUPPORTED_ROM

        text.contains("timed out") || text.contains("timeout") ||
            text.contains("no response") ->
            PairError.TIMEOUT

        text.contains("no route to host") || text.contains("unreachable") ||
            text.contains("network is unreachable") ->
            PairError.HOST_UNREACHABLE

        text.contains("connection refused") || text.contains("refused") ||
            text.contains("failed to connect") || text.contains("not a pairing") ||
            text.contains("closed") ->
            PairError.PORT_NOT_PAIRING

        text.contains("expired") || text.contains("too old") ||
            text.contains("已过期") ->
            PairError.CODE_EXPIRED

        text.contains("failed to authenticate") || text.contains("authentication failed") ||
            text.contains("wrong") || text.contains("bad code") ||
            text.contains("incorrect") || text.contains("invalid code") ->
            PairError.WRONG_CODE

        else -> PairError.UNKNOWN
    }
}

/**
 * 配对状态机（对应需求 C1–C5）。
 *
 * - 串行化：同一时刻只允许一次握手（[Mutex]）；
 * - 超时：默认 10 秒（需求 C5）；
 * - 取消：[cancel] 必须释放协程与底层 socket（adb 子进程会被强杀）。
 */
class PairingManager(private val adb: AdbClient) {

    /** 配对过程中的状态（UI 通过 StateFlow 观察） */
    sealed interface State {
        /** 空闲 */
        data object Idle : State

        /** 握手中 */
        data object Pairing : State

        /** 配对成功：给出可直接 connect 的地址与端口 */
        data class Success(val host: String, val connectPort: Int) : State

        /** 配对失败 */
        data class Failure(val reason: PairError, val detail: String) : State
    }

    private val _state: MutableStateFlow<State> = MutableStateFlow(State.Idle)

    /** 对外只读状态流，初始值 Idle */
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var activeJob: Job? = null

    /**
     * 待回连的**连接端口**（与配对端口是两个不同的端口，需求 C3：配对成功后自动回连）。
     *
     * UI 在弹配对对话框前用 [setConnectPort] 填入 mDNS 解析出的 connect 端口；
     * 为 null 时保持旧行为（只配对不连接）。
     */
    @Volatile
    private var pendingConnectPort: Int? = null

    /**
     * 设置配对成功后要自动回连的连接端口。
     *
     * @param port mDNS `_adb-tls-connect._tcp.` 解析出的连接端口；传 null 表示只配对不连接。
     */
    fun setConnectPort(port: Int?) {
        pendingConnectPort = port
        Logx.d(TAG, "已设置回连端口: $port")
    }

    /**
     * 当前是否已登记**真实的连接端口**。
     *
     * 用途：UI 在收到 [State.Success] 时用它判断 `connectPort` 是否可信。
     * - 返回 true：`Success.connectPort` 是 mDNS 解析出的连接端口（且已完成回连），
     *   可以安全拼成 `host:connectPort` 作为 serial；
     * - 返回 false：说明只发现了 `_adb-tls-pairing._tcp.`、没有 connect 服务，
     *   `Success.connectPort` 只是**配对端口的兜底值**，拼出来的 serial 与
     *   `adb devices` 里的真实 serial 不一致 —— 此时 UI 应先 `DeviceRepository.refresh()`
     *   再用 host 去 `repo.devices` 里反查真实 serial，并提示用户重新扫描获取连接端口。
     *
     * 注意：[cancel] / [reset] 会清空登记值，所以要在处理 Success 的当下调用。
     */
    fun hasKnownConnectPort(): Boolean = pendingConnectPort != null

    /**
     * 执行一次配对握手。
     *
     * @param host 被控端 IP
     * @param port **配对端口**（不是连接端口）
     * @param code 6 位配对码
     * @return 成功返回 adb 原始输出；失败返回带中文人话的 Result.failure
     *
     * 自动回连：若调用前通过 [setConnectPort] 设置了连接端口，配对成功后会立即对该端口执行
     * `adb connect`，只有回连成功才 emit [State.Success]（connectPort 为回连端口）；
     * 回连失败 emit [State.Failure]（reason 取自现有 [PairError]，不新增枚举值）。
     * 未设置连接端口时保持「只配对不连接」的旧行为。
     */
    suspend fun pair(host: String, port: Int, code: String): Result<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val self = coroutineContext[Job]
                activeJob = self
                _state.value = State.Pairing
                Logx.i(TAG, "开始配对 $host:$port")

                val outcome: Result<String> = try {
                    withTimeout(DEFAULT_TIMEOUT_MS) {
                        adb.pair(host, port, code)
                    }
                } catch (t: TimeoutCancellationException) {
                    Result.failure(
                        IllegalStateException("配对超时（${DEFAULT_TIMEOUT_MS}ms），配对码可能已失效")
                    )
                } catch (c: CancellationException) {
                    Result.failure(IllegalStateException("配对已取消"))
                }

                val final: Result<String> = outcome.fold(
                    onSuccess = { text ->
                        val combined = text.trim()
                        if (isSuccessText(combined)) {
                            // 配对成功：若 UI 已指定连接端口，立即自动回连（需求 C3）
                            val target = pendingConnectPort
                            if (target == null) {
                                // 旧行为：只配对不连接。
                                // 注意：此时 Success.connectPort 是配对端口的兜底值，
                                // UI 应配合 hasKnownConnectPort() 判断其可信度。
                                val fallback = extractPort(combined, port)
                                Logx.w(
                                    TAG,
                                    "配对成功但未登记连接端口，$host 的后续连接需重新扫描 " +
                                        "（Success.connectPort=$fallback 为配对端口兜底值）"
                                )
                                _state.value = State.Success(host, fallback)
                                outcome
                            } else {
                                val connectResult = adb.connect(host, target)
                                val connectText = connectResult.getOrDefault("").trim()
                                if (connectResult.isSuccess && isConnectedText(connectText)) {
                                    Logx.i(TAG, "配对后自动回连成功 $host:$target")
                                    _state.value = State.Success(host, target)
                                    outcome
                                } else {
                                    val raw = connectResult.exceptionOrNull()?.message
                                        ?.takeIf { it.isNotBlank() }
                                        ?: connectText.ifBlank { "无法连接到 $host:$target" }
                                    val reason = if (raw.isBlank()) {
                                        PairError.HOST_UNREACHABLE
                                    } else {
                                        classifyPairError(raw)
                                    }
                                    Logx.w(TAG, "配对成功但回连失败 $host:$target : $raw")
                                    _state.value =
                                        State.Failure(reason, humanizeAdbError(raw))
                                    Result.failure(IllegalStateException(humanizeAdbError(raw)))
                                }
                            }
                        } else {
                            val reason = classifyPairError(combined)
                            _state.value = State.Failure(reason, combined)
                            Result.failure(IllegalStateException(humanizeAdbError(combined)))
                        }
                    },
                    onFailure = { error ->
                        val msg = error.message.orEmpty().ifBlank { "配对失败" }
                        val reason = if (error is TimeoutCancellationException) {
                            PairError.TIMEOUT
                        } else {
                            classifyPairError(msg)
                        }
                        _state.value = State.Failure(reason, msg)
                        Result.failure(IllegalStateException(humanizeAdbError(msg)))
                    }
                )

                if (activeJob === self) {
                    activeJob = null
                }
                final
            }
        }

    /**
     * 取消配对。必须彻底释放协程与底层 adb 子进程（需求 C5：连续开关 20 次无句柄泄漏）。
     */
    fun cancel() {
        // 一并清掉待回连端口，避免下一次配对误用上一次的脏值
        pendingConnectPort = null
        val job = activeJob
        if (job != null) {
            activeJob = null
            job.cancel()
            Logx.i(TAG, "配对已取消，协程已释放")
        } else {
            _state.value = State.Idle
        }
    }

    /** 主动把状态复位为 Idle（例如 UI 关闭配对对话框时）。 */
    fun reset() {
        cancel()
        _state.value = State.Idle
    }

    /**
     * 判定 `adb connect` 输出是否表示已连上。
     * `adb connect` 连不上时退出码同样是 0，只能看文本。
     */
    private fun isConnectedText(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("connected to") ||
            lower.contains("already connected") ||
            lower.contains("已连接")
    }

    private fun isSuccessText(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("successfully paired") ||
            lower.contains("successfully") && lower.contains("pair") ||
            lower.startsWith("success")
    }

    /**
     * 从 `Successfully paired to 192.168.1.5:37123` 中解析端口。
     * 解析不到时退回传入的配对端口（调用方应改用 mDNS 的 connect 端口）。
     */
    private fun extractPort(text: String, fallback: Int): Int {
        val idx = text.lastIndexOf(':')
        if (idx < 0) {
            return fallback
        }
        val tail = text.substring(idx + 1).trim()
        val digits = tail.takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: fallback
    }

    companion object {
        private const val TAG = "PairingManager"

        /** 默认配对超时：10 秒（需求 C5） */
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}

/**
 * 指数退避重连辅助（需求 B5：1s/2s/4s/8s/16s，上限 30s，最多 5 次）。
 * 放在本文件内便于 ViewModel 直接复用。
 */
object Backoff {
    /** 返回第 attempt 次（从 0 开始）重试需要等待的时间；超过次数返回 null */
    fun delayFor(attempt: Int): Long? {
        if (attempt < 0 || attempt >= MAX_ATTEMPTS) {
            return null
        }
        val value = (BASE_MS shl attempt).coerceAtMost(MAX_MS)
        return value
    }

    /** 挂起等待第 attempt 次重试；返回 false 表示已超过最大次数 */
    suspend fun wait(attempt: Int): Boolean {
        val ms = delayFor(attempt) ?: return false
        delay(ms)
        return true
    }

    const val BASE_MS = 1_000L
    const val MAX_MS = 30_000L
    const val MAX_ATTEMPTS = 5
}
