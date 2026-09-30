package com.adb.adbwirelesshelper.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adb.adbwirelesshelper.data.file.FileRepository
import com.adb.adbwirelesshelper.data.media.RemoteFileStream
import com.adb.adbwirelesshelper.data.repository.DeviceRepository
import com.adb.adbwirelesshelper.domain.model.DeviceState
import com.adb.adbwirelesshelper.domain.model.FileEntry
import com.adb.adbwirelesshelper.domain.model.FileKind
import com.adb.adbwirelesshelper.domain.model.FileSortDir
import com.adb.adbwirelesshelper.domain.model.FileSortKey
import com.adb.adbwirelesshelper.domain.model.StorageInfo
import com.adb.adbwirelesshelper.util.Logx
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** 面包屑的一级：显示名 + 绝对路径 */
data class Crumb(val label: String, val path: String)

/**
 * 文件管理页的 ViewModel（规格 §4.6.1）。
 *
 * 职责边界：
 * - 只负责「当前目录的数据 + 交互状态」，不持有任何 Android UI 类型；
 * - 所有写操作（新建 / 重命名 / 删除 / 复制 / 移动）都在这里发命令，
 *   UI 只负责弹确认框并把结果传进来；
 * - **目录永远排在文件之前**，组内再按所选键排序（Android 文件管理器通行做法）。
 *
 * @param repo    被控端文件系统仓储
 * @param devices 设备仓储：取展示名，以及**掉线探测**（列目录失败不等于掉线，
 *                也可能是目录权限问题，必须回查 `adb devices` 的状态列才能判定）
 */
class FileBrowserViewModel(
    private val repo: FileRepository,
    private val devices: DeviceRepository,
) : ViewModel() {

    private var serial: String = ""

    private val _path = MutableStateFlow(FileRepository.DEFAULT_ROOT)
    val path: StateFlow<String> = _path

    /** 原始列表（**含**隐藏文件、未经排序与搜索过滤） */
    private val _rawEntries = MutableStateFlow<List<FileEntry>>(emptyList())

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _sortKey = MutableStateFlow(FileSortKey.NAME)
    val sortKey: StateFlow<FileSortKey> = _sortKey

    private val _sortDir = MutableStateFlow(FileSortDir.ASC)
    val sortDir: StateFlow<FileSortDir> = _sortDir

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden

    private val _selectMode = MutableStateFlow(false)
    val selectMode: StateFlow<Boolean> = _selectMode

    /** 多选中的条目**绝对路径**集合 */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected

    private val _storages = MutableStateFlow<List<StorageInfo>>(emptyList())
    val storages: StateFlow<List<StorageInfo>> = _storages

    /** 存储根路径集合：用于判断「当前是否在根目录」以及面包屑的起点 */
    private val _rootPaths = MutableStateFlow<Set<String>>(emptySet())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** 一次性提示（由 UI 消费后清空） */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    /** 被控端展示名：型号优先，拿不到就退回 serial（顶栏副标题用） */
    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName

    /**
     * 与设备**当前**的 ADB 连接是否还在。
     *
     * ⚠️ 判定依据必须是「探测」而不是「列目录失败」：目录读不出来有相当比例是权限问题
     * （Android 11+ 的 `/sdcard/Android/data`、未授权等），此时设备其实还连着，
     * 把它标成掉线会让「重新加载」按钮永远无效。
     */
    private val _connected = MutableStateFlow(true)
    val connected: StateFlow<Boolean> = _connected

    /**
     * 目录 → 子项数量，供列表副行显示「N 项」。
     *
     * 只在列目录成功后**后台**统计前 [MAX_COUNTED_DIRS] 个目录，失败就留空
     * （UI 侧退回到「文件夹」文案），绝不阻塞列表渲染。
     */
    private val _dirCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val dirCounts: StateFlow<Map<String, Int>> = _dirCounts

    /** 「复制到 / 移动到」候选目录的**总数**（超过 [MAX_TARGET_DIRS] 时 UI 要提示被截断） */
    private val _totalTargetCount = MutableStateFlow(0)
    val totalTargetCount: StateFlow<Int> = _totalTargetCount

    /**
     * 最终展示给 UI 的列表：过滤隐藏项 → 搜索 → 排序。
     *
     * 隐藏项在**这一层**过滤而不是请求时过滤，
     * 这样切换「显示隐藏文件」开关不需要重新发 adb 命令。
     */
    val entries: StateFlow<List<FileEntry>> = combine(
        _rawEntries, _showHidden, _query, _sortKey, _sortDir
    ) { raw, showHidden, query, key, dir ->
        val filtered: List<FileEntry> = raw.filter { entry ->
            (showHidden || !entry.name.startsWith(".")) &&
                (query.isBlank() || entry.name.contains(query, ignoreCase = true))
        }
        filtered.sortedWith(comparator(key, dir))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前是否在某个存储根（决定「存储概览」是否显示，规格 §4.6.1） */
    val atRoot: StateFlow<Boolean> =
        combine(_path, _rootPaths) { p, roots -> roots.isEmpty() || p in roots }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 面包屑：从存储根到当前目录 */
    val crumbs: StateFlow<List<Crumb>> =
        combine(_path, _rootPaths, _storages) { p, roots, storages ->
            buildCrumbs(p, roots, storages)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var loadJob: Job? = null
    private var countJob: Job? = null

    // ---------------------------------------------------------------- 绑定 / 加载

    /** 绑定设备并加载默认存储根 */
    fun bind(serial: String) {
        val switched: Boolean = this.serial != serial
        this.serial = serial
        // 展示名与「是否掉线」都走 devices，先拉一次：
        // refresh 失败（例如 adb 二进制刚被回收）不影响页面，退回 serial 即可。
        viewModelScope.launch {
            runCatching { devices.refresh() }
            _deviceName.value = devices.get(serial)?.model?.takeIf { it.isNotBlank() } ?: serial
        }
        if (!switched && _rawEntries.value.isNotEmpty()) return
        loadStorages()
        load(FileRepository.DEFAULT_ROOT)
    }

    private fun loadStorages() {
        viewModelScope.launch {
            repo.storages(serial)
                .onSuccess { list ->
                    _storages.value = list
                    _rootPaths.value = list.map { it.path }.toSet()
                }
                .onFailure { error ->
                    Logx.w(TAG, "读取存储列表失败：${error.message}")
                    // 拿不到存储列表也要能进默认根目录，不能把页面卡死
                    _rootPaths.value = setOf(FileRepository.DEFAULT_ROOT)
                }
        }
    }

    /** 载入指定目录 */
    fun load(path: String) {
        loadJob?.cancel()
        exitSelect()
        _path.value = path
        _error.value = null
        loadJob = viewModelScope.launch {
            _loading.value = true
            val result: Result<List<FileEntry>> = repo.listDir(serial, path)
            result
                .onSuccess { list ->
                    _rawEntries.value = list
                    _connected.value = true
                    countSubDirs(list)
                }
                .onFailure { error ->
                    Logx.w(TAG, "列目录失败 $path：${error.message}")
                    _rawEntries.value = emptyList()
                    _error.value = error.message ?: "无法读取该目录"
                    // 列目录失败 ≠ 掉线，回查设备状态再定
                    _connected.value = probeConnection()
                }
            _loading.value = false
        }
    }

    /**
     * 后台统计前 [MAX_COUNTED_DIRS] 个目录的子项数。
     *
     * 串行执行（[com.adb.adbwirelesshelper.data.adb.ShellExecutor] 内部有 Mutex，
     * 并发也不会更快），逐个更新 [dirCounts] 让计数逐条浮现在列表上。
     */
    private fun countSubDirs(list: List<FileEntry>) {
        countJob?.cancel()
        _dirCounts.value = emptyMap()
        val dirs: List<FileEntry> = list.filter { it.isDir }.take(MAX_COUNTED_DIRS)
        if (dirs.isEmpty()) return
        countJob = viewModelScope.launch {
            val acc = LinkedHashMap<String, Int>()
            runCatching {
                for (dir in dirs) {
                    val children: List<FileEntry> =
                        repo.listDir(serial, dir.path).getOrDefault(emptyList())
                    acc[dir.path] = children.size
                    _dirCounts.value = acc.toMap()
                }
            }.onFailure { error ->
                Logx.w(TAG, "统计目录子项失败：${error.message}")
            }
        }
    }

    /**
     * 探测设备是否还在线。
     *
     * 只看 `adb devices` 的状态列是否仍是 [DeviceState.DEVICE]，
     * 与「某条 shell 命令是否成功」无关。
     */
    private suspend fun probeConnection(): Boolean = try {
        devices.refresh()
        devices.get(serial)?.state == DeviceState.DEVICE
    } catch (error: Exception) {
        Logx.w(TAG, "掉线探测失败：${error.message}")
        false
    }

    fun refresh() = load(_path.value)

    /** 进入子目录；非目录直接忽略 */
    fun enter(entry: FileEntry) {
        if (entry.isDir) load(entry.path)
    }

    /**
     * 返回上一级；**已在某个存储根、或上一级已经出了存储根范围**时返回 false，
     * UI 据此决定是否退出页面。
     *
     * ⚠️ 三层判据缺一不可，历史 bug 就是只留了第 1 层：
     * 1. 当前就是这个存储根 → 不能再退；
     * 2. 已经在 `/` → 不能再退；
     * 3. **上一级不在任何存储根之内** → 不能再退。这一条是给
     *    `/storage/emulated` → `/storage`、`/storage` → `/` 这类**挂载点层**兜底的：
     *    它们既不是用户能用的目录（列出来是一堆数字挂载点），也不是本页的合法层级。
     *    用户要求 `/storage/emulated/0` 就是最顶层，这条判据是最终保证。
     */
    fun up(): Boolean {
        val roots: Set<String> = knownRoots()
        val current: String = _path.value.trimEnd('/').ifBlank { "/" }
        if (current in roots) return false
        val parent: String = current.substringBeforeLast('/').ifBlank { "/" }
        if (parent == current) return false
        if (!insideRoot(parent, roots)) return false
        load(parent)
        return true
    }

    /**
     * 已知存储根（尾斜杠已归一化）。
     *
     * ⚠️ 集合还没加载出来时**回退为 [FileRepository.DEFAULT_ROOT]**，不能回退成空集：
     * [loadStorages] 要跑 `ls /storage` + 若干次 `df`，首次进页面时它和 [load] 是并行的，
     * 若用户在这几百毫秒内已经进了子目录又按返回，空集会让 [insideRoot] 恒为 false，
     * 表现为「按返回直接退出页面」。宁可少退一层，也不能把用户踢出当前页。
     */
    private fun knownRoots(): Set<String> {
        val raw: Set<String> = _rootPaths.value
            .map { it.trimEnd('/').ifBlank { "/" } }
            .toSet()
        return if (raw.isEmpty()) setOf(FileRepository.DEFAULT_ROOT) else raw
    }

    /** [path] 是否落在任一存储根之内（含根本身）；根目录集合为空时恒为 false（保守，宁可不退） */
    private fun insideRoot(path: String, roots: Set<String>): Boolean =
        roots.any { root -> path == root || path.startsWith("$root/") }

    // ---------------------------------------------------------------- 排序 / 搜索

    fun setSort(key: FileSortKey) {
        if (_sortKey.value == key) {
            // 同键再点 = 反转方向
            _sortDir.value = if (_sortDir.value == FileSortDir.ASC) FileSortDir.DESC else FileSortDir.ASC
        } else {
            _sortKey.value = key
            _sortDir.value = if (key == FileSortKey.NAME) FileSortDir.ASC else FileSortDir.DESC
        }
    }

    fun setQuery(text: String) {
        _query.value = text
    }

    fun toggleHidden() {
        _showHidden.value = !_showHidden.value
    }

    // ---------------------------------------------------------------- 多选

    fun enterSelect(entry: FileEntry) {
        _selectMode.value = true
        _selected.value = setOf(entry.path)
    }

    fun exitSelect() {
        _selectMode.value = false
        _selected.value = emptySet()
    }

    /**
     * 清空已选，但**停留在多选态**。
     *
     * 与 [exitSelect] 的区别：给多选「更多」菜单里的「取消选择」用 ——
     * 用户只是想重选，不该被顺手踢出多选态。操作条在 0 项时全禁用（规格 §4.6.1），
     * 不会出现「空着一条死操作栏」。
     */
    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun toggleSelect(entry: FileEntry) {
        val current: Set<String> = _selected.value
        _selected.value = if (entry.path in current) current - entry.path else current + entry.path
        if (_selected.value.isEmpty()) _selectMode.value = false
    }

    /**
     * 全选当前可见条目。
     *
     * ⚠️ 空目录 / 搜索无结果时**不进入多选态** —— 否则会出现一条 5 个按钮全灰、
     * 永远选不出任何东西的死操作栏（原型验收时踩过这个坑）。
     */
    fun selectAll() {
        val list: List<FileEntry> = entries.value
        if (list.isEmpty()) {
            _toast.value = if (_query.value.isBlank()) "当前目录没有可选项" else "没有匹配项可选"
            return
        }
        _selectMode.value = true
        _selected.value = list.map { it.path }.toSet()
    }

    // ---------------------------------------------------------------- 写操作

    /**
     * 校验名称，返回错误信息；合法时返回 null。
     *
     * 新建与重命名**共用这一套规则**（原型验收时这两处曾出现两套标准）。
     *
     * @param newName 待校验名称
     * @param oldName 重命名时传原名（用于排除自身），新建时传 null
     */
    fun validateName(newName: String, oldName: String? = null): String? {
        val name: String = newName.trim()
        if (name.isEmpty()) return "名称不能为空"
        if (name.contains("/")) return "名称不能包含 “/”"
        if (name == "." || name == "..") return "名称不能是 “.” 或 “..”"
        val dup: Boolean = _rawEntries.value.any { entry ->
            entry.name.equals(name, ignoreCase = true) && entry.name != oldName
        }
        if (dup) return "已存在同名项：$name"
        return null
    }

    fun newFolder(name: String) {
        viewModelScope.launch {
            _busy.value = true
            val dir: String = "${_path.value.trimEnd('/')}/$name"
            repo.mkdir(serial, dir)
                .onSuccess { refresh(); _toast.value = "已新建文件夹：$name" }
                .onFailure { error -> _toast.value = "新建失败：${error.message}" }
            _busy.value = false
        }
    }

    fun rename(entry: FileEntry, newName: String) {
        viewModelScope.launch {
            _busy.value = true
            val target: String = "${entry.path.substringBeforeLast('/')}/$newName"
            repo.move(serial, entry.path, target)
                .onSuccess {
                    refresh()
                    _toast.value = "已重命名为：$newName"
                }
                .onFailure { error -> _toast.value = "重命名失败：${error.message}" }
            _busy.value = false
        }
    }

    /** 删除当前选中的条目。调用方必须先弹确认框。 */
    fun deleteSelected() {
        val paths: List<String> = _selected.value.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            repo.delete(serial, paths)
                .onSuccess {
                    exitSelect()
                    refresh()
                    _toast.value = "已删除 ${paths.size} 项"
                }
                .onFailure { error -> _toast.value = "删除失败：${error.message}" }
            _busy.value = false
        }
    }

    /** 待执行的复制 / 移动操作 */
    private var pendingOp: PendingOp? = null

    private data class PendingOp(val isCopy: Boolean, val paths: List<String>)

    fun beginCopy() {
        val paths: List<String> = _selected.value.toList()
        if (paths.isEmpty()) return
        pendingOp = PendingOp(isCopy = true, paths = paths)
    }

    fun beginMove() {
        val paths: List<String> = _selected.value.toList()
        if (paths.isEmpty()) return
        pendingOp = PendingOp(isCopy = false, paths = paths)
    }

    /**
     * 把待执行操作落地到目标目录。
     *
     * 两件必须做对的事：
     * 1. **重名自动改名**：目标已有同名项时追加 ` (2)`、` (3)`（插在扩展名前），
     *    不静默覆盖、也不直接拒绝；
     * 2. **移动目录是递归的**：`mv` 本身就会带走整棵子树，被控端执行即可，
     *    不需要客户端搬键 —— 这与原型里维护 mock 树的实现不同。
     */
    fun executePending(targetDir: String) {
        val op: PendingOp = pendingOp ?: return
        pendingOp = null
        viewModelScope.launch {
            _busy.value = true
            // ★ 必须是**可变**集合：同批搬多个文件时，前面刚用掉的名字要立刻记进来，
            //   否则两张同名图会算出同一个 finalName 互相覆盖。
            val existing: MutableSet<String> = repo.listDir(serial, targetDir)
                .getOrDefault(emptyList())
                .map { it.name.lowercase() }
                .toMutableSet()

            var ok = 0
            val renamed = ArrayList<String>()
            for (src in op.paths) {
                val srcName: String = src.substringAfterLast('/')
                val finalName: String = uniqueName(existing, srcName)
                existing.add(finalName.lowercase())
                val dst: String = "${targetDir.trimEnd('/')}/$finalName"
                val result: Result<Unit> = if (op.isCopy) {
                    repo.copy(serial, src, dst)
                } else {
                    repo.move(serial, src, dst)
                }
                if (result.isSuccess) {
                    ok++
                    if (finalName != srcName) renamed.add("$srcName → $finalName")
                } else {
                    Logx.w(TAG, "${if (op.isCopy) "复制" else "移动"}失败 $src：${result.exceptionOrNull()?.message}")
                }
            }
            exitSelect()
            refresh()
            val verb: String = if (op.isCopy) "复制" else "移动"
            _toast.value = if (renamed.isEmpty()) {
                "已${verb} $ok 项到 ${targetDir.substringAfterLast('/')}"
            } else {
                "已${verb} $ok 项，其中 ${renamed.size} 项重名已改名"
            }
            _busy.value = false
        }
    }

    fun cancelPending() {
        pendingOp = null
    }

    fun consumeToast() {
        _toast.value = null
    }

    // ---------------------------------------------------------------- 覆盖层接口

    /*
     * 下面这组挂起方法**只做转发**，不含任何 UI 状态。
     *
     * 存在的理由：图片查看器 / 视频播放器是另外两个 Composable 覆盖层，
     * 它们不应该自己去碰 [FileRepository]（那样就得再传一遍 serial，也拿不到当前目录上下文）。
     * 由本 ViewModel 统一暴露，覆盖层只拿闭包。
     */

    /** 读远端文件字节（图片预览走 `exec-out cat`，不落盘） */
    suspend fun readBytes(path: String): Result<ByteArray> = repo.readBytes(serial, path)

    /** 删除若干路径（覆盖层里删除单张图 / 单个视频） */
    suspend fun deletePaths(paths: List<String>): Result<Unit> = repo.delete(serial, paths)

    /** 重命名单个路径：新路径 = 同目录 + 新名 */
    suspend fun renamePath(path: String, newName: String): Result<Unit> =
        repo.move(serial, path, "${path.substringBeforeLast('/')}/$newName")

    /**
     * 把远端文件拉到控制端本地。
     *
     * ⚠️ 视频 / 音乐**已经不再走它** —— 改用 [openStream] + `MediaDataSource`
     * 流式播，不落盘。保留仅为将来的导出功能，以及被控端实在没有
     * tail/head/dd 时的最后退路。
     */
    suspend fun pullTo(remotePath: String, local: File): Result<String> =
        repo.pull(serial, remotePath, local)

    /** 取某目录下的直接子目录（供「复制到 / 移动到」与目标目录导航使用） */
    suspend fun subDirs(dir: String): List<FileEntry> =
        repo.listDir(serial, dir).getOrDefault(emptyList()).filter { it.isDir }

    /**
     * 打开远端文件的**流式读源**（视频 / 音乐不落盘播放）。
     *
     * serial 由本 VM 持有，覆盖层只要把 [FileEntry] 传进来即可，
     * 不需要自己知道设备是谁。
     *
     * 返回的 [RemoteFileStream] 用完**必须 close()**（覆盖层退出时调），
     * 否则内部预取协程会一直挂着。
     */
    suspend fun openStream(entry: FileEntry): Result<RemoteFileStream> =
        repo.openStream(serial, entry.path, entry.sizeBytes)

    /**
     * 「复制到 / 移动到」的候选目标目录。
     *
     * 构成 = 存储根 + 当前目录的直接子目录，再剔除三类非法目标：
     * 1. 当前所在目录本身（原地复制没意义，`cp -r a a` 还会把 a 塞进 a 里）；
     * 2. 任一被选中的路径；
     * 3. 任一被选中目录的**子孙**目录（把父目录搬进自己的子目录，`mv` 会直接失败）。
     *
     * 结果只返回前 [MAX_TARGET_DIRS] 个，总数写进 [totalTargetCount]，
     * UI 据此决定是否补一行「仅显示前 N 个」的提示。
     */
    suspend fun targetCandidates(): List<FileEntry> {
        val current: String = _path.value
        val blocked: Set<String> = _selected.value
        val roots: List<FileEntry> = _storages.value.map { info ->
            FileEntry(
                name = info.label,
                path = info.path,
                isDir = true,
                sizeBytes = 0L,
                modifiedEpochSec = 0L,
                modifiedText = "",
                kind = FileKind.DIR,
            )
        }
        val subs: List<FileEntry> = runCatching { subDirs(current) }.getOrDefault(emptyList())
        val all: List<FileEntry> = (roots + subs).filter { entry ->
            entry.path != current &&
                entry.path !in blocked &&
                blocked.none { picked -> entry.path.startsWith("$picked/") }
        }
        _totalTargetCount.value = all.size
        return all.take(MAX_TARGET_DIRS)
    }

    // ---------------------------------------------------------------- 工具

    /** 在已有名字集合里为 name 找一个不冲突的名字（追加 ` (2)` / ` (3)`） */
    internal fun uniqueName(existing: Set<String>, name: String): String {
        if (name.lowercase() !in existing) return name
        val dot: Int = name.lastIndexOf('.')
        val base: String = if (dot > 0) name.substring(0, dot) else name
        val ext: String = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate.lowercase() !in existing) return candidate
            n++
            if (n > 999) return "$base (${System.currentTimeMillis()})$ext"
        }
    }

    /**
     * 目录永远在前（不受升降序影响），组内按所选键排序。
     */
    internal fun comparator(key: FileSortKey, dir: FileSortDir): Comparator<FileEntry> {
        val inner: Comparator<FileEntry> = when (key) {
            FileSortKey.NAME -> compareBy<FileEntry> { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.name }
            FileSortKey.SIZE -> compareBy<FileEntry> { it.sizeBytes }
                .thenBy { it.name.lowercase(Locale.ROOT) }
            FileSortKey.DATE -> compareBy<FileEntry> { it.modifiedEpochSec }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        }
        val ordered: Comparator<FileEntry> =
            if (dir == FileSortDir.ASC) inner else inner.reversed()
        // isDir = true 排前面：compareByDescending 让 true 先于 false
        return compareByDescending<FileEntry> { it.isDir }.then(ordered)
    }

    private fun buildCrumbs(
        path: String,
        roots: Set<String>,
        storages: List<StorageInfo>,
    ): List<Crumb> {
        // 找到当前路径所属的根
        val root: String = roots.firstOrNull { path == it || path.startsWith("$it/") }
            ?: path.substringBefore('/').takeIf { it.isNotEmpty() }?.let { "/$it" }
            ?: path
        val out = ArrayList<Crumb>()
        val rootLabel: String = storages.firstOrNull { it.path == root }?.label
            ?: root.substringAfterLast('/').ifBlank { root }
        out.add(Crumb(rootLabel, root))
        if (path != root) {
            val rest: String = path.removePrefix(root).trim('/')
            if (rest.isNotEmpty()) {
                var acc = root
                for (seg in rest.split('/')) {
                    acc = "$acc/$seg"
                    out.add(Crumb(seg, acc))
                }
            }
        }
        return out
    }

    companion object {
        /** 后台统计子项数的目录上限（再多会拖慢列表） */
        const val MAX_COUNTED_DIRS: Int = 10

        /** 「复制到 / 移动到」Sheet 里最多展示的目标目录数 */
        const val MAX_TARGET_DIRS: Int = 24

        private const val TAG = "FileBrowserViewModel"

        /** 文件大小格式化 */
        fun formatSize(bytes: Long): String {
            if (bytes < 0) return "-"
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
            val gb = mb / 1024.0
            return String.format(Locale.US, "%.2f GB", gb)
        }

        /** 是否可用内置查看器打开（图片 / 视频 / 音乐，与 FileModels 的同名扩展保持一致） */
        fun isPreviewable(entry: FileEntry): Boolean =
            entry.kind == FileKind.IMAGE ||
                entry.kind == FileKind.VIDEO ||
                entry.kind == FileKind.AUDIO
    }
}
