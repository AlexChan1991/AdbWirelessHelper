package com.adb.adbwirelesshelper.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adb.adbwirelesshelper.domain.model.FileEntry
import com.adb.adbwirelesshelper.ui.components.WavyProgressIndicator
import com.adb.adbwirelesshelper.ui.theme.AppIcon
import com.adb.adbwirelesshelper.ui.theme.Motion
import com.adb.adbwirelesshelper.ui.theme.Shape3xl
import com.adb.adbwirelesshelper.ui.theme.ShapeFull
import com.adb.adbwirelesshelper.ui.theme.ShapeMd
import com.adb.adbwirelesshelper.ui.theme.ShapeSm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- 文案常量

private const val TXT_CLOSE = "关闭"
private const val TXT_DETAIL = "详情"
private const val TXT_MORE = "更多"

private const val TXT_PREV = "上一张"
private const val TXT_NEXT = "下一张"
private const val TXT_ZOOM_OUT = "缩小"
private const val TXT_ZOOM_IN = "放大"
private const val TXT_RESET = "复位"
private const val TXT_ROTATE = "旋转 90°"
private const val TXT_RESET_DONE = "已复位"

private const val TXT_CD_IMAGE = "图片"
private const val TXT_LOAD_FAILED = "无法加载此图片"

private const val TXT_PANEL_TITLE_DETAIL = "详情"
private const val TXT_PANEL_TITLE_MORE = "更多操作"
private const val TXT_MENU_DETAIL = "查看详情"
private const val TXT_MENU_SHARE = "分享"
private const val TXT_MENU_RENAME = "重命名"
private const val TXT_MENU_DELETE = "删除"
private const val TXT_SHARE_UNSUPPORTED = "暂不支持分享"

private const val TXT_DELETE_TITLE = "删除这张图片？"
private const val TXT_DELETE_BODY = "会在被控端执行删除，且无法恢复。"
private const val TXT_DELETE_CONFIRM = "删除"
private const val TXT_CANCEL = "取消"

private const val TXT_RENAME_TITLE = "重命名"
private const val TXT_RENAME_LABEL = "新名称"
private const val TXT_OK = "确定"
private const val TXT_ERR_NAME_EMPTY = "名称不能为空"
private const val TXT_ERR_NAME_SLASH = "名称不能包含 “/”"
private const val TXT_RENAME_FAILED = "重命名失败"
private const val TXT_DELETE_FAILED = "删除失败"

private const val TXT_ROW_NAME = "文件名"
private const val TXT_ROW_PATH = "路径"
private const val TXT_ROW_TYPE = "类型"
private const val TXT_ROW_SIZE = "大小"
private const val TXT_ROW_MODIFIED = "修改时间"
private const val TXT_ROW_ORDER = "当前序号"
private const val TXT_ROW_VIEW = "缩放 · 旋转"

private const val TXT_KIND_IMAGE = "图像"
private const val TXT_EXT_UNKNOWN = "未知"

// ---------------------------------------------------------------- 参数常量

/** 缩放下限（1× = 适应屏幕） */
private const val MIN_SCALE: Float = 1f

/** 缩放上限（规格 §4.6.2：1×–6×） */
private const val MAX_SCALE: Float = 6f

/** 底部 ± 按钮的单次步进 */
private const val SCALE_STEP: Float = 1.35f

/** 横向拖动判定阈值：位移超过 44dp 才翻页（低于此值按「手抖」忽略） */
private const val SWIPE_THRESHOLD_DP: Float = 44f

/** HUD 浮现后停留时长 */
private const val HUD_DURATION_MS: Long = 900L

/** 覆盖层进出场时长（规格 §1.5.3：container transform / 页面转场 = 400ms） */
private const val ENTER_DURATION_MS: Int = 400

/** 覆盖层进场起始缩放 */
private const val ENTER_START_SCALE: Float = 0.96f

/** 内部 toast 停留时长（分享不支持 / 操作结果） */
private const val NOTICE_DURATION_MS: Long = 1_800L

/** 右侧面板宽度 */
private val PANEL_WIDTH: Dp = 280.dp

/** 顶栏内容高度（不含状态栏，状态栏由 windowInsetsPadding 另外让位） */
private val TOP_BAR_HEIGHT: Dp = 64.dp

/** 底部操作区（56dp 按钮行 + 8dp + 64dp 缩略图条 + 12dp 外边距）的大致高度 */
private val BOTTOM_CHROME_HEIGHT: Dp = 140.dp

/** 缩略图条单项尺寸 */
private val THUMB_SIZE: Dp = 48.dp

/**
 * 解码目标边长的放大倍数。
 *
 * 直接按屏幕尺寸采样，放大到 6× 时会糊成马赛克；这里留 2× 余量，
 * 再由 [MAX_DECODE_PIXELS] 兜住总像素，兼顾清晰度与内存。
 */
private const val DECODE_OVERSAMPLE: Float = 2f

/** 单次解码的像素预算上限：约 4M 像素（ARGB_8888 ≈ 16MB），超过就继续降采样 */
private const val MAX_DECODE_PIXELS: Long = 4_000_000L

/** 缩略图缓存里的缩略图边长（px） */
private const val THUMB_BOX_PX: Int = 128

/** 缩略图缓存容量上限；超出后回收最早放入的那张 */
private const val THUMB_CACHE_MAX: Int = 24

// ---------------------------------------------------------------- 自绘「减号」图标

/**
 * 缩小图标（24dp 减号）。
 *
 * [AppIcon] 里没有 `Minus`，而本项目**不引入 material-icons-extended**，
 * 所以仿照 AppIcons.kt 的写法在本文件内自绘：一根水平线，线宽 2f、圆头端点，
 * 与图标库其余线性图标视觉重量一致。
 */
private val IconMinus: ImageVector = ImageVector.Builder(
    name = "Minus",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).path(
    fill = null,
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 2f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
) {
    moveTo(5f, 12f)
    lineTo(19f, 12f)
}.build()

/** 右侧面板的三种状态。 */
private enum class ViewerPanel {
    /** 不显示面板 */
    NONE,

    /** 详情面板（7 行元数据） */
    DETAIL,

    /** 更多菜单（4 项） */
    MORE,
}

// ---------------------------------------------------------------- 覆盖层

/**
 * ★ 全屏图片查看器覆盖层（设计规格 §4.6.2）。
 *
 * 这是**覆盖层不是路由**：不进返回栈，关闭即回到文件管理页。调用方把它
 * 放进当前页面的 Compose 树里（通常包在 `Box` 的最后一层）即可。
 *
 * 被查看的图片物理上在**被控端设备**上，本组件通过 [readBytes]（即
 * `adb exec-out cat <path>`）把整份字节读进内存，再用 `BitmapFactory` 解码。
 * 每次 IO 动辄几 MB，所以这里做了三件事（详见 [decodeSampled] / 缩略图缓存）：
 * 1. **降采样**：先读原始尺寸再算 `inSampleSize`，避免 4000×3000 直解 OOM；
 * 2. **缩略图缓存**：已经加载过的图片复用位图，缩略图条**不重复发起 IO**；
 * 3. **及时回收**：切换 / 离开时回收正在显示的那张全尺寸位图。
 *
 * @param entries    当前目录里的图片条目（按调用方的排序原样传入）
 * @param startIndex 起始下标；越界会被夹到合法范围
 * @param readBytes  读远端文件字节，通常是 `FileRepository.readBytes(serial, path)`
 * @param onDelete   删除远端文件；成功返回 `Result.success(Unit)`
 * @param onRename   重命名远端文件（新名称**不含路径**）
 * @param onClose    关闭覆盖层；调用方负责展示 Snackbar 之类的后续提示
 */
@Composable
fun ImageViewerOverlay(
    entries: List<FileEntry>,
    startIndex: Int,
    readBytes: suspend (String) -> Result<ByteArray>,
    onDelete: suspend (String) -> Result<Unit>,
    onRename: suspend (String, String) -> Result<Unit>,
    onClose: () -> Unit,
) {
    // ---------------------------------------------------------------- 本地副本
    //
    // 入参 entries 是不可变 List，而重命名 / 删除必须立刻反映到界面上，
    // 所以内部维护一份可变副本。entries 实例变化（调用方刷新了目录）时重建，
    // 此时本地未提交的改动会被上游覆盖 —— 这是刻意的：上游才是数据源。
    val list = remember(entries) {
        mutableStateListOf<FileEntry>().apply { addAll(entries) }
    }

    if (list.isEmpty()) {
        // 空列表没有可查看的内容，直接退出（调用方一般会顺带弹一条提示）
        LaunchedEffect(Unit) { onClose() }
        return
    }

    // ---------------------------------------------------------------- 状态机
    val lastIndex: Int = list.lastIndex
    var index by remember { mutableStateOf(startIndex.coerceIn(0, lastIndex)) }
    var scale by remember { mutableStateOf(1f) }
    var rotation by remember { mutableStateOf(0) }
    var panel by remember { mutableStateOf(ViewerPanel.NONE) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(TextFieldValue()) }
    var renameError by remember { mutableStateOf<String?>(null) }

    // HUD：`hudText` 为内容，`hudSeq` 让「连续两次相同文案」也能重新计时
    var hudText by remember { mutableStateOf<String?>(null) }
    var hudSeq by remember { mutableStateOf(0) }
    var noticeText by remember { mutableStateOf<String?>(null) }
    var noticeSeq by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    val showHud: (String) -> Unit = { text -> hudText = text; hudSeq++ }
    val showNotice: (String) -> Unit = { text -> noticeText = text; noticeSeq++ }

    LaunchedEffect(hudSeq) {
        if (hudText != null) {
            delay(HUD_DURATION_MS)
            hudText = null
        }
    }
    LaunchedEffect(noticeSeq) {
        if (noticeText != null) {
            delay(NOTICE_DURATION_MS)
            noticeText = null
        }
    }

    // ---------------------------------------------------------------- 进出场
    var entered by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(closing) {
        if (closing) {
            // 等退场动画跑完再真正移除覆盖层
            delay(ENTER_DURATION_MS.toLong())
            onClose()
        }
    }
    val requestClose: () -> Unit = { if (!closing) closing = true }
    // spring 是欠阻尼的，这里统一用补间；alpha/scale 仍然先夹取再使用
    val overlayAlpha by animateFloatAsState(
        targetValue = if (entered && !closing) 1f else 0f,
        animationSpec = tween(
            durationMillis = ENTER_DURATION_MS,
            easing = Motion.EasingEmphasized,
        ),
        label = "imageViewerAlpha",
    )
    val overlayScale by animateFloatAsState(
        targetValue = if (entered && !closing) 1f else ENTER_START_SCALE,
        animationSpec = tween(
            durationMillis = ENTER_DURATION_MS,
            easing = Motion.EasingEmphasized,
        ),
        label = "imageViewerScale",
    )

    // ---------------------------------------------------------------- 解码尺寸
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val decodeMaxW: Int = remember(configuration, density) {
        with(density) { (configuration.screenWidthDp.dp * DECODE_OVERSAMPLE).roundToPx() }
    }
    val decodeMaxH: Int = remember(configuration, density) {
        with(density) { (configuration.screenHeightDp.dp * DECODE_OVERSAMPLE).roundToPx() }
    }

    // ---------------------------------------------------------------- 缩略图缓存
    //
    // ★ 缩略图条**绝不自己发起 IO**：只有当某张图片被真正查看过、解码出全尺寸位图后，
    //   才顺手缩一张 128px 的小图放进这里（同一个字节数组内完成，不额外读远端）。
    //   没缓存到的条目画灰色占位块，用户滑过缩略图条不会触发任何 adb 命令。
    //
    // 用 SnapshotStateMap 而不是普通 HashMap：写入要能被 Compose 观察到，
    // 否则新缓存进来的缩略图不会触发缩略图条重组，会一直停在占位块。
    // 以 **path** 为键而不是下标 —— 删除条目会让后面所有下标整体前移，
    // 用下标做键会导致「删一张后所有缩略图全部错位」。
    val thumbs = remember { mutableStateMapOf<String, Bitmap>() }
    val putThumb: (String, Bitmap) -> Unit = { path, bitmap ->
        if (!thumbs.containsKey(path)) {
            // 超容量时腾位置：迭代顺序不保证是严格的插入顺序，
            // 所以这里是「容量上限」而非严格 LRU，语义上足够。
            while (thumbs.size >= THUMB_CACHE_MAX) {
                val victim: String = thumbs.keys.firstOrNull() ?: break
                thumbs.remove(victim)?.let { old -> recycleLater(old) }
            }
            thumbs[path] = bitmap
        } else {
            // 已缓存过（例如来回切换），新解出来的这份直接回收，避免泄漏
            recycleLater(bitmap)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            for (bitmap in thumbs.values) {
                recycleLater(bitmap)
            }
            thumbs.clear()
        }
    }

    // ---------------------------------------------------------------- 翻页
    // 两端循环：第一张的「上一张」= 最后一张，最后一张的「下一张」= 第一张。
    // 只有 1 张时取模后仍是自己，按钮照常可点（不会变成死按钮）。
    // 翻页一定把缩放与旋转复位 —— 否则下一张会带着上一张的 6×/90° 出现。
    val goTo: (Int) -> Unit = { target ->
        val size: Int = list.size
        if (size > 0) {
            index = ((target % size) + size) % size
            scale = 1f
            rotation = 0
            hudText = null
        }
    }
    val goPrev: () -> Unit = { goTo(index - 1) }
    val goNext: () -> Unit = { goTo(index + 1) }

    // ---------------------------------------------------------------- 缩放 / 旋转
    val zoomBy: (Float) -> Unit = { factor ->
        val next: Float = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (next != scale) {
            scale = next
            showHud("${(scale * 100).toInt()}%")
        }
    }
    val zoomIn: () -> Unit = { zoomBy(SCALE_STEP) }
    val zoomOut: () -> Unit = { zoomBy(1f / SCALE_STEP) }
    val rotate: () -> Unit = {
        rotation = (rotation + 90) % 360
        showHud("$rotation°")
    }
    val reset: () -> Unit = {
        scale = 1f
        rotation = 0
        showHud(TXT_RESET_DONE)
    }

    // ---------------------------------------------------------------- 加载
    val entry: FileEntry = list[index]
    var loadFailed by remember { mutableStateOf(false) }

    // 用 loadSeq 兜住「下标没变但内容变了」的场景（删除后顶上来的那张）。
    var loadSeq by remember { mutableStateOf(0) }

    /**
     * 当前这张的位图；null 表示「加载中」或「失败」，二者由 [loadFailed] 区分。
     *
     * ⚠️ 回收刻意放在 `awaitDispose` 里、且只回收**本协程自己解出来的那一张**
     * （用局部变量 `decoded` 而不是共享的 `value`）：
     * key 变化时旧协程被取消、新协程几乎同时启动，若两边都读写同一个 `value`，
     * 就会出现「新图还没解完，旧协程把它回收了」或者「回收了还在显示的图」。
     */
    val bitmap: Bitmap? by produceState<Bitmap?>(null, index, loadSeq) {
        value = null
        loadFailed = false
        val path: String? = list.getOrNull(index)?.path
        if (path == null) {
            loadFailed = true
            return@produceState
        }
        val bytes: ByteArray? = try {
            readBytes(path).getOrNull()
        } catch (cancelled: CancellationException) {
            // 协程取消必须原样抛出，不能被当成「加载失败」吞掉
            throw cancelled
        } catch (error: Throwable) {
            null
        }
        if (bytes == null) {
            loadFailed = true
            return@produceState
        }
        val decoded: Bitmap? = decodeSampled(bytes, decodeMaxW, decodeMaxH)
        if (decoded == null) {
            loadFailed = true
            return@produceState
        }
        // 顺手缓存缩略图：复用同一份字节，不发第二次 IO
        makeThumbnail(decoded, THUMB_BOX_PX)?.let { thumb -> putThumb(path, thumb) }
        value = decoded
        // 回收只针对**本协程解出来的这一张**（decoded 是局部变量，不会误伤新图）
        awaitDispose {
            if (value === decoded) value = null
            recycleLater(decoded)
        }
    }

    // ---------------------------------------------------------------- 删除 / 重命名
    val performDelete: () -> Unit = {
        val target: FileEntry? = list.getOrNull(index)
        showDeleteConfirm = false
        panel = ViewerPanel.NONE
        if (target != null) {
            scope.launch {
                val result: Result<Unit> = onDelete(target.path)
                if (result.isSuccess) {
                    list.remove(target)
                    if (list.isEmpty()) {
                        // 列表空了就没有可看的东西，直接关闭；
                        // Snackbar「图片已删除」由调用方给。
                        onClose()
                    } else {
                        index = index.coerceIn(0, list.lastIndex)
                        scale = 1f
                        rotation = 0
                        loadSeq++
                    }
                } else {
                    val reason: String? = result.exceptionOrNull()?.message
                    showNotice(if (reason.isNullOrBlank()) TXT_DELETE_FAILED else "$TXT_DELETE_FAILED：$reason")
                }
            }
        }
    }

    val performRename: (String) -> Unit = { rawName ->
        val target: FileEntry? = list.getOrNull(index)
        val name: String = rawName.trim()
        renameError = when {
            target == null -> TXT_RENAME_FAILED
            name.isEmpty() -> TXT_ERR_NAME_EMPTY
            name.contains('/') -> TXT_ERR_NAME_SLASH
            else -> null
        }
        if (target != null && renameError == null) {
            if (name == target.name) {
                showRenameDialog = false
            } else {
                scope.launch {
                    val result: Result<Unit> = onRename(target.path, name)
                    if (result.isSuccess) {
                        val dir: String = target.path.substringBeforeLast('/')
                        val newPath: String = if (dir.isEmpty()) name else "$dir/$name"
                        list[index] = target.copy(name = name, path = newPath)
                        showRenameDialog = false
                        panel = ViewerPanel.NONE
                        showNotice("已重命名为：$name")
                    } else {
                        renameError = result.exceptionOrNull()?.message ?: TXT_RENAME_FAILED
                    }
                }
            }
        }
    }

    val openRename: () -> Unit = {
        val current: FileEntry = list[index]
        // 预填原名并全选：用户直接输入即整体替换
        renameValue = TextFieldValue(
            text = current.name,
            selection = TextRange(0, current.name.length),
        )
        renameError = null
        showRenameDialog = true
    }

    // ---------------------------------------------------------------- 布局
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(overlayAlpha.coerceIn(0f, 1f))
            .scale(overlayScale.coerceIn(0f, 1f)),
    ) {
        // Z0 图片舞台
        ImageStage(
            bitmap = bitmap,
            failed = loadFailed,
            scale = scale,
            rotation = rotation,
            gestureKey = index,
            onZoom = zoomBy,
            onSwipeLeft = goNext,
            onSwipeRight = goPrev,
            onResetRequest = reset,
        )

        // Z0 附属：右下角 n/N 序号药丸
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(end = 12.dp, bottom = BOTTOM_CHROME_HEIGHT + 8.dp),
            shape = ShapeFull,
            color = Color.Black.copy(alpha = 0.5f),
            contentColor = Color.White,
        ) {
            Text(
                text = "${index + 1}/${list.size}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }

        // Z1 顶部栏
        ViewerTopBar(
            name = entry.name,
            subtitle = "${index + 1}/${list.size} · ${FileBrowserViewModel.formatSize(entry.sizeBytes)} · ${entry.modifiedText}",
            onClose = requestClose,
            onDetail = {
                panel = if (panel == ViewerPanel.DETAIL) ViewerPanel.NONE else ViewerPanel.DETAIL
            },
            onMore = {
                panel = if (panel == ViewerPanel.MORE) ViewerPanel.NONE else ViewerPanel.MORE
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Z2 底部操作区
        ViewerBottomBar(
            index = index,
            entries = list,
            thumbs = thumbs,
            onPrev = goPrev,
            onNext = goNext,
            onZoomOut = zoomOut,
            onZoomIn = zoomIn,
            onReset = reset,
            onRotate = rotate,
            onSelect = { target -> goTo(target) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Z3 HUD（null 时自行退场，由 ViewerHud 内部的 AnimatedVisibility 处理）
        ViewerHud(
            text = hudText,
            modifier = Modifier.align(Alignment.Center),
        )

        // 内部 toast（分享不支持 / 写操作结果）
        if (noticeText != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 24.dp, end = 24.dp, bottom = BOTTOM_CHROME_HEIGHT + 8.dp),
                shape = ShapeFull,
                color = Color.White.copy(alpha = 0.92f),
                contentColor = Color.Black,
            ) {
                Text(
                    text = noticeText ?: "",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Z4 右侧面板（含半透明遮罩，点遮罩即收起）
        if (panel != ViewerPanel.NONE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { panel = ViewerPanel.NONE },
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(PANEL_WIDTH),
        ) {
            AnimatedVisibility(
                visible = panel == ViewerPanel.DETAIL,
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
                modifier = Modifier.fillMaxHeight(),
            ) {
                DetailPanel(
                    entry = entry,
                    index = index,
                    total = list.size,
                    scale = scale,
                    rotation = rotation,
                    onDismiss = { panel = ViewerPanel.NONE },
                )
            }
            AnimatedVisibility(
                visible = panel == ViewerPanel.MORE,
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
                modifier = Modifier.fillMaxHeight(),
            ) {
                MorePanel(
                    onDetail = { panel = ViewerPanel.DETAIL },
                    onShare = {
                        panel = ViewerPanel.NONE
                        showNotice(TXT_SHARE_UNSUPPORTED)
                    },
                    onRename = {
                        panel = ViewerPanel.NONE
                        openRename()
                    },
                    onDelete = { showDeleteConfirm = true },
                    onDismiss = { panel = ViewerPanel.NONE },
                )
            }
        }
    }

    // 面板里弹出的确认对话框也挂在覆盖层内（Z4）
    if (showDeleteConfirm) {
        DeleteConfirmDialog(
            onConfirm = performDelete,
            onDismiss = { showDeleteConfirm = false },
        )
    }
    if (showRenameDialog) {
        RenameDialog(
            value = renameValue,
            error = renameError,
            onValueChange = { renameValue = it; renameError = null },
            onConfirm = { performRename(renameValue.text) },
            onDismiss = { showRenameDialog = false },
        )
    }
}

// ---------------------------------------------------------------- Z0 图片舞台

/**
 * 图片舞台：黑底、居中大图，承载缩放 / 旋转 / 翻页三种手势。
 *
 * 手势分三条 `pointerInput` 挂，彼此不抢事件：
 * - 双指捏合 [detectTransformGestures]（panZoomLock = true，只取缩放，忽略位移与旋转手势）；
 * - 双击 [detectTapGestures] = 复位；
 * - 单指横向拖动 [detectHorizontalDragGestures]，累计位移 > 44dp 才翻页。
 *
 * @param gestureKey 下标。作为 `pointerInput` 的 key：翻页后重启手势块，
 *                   保证 lambda 里拿到的是最新的 [onSwipeLeft] / [onSwipeRight]。
 */
@Composable
private fun ImageStage(
    bitmap: Bitmap?,
    failed: Boolean,
    scale: Float,
    rotation: Int,
    gestureKey: Int,
    onZoom: (Float) -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onResetRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ★ `Dp.toPx()` 不是顶层扩展函数，而是 `Density` 接口的**成员**，
    //   所以在 pointerInput 的 lambda 里没法直接调（那边没有 Density 作用域）。
    //   这里在 Composable 层用 LocalDensity 先算成 px 再传进手势块。
    val density: Density = LocalDensity.current
    val thresholdPx: Float = remember(density) {
        with(density) { SWIPE_THRESHOLD_DP.dp.toPx() }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(gestureKey) {
                detectTransformGestures(panZoomLock = true) { _, _, zoom, _ -> onZoom(zoom) }
            }
            .pointerInput(gestureKey) {
                detectTapGestures(onDoubleTap = { onResetRequest() })
            }
            .pointerInput(gestureKey) {
                var accumulated = 0f
                val threshold: Float = thresholdPx
                detectHorizontalDragGestures(
                    onDragEnd = { accumulated = 0f },
                    onDragCancel = { accumulated = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        accumulated += dragAmount
                        when {
                            // 往左滑 → 下一张
                            accumulated <= -threshold -> {
                                accumulated = 0f
                                onSwipeLeft()
                            }
                            // 往右滑 → 上一张
                            accumulated >= threshold -> {
                                accumulated = 0f
                                onSwipeRight()
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = TXT_CD_IMAGE,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(scale)
                    .rotate(rotation.toFloat()),
                contentScale = ContentScale.Fit,
            )
        }

        when {
            failed -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        AppIcon.Warning,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = TXT_LOAD_FAILED,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            // bitmap 为 null 且还没判失败 = 加载中
            bitmap == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // 黑底上看不见主题色，这里显式传白色
                    WavyProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        size = 40.dp,
                        stroke = 3.dp,
                        color = Color.White,
                    )
                }
            }

            else -> Unit
        }
    }
}

// ---------------------------------------------------------------- Z1 顶部栏

/**
 * 顶部栏：左关闭、中「文件名 + 序号·大小·修改时间」、右「详情 / 更多」。
 *
 * targetSdk = 35 在 Android 15 起强制 edge-to-edge，自定义顶栏必须自己
 * `windowInsetsPadding(WindowInsets.statusBars)`，否则会画到状态栏底下。
 */
@Composable
private fun ViewerTopBar(
    name: String,
    subtitle: String,
    onClose: () -> Unit,
    onDetail: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(TOP_BAR_HEIGHT)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(AppIcon.Close, contentDescription = TXT_CLOSE, tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDetail) {
            Icon(AppIcon.Info, contentDescription = TXT_DETAIL, tint = Color.White)
        }
        IconButton(onClick = onMore) {
            Icon(AppIcon.MoreVert, contentDescription = TXT_MORE, tint = Color.White)
        }
    }
}

// ---------------------------------------------------------------- Z2 底部操作区

/**
 * 底部操作区（规格 P3 原则：**常驻不自动隐藏**）。
 *
 * 上半：一行 6 枚图标（上一张 / 缩小 / 复位 / 放大 / 旋转 / 下一张）；
 * 下半：缩略图条，当前项描边高亮，点击直接跳。
 */
@Composable
private fun ViewerBottomBar(
    index: Int,
    entries: List<FileEntry>,
    thumbs: Map<String, Bitmap>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onReset: () -> Unit,
    onRotate: () -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .background(Color.Black.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerIconButton(icon = AppIcon.Back, desc = TXT_PREV, onClick = onPrev)
            ViewerIconButton(icon = IconMinus, desc = TXT_ZOOM_OUT, onClick = onZoomOut)
            ViewerIconButton(icon = AppIcon.Refresh, desc = TXT_RESET, onClick = onReset)
            ViewerIconButton(icon = AppIcon.Add, desc = TXT_ZOOM_IN, onClick = onZoomIn)
            ViewerIconButton(icon = AppIcon.Rotate, desc = TXT_ROTATE, onClick = onRotate)
            ViewerIconButton(icon = AppIcon.ChevronRight, desc = TXT_NEXT, onClick = onNext)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.forEachIndexed { i, item ->
                ThumbnailItem(
                    bitmap = thumbs[item.path],
                    selected = i == index,
                    onClick = { onSelect(i) },
                )
            }
        }
    }
}

/** 底部操作区的一枚图标按钮。 */
@Composable
private fun ViewerIconButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = desc, tint = Color.White)
    }
}

/**
 * 缩略图条单项。
 *
 * 只有**缓存里已经有**缩略图时才画真图，否则画灰色占位块 ——
 * 缩略图条不发起任何 IO，避免横向一滑就打出一串 adb 命令。
 */
@Composable
private fun ThumbnailItem(
    bitmap: Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor: Color = if (selected) {
        Color.White
    } else {
        Color.White.copy(alpha = 0.18f)
    }
    Box(
        modifier = Modifier
            .size(THUMB_SIZE)
            .clip(ShapeMd)
            .background(Color.White.copy(alpha = if (selected) 0.22f else 0.08f))
            .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = ShapeMd)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(ShapeSm)
                    .background(Color.White.copy(alpha = 0.25f)),
            )
        }
    }
}

// ---------------------------------------------------------------- Z3 HUD

/**
 * 居中 HUD：缩放 / 旋转 / 复位时短提示（`135%` / `90°` / `已复位`）。
 *
 * 用 [Motion.springFast] 浮出。这里刻意用 `AnimatedVisibility` 而不是
 * `animateFloatAsState(targetValue = 1f)`：后者**初始值就等于目标值**，
 * 第一次出现时没有任何动画（看着像直接蹦出来）。
 *
 * `text == null` 时走 exit 动画；AnimatedVisibility 在退场期间会保留最后一次的
 * 内容，所以不会出现「文字先变空再淡出」。
 */
@Composable
private fun ViewerHud(
    text: String?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = scaleIn(
            animationSpec = Motion.springFast(),
            initialScale = 0.85f,
        ) + fadeIn(animationSpec = Motion.springFast()),
        exit = scaleOut(
            animationSpec = Motion.springFast(),
            targetScale = 0.92f,
        ) + fadeOut(animationSpec = Motion.springFast()),
    ) {
        Surface(
            shape = ShapeFull,
            color = Color.Black.copy(alpha = 0.62f),
            contentColor = Color.White,
        ) {
            Text(
                text = text ?: "",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------- Z4 右侧面板

/** 详情面板：7 行元数据。 */
@Composable
private fun DetailPanel(
    entry: FileEntry,
    index: Int,
    total: Int,
    scale: Float,
    rotation: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(),
        shape = Shape3xl,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = TXT_PANEL_TITLE_DETAIL,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss) {
                    Icon(AppIcon.Close, contentDescription = TXT_CLOSE)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            DetailRow(label = TXT_ROW_NAME, value = entry.name)
            DetailRow(label = TXT_ROW_PATH, value = entry.path)
            DetailRow(label = TXT_ROW_TYPE, value = typeLabelOf(entry.name))
            DetailRow(
                label = TXT_ROW_SIZE,
                value = FileBrowserViewModel.formatSize(entry.sizeBytes),
            )
            DetailRow(label = TXT_ROW_MODIFIED, value = entry.modifiedText)
            DetailRow(label = TXT_ROW_ORDER, value = "${index + 1} / $total")
            DetailRow(
                label = TXT_ROW_VIEW,
                value = "${(scale * 100).toInt()}% · ${rotation}°",
            )
        }
    }
}

/** 详情面板的一行：左标签、右数值（数值可换行，路径很长时要看全）。 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 更多菜单：查看详情 / 分享 / 重命名 / 删除。 */
@Composable
private fun MorePanel(
    onDetail: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(),
        shape = Shape3xl,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = TXT_PANEL_TITLE_MORE,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss) {
                    Icon(AppIcon.Close, contentDescription = TXT_CLOSE)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            MenuItem(icon = AppIcon.Info, text = TXT_MENU_DETAIL, onClick = onDetail)
            MenuItem(icon = AppIcon.Copy, text = TXT_MENU_SHARE, onClick = onShare)
            MenuItem(icon = AppIcon.Clipboard, text = TXT_MENU_RENAME, onClick = onRename)
            MenuItem(
                icon = AppIcon.Delete,
                text = TXT_MENU_DELETE,
                onClick = onDelete,
                danger = true,
            )
        }
    }
}

/** 更多菜单的一项。 */
@Composable
private fun MenuItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val foreground: Color = if (danger) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeMd)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- 对话框

/** 删除确认框。 */
@Composable
private fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(TXT_DELETE_TITLE) },
        text = { Text(TXT_DELETE_BODY) },
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

/**
 * 重命名对话框。
 *
 * 打开时由调用方预填原名并全选（[TextRange] 覆盖整段），用户直接输入即整体替换。
 */
@Composable
private fun RenameDialog(
    value: TextFieldValue,
    error: String?,
    onValueChange: (TextFieldValue) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(TXT_RENAME_TITLE) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(TXT_RENAME_LABEL) },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(TXT_OK) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(TXT_CANCEL) }
        },
    )
}

// ---------------------------------------------------------------- 解码 / 工具

/**
 * 按屏幕尺寸降采样解码，返回可直接显示的 [Bitmap]；失败返回 null。
 *
 * ★ 为什么必须采样：被控端的照片动辄 4000×3000，按 ARGB_8888 直接解码就是
 *   4000×3000×4B ≈ **48MB** 一块内存。低端机单进程堆上限常只有 128–256MB，
 *   OOM 会直接把 App 打死，而且用户看到的是「点了图片就闪退」，没有任何提示。
 *
 * 做法是标准的「两段式」：
 * 1. `inJustDecodeBounds = true` 只解析文件头拿到原始宽高，**不分配像素内存**；
 * 2. 用 [computeInSampleSize] 算出 2 的幂次采样率，再真正解码。
 *
 * 另外还留了 2× 的边长余量（[DECODE_OVERSAMPLE]）以便放大到 6× 时不至于太糊，
 * 并由 [MAX_DECODE_PIXELS] 兜住总像素 —— 长条图即使边长达标，总像素仍可能爆。
 */
private fun decodeSampled(bytes: ByteArray, maxW: Int, maxH: Int): Bitmap? {
    // 第一段：只解析边界
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val srcW: Int = bounds.outWidth
    val srcH: Int = bounds.outHeight
    if (srcW <= 0 || srcH <= 0) return null

    // 第二段：真正解码
    val options = BitmapFactory.Options().apply {
        inSampleSize = computeInSampleSize(srcW, srcH, maxW, maxH)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (error: OutOfMemoryError) {
        // 采样后仍不够（比如同时驻留了多张大图）：宁可显示「无法加载」，也不要崩
        null
    } catch (error: Throwable) {
        null
    }
}

/**
 * 计算 2 的幂次采样率，使解码结果**不超过** [maxW] × [maxH]，且总像素不超过 [MAX_DECODE_PIXELS]。
 *
 * 只取 2 的幂：`BitmapFactory` 对非 2 的幂会向下取整到最近的 2 的幂，
 * 自己算成非 2 的幂没有意义，反而让人误以为精确。
 */
private fun computeInSampleSize(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Int {
    var sample = 1
    while (srcW / sample > maxW || srcH / sample > maxH) {
        sample = sample shl 1
        if (sample > 32) break
    }
    // 像素预算兜底：长条图即使边长达标，总像素也可能过大
    while (sample <= 32) {
        val w: Long = (srcW / sample).toLong()
        val h: Long = (srcH / sample).toLong()
        if (w <= 0L || h <= 0L || w * h <= MAX_DECODE_PIXELS) break
        sample = sample shl 1
    }
    return sample.coerceIn(1, 32)
}

/**
 * 从已解码的大图缩一张缩略图出来，供缩略图条复用。
 *
 * ⚠️ 必须返回**新**的 [Bitmap]：全尺寸那张会在切换 / 离开时被 `recycle()`，
 *    如果这里直接返回原对象（`Bitmap.createScaledBitmap` 在尺寸相同时会原样返回 src），
 *    缩略图条就会画到一张已回收的位图 —— 那是一次必崩的
 *    `Canvas: trying to use a recycled bitmap`。
 */
private fun makeThumbnail(source: Bitmap, boxPx: Int): Bitmap? {
    val w: Int = source.width
    val h: Int = source.height
    if (w <= 0 || h <= 0) return null
    return try {
        if (w <= boxPx && h <= boxPx) {
            // 原图已经很小：仍要复制一份（不能返回 source，理由见 KDoc）
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        } else {
            val ratio: Float = boxPx.toFloat() / maxOf(w, h).toFloat()
            Bitmap.createScaledBitmap(
                source,
                (w * ratio).toInt().coerceAtLeast(1),
                (h * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
    } catch (error: OutOfMemoryError) {
        null
    } catch (error: Throwable) {
        null
    }
}

/**
 * 把位图的回收**推迟到下一个主线程消息**执行。
 *
 * ★ 为什么不能直接 `recycle()`：Compose 一帧的执行顺序是
 *   `重组 → apply（awaitDispose / DisposableEffect 在这里跑）→ 布局 → 绘制`，
 *   而 apply 阶段里产生的失效要等**下一帧**才会被处理。也就是说：切换图片时
 *   旧协程在 apply 阶段回收了位图，本帧的绘制却仍然拿着上一棵（含这张图的）旧树去画
 *   → `java.lang.IllegalStateException / RuntimeException: Can't draw a recycled bitmap`。
 *
 *   推到下一个主线程消息再回收，那时本帧已经画完，而下一帧的树里这张图已经被移除，
 *   不会再有人去画它。缩略图缓存的淘汰 / 清空同理。
 */
private fun recycleLater(bitmap: Bitmap) {
    Handler(Looper.getMainLooper()).post {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/** 「扩展名大写 · 图像」；没有扩展名时显示「未知 · 图像」。 */
private fun typeLabelOf(name: String): String {
    val dot: Int = name.lastIndexOf('.')
    val ext: String = if (dot > 0 && dot < name.length - 1) {
        name.substring(dot + 1).uppercase()
    } else {
        TXT_EXT_UNKNOWN
    }
    return "$ext · $TXT_KIND_IMAGE"
}
