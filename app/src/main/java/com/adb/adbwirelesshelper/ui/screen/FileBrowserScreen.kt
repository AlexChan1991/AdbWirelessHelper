package com.adb.adbwirelesshelper.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.adb.adbwirelesshelper.domain.model.FileEntry
import com.adb.adbwirelesshelper.domain.model.FileKind
import com.adb.adbwirelesshelper.domain.model.FileSortDir
import com.adb.adbwirelesshelper.domain.model.FileSortKey
import com.adb.adbwirelesshelper.domain.model.StorageInfo
import com.adb.adbwirelesshelper.ui.components.EmptyHint
import com.adb.adbwirelesshelper.ui.components.ErrorBanner
import com.adb.adbwirelesshelper.ui.components.WavyProgressIndicator
import com.adb.adbwirelesshelper.ui.theme.AppIcon
import com.adb.adbwirelesshelper.ui.theme.Motion
import com.adb.adbwirelesshelper.ui.theme.Radius
import com.adb.adbwirelesshelper.ui.theme.Shape2xl
import com.adb.adbwirelesshelper.ui.theme.ShapeFull
import com.adb.adbwirelesshelper.ui.theme.ShapeMd
import com.adb.adbwirelesshelper.ui.theme.ShapeXl
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- 文案常量

private const val TXT_TITLE = "文件管理"
private const val TXT_BACK = "返回"
private const val TXT_CLOSE_SELECT = "退出多选"
/** 顶栏返回键右侧的「×」：无论当前在哪一层、是否处于多选态，都**直接退出本页** */
private const val TXT_CLOSE_PAGE = "关闭文件管理"
private const val TXT_SEARCH = "搜索"
private const val TXT_SORT = "排序"
private const val TXT_MORE = "更多"
private const val TXT_CLEAR_SEARCH = "清除搜索"
private const val TXT_SEARCH_PLACEHOLDER = "在当前目录中搜索…"
private const val TXT_OFFLINE_BANNER = "连接已断开，当前显示的是断开前的缓存。"
private const val TXT_RELOAD = "重新加载"
private const val TXT_EMPTY_DIR = "此目录为空"
private const val TXT_NO_MATCH = "没有匹配项"
private const val TXT_NO_TARGET = "没有可用的目标目录"
private const val TXT_DIR_FALLBACK = "文件夹"
private const val TXT_SELECTED_PREFIX = "已选 "
private const val TXT_SELECTED_SUFFIX = " 项"
private const val TXT_ACT_COPY = "复制"
private const val TXT_ACT_MOVE = "移动"
private const val TXT_ACT_RENAME = "重命名"
private const val TXT_ACT_DELETE = "删除"
private const val TXT_ACT_MORE = "更多"
private const val TXT_SORT_SHEET_TITLE = "排序方式"
private const val TXT_MORE_SHEET_TITLE = "操作"
private const val TXT_SELECT_MORE_SHEET_TITLE = "多选操作"
private const val TXT_MENU_NEW_FOLDER = "新建文件夹"
private const val TXT_MENU_SELECT_ALL = "全选"
private const val TXT_MENU_REFRESH = "刷新"
private const val TXT_MENU_SHOW_HIDDEN = "显示隐藏文件"
private const val TXT_MENU_HIDE_HIDDEN = "不显示隐藏文件"
private const val TXT_MENU_CLEAR_SELECTION = "取消选择"
private const val TXT_MENU_SHARE = "分享"
private const val TXT_MENU_EXIT_SELECT = "退出多选"
private const val TXT_NEW_FOLDER_TITLE = "新建文件夹"
private const val TXT_RENAME_TITLE = "重命名"
private const val TXT_NAME_LABEL = "名称"
private const val TXT_CANCEL = "取消"
private const val TXT_OK = "确定"
private const val TXT_DELETE_TITLE_PREFIX = "删除 "
private const val TXT_DELETE_TITLE_SUFFIX = " 项？"
private const val TXT_DELETE_REST_PREFIX = "等 "
private const val TXT_DELETE_REST_SUFFIX = " 项"
// 真机实现：删除是真的 `rm -rf`，绝不能写「原型为模拟操作」误导用户
private const val TXT_DELETE_FOOTER = "删除后无法恢复。"
private const val TXT_DELETE_CONFIRM = "删除"
private const val TXT_TARGET_COPY_TITLE = "复制到（共 "
private const val TXT_TARGET_MOVE_TITLE = "移动到（共 "
private const val TXT_TARGET_TITLE_SUFFIX = " 项）"
private const val TXT_TARGET_TRUNCATED_PREFIX = "仅显示前 "
private const val TXT_TARGET_TRUNCATED_MID = " 个目录（共 "
private const val TXT_TARGET_TRUNCATED_SUFFIX = " 个）"
private const val TXT_TOAST_OFFLINE = "连接已断开"
private const val TXT_TOAST_NO_PREVIEW = "暂不支持预览"
private const val TXT_TOAST_NO_SHARE = "暂不支持分享"
private const val TXT_STORAGE_USED = "已用 "
private const val TXT_STORAGE_TOTAL = " · 共 "
private const val TXT_STORAGE_FREE = " · 可用 "
private const val TXT_CRUMB_SEPARATOR = "/"

/** 顶栏**内容**高度（不含状态栏）；状态栏由 [FileTopAppBar] 自己让位。 */
private val TOP_BAR_HEIGHT_FILE: Dp = 64.dp

/** 面包屑条高度 */
private val CRUMB_BAR_HEIGHT: Dp = 32.dp

/** 搜索栏展开后的高度 */
private val SEARCH_BAR_HEIGHT: Dp = 56.dp

/** 列表行缩略图边长 */
private val THUMB_SIZE: Dp = 40.dp

/** 底部多选操作条高度 */
private val SELECT_BAR_HEIGHT: Dp = 56.dp

/** 底部 Sheet 行高 */
private val SHEET_ROW_HEIGHT: Dp = 48.dp

/** 排序 Sheet 的 6 个选项。 */
private data class SortOption(
    val label: String,
    val key: FileSortKey,
    val dir: FileSortDir,
)

private val SORT_OPTIONS: List<SortOption> = listOf(
    SortOption("名称 · 升序", FileSortKey.NAME, FileSortDir.ASC),
    SortOption("名称 · 降序", FileSortKey.NAME, FileSortDir.DESC),
    SortOption("大小 · 升序", FileSortKey.SIZE, FileSortDir.ASC),
    SortOption("大小 · 降序", FileSortKey.SIZE, FileSortDir.DESC),
    SortOption("日期 · 升序", FileSortKey.DATE, FileSortDir.ASC),
    SortOption("日期 · 降序", FileSortKey.DATE, FileSortDir.DESC),
)

/** 底部 Sheet 的四种形态。同一时刻只会有一个，因此共用一个 sheetState。 */
private sealed class FileSheet {
    /** 排序方式 */
    object Sort : FileSheet()

    /** 顶栏「更多」 */
    object More : FileSheet()

    /** 多选态下操作条的「更多」 */
    object SelectMore : FileSheet()

    /** 复制到 / 移动到 的目标目录选择；isCopy = true 表示复制 */
    data class Target(val isCopy: Boolean) : FileSheet()
}

// ---------------------------------------------------------------- 页面

/**
 * 文件管理页（设计规格 §4.6.1）。
 *
 * 管理的是**被控端设备**的文件系统：列目录 / 新建 / 重命名 / 复制 / 移动 / 删除
 * 全部走 adb shell，删除是真的 `rm -rf`。
 *
 * 区块自上而下：顶栏 → 搜索栏（可收起）→ 路径面包屑 → 掉线横幅 → 存储概览（仅根目录）
 * → 文件列表 → 多选操作条。
 *
 * 三处与本项目其余页面不同的取舍：
 * 1. **本页不放 StatusStrip** —— 连接状态由面包屑下方的掉线横幅表达，
 *    避免同一屏出现两条都在说「连没连上」的横条；
 * 2. **副标题不随滚动淡出**（§1.6 的例外）：副标题是当前路径，用户在深目录里
 *    最需要的就是这一行，淡出等于把导航信息藏起来；
 * 3. 顶栏**左起第二个**返回箭头**不是**直接退出页面：非根目录时先退一级目录，
 *    与系统返回键（[BackHandler]）行为一致。
 *
 * ⚠️ 顶栏左侧两个按钮的顺序与语义**以 [FileTopAppBar] 的 KDoc 为唯一权威**
 * （2026-09-27 由用户指定：关闭在左、返回紧随其右）：
 * 最左「×」= 关闭本页，左起第二个返回箭头 = 退一级目录 / 多选态下退出多选。
 * 两者**不要合并**，本页其余注释出现「左上角」字样时一律指「×」而不是返回箭头。
 *
 * @param vm     文件管理 ViewModel
 * @param serial 目标设备 serial
 * @param onBack 退出页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    vm: FileBrowserViewModel,
    serial: String,
    onBack: () -> Unit,
) {
    val path by vm.path.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val entries by vm.entries.collectAsState()
    val sortKey by vm.sortKey.collectAsState()
    val sortDir by vm.sortDir.collectAsState()
    val query by vm.query.collectAsState()
    val showHidden by vm.showHidden.collectAsState()
    val selectMode by vm.selectMode.collectAsState()
    val selected by vm.selected.collectAsState()
    val storages by vm.storages.collectAsState()
    val atRoot by vm.atRoot.collectAsState()
    val crumbs by vm.crumbs.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val connected by vm.connected.collectAsState()
    val dirCounts by vm.dirCounts.collectAsState()
    val totalTargetCount by vm.totalTargetCount.collectAsState()
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()

    val snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    var searchOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<FileSheet?>(null) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteOpen by remember { mutableStateOf(false) }
    var targetDirs by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var playerIndex by remember { mutableStateOf<Int?>(null) }
    var audioIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(serial) { vm.bind(serial) }

    LaunchedEffect(toast) {
        val text: String = toast ?: return@LaunchedEffect
        if (text.isNotBlank()) {
            snackbarHostState.showSnackbar(text)
            vm.consumeToast()
        }
    }

    // 「复制到 / 移动到」Sheet 打开时：先落定待执行操作，再挂起取候选目录
    LaunchedEffect(sheet) {
        val current: FileSheet = sheet ?: return@LaunchedEffect
        if (current !is FileSheet.Target) return@LaunchedEffect
        if (current.isCopy) vm.beginCopy() else vm.beginMove()
        targetDirs = vm.targetCandidates()
    }

    // 当前目录下的图片 / 视频 / 音频全集：覆盖层的翻页范围
    val images: List<FileEntry> = remember(entries) { entries.filter { it.kind == FileKind.IMAGE } }
    val videos: List<FileEntry> = remember(entries) { entries.filter { it.kind == FileKind.VIDEO } }
    val audios: List<FileEntry> = remember(entries) { entries.filter { it.kind == FileKind.AUDIO } }

    // 删除确认框要列名字：selected 存的是路径，回表里取展示名
    val selectedEntries: List<FileEntry> =
        remember(entries, selected) { entries.filter { it.path in selected } }

    val showToast: (String) -> Unit = { text -> scope.launch { snackbarHostState.showSnackbar(text) } }

    val dismissSheet: () -> Unit = {
        scope
            .launch { runCatching { sheetState.hide() } }
            .invokeOnCompletion {
                if (sheet is FileSheet.Target) vm.cancelPending()
                sheet = null
            }
    }

    // ---- 系统返回键 ----
    // 两个 enabled 互斥：多选态优先吃掉返回键（退出多选而不是退出页面）。
    BackHandler(enabled = selectMode) { vm.exitSelect() }
    // 非多选态：先退一级目录，已在存储根则退出页面。
    // 规格原文是 `enabled = !atRoot`，这里放宽成 `!selectMode` —— 否则在根目录会退化成
    // 依赖 NavHost 的默认返回行为，多一层不确定性。
    // ⚠️ 覆盖层（图片 / 视频 / 音频）打开时必须让位，否则返回键会「先退目录、覆盖层还挂着」。
    BackHandler(
        enabled = !selectMode &&
            viewerIndex == null &&
            playerIndex == null &&
            audioIndex == null,
    ) { if (!vm.up()) onBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            FileTopAppBar(
                title = TXT_TITLE,
                subtitle = "$deviceName · $path",
                selectMode = selectMode,
                onBack = {
                    if (selectMode) {
                        vm.exitSelect()
                    } else if (!atRoot) {
                        if (!vm.up()) onBack()
                    } else {
                        onBack()
                    }
                },
                // ⚠️ 这里的 `onBack` 取的是 **FileBrowserScreen 自己的参数**（NavHost 的 popBackStack），
                //    不是上面那个同名的 `onBack = { … }` 命名实参（命名实参不会引入新绑定）。
                //    「×」直连出栈：不经过多选/退目录那套分支。
                onClose = onBack,
                onSearch = {
                    searchOpen = !searchOpen
                    if (!searchOpen) vm.setQuery("")
                },
                onSort = { sheet = FileSheet.Sort },
                onMore = { sheet = FileSheet.More },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ---- 搜索栏（条件展开）----
            SearchBar(
                expanded = searchOpen,
                query = query,
                onQuery = { vm.setQuery(it) },
                onClose = {
                    searchOpen = false
                    vm.setQuery("")
                },
            )

            // ---- 路径面包屑 ----
            CrumbBar(
                crumbs = crumbs,
                onNavigate = { vm.load(it) },
            )

            // ---- 掉线横幅（面包屑下方）----
            if (!connected) {
                ErrorBanner(
                    text = TXT_OFFLINE_BANNER,
                    actionLabel = TXT_RELOAD,
                    onAction = { vm.refresh() },
                )
            }

            // ---- 文件列表 ----
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // 存储概览：仅在存储根展示
                if (atRoot && storages.isNotEmpty()) {
                    items(storages, key = { it.path }) { info -> StorageCard(info = info) }
                }

                // 列目录出错（且掉线横幅解释不了）就地再给一条
                val err: String? = error
                if (err != null) {
                    item {
                        ErrorBanner(
                            text = err,
                            actionLabel = TXT_RELOAD,
                            onAction = { vm.refresh() },
                        )
                    }
                }

                when {
                    loading && entries.isEmpty() -> item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // 规格 §1.5.4：一律波浪指示器，不用转圈
                            // （转圈会让人误以为「文件正在下载」）
                            WavyProgressIndicator(size = 28.dp)
                        }
                    }

                    entries.isEmpty() -> item {
                        // 两种空态文案必须分开：目录真的空 ≠ 搜索没命中
                        EmptyHint(text = if (query.isBlank()) TXT_EMPTY_DIR else TXT_NO_MATCH)
                    }

                    else -> items(entries, key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            selected = entry.path in selected,
                            selectMode = selectMode,
                            dirCount = if (entry.isDir) dirCounts[entry.path] else null,
                            onClick = {
                                if (selectMode) {
                                    vm.toggleSelect(entry)
                                } else {
                                    handleEntryClick(
                                        entry = entry,
                                        vm = vm,
                                        images = images,
                                        videos = videos,
                                        audios = audios,
                                        onOpenImage = { viewerIndex = it },
                                        onOpenVideo = { playerIndex = it },
                                        onOpenAudio = { audioIndex = it },
                                        onToast = showToast,
                                    )
                                }
                            },
                            onLongClick = {
                                // 掉线时长按不进多选：选了也发不出去
                                if (!connected) {
                                    showToast(TXT_TOAST_OFFLINE)
                                } else {
                                    vm.enterSelect(entry)
                                }
                            },
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            // ---- 多选操作条 ----
            if (selectMode) {
                SelectActionBar(
                    count = selected.size,
                    enabled = connected && !busy,
                    onCopy = { sheet = FileSheet.Target(isCopy = true) },
                    onMove = { sheet = FileSheet.Target(isCopy = false) },
                    onRename = { renameTarget = selectedEntries.firstOrNull() },
                    onDelete = { deleteOpen = true },
                    onMore = { sheet = FileSheet.SelectMore },
                )
            }
        }
    }

    // ---- 底部 Sheet ----
    val opened: FileSheet? = sheet
    if (opened != null) {
        ModalBottomSheet(
            onDismissRequest = dismissSheet,
            sheetState = sheetState,
            // 规格 §1.3：底部 Sheet 顶部圆角 32 dp
            shape = RoundedCornerShape(topStart = Radius.Xl3, topEnd = Radius.Xl3),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            ) {
                when (opened) {
                FileSheet.Sort -> {
                    Text(
                        text = TXT_SORT_SHEET_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    for (option in SORT_OPTIONS) {
                        SheetRow(
                            label = option.label,
                            checked = sortKey == option.key && sortDir == option.dir,
                            onClick = {
                                applySort(vm, option.key, option.dir)
                                dismissSheet()
                            },
                        )
                    }
                }

                FileSheet.More -> {
                    Text(
                        text = TXT_MORE_SHEET_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    SheetRow(
                        label = TXT_MENU_NEW_FOLDER,
                        icon = AppIcon.Add,
                        checked = false,
                        onClick = { dismissSheet(); newFolderOpen = true },
                    )
                    SheetRow(
                        label = TXT_MENU_SELECT_ALL,
                        icon = AppIcon.Bookmark,
                        checked = false,
                        onClick = { dismissSheet(); vm.selectAll() },
                    )
                    SheetRow(
                        label = TXT_MENU_REFRESH,
                        icon = AppIcon.Refresh,
                        checked = false,
                        onClick = { dismissSheet(); vm.refresh() },
                    )
                    SheetRow(
                        label = if (showHidden) TXT_MENU_HIDE_HIDDEN else TXT_MENU_SHOW_HIDDEN,
                        icon = AppIcon.Tune,
                        checked = showHidden,
                        onClick = { dismissSheet(); vm.toggleHidden() },
                    )
                }

                FileSheet.SelectMore -> {
                    Text(
                        text = TXT_SELECT_MORE_SHEET_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    SheetRow(
                        label = TXT_MENU_SELECT_ALL,
                        icon = AppIcon.Bookmark,
                        checked = false,
                        onClick = { dismissSheet(); vm.selectAll() },
                    )
                    SheetRow(
                        label = TXT_MENU_CLEAR_SELECTION,
                        icon = AppIcon.Close,
                        checked = false,
                        onClick = { dismissSheet(); vm.clearSelection() },
                    )
                    SheetRow(
                        label = TXT_MENU_SHARE,
                        icon = AppIcon.Clipboard,
                        checked = false,
                        onClick = { dismissSheet(); showToast(TXT_TOAST_NO_SHARE) },
                    )
                    SheetRow(
                        label = TXT_MENU_EXIT_SELECT,
                        icon = AppIcon.Close,
                        checked = false,
                        onClick = { dismissSheet(); vm.exitSelect() },
                    )
                }

                is FileSheet.Target -> {
                    val titlePrefix: String =
                        if (opened.isCopy) TXT_TARGET_COPY_TITLE else TXT_TARGET_MOVE_TITLE
                    Text(
                        text = "$titlePrefix${selected.size}$TXT_TARGET_TITLE_SUFFIX",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    if (targetDirs.isEmpty()) {
                        EmptyHint(text = TXT_NO_TARGET)
                    } else {
                        for (dir in targetDirs) {
                            SheetRow(
                                label = dir.name,
                                icon = AppIcon.Home,
                                checked = false,
                                onClick = {
                                    dismissSheet()
                                    vm.executePending(dir.path)
                                },
                            )
                        }
                    }
                    if (totalTargetCount > FileBrowserViewModel.MAX_TARGET_DIRS) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = TXT_TARGET_TRUNCATED_PREFIX +
                                FileBrowserViewModel.MAX_TARGET_DIRS +
                                TXT_TARGET_TRUNCATED_MID +
                                totalTargetCount +
                                TXT_TARGET_TRUNCATED_SUFFIX,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                }
            }
        }
    }

    // ---- 对话框 ----
    if (newFolderOpen) {
        NameEditDialog(
            title = TXT_NEW_FOLDER_TITLE,
            initial = "",
            validate = { vm.validateName(it, null) },
            onDismiss = { newFolderOpen = false },
            onConfirm = { name ->
                newFolderOpen = false
                vm.newFolder(name)
            },
        )
    }

    val renameEntry: FileEntry? = renameTarget
    if (renameEntry != null) {
        NameEditDialog(
            title = TXT_RENAME_TITLE,
            initial = renameEntry.name,
            validate = { vm.validateName(it, renameEntry.name) },
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                vm.rename(renameEntry, name)
            },
        )
    }

    if (deleteOpen) {
        DeleteConfirmDialog(
            names = selectedEntries.map { it.name },
            onDismiss = { deleteOpen = false },
            onConfirm = {
                deleteOpen = false
                vm.deleteSelected()
            },
        )
    }

    // ---- 覆盖层（图片查看器 / 视频播放器 / 音乐播放器）----
    val vIdx: Int? = viewerIndex
    if (vIdx != null && images.isNotEmpty()) {
        ImageViewerOverlay(
            entries = images,
            startIndex = vIdx.coerceIn(0, images.lastIndex),
            readBytes = { vm.readBytes(it) },
            onDelete = { vm.deletePaths(listOf(it)) },
            onRename = { target, newName -> vm.renamePath(target, newName) },
            onClose = {
                viewerIndex = null
                vm.refresh()
            },
        )
    }

    val pIdx: Int? = playerIndex
    if (pIdx != null && videos.isNotEmpty()) {
        VideoPlayerOverlay(
            entries = videos,
            startIndex = pIdx.coerceIn(0, videos.lastIndex),
            openStream = { vm.openStream(it) },
            onClose = {
                playerIndex = null
                vm.refresh()
            },
        )
    }

    val aIdx: Int? = audioIndex
    if (aIdx != null && audios.isNotEmpty()) {
        AudioPlayerOverlay(
            entries = audios,
            startIndex = aIdx.coerceIn(0, audios.lastIndex),
            openStream = { vm.openStream(it) },
            onClose = {
                audioIndex = null
                vm.refresh()
            },
        )
    }

    // 覆盖层打开时系统返回键先关覆盖层。
    // 放在三个覆盖层**之后**注册：Compose 的 BackHandler 是「后注册的先拿到事件」，
    // 所以即便覆盖层内部自己也注册了 BackHandler，这里也不会抢在它前面。
    BackHandler(
        enabled = viewerIndex != null || playerIndex != null || audioIndex != null,
    ) {
        when {
            viewerIndex != null -> viewerIndex = null
            playerIndex != null -> playerIndex = null
            else -> audioIndex = null
        }
        vm.refresh()
    }
}

/**
 * 单击条目的分派。
 *
 * 目录 = 进入；图片 / 视频 / 音频 = 打开对应覆盖层；其余类型只给一条 Snackbar。
 *
 * ⚠️ 音频**不能**落到「暂不支持预览」：它有自己的播放器覆盖层。
 */
private fun handleEntryClick(
    entry: FileEntry,
    vm: FileBrowserViewModel,
    images: List<FileEntry>,
    videos: List<FileEntry>,
    audios: List<FileEntry>,
    onOpenImage: (Int) -> Unit,
    onOpenVideo: (Int) -> Unit,
    onOpenAudio: (Int) -> Unit,
    onToast: (String) -> Unit,
) {
    when {
        entry.isDir -> vm.enter(entry)
        entry.kind == FileKind.IMAGE -> {
            val index: Int = images.indexOfFirst { it.path == entry.path }
            if (index >= 0) onOpenImage(index) else onToast(TXT_TOAST_NO_PREVIEW)
        }

        entry.kind == FileKind.VIDEO -> {
            val index: Int = videos.indexOfFirst { it.path == entry.path }
            if (index >= 0) onOpenVideo(index) else onToast(TXT_TOAST_NO_PREVIEW)
        }

        entry.kind == FileKind.AUDIO -> {
            val index: Int = audios.indexOfFirst { it.path == entry.path }
            if (index >= 0) onOpenAudio(index) else onToast(TXT_TOAST_NO_PREVIEW)
        }

        else -> onToast(TXT_TOAST_NO_PREVIEW)
    }
}

/**
 * 把 UI 上的「排序键 + 方向」落到 [FileBrowserViewModel.setSort]。
 *
 * ⚠️ [FileBrowserViewModel.setSort] 的语义是「**同键再点 = 反转方向**」，
 * 点「大小 · 降序」时不能直接调一次了事：
 * - 当前键不是 SIZE → 先 `setSort(SIZE)`，VM 给出默认方向（NAME 默认 ASC、SIZE/DATE 默认 DESC）；
 *   默认方向仍不是想要的 → 再 `setSort(SIZE)` 反转一次；
 * - 当前键已是 SIZE 但方向相反 → `setSort(SIZE)` 反转一次；
 * - 已经一致 → 什么都不做（否则会莫名反转）。
 *
 * 因为 `setSort` 是同步改 StateFlow 的，这里连着读 `vm.sortDir.value` 能立刻拿到上一步的结果。
 */
private fun applySort(vm: FileBrowserViewModel, key: FileSortKey, dir: FileSortDir) {
    if (vm.sortKey.value != key) {
        vm.setSort(key)
        if (vm.sortDir.value != dir) vm.setSort(key)
    } else if (vm.sortDir.value != dir) {
        vm.setSort(key)
    }
}

// ---------------------------------------------------------------- 顶栏

/**
 * 文件管理页顶栏（规格 §4.6.1 区块 1）。
 *
 * 固定 64 dp + 状态栏让位（[WindowInsets.statusBars] 必须放在 `height()` **之前**，
 * 否则 64 dp 会把内容区压扁）。
 *
 * 副标题是「设备名 · 当前路径」，**等宽 + 单行省略、不换行、不随滚动淡出**。
 *
 * **左侧两个按钮语义不同，不要合并**（顺序于 2026-09-27 由用户指定：**关闭在左、返回紧随其右**）：
 * - **最左「×」= 关闭本页**（[onClose]）：任何层级、任何状态一次性退出，
 *   不吃多选态、也不逐层回退。规格 §4.6.1 区块 1；
 * - 它右边的返回箭头 = 「退一级目录」；在多选态下复用为「退出多选」（图标 ×，文案随状态变）。
 */
@Composable
private fun FileTopAppBar(
    title: String,
    subtitle: String,
    selectMode: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onSearch: () -> Unit,
    onSort: () -> Unit,
    onMore: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // ★ targetSdk 35 起 Android 15 强制 edge-to-edge：自定义 Surface 顶栏
            //   必须自己补状态栏避让（M3 原生 TopAppBar 内部自带这层）。
            //   放在 height() 之前 → 总高 = 状态栏 + 64 dp，内容区不被压扁。
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(TOP_BAR_HEIGHT_FILE),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ★ 「×」在最左：不论当前在哪一层、是否多选，**点了就退出本页**。
            //   ⚠️ 它在**任何层级、任何状态**下都只做一件事 —— 退出本页：
            //   ① 在多选态里点它也会退出本页（不等同于「退出多选」）；
            //   ② 在深层目录里点它不会一级级往回退，一次到位。
            //   与右边返回键的区别正是用户要的「不用连按好几次返回才出得去」。
            IconButton(onClick = onClose) {
                Icon(imageVector = AppIcon.Close, contentDescription = TXT_CLOSE_PAGE)
            }
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = if (selectMode) AppIcon.Close else AppIcon.Back,
                    contentDescription = if (selectMode) TXT_CLOSE_SELECT else TXT_BACK,
                )
            }
            // 两个 48 dp 图标按钮之间留一点视觉间距（Row 的横向 padding 只作用在最外侧）
            Spacer(modifier = Modifier.width(2.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // §1.6 例外：本页副标题**不淡出** —— 它就是当前路径导航本身
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onSearch) {
                Icon(imageVector = AppIcon.Search, contentDescription = TXT_SEARCH)
            }
            IconButton(onClick = onSort) {
                // 图标库没有 sort 图标，用「调节」表达排序入口
                Icon(imageVector = AppIcon.Tune, contentDescription = TXT_SORT)
            }
            IconButton(onClick = onMore) {
                Icon(imageVector = AppIcon.MoreVert, contentDescription = TXT_MORE)
            }
        }
    }
}

// ---------------------------------------------------------------- 搜索栏

/**
 * 搜索栏（条件渲染）。
 *
 * 输入即过滤（[FileBrowserViewModel.setQuery]），**不发请求** —— 过滤发生在
 * 已经拿到的列表上，切搜索词不该产生任何 adb 命令。
 *
 * 展开 / 收起：`EasingEmphasizedDecelerate` + 200 ms 的高度 + 透明度动画。
 *
 * ⚠️ 容器高度在动画中会降到 0，而内部 [TextField] 有自己的最小高度。若让子项跟着
 *    父级收缩（`fillMaxSize`），约束会变成 min > max 直接崩；所以这里给内容行
 *    [Modifier.requiredHeight] 固定 56 dp，再靠 `clipToBounds()` 裁出「展开」的观感。
 */
@Composable
private fun SearchBar(
    expanded: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
) {
    val raw by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = Motion.DURATION_SHORT,
            easing = Motion.EasingEmphasizedDecelerate,
        ),
        label = "searchBarFraction",
    )
    // tween 不会下冲，但凡是喂给 padding / height 的动画值一律先夹一次，防后患
    val fraction: Float = raw.coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SEARCH_BAR_HEIGHT * fraction)
            .clipToBounds()
            .alpha(fraction),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .requiredHeight(SEARCH_BAR_HEIGHT)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AppIcon.Search,
                contentDescription = TXT_SEARCH,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = {
                    Text(
                        text = TXT_SEARCH_PLACEHOLDER,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(onClick = onClose) {
                Icon(imageVector = AppIcon.Close, contentDescription = TXT_CLEAR_SEARCH)
            }
        }
    }
}

// ---------------------------------------------------------------- 面包屑

/**
 * 路径面包屑（~32 dp，横向可滚动）。
 *
 * 当前级（最后一级）用 `primaryContainer` 高亮且**不可点**；
 * 点任一级是**直接跳**到该目录（[FileBrowserViewModel.load]，不是逐级后退）。
 */
@Composable
private fun CrumbBar(
    crumbs: List<Crumb>,
    onNavigate: (String) -> Unit,
) {
    if (crumbs.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CRUMB_BAR_HEIGHT)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    text = TXT_CRUMB_SEPARATOR,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            val isLast: Boolean = index == crumbs.lastIndex
            if (isLast) {
                Surface(
                    shape = ShapeFull,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    CrumbLabel(text = crumb.label)
                }
            } else {
                Surface(
                    onClick = { onNavigate(crumb.path) },
                    shape = ShapeFull,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    CrumbLabel(text = crumb.label)
                }
            }
        }
    }
}

/** 面包屑每一级的文字 */
@Composable
private fun CrumbLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// ---------------------------------------------------------------- 存储概览

/**
 * 单张存储概览卡（仅在存储根展示）。
 *
 * 进度用 [LinearProgressIndicator]，宽度过渡走 [Motion.springSlow]；
 * spring 阻尼 0.6 在回到 0 时会**下冲到负值**，喂给 progress 前必须夹到 0..1。
 */
@Composable
private fun StorageCard(info: StorageInfo) {
    val target: Float = (info.usedPercent / 100f).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = Motion.springSlow(),
        label = "storageProgress",
    )
    val ratio: Float = animated.coerceIn(0f, 1f)
    val freeBytes: Long = (info.totalBytes - info.usedBytes).coerceAtLeast(0L)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        shape = ShapeXl,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = info.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${info.usedPercent}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = TXT_STORAGE_USED +
                    FileBrowserViewModel.formatSize(info.usedBytes) +
                    TXT_STORAGE_TOTAL +
                    FileBrowserViewModel.formatSize(info.totalBytes) +
                    TXT_STORAGE_FREE +
                    FileBrowserViewModel.formatSize(freeBytes),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- 列表行

/**
 * 一个目录项 / 文件项。
 *
 * 长按（系统默认长按阈值）进入多选；点击行为由 [onClick] 决定。
 *
 * [Modifier.combinedClickable] 在 foundation 1.7.6 里仍是 [ExperimentalFoundationApi]，
 * 这里就地 OptIn，不外溢到整个文件。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    selectMode: Boolean,
    dirCount: Int?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            SelectDot(selected = selected)
            Spacer(modifier = Modifier.width(10.dp))
        }
        FileThumb(entry = entry)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitleOf(entry, dirCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 目录副行：有计数就显示「N 项」，没统计到就退回「文件夹」；文件副行 = 大小 · 日期 */
private fun subtitleOf(entry: FileEntry, dirCount: Int?): String {
    if (entry.isDir) {
        return dirCount?.let { "$it 项" } ?: TXT_DIR_FALLBACK
    }
    return FileBrowserViewModel.formatSize(entry.sizeBytes) + " · " + entry.modifiedText
}

/** 多选态行首的选择圆点 */
@Composable
private fun SelectDot(selected: Boolean) {
    val borderColor: Color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = CircleShape,
            )
            .border(width = 1.5.dp, color = borderColor, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            // 图标库没有 check 图标，也不想为它引入 material-icons-extended
            Text(
                text = "✓",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 40 dp 缩略图：目录 / 图片 / 视频 / 其余类型 */
@Composable
private fun FileThumb(entry: FileEntry) {
    when (entry.kind) {
        FileKind.DIR -> {
            Box(
                modifier = Modifier
                    .size(THUMB_SIZE)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = ShapeMd,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // 图标库没有 folder 图标，用 Home 近似
                Icon(
                    imageVector = AppIcon.Home,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        FileKind.IMAGE -> {
            // ★ 刻意**不解码位图**：列表里每一行都去 `exec-out cat` 一张图会产生几十条
            //   串行 adb 命令，滚动必然卡死。这里用名称哈希派生一个固定色块占位，
            //   同一张图每次进目录颜色一致，不会因为重组而跳色。真正的解码交给图片查看器。
            Box(
                modifier = Modifier
                    .size(THUMB_SIZE)
                    .background(color = placeholderColorOf(entry.name), shape = ShapeMd),
            )
        }

        FileKind.VIDEO -> {
            Box(
                modifier = Modifier
                    .size(THUMB_SIZE)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = ShapeMd,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // 胶片：图标库没有 filmstrip，用 Play 表达「可播放」
                Icon(
                    imageVector = AppIcon.Play,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        else -> {
            val tint: Color = kindTintOf(entry.kind)
            Box(
                modifier = Modifier
                    .size(THUMB_SIZE)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = ShapeMd,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcon.Info,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** APK / 压缩包 / 文本 / 音频 / 其他共用一个 Info 图标，靠 tint 区分 */
@Composable
private fun kindTintOf(kind: FileKind): Color = when (kind) {
    FileKind.AUDIO -> MaterialTheme.colorScheme.tertiary
    FileKind.TEXT -> MaterialTheme.colorScheme.onSurfaceVariant
    FileKind.APK -> MaterialTheme.colorScheme.primary
    FileKind.ARCHIVE -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 图片缩略图的占位色。
 *
 * 由文件名哈希派生色相，饱和度 / 明度固定，保证同一文件每次进目录颜色一致。
 */
private fun placeholderColorOf(name: String): Color {
    val hash: Int = name.hashCode() and 0x7FFFFFFF
    val hue: Float = (hash % 360).toFloat()
    return Color.hsv(hue = hue, saturation = 0.32f, value = 0.88f)
}

// ---------------------------------------------------------------- 多选操作条

/**
 * 多选操作条（规格 §4.6.1 区块 7）。
 *
 * 顶部一行「已选 N 项」，下方 5 个**等宽**按钮装进一个 28 dp 圆角容器。
 *
 * 禁用规则：[FileBrowserViewModel] 的操作在掉线时发不出去，所以掉线时全部禁用；
 * 已选 0 项时 5 个全禁用；重命名只在 **N == 1** 时可用（多选重命名没有目标名）。
 */
@Composable
private fun SelectActionBar(
    count: Int,
    enabled: Boolean,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$TXT_SELECTED_PREFIX$count$TXT_SELECTED_SUFFIX",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 3.dp),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(SELECT_BAR_HEIGHT),
            shape = Shape2xl,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectAction(
                    icon = AppIcon.Copy,
                    label = TXT_ACT_COPY,
                    enabled = enabled && count > 0,
                    onClick = onCopy,
                )
                // 图标库没有 move 图标，用右箭头表达「移到别处」
                SelectAction(
                    icon = AppIcon.ChevronRight,
                    label = TXT_ACT_MOVE,
                    enabled = enabled && count > 0,
                    onClick = onMove,
                )
                // 图标库没有 edit 图标，用「调节」表达改名
                SelectAction(
                    icon = AppIcon.Tune,
                    label = TXT_ACT_RENAME,
                    enabled = enabled && count == 1,
                    onClick = onRename,
                )
                SelectAction(
                    icon = AppIcon.Delete,
                    label = TXT_ACT_DELETE,
                    enabled = enabled && count > 0,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
                SelectAction(
                    icon = AppIcon.MoreVert,
                    label = TXT_ACT_MORE,
                    enabled = enabled,
                    onClick = onMore,
                )
            }
        }
    }
}

@Composable
private fun RowScope.SelectAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------- Sheet 行

/**
 * 底部 Sheet 的通用行：可选图标、可选打勾、可禁用。
 *
 * @param checked null = 不打勾（普通操作项）；否则按布尔值决定是否打勾
 */
@Composable
private fun SheetRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    checked: Boolean? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SHEET_ROW_HEIGHT)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (checked == true) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ---------------------------------------------------------------- 对话框

/**
 * 新建文件夹 / 重命名 共用的名称输入对话框。
 *
 * 打开时**自动聚焦并全选**现有名称（重命名预填原名，新建为空）；
 * 每帧用 [validate] 做即时校验，有错就阻止提交并在输入框下方红字提示。
 */
@Composable
private fun NameEditDialog(
    title: String,
    initial: String,
    validate: (String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value: TextFieldValue by remember(initial) {
        mutableStateOf(TextFieldValue(text = initial, selection = TextRange(0, initial.length)))
    }
    val focusRequester: FocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val errorText: String? = validate(value.text)
    // 刚打开就飘「名称不能为空」是噪音，空输入时不报红（提交按钮仍然是禁用的）
    val showError: Boolean = value.text.isNotEmpty() && errorText != null

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = Shape2xl,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(TXT_NAME_LABEL) },
                    singleLine = true,
                    isError = showError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                if (showError) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = ShapeMd,
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(
                            text = errorText ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(TXT_CANCEL) }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { onConfirm(value.text.trim()) },
                        enabled = errorText == null,
                    ) {
                        Text(TXT_OK)
                    }
                }
            }
        }
    }
}

/**
 * 删除确认框。
 *
 * ⚠️ 这是真机实现：确认后会在被控端执行 `rm -rf`，所以文案必须写明不可恢复，
 * 不能像原型那样写「模拟操作」。
 */
@Composable
private fun DeleteConfirmDialog(
    names: List<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val total: Int = names.size
    val listed: List<String> = names.take(3)
    val rest: Int = total - listed.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$TXT_DELETE_TITLE_PREFIX$total$TXT_DELETE_TITLE_SUFFIX") },
        text = {
            Column {
                for (name in listed) {
                    Text(
                        text = "· $name",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (rest > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$TXT_DELETE_REST_PREFIX$rest$TXT_DELETE_REST_SUFFIX",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = TXT_DELETE_FOOTER,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(TXT_DELETE_CONFIRM, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(TXT_CANCEL) }
        },
    )
}
