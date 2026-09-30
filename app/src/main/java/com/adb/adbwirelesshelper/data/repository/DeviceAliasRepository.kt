package com.adb.adbwirelesshelper.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.adb.adbwirelesshelper.util.Logx
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
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

/**
 * 独立 DataStore 文件，与 `connection_history`、`adbwifi_settings` 都分开。
 *
 * ★ 别名**不塞进** HistoryDevice：ConnectionHistoryRepository 的落盘格式是定长 5 字段
 *   （`FIELD_COUNT = 5`，`|` 分隔 + Base64），往里加一个字段就要处理旧数据的兼容；
 *   别名与历史的生命周期本就不同（历史会被「清空」，别名不应该跟着没），
 *   所以各自一个文件、各自一份格式。
 */
private val Context.aliasDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "device_alias"
)

// ---------------------------------------------------------------- 序列化
//
// 每条记录 2 个字段：`别名键|别名`，用 `|` 分隔；记录之间用 `\n` 分隔。
// 两个字段都走 URL-safe Base64，**保证不含分隔符** —— 否则用户把别名写成
// 「客厅|小米」或粘一段带换行的文字，整份别名表就解析错位了。
//
// 这两个函数是纯函数（不依赖 Android 运行时），因此可以直接在 JVM 单测里覆盖。

private const val ALIAS_FIELD_SEP: Char = '|'
private const val ALIAS_RECORD_SEP: Char = '\n'
private const val ALIAS_FIELD_COUNT: Int = 2

/**
 * 别名条数上限。
 *
 * 别名的条数 = 「你改过名的设备数」，正常使用到不了这个量级；设上限只是为了
 * 极端情况（脚本反复写入 / 日后接入别的来源）下不让它无限膨胀。
 * 触发时按 key 排序截断 —— 没有时间戳可依，按 key 至少是**确定性**的
 * （同一份输入永远得到同一份输出，不会每次落盘丢不同的条目）。
 */
private const val MAX_ALIASES: Int = 200

private const val TAG: String = "DeviceAlias"

/**
 * 别名表 → 落盘字符串。
 *
 * 空白 key 或空白别名**一律不写入**（空别名等于「没设过」，存下来只会让
 * decode 侧多一个要判空的分支）。
 *
 * @return 空表返回空串，不是 `null`
 */
internal fun encodeAliases(map: Map<String, String>): String =
    map.entries
        .asSequence()
        .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        .joinToString(separator = ALIAS_RECORD_SEP.toString()) { entry ->
            buildString {
                append(aliasB64(entry.key)).append(ALIAS_FIELD_SEP)
                append(aliasB64(entry.value))
            }
        }

/**
 * 反序列化。**逐条容错**：任何一条字段数不对 / Base64 损坏 / 解出来是空白就整条跳过，
 * 绝不因为一条脏数据把整份别名表丢掉（磁盘位翻转、用户手改、将来格式演进都要能扛住）。
 *
 * 同一 key 重复出现时保留**第一条**（与 `decodeHistory` 一致）。
 */
internal fun decodeAliases(raw: String): Map<String, String> {
    if (raw.isBlank()) {
        return emptyMap()
    }
    val out: LinkedHashMap<String, String> = LinkedHashMap()
    for (line in raw.split(ALIAS_RECORD_SEP)) {
        if (line.isBlank()) {
            continue
        }
        val f: List<String> = line.split(ALIAS_FIELD_SEP)
        if (f.size != ALIAS_FIELD_COUNT) {
            Logx.w(TAG, "别名记录字段数异常，已跳过该条")
            continue
        }
        val key: String = aliasUnb64(f[0]) ?: continue
        val value: String = aliasUnb64(f[1]) ?: continue
        if (key.isBlank() || value.isBlank()) {
            continue
        }
        if (out.containsKey(key)) {
            Logx.w(TAG, "别名 key 重复，保留第一条：$key")
            continue
        }
        out[key] = value
    }
    return out
}

private fun aliasB64(text: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

private fun aliasUnb64(text: String): String? = runCatching {
    String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
}.getOrNull()

// ---------------------------------------------------------------- 仓储

/**
 * 设备别名仓储（持久化到 DataStore，与连接历史**互相独立**）。
 *
 * - [aliases] 是唯一数据源，进程内热数据，UI 直接 `collect`；
 * - 主键由调用方用 `deviceAliasKey(serial)` 算好（内部委托 `serialToHost`，取**最后**
 *   一个冒号 —— IPv6 的 `[::1]:5555` 才能切成 `[::1]` 而不是 `[`），
 *   这样无线设备换端口后别名仍然命中；仓储本身不 care 键的语义；
 * - 写入由 [Mutex] 串行化，「读—改—写」不会被并发打断；
 * - 与历史一样，**只有真正变化时才落盘**。
 */
class DeviceAliasRepository(private val context: Context) {

    private val dataStore: DataStore<Preferences>
        get() = context.applicationContext.aliasDataStore

    /** 仓储自有的写入作用域：进程级单例，随进程存活，无需外部 start/stop。 */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock: Mutex = Mutex()

    /** 磁盘内容是否已读入内存。**只由 [lock] 保护**。 */
    private var loaded: Boolean = false

    private val _aliases: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap())

    /**
     * 别名表。key = `deviceAliasKey(serial)`，value = 别名。
     * 表中**不含**空别名（[set] 传空白等同删除）。
     */
    val aliases: StateFlow<Map<String, String>> = _aliases.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .catch { e ->
                    if (e is IOException) {
                        // ★ 这里**不能** emit(emptyPreferences())。
                        //   emit 一个空 Preferences 会让 collector 体跑一次，把 _aliases 置成
                        //   空表、loaded 置成 true；而 init 在构造时就起协程，用户长按改名
                        //   通常发生在几秒之后 —— init 先落地，于是后续 set() 一进
                        //   ensureLoadedLocked() 就在 `if (loaded) return true` 处直接放行，
                        //   在空表上加 1 条并整体写回，磁盘上其余条目被永久清掉
                        //   （ensureLoadedLocked 的 false 护栏会被这里提前置的 loaded 架空）。
                        //   不 emit → 该流直接结束、collector 体一次都不跑，loaded 保持 false，
                        //   装载交给 ensureLoadedLocked() 自己再读一次。
                        Logx.w(TAG, "DataStore 读取失败，不装载（避免空表覆盖磁盘）: ${e.message}")
                    } else {
                        throw e
                    }
                }
                .map { prefs -> decodeAliases(prefs[Keys.ENTRIES].orEmpty()) }
                .collect { map ->
                    // 与历史同理：只在「首次装载」时采用磁盘值。
                    // 装载之后内存态就是唯一权威（本类是唯一写入方），后续由 persistLocked 落盘。
                    lock.withLock {
                        if (!loaded) {
                            loaded = true
                            // 与 ensureLoadedLocked 保持同一套上限规则，两条装载路径不能分叉
                            _aliases.value = capAliases(map)
                        }
                    }
                }
        }
    }

    /**
     * 确保磁盘内容已读入内存。**必须在 [lock] 内调用**。
     *
     * 没有这一步，[set] 会在「还没读出来」的空表上做修改并整体写回，
     * 结果是**把之前攒的所有别名清成只剩当前这一条**。
     *
     * ★ 读失败时**不能**把内存置成空表、更**不能**置 `loaded = true`：
     *   磁盘上有 20 条、某次冷启动读盘抛异常（文件锁 / 权限 / 瞬时 IO）→
     *   若这里退化成空表并标记已装载，用户随后一次 `set()` 就会在空表上加 1 条并
     *   整体写回，**其余 19 条永久消失**，日志里只有一行 `Logx.w`。
     *   原则是：**读不出来就不许写**。返回 false 让 [set] / [remove] 直接放弃。
     *
     * ★ `runCatching` 会吞掉 [CancellationException]，这里显式放行：
     *   本函数跑在 `viewModelScope.launch` 里，ViewModel 销毁时的取消信号若被吃掉，
     *   流程会继续往下走并写盘，破坏结构化并发。
     *
     * @return 已装载（内存态可信）返回 true；读盘失败返回 false
     */
    private suspend fun ensureLoadedLocked(): Boolean {
        if (loaded) {
            return true
        }
        val prefs: Preferences? = runCatching { dataStore.data.first() }
            .onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
                Logx.w(TAG, "首次读取别名失败: ${error.message}")
            }
            .getOrNull()
        if (prefs == null) {
            return false
        }
        _aliases.value = capAliases(decodeAliases(prefs[Keys.ENTRIES].orEmpty()))
        loaded = true
        return true
    }

    /**
     * 设置别名。
     *
     * @param key   别名主键（调用方用 `deviceAliasKey(serial)` 算好）
     * @param alias 别名；**为 null 或空白时视为删除该键**（不存空串）
     */
    suspend fun set(key: String, alias: String?) {
        val k: String = key.trim()
        if (k.isEmpty()) {
            return
        }
        val v: String = alias?.trim().orEmpty()
        lock.withLock {
            if (!ensureLoadedLocked()) {
                // 读不出来就不许写：宁可这次改名不生效，也不能覆盖掉磁盘上的整份别名表
                Logx.w(TAG, "别名表尚未装载，放弃本次写入：$k")
                return
            }
            val next: LinkedHashMap<String, String> = LinkedHashMap(_aliases.value)
            if (v.isEmpty()) {
                // 空白 = 清除；本来就没有这条 → 无事发生，不写盘
                if (next.remove(k) == null) {
                    return
                }
            } else {
                if (next[k] == v) {
                    return
                }
                next[k] = v
            }
            // keep = k：容量截断时保住刚写的这条，不能让它被裁掉
            persistLocked(next, keep = k)
            Logx.i(TAG, "别名已更新：$k -> ${if (v.isEmpty()) "（已移除）" else v}")
        }
    }

    /** 删除某个别名。本来就没有 → 不写盘。 */
    suspend fun remove(key: String) {
        val k: String = key.trim()
        if (k.isEmpty()) {
            return
        }
        lock.withLock {
            if (!ensureLoadedLocked()) {
                Logx.w(TAG, "别名表尚未装载，放弃本次移除：$k")
                return
            }
            val next: LinkedHashMap<String, String> = LinkedHashMap(_aliases.value)
            if (next.remove(k) == null) {
                return
            }
            persistLocked(next)
            Logx.i(TAG, "别名已移除：$k")
        }
    }

    /**
     * 容量截断（[MAX_ALIASES]）。
     *
     * ★ `keep` 必保：写路径传入本次刚设置的 key。若按 key 排序后无条件 `take(200)`，
     *   用户刚设的那条可能排序靠后被当场丢掉，而上层 snackbar 已经说了「已保存」——
     *   与 [persistLocked] 的失败回滚是同一类「说了谎」的问题。
     *
     * 无 `keep` 时（装载磁盘数据）按 [LinkedHashMap] 的插入顺序 = 文件里的记录顺序淘汰，
     * 给定同一份输入永远得到同一份输出，不会每次落盘丢不同的条目。
     */
    private fun capAliases(map: Map<String, String>, keep: String? = null): Map<String, String> {
        if (map.size <= MAX_ALIASES) {
            return map
        }
        Logx.w(TAG, "别名条数超过上限 $MAX_ALIASES，丢弃最早写入的条目")
        val out: LinkedHashMap<String, String> = LinkedHashMap()
        // 本次刚写的这条排在最前，保证它一定落在截断后的窗口内
        val kept: String? = if (keep == null) null else map[keep]
        if (keep != null && kept != null) {
            out[keep] = kept
        }
        for (entry in map.entries) {
            if (out.size >= MAX_ALIASES) {
                break
            }
            if (entry.key == keep) {
                continue
            }
            out[entry.key] = entry.value
        }
        return out
    }

    /**
     * 必须在 [lock] 内调用：先更新内存态保证 UI 立即响应，再落盘。
     *
     * ★ 落盘失败必须**回滚内存态并向上抛**。只 `Logx.e` 的话，上层的
     *   `runCatching { set() }.onFailure` 永远走不到，「已保存」就是一句假话：
     *   用户看到别名当场生效、下次冷启动又没了。回滚保证 UI 不显示未落盘的数据，
     *   抛出保证上层能给出真实的失败提示。
     *
     * @param keep 容量截断时必须保住的 key（见 [capAliases]）
     */
    private suspend fun persistLocked(map: Map<String, String>, keep: String? = null) {
        val before: Map<String, String> = _aliases.value
        val capped: Map<String, String> = capAliases(map, keep)
        _aliases.value = capped
        // ★ 先取异常、再统一处理：不要在 onFailure 里 throw —— 那样回滚那行永远走不到，
        //   取消时 _aliases 会停在已改未落盘的 capped 上，内存与磁盘不一致。
        val failure: Throwable? = runCatching {
            dataStore.edit { prefs -> prefs[Keys.ENTRIES] = encodeAliases(capped) }
        }.exceptionOrNull()

        if (failure != null) {
            // 取消也要回滚：协程被取消不代表这次修改被采纳了
            _aliases.value = before
            if (failure is CancellationException) {
                // 取消不是失败，原样抛出（不包装成 IOException），让上层按取消处理
                throw failure
            }
            Logx.e(TAG, "DataStore 写入失败，已回滚内存态: ${failure.message}")
            throw IOException("设备别名写入失败", failure)
        }
    }

    private object Keys {
        val ENTRIES = stringPreferencesKey("alias_entries")
    }
}
