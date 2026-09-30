package com.adb.adbwirelesshelper.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.adb.adbwirelesshelper.domain.model.AdbDevice
import com.adb.adbwirelesshelper.domain.model.DeviceState
import com.adb.adbwirelesshelper.domain.model.HistoryDevice
import com.adb.adbwirelesshelper.domain.model.serialToHost
import com.adb.adbwirelesshelper.util.Logx
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 独立 DataStore 文件，与 `adbwifi_settings` 分开，避免历史记录写坏设置。 */
private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "connection_history"
)

// ---------------------------------------------------------------- 序列化
//
// 每条记录 5 个字段，用 `|` 分隔；记录之间用 `\n` 分隔。
// 文本字段（host / serial / label）走 URL-safe Base64，**保证不含分隔符** ——
// 否则型号名里一个 `|` 或换行就能把整份记录解析错位。
//
// 这两个函数是纯函数（不依赖 Android 运行时），因此可以直接在 JVM 单测里覆盖。

private const val FIELD_SEP: Char = '|'
private const val RECORD_SEP: Char = '\n'
private const val FIELD_COUNT: Int = 5

/** 最多保留多少条历史。超出后按「最近连接」排序丢弃最旧的。 */
private const val MAX_ENTRIES: Int = 50

/** 同一设备的 `lastConnectedAt` 多久才刷新一次（避免 2 秒一次的轮询反复写盘）。 */
private const val TIME_BUMP_THRESHOLD_MS: Long = 60_000L

internal fun encodeHistory(list: List<HistoryDevice>): String =
    list.joinToString(separator = RECORD_SEP.toString()) { e ->
        buildString {
            append(b64(e.host)).append(FIELD_SEP)
            append(b64(e.serial)).append(FIELD_SEP)
            append(e.port).append(FIELD_SEP)
            append(b64(e.label.orEmpty())).append(FIELD_SEP)
            append(e.lastConnectedAt)
        }
    }

/**
 * 反序列化。**逐条容错**：任何一条字段数不对 / 数值越界 / Base64 损坏就整条跳过，
 * 绝不因为一条脏数据把整份历史丢掉（旧版本升级、用户手改、磁盘位翻转都要能扛住）。
 */
internal fun decodeHistory(raw: String): List<HistoryDevice> {
    if (raw.isBlank()) {
        return emptyList()
    }
    val out: ArrayList<HistoryDevice> = ArrayList()
    val seenHosts: HashSet<String> = HashSet()
    for (line in raw.split(RECORD_SEP)) {
        if (line.isBlank()) {
            continue
        }
        val f: List<String> = line.split(FIELD_SEP)
        if (f.size != FIELD_COUNT) {
            Logx.w(TAG, "历史记录字段数异常，已跳过该条")
            continue
        }
        val host: String = unb64(f[0]) ?: continue
        val serial: String = unb64(f[1]) ?: continue
        val port: Int = f[2].toIntOrNull() ?: continue
        val label: String? = unb64(f[3])?.ifBlank { null }
        val time: Long = f[4].toLongOrNull() ?: continue
        if (host.isBlank() || serial.isBlank() || port < 0 || port > 65535) {
            continue
        }
        if (!seenHosts.add(host)) {
            continue
        }
        out.add(HistoryDevice(host = host, port = port, serial = serial, label = label, lastConnectedAt = time))
    }
    out.sortByDescending { it.lastConnectedAt }
    return out.take(MAX_ENTRIES)
}

private fun b64(text: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

private fun unb64(text: String): String? = runCatching {
    String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
}.getOrNull()

private const val TAG: String = "ConnectionHistory"

// ---------------------------------------------------------------- 仓储

/**
 * 「曾经连接过的设备」历史仓储（持久化到 DataStore）。
 *
 * - [history] 为唯一数据源，按 `lastConnectedAt` **倒序**（最近连过的在最上面）；
 * - 写入由 [Mutex] 串行化：[recordOnline] 会被设备轮询（2 秒一次）反复调用，
 *   并发「读—改—写」会丢更新；
 * - 内容没有变化时**不落盘**（见 [TIME_BUMP_THRESHOLD_MS]），避免高频写 DataStore。
 */
class ConnectionHistoryRepository(private val context: Context) {

    private val dataStore: DataStore<Preferences>
        get() = context.applicationContext.historyDataStore

    /** 仓储自有的写入作用域：进程级单例，随进程存活，无需外部 start/stop。 */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock: Mutex = Mutex()

    /** 磁盘内容是否已读入内存。**只由 [lock] 保护**。 */
    private var loaded: Boolean = false

    /**
     * 被用户显式删除 / 清空的 host，**只由 [lock] 保护**。
     *
     * 存在的理由：设备只要还在线，[recordOnline] 每 2 秒就会把它重新写回历史，
     * 表现成「刚点完清空，那条又冒出来了」（而且时间戳还被刷新成「刚刚」）。
     * 抑制会一直生效到该设备**真正断连**（[recordOnline] 发现这次 incoming 里没有它，
     * 就解除抑制），这样以后重新连上仍会正常入历史 —— 抑制是临时的，不是永久拉黑。
     */
    private val suppressed: MutableSet<String> = LinkedHashSet()

    private val _history: MutableStateFlow<List<HistoryDevice>> = MutableStateFlow(emptyList())

    /** 历史设备列表（最近连接的在前） */
    val history: StateFlow<List<HistoryDevice>> = _history.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .catch { e ->
                    if (e is IOException) {
                        Logx.w(TAG, "DataStore 读取失败，按空历史处理: ${e.message}")
                        emit(emptyPreferences())
                    } else {
                        throw e
                    }
                }
                .map { prefs -> decodeHistory(prefs[Keys.ENTRIES].orEmpty()) }
                .collect { list ->
                    // 只在「首次装载」时采用磁盘值。
                    // ★ 不能无条件回写：App 冷启动时 DeviceRepository.refresh() 会立刻调用
                    //   recordOnline()，而 DataStore 的首帧读盘是异步的。若这里无条件覆盖，
                    //   读盘回来的旧值会把刚记下的那条冲掉。
                    //   装载之后内存态就是唯一权威（本类自己就是唯一写入方），后续落盘由
                    //   persistLocked 负责。
                    lock.withLock {
                        if (!loaded) {
                            loaded = true
                            _history.value = list
                        }
                    }
                }
        }
    }

    /**
     * 确保磁盘内容已读入内存。**必须在 [lock] 内调用**。
     *
     * 没有这一步，[recordOnline] 会在「历史还没读出来」的空列表上做合并并整体写回，
     * 结果是**把之前攒的所有历史记录清成只剩当前这一条**。
     */
    private suspend fun ensureLoadedLocked() {
        if (loaded) {
            return
        }
        val prefs: Preferences? = runCatching { dataStore.data.first() }
            .onFailure { Logx.w(TAG, "首次读取历史失败: ${it.message}") }
            .getOrNull()
        _history.value = decodeHistory(prefs?.get(Keys.ENTRIES).orEmpty())
        loaded = true
    }

    /**
     * 把当前**在线**的设备并入历史。
     *
     * 由 [DeviceRepository.refresh] 在每次 `adb devices -l` 之后调用，这样任何连接路径
     * （扫描点击 / 手动添加 / 配对成功后自动回连 / 进程外已连上的）都会被记一笔。
     *
     * @param devices 当前 adb 报告的全部设备（内部只取 [DeviceState.DEVICE]）
     */
    suspend fun recordOnline(devices: List<AdbDevice>) {
        val now: Long = System.currentTimeMillis()
        val incoming: List<HistoryDevice> = devices
            .asSequence()
            .filter { it.state == DeviceState.DEVICE }
            .map { it.toHistoryEntry() }
            .filter { it.host.isNotBlank() }
            .toList()
        if (incoming.isEmpty()) {
            return
        }

        lock.withLock {
            ensureLoadedLocked()

            // 已经断连的设备解除抑制：抑制只在「它还在线」期间有意义，
            // 断连后再连上应该重新进历史，否则删一次就永久记不回来了。
            if (suppressed.isNotEmpty()) {
                suppressed.removeAll { host -> incoming.none { it.host == host } }
            }

            val merged: MutableList<HistoryDevice> = _history.value.toMutableList()
            var changed: Boolean = false
            for (item in incoming) {
                // 用户刚删掉 / 刚清空过的设备，在它断连之前不再重新记录
                if (item.host in suppressed) {
                    continue
                }
                val idx: Int = merged.indexOfFirst { it.host == item.host }
                if (idx < 0) {
                    merged.add(item.copy(lastConnectedAt = now))
                    changed = true
                    continue
                }
                val old: HistoryDevice = merged[idx]
                // 时间戳每 60s 才刷新一次：否则 2 秒一轮的轮询会让列表每次都「变了」，
                // 进而每次都写一次 DataStore。
                val bump: Boolean = now - old.lastConnectedAt >= TIME_BUMP_THRESHOLD_MS
                val next: HistoryDevice = old.copy(
                    port = item.port,
                    serial = item.serial,
                    label = item.label ?: old.label,
                    lastConnectedAt = if (bump) now else old.lastConnectedAt
                )
                if (next != old) {
                    merged[idx] = next
                    changed = true
                }
            }
            if (!changed) {
                return
            }
            persistLocked(merged.sortedByDescending { it.lastConnectedAt }.take(MAX_ENTRIES))
        }
    }

    /**
     * 删除一条（按 [host]）。
     *
     * 只删记录，**不断开已建立的连接**。若这台设备此刻还在线，[recordOnline] 会在 2 秒内
     * 把它写回来 —— 所以这里同时把它记入 [suppressed]，让「删掉」这件事真的生效到断连为止。
     */
    suspend fun remove(host: String) {
        if (host.isBlank()) {
            return
        }
        lock.withLock {
            ensureLoadedLocked()
            val next: List<HistoryDevice> = _history.value.filterNot { it.host == host }
            if (next.size == _history.value.size) {
                return
            }
            suppressed.add(host)
            persistLocked(next)
            Logx.i(TAG, "已删除历史记录 $host")
        }
    }

    /** 清空全部历史。仍在线的设备会被抑制到断连为止，避免立刻被重新写回。 */
    suspend fun clear() {
        lock.withLock {
            ensureLoadedLocked()
            if (_history.value.isEmpty()) {
                return
            }
            suppressed.addAll(_history.value.map { it.host })
            persistLocked(emptyList())
            Logx.i(TAG, "已清空历史记录")
        }
    }

    /** 必须在 [lock] 内调用：先更新内存态保证 UI 立即响应，再落盘。 */
    private suspend fun persistLocked(list: List<HistoryDevice>) {
        _history.value = list
        runCatching {
            dataStore.edit { prefs -> prefs[Keys.ENTRIES] = encodeHistory(list) }
        }.onFailure {
            Logx.e(TAG, "DataStore 写入失败: ${it.message}")
        }
    }

    private object Keys {
        val ENTRIES = stringPreferencesKey("history_entries")
    }
}

/**
 * `adb devices -l` 的一条结果 → 历史条目（[HistoryDevice.host] 由 [serialToHost] 统一切分）。
 *
 * ★ 这里**必须**走 [serialToHost] 而不是自己再切一次：host 就是历史记录的主键，
 *   而别名表的主键是 `deviceAliasKey(serial)` —— 两者只有共用同一个函数，
 *   「已知设备里设的别名在历史列表里也生效」才成立（IPv6 下两种切法结果不同）。
 *
 * ⚠️ 可见性为什么是 `internal` 而不是 `private`：为了让 JVM 单测能**交叉验证**
 *   「`deviceAliasKey(serial)` == 本函数切出来的 host」，而不是两边各断言一遍相同字面量
 *   （那样等于没测）。本工程是单模块 app、无同名包级函数、test 源集可访问 internal，
 *   放宽可见性没有实际风险。改动本函数时请留意 DeviceAliasRepositoryTest 依赖它。
 */
internal fun AdbDevice.toHistoryEntry(): HistoryDevice {
    val host: String = serialToHost(serial)
    // 端口 = host 之后剩下的那一段；USB（无冒号）或 serial 以 ':' 结尾 → 记 0
    val tail: String = serial.substring(host.length)
    val port: Int = if (tail.startsWith(":")) {
        tail.removePrefix(":").toIntOrNull() ?: 0
    } else {
        0
    }
    val name: String? = model?.takeIf { it.isNotBlank() }
        ?: product?.takeIf { it.isNotBlank() }
        ?: deviceCode?.takeIf { it.isNotBlank() }
    return HistoryDevice(host = host, port = port, serial = serial, label = name)
}
