package com.adb.adbwirelesshelper.ui.screen

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import com.adb.adbwirelesshelper.data.scrcpy.ControlProtocol
import com.adb.adbwirelesshelper.ui.components.WavyProgressIndicator
import com.adb.adbwirelesshelper.ui.theme.AppIcon
import com.adb.adbwirelesshelper.ui.theme.Motion
import com.adb.adbwirelesshelper.ui.theme.Radius
import com.adb.adbwirelesshelper.ui.theme.Shape2xl
import com.adb.adbwirelesshelper.ui.theme.ShapeFull
import com.adb.adbwirelesshelper.ui.theme.ShapeLg
import com.adb.adbwirelesshelper.ui.theme.ShapeSm
import com.adb.adbwirelesshelper.util.bitrateText

/** ---------- 页面文案（集中在此，不新增 strings.xml 条目） ---------- */
private const val TEXT_DEPLOYING: String = "正在推送 scrcpy-server 并建立隧道…"
private const val TEXT_CONNECTING: String = "正在握手，等待首帧…"
private const val TEXT_RECONNECTING: String = "投屏中断，正在自动重连"
private const val TEXT_EXIT_TITLE: String = "退出投屏"
private const val TEXT_EXIT_BODY: String = "退出会关闭视频与控制通道、释放解码器并移除端口转发。确认退出？"
private const val TEXT_CONFIRM: String = "确认退出"
private const val TEXT_CANCEL: String = "取消"
private const val TEXT_RETRY: String = "重试"
private const val TEXT_STAT_FPS: String = "fps"
private const val TEXT_STAT_LATENCY: String = "延迟"
private const val TEXT_STAT_DROPPED: String = "丢帧"
private const val TEXT_ACTION_HOME: String = "主页"
private const val TEXT_ACTION_BACK: String = "返回"
private const val TEXT_ACTION_RECENT: String = "最近任务"
private const val TEXT_ACTION_VOLUME_UP: String = "音量+"
private const val TEXT_ACTION_VOLUME_DOWN: String = "音量-"
private const val TEXT_ACTION_POWER: String = "电源"
private const val TEXT_ACTION_ROTATE: String = "旋转"
private const val TEXT_ACTION_FULLSCREEN: String = "全屏"
private const val TEXT_ACTION_EXIT: String = "退出"
/** 方向策略按钮上的短标签：跟随被控端 / 锁竖屏 / 锁横屏 */
private const val TEXT_ORIENT_FOLLOW: String = "跟随"
private const val TEXT_ORIENT_PORTRAIT: String = "竖屏"
private const val TEXT_ORIENT_LANDSCAPE: String = "横屏"

/** 音频徽标与统计叠层里的音频项文案 */
private const val TEXT_AUDIO_ON: String = "音频开"
private const val TEXT_AUDIO_STAT_OFF: String = "A:关"

/**
 * 音频徽标距顶部的偏移。
 *
 * 统计叠层固定在 `top = 56dp`，全屏时两者都在左上角，所以徽标要往下让开，
 * 避免叠在一起；非全屏时不显示统计叠层，直接用 56dp。
 */
private const val AUDIO_BADGE_TOP_DP: Int = 56
private const val AUDIO_BADGE_TOP_WITH_STATS_DP: Int = 92

/** 顶部工具条里每个图标按钮的方框边长（dp）。 */
private const val TOOLBAR_ICON_BOX_DP: Int = 40

/** 底部导航键的方框边长（dp）：比工具条略大，方便拇指点。 */
private const val NAV_KEY_SIZE_DP: Int = 48

/** 图标本体占方框的比例：0.5 即 24dp 方框配 12dp 图标。 */
private const val TOOLBAR_ICON_RATIO: Float = 0.5f

/**
 * 统计叠层 M5 形变的「宽态」圆角（规格 §1.3 M5 / §4.3.1 Z3）。
 *
 * 形变的两个端点：**带音频项时内容偏宽** → 10dp 圆角矩形；**不带音频项时内容变窄**
 * → `Radius.Full` 胶囊。形变不是离散切换，而是这段：[10.dp] ↔ [Radius.Full]，
 * 由 `animateDpAsState` + `Motion.springDefault()` 连续过渡。
 */
private val STATS_CORNER_WIDE: Dp = 10.dp

/** 画面首帧淡入的起始缩放（规格 §4.3.1 Z0 / §1.5.1 Overshoot spring）。 */
private const val FIRST_FRAME_SCALE_START: Float = 0.9f

/**
 * 投屏控制页（对应原型图 ④）。
 *
 * 结构：
 * - 全屏 `AndroidView(TextureView)` 承载解码画面，并通过 SurfaceTextureListener 把 Surface 交给 ViewModel；
 * - 顶部悬浮工具条：**固定显示，不自动隐藏**（音量／电源／旋转／方向／全屏／退出）；
 * - 底部悬浮导航条：安卓三键（返回 / 主页 / 最近任务），同样固定显示；
 * - 左上角半透明统计叠层：fps / 码率 / 延迟 / 丢帧；
 * - 返回键两段式：先退全屏，再二次确认退出。
 *
 * @param vm     由 AppViewModelFactory 注入的投屏 ViewModel
 * @param serial 目标设备序列号
 * @param onExit 退出回调（退出前的清理四件套由 ViewModel 负责）
 */
@Composable
fun MirrorScreen(
    vm: MirrorViewModel,
    serial: String,
    onExit: () -> Unit
) {
    val context: android.content.Context = LocalContext.current
    val activity: Activity? = remember(context) { context.findActivity() }

    LaunchedEffect(serial) {
        vm.bind(serial)
        vm.start()
    }
    DisposableEffect(Unit) {
        vm.onResume()
        onDispose {
            vm.onPause()
            vm.stop()
        }
    }
    KeepScreenOnEffect(activity)

    val state: MirrorState by vm.state.collectAsState()
    val remoteSize: RemoteSize by vm.remoteSize.collectAsState()
    val orientationMode: OrientationMode by vm.orientationMode.collectAsState()

    // ---- 画面首帧淡入（规格 §4.3.1 Z0 / §1.5.1 Overshoot spring，400ms）----
    // 触发源只有「是否出过图」，不去 ViewModel 里新增可见状态：Streaming 即代表最后一帧已渲染。
    //
    // ⚠️ Reconnecting / Failed 也要算「已出图」：规格 §4.3.3 明确这两种状态是**保留最后一帧**，
    //    若只认 Streaming，断流 / 失败那一瞬间画面会被淡出成黑屏，等于把用户最后一眼看到的画面抹掉。
    val pictureRevealed: Boolean =
        state is MirrorState.Streaming ||
            state is MirrorState.Reconnecting ||
            state is MirrorState.Failed
    val firstFrameScale: Float by animateFloatAsState(
        targetValue = if (pictureRevealed) 1f else FIRST_FRAME_SCALE_START,
        animationSpec = Motion.springOvershoot(),
        label = "firstFrameScale"
    )
    val firstFrameAlpha: Float by animateFloatAsState(
        targetValue = if (pictureRevealed) 1f else 0f,
        animationSpec = Motion.springOvershoot(),
        label = "firstFrameAlpha"
    )

    var fullscreen: Boolean by remember { mutableStateOf(true) }
    var exitConfirming: Boolean by remember { mutableStateOf(false) }

    // 方向跟随：把控制端窗口方向对齐到被控端画面方向（「按被控设备尺寸改变控制端方向」）。
    // 前提是 AndroidManifest 里给 MainActivity 声明了 configChanges —— 否则每次转向都会重建
    // Activity → MirrorScreen 被 dispose → vm.stop() 把投屏会话整个拆掉。
    //
    // 去重：MIUI letterbox 兼容模式下，对同一个方向值重复调用 requestOrientation 不会被尊重，
    // 系统反而把它当成「又要改方向」→ 重新 letterbox / recreate 窗口 → 触发底层 Surface 重建
    // → 解码器失配 → 投屏断流。这里只在目标值 != 上次下发值时才真正设置。
    var lastRequestedOrientation: Int? by remember { mutableStateOf(null) }
    LaunchedEffect(activity, orientationMode, remoteSize) {
        val target = pickOrientation(orientationMode, remoteSize)
        if (target != lastRequestedOrientation) {
            lastRequestedOrientation = target
            runCatching { activity?.requestedOrientation = target }
        }
    }
    DisposableEffect(activity) {
        onDispose {
            // 离开投屏页恢复系统默认，别把方向锁泄漏到列表页 / 详情页；
            // 同时重置记录值，避免下次进入时残留旧值跳过必要的设置。
            lastRequestedOrientation = null
            runCatching {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // 控制栏固定常显，不再有「无操作自动隐藏」逻辑（原 toolbarVisible / poke() 已移除）。

    BackHandler(enabled = true) {
        if (fullscreen) {
            // 第一段：退出全屏（工具条固定显示，不再被返回键收起）
            fullscreen = false
            setFullscreen(activity, false)
        } else {
            // 第二段：弹二次确认
            exitConfirming = true
        }
    }

    fun doExit() {
        exitConfirming = false
        vm.stop()
        onExit()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ---- 等比适配（letterbox / pillarbox）----
        // 画面完整可见且不拉伸变形，多出来的部分留黑边。
        // 这一步同时决定了触控坐标系：pointerInput 就挂在「等于画面本身」的这块区域上，
        // 所以 RawTouch 里的坐标天然是画面内坐标，换算到远端是纯线性映射，无需补偿留白偏移。
        val containerWidth: Float = maxWidth.value
        val containerHeight: Float = maxHeight.value
        val ratio: Float = remoteSize.aspect
        val videoWidth: Dp
        val videoHeight: Dp
        if (ratio > 0f && containerWidth > 0f && containerHeight > 0f) {
            if (ratio >= containerWidth / containerHeight) {
                // 画面比屏幕更「宽」：以宽度为准，上下留黑边
                videoWidth = maxWidth
                videoHeight = (containerWidth / ratio).dp
            } else {
                // 画面比屏幕更「高」：以高度为准，左右留黑边
                videoHeight = maxHeight
                videoWidth = (containerHeight * ratio).dp
            }
        } else {
            // 还没拿到尺寸（握手完成前）：先铺满，拿到后会自动收敛到正确比例
            videoWidth = maxWidth
            videoHeight = maxHeight
        }

        AndroidView(
            factory = { ctx ->
                // 改用 TextureView 而非 SurfaceView：TextureView 的 Surface 由我们持有的
                // SurfaceTexture 决定，不绑定窗口专属 BufferQueue，窗口层 relayout/recreate
                // 时不会换新 Surface → 解码器不再失配（这是投屏断流的根因，见改动说明）。
                TextureView(ctx).apply {
                    // cached 必须缓存并复用同一个 Surface 对象：Surface(surfaceTexture)
                    // 每次调用都返回新对象，而 MirrorViewModel.attachSurface() 用引用相等（===）
                    // 比对 Surface 身份。若 onSurfaceTextureSizeChanged 里重新 new Surface，
                    // 会被判为「Surface 换了」→ 触发换绑（MTK setOutputSurface 必失败）→ 重建解码器。
                    var cached: Surface? = null
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            st: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            cached?.release()
                            cached = Surface(st)
                            if (remoteSize.width > 0 && remoteSize.height > 0) {
                                // MediaCodec 输出到 Surface 时会自行设置 buffer 尺寸；
                                // 这里预设是为了覆盖 SurfaceTexture 在 MediaCodec configure
                                // 之前就已存在的场景，避免解码分辨率 ≠ 纹理缓冲尺寸导致采样错位/黑屏。
                                st.setDefaultBufferSize(remoteSize.width, remoteSize.height)
                            }
                            vm.attachSurface(cached)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            st: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            // 关键：不要重建 Surface，直接复用缓存对象。
                            // 同一对象 → ViewModel 判定复用，不触发换绑，MTK 不会失配。
                            vm.attachSurface(cached)
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            vm.attachSurface(null)
                            cached?.release()
                            cached = null
                            // return true 表示允许系统释放 SurfaceTexture
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                            // 无需处理
                        }
                    }
                }
            },
            update = { view ->
                // 每次重组都会调用：确保缓冲尺寸与解码分辨率对账，覆盖首次创建时
                // remoteSize 尚未就绪、而 SurfaceTexture 已先存在的场景。
                if (remoteSize.width > 0 && remoteSize.height > 0) {
                    view.surfaceTexture?.setDefaultBufferSize(remoteSize.width, remoteSize.height)
                }
            },
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = videoWidth, height = videoHeight)
                // 首帧 Overshoot 淡入：0.9→1.0 缩放 + alpha 0→1，spring(0.45, 320) ≈ 400ms
                // （规格 §4.3.1 Z0 / §1.5.1）。alpha 的目标值虽是 1f，但 Overshoot spring
                // 会短暂冲过 target，>1 的层透明度是无意义的，故用 coerceIn 夹住。
                .graphicsLayer {
                    scaleX = firstFrameScale
                    scaleY = firstFrameScale
                    alpha = firstFrameAlpha.coerceIn(0f, 1f)
                }
                .pointerInput(remoteSize.width, remoteSize.height) {
                    // ⚠️ 画面矩形的像素尺寸必须在循环内**每次事件重新读**（AwaitPointerEventScope.size），
                    // 不能在循环外只读一次：控制端转向时 SurfaceView 尺寸会变，但 remoteSize 没变，
                    // 于是 pointerInput 的 key 不变、这个 block 不会重启 —— 若缓存了旧尺寸，
                    // 旋转之后触控映射整体偏移。读实时 size 与截图里看到的画面严格一致。
                    // 单一手势通道：只透传原生 DOWN / MOVE / UP，不再本地合成点击/双击/长按。
                    // 点击、双击、长按、拖动、惯性全部交给被控端自己判定 —— 这才和真机一致，
                    // 也是「拖动不再丢失」的原因（旧实现用两个独立识别器互相吃事件）。
                    // 遍历 event.changes 可让多指各自带独立 pointerId 一起转发（双指缩放可用）。
                    awaitEachGesture {
                        // 当前「已按下但尚未抬起」的触点：pointerId → 最后已知位置。
                        // 只用于下面 finally 里的兜底抬指，不参与正常事件流。
                        val activePointers: LinkedHashMap<Long, Offset> = LinkedHashMap()
                        // 循环里最近一次读到的画布尺寸。兜底抬指时循环变量已不在作用域，所以提到外面。
                        var lastCanvasWidthPx: Int = 0
                        var lastCanvasHeightPx: Int = 0
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val canvasWidthPx: Int = size.width
                                val canvasHeightPx: Int = size.height
                                lastCanvasWidthPx = canvasWidthPx
                                lastCanvasHeightPx = canvasHeightPx
                                var anyPressed: Boolean = false
                                for (change in event.changes) {
                                    val pointerId: Long = change.id.value
                                    val position: Offset = change.position
                                    when {
                                        change.pressed && !change.previousPressed -> {
                                            vm.onTouch(
                                                RawTouch(
                                                    TouchKind.DOWN,
                                                    position.x,
                                                    position.y,
                                                    canvasWidthPx,
                                                    canvasHeightPx,
                                                    pointerId
                                                )
                                            )
                                            activePointers[pointerId] = position
                                            change.consume()
                                            anyPressed = true
                                        }

                                        change.pressed -> {
                                            vm.onTouch(
                                                RawTouch(
                                                    TouchKind.MOVE,
                                                    position.x,
                                                    position.y,
                                                    canvasWidthPx,
                                                    canvasHeightPx,
                                                    pointerId
                                                )
                                            )
                                            activePointers[pointerId] = position
                                            change.consume()
                                            anyPressed = true
                                        }

                                        change.previousPressed -> {
                                            vm.onTouch(
                                                RawTouch(
                                                    TouchKind.UP,
                                                    position.x,
                                                    position.y,
                                                    canvasWidthPx,
                                                    canvasHeightPx,
                                                    pointerId
                                                )
                                            )
                                            activePointers.remove(pointerId)
                                            change.consume()
                                        }
                                    }
                                }
                                if (!anyPressed) break
                            }
                        } finally {
                            // ★ 兜底补发 UP：这个 block 的 key 是远端分辨率，被控端旋转
                            // （或解码器上报新尺寸）会让 key 变化 → 整个协程被**取消重启**。
                            // 取消发生在 `awaitPointerEvent()` 挂起点，取消**不会**再送来一个
                            // 「抬起」事件，于是正在拖动的那根手指永远不会发 UP，
                            // 被控端就**永久停在按下状态**（之后点哪都像长按/拖拽，必须重启会话才能恢复）。
                            // 这里对仍然按着的触点各补发一次 UP。
                            //
                            // 注意：`finally` 在取消路径上也会执行，而 `vm.onTouch` →
                            // `sendAsync` 已改为**非挂起**的 `trySend`，所以在已取消的协程里调用是安全的。
                            for ((pointerId, position) in activePointers) {
                                vm.onTouch(
                                    RawTouch(
                                        TouchKind.UP,
                                        position.x,
                                        position.y,
                                        lastCanvasWidthPx,
                                        lastCanvasHeightPx,
                                        pointerId
                                    )
                                )
                            }
                            activePointers.clear()
                        }
                    }
                }
        )

        // ---- 左上角统计叠层（可随工具条一起隐藏） ----
        val streaming: MirrorState.Streaming? = state as? MirrorState.Streaming
        // 智能转型不会穿过 `if` 之外，这里先取出纯值，避免在 lambda / 后续分支里失效
        val audioActive: Boolean = streaming?.audioActive == true
        val audioNote: String = streaming?.audioNote.orEmpty()
        if (fullscreen && streaming != null) {
            StatsOverlay(
                fps = streaming.fps,
                kbps = streaming.kbps,
                latencyMs = streaming.latencyMs,
                dropped = streaming.dropped,
                audioLabel = if (audioActive || audioNote.isNotBlank()) {
                    audioStatLabel(active = audioActive)
                } else {
                    null
                }
            )
        }

        // ---- 音频状态徽标：本功能唯一的可诊断出口 ----
        // 未开音频 / 无任何提示时**完全不渲染**，保证 audioEnabled=false 时界面与改动前一致。
        // note 是给用户排查用的原文，这里原样展示，不改写、不截半句（仅限 2 行 + 省略号）。
        if (audioActive || audioNote.isNotBlank()) {
            AudioBadge(
                active = audioActive,
                note = audioNote,
                topPadding = if (fullscreen) AUDIO_BADGE_TOP_WITH_STATS_DP else AUDIO_BADGE_TOP_DP
            )
        }

        // ---- 顶部悬浮工具条（固定显示） ----
        MirrorToolbar(
            orientationLabel = orientationLabel(orientationMode),
            onOrientation = { vm.cycleOrientationMode() },
            onVolumeUp = { vm.injectKeyTap(ControlProtocol.KEYCODE_VOLUME_UP) },
            onVolumeDown = { vm.injectKeyTap(ControlProtocol.KEYCODE_VOLUME_DOWN) },
            onPower = { vm.toggleDisplayPower() },
            onRotate = { vm.rotateDevice() },
            onFullscreen = {
                fullscreen = !fullscreen
                setFullscreen(activity, fullscreen)
            },
            onExit = { exitConfirming = true }
        )

        // ---- 底部安卓三键导航条（固定显示） ----
        MirrorNavBar(
            onBack = { vm.injectKeyTap(ControlProtocol.KEYCODE_BACK) },
            onHome = { vm.injectKeyTap(ControlProtocol.KEYCODE_HOME) },
            onRecents = { vm.injectKeyTap(ControlProtocol.KEYCODE_APP_SWITCH) }
        )

        // ---- 状态提示层 ----
        when (state) {
            MirrorState.Idle -> Unit

            MirrorState.Deploying -> CenterHint(message = TEXT_DEPLOYING, spinning = true)

            MirrorState.Connecting -> CenterHint(message = TEXT_CONNECTING, spinning = true)

            // 中途断流、正在自动重连：明确告诉用户「第几次」，
            // 否则他只会看到转圈，以为 App 卡死从而退出重进（那反而会把会话拆掉）。
            is MirrorState.Reconnecting -> {
                // state 是 collectAsState() 的委托属性，编译器不允许对委托属性做智能转换，
                // 所以先取到局部 val 再读字段。
                val re: MirrorState.Reconnecting = state as MirrorState.Reconnecting
                CenterHint(
                    message = "$TEXT_RECONNECTING（${re.attempt}/${re.maxAttempt}）",
                    spinning = true
                )
            }

            is MirrorState.Streaming -> Unit

            is MirrorState.Failed -> CenterFailure(
                message = (state as MirrorState.Failed).message,
                onRetry = { vm.restart() },
                onExit = { doExit() }
            )
        }

        if (exitConfirming) {
            AlertDialog(
                onDismissRequest = { exitConfirming = false },
                title = { Text(text = TEXT_EXIT_TITLE) },
                text = { Text(text = TEXT_EXIT_BODY) },
                confirmButton = {
                    TextButton(onClick = { doExit() }) {
                        Text(text = TEXT_CONFIRM)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { exitConfirming = false }) {
                        Text(text = TEXT_CANCEL)
                    }
                }
            )
        }
    }

    DisposableEffect(fullscreen) {
        setFullscreen(activity, fullscreen)
        onDispose { setFullscreen(activity, false) }
    }
}

/**
 * 顶部悬浮工具条（**固定显示，不自动隐藏**）。
 *
 * 只放「控制端自身」的开关：音量 / 电源 / 旋转 / 方向策略 / 全屏 / 退出。
 * 安卓三键（返回 / 主页 / 最近任务）已移到 [MirrorNavBar]，不再占据顶部空间。
 */
@Composable
private fun MirrorToolbar(
    orientationLabel: String,
    onOrientation: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onPower: () -> Unit,
    onRotate: () -> Unit,
    onFullscreen: () -> Unit,
    onExit: () -> Unit
) {
    // 同 MirrorNavBar：撑满全高，让 Alignment.TopCenter 真正生效，
    // 而不是偷偷依赖父容器 BoxWithConstraints 的默认 TopStart。
    //
    // ★ windowInsetsPadding(statusBars) 是**必须的**：本工程 targetSdk = 35，
    //   而 Android 15（API 35）起对 targetSdk ≥ 35 的应用**强制 edge-to-edge**，
    //   内容会一直绘制到系统栏底下。不避让的话工具条会被状态栏盖住半截，
    //   表现为「用户看着按钮在那儿，点下去却没反应」。
    //   全屏模式（FLAG_FULLSCREEN）下 statusBars 高度归 0，这里自动退回只留 8dp，
    //   不需要为两种模式分别写偏移量。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp, start = 8.dp, end = 8.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        // Button Group 药丸：黑 0.55 + `2xl`=28dp 圆角（规格 §1.2.5 / §1.3 / §1.6 Button Group）
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = Shape2xl,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    // 工具条图标还有 6 个 + 方向按钮，窄屏（竖屏手机）仍可能放不下：
                    // 允许横向滚动，避免右侧的「退出」被挤出可点区域。
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarIcon(
                    vector = AppIcon.VolumeUp,
                    description = TEXT_ACTION_VOLUME_UP,
                    onClick = onVolumeUp
                )
                // AppIcon 未提供 VolumeDown，这里复用 VolumeUp 图标并旋转 180° 表示「音量-」
                ToolbarIcon(
                    vector = AppIcon.VolumeUp,
                    description = TEXT_ACTION_VOLUME_DOWN,
                    rotation = 180f,
                    onClick = onVolumeDown
                )
                ToolbarIcon(
                    vector = AppIcon.Power,
                    description = TEXT_ACTION_POWER,
                    onClick = onPower
                )
                ToolbarIcon(
                    vector = AppIcon.Rotate,
                    description = TEXT_ACTION_ROTATE,
                    onClick = onRotate
                )
                // 方向策略按钮：显示当前策略，点一次轮换
                // 跟随 → 竖屏 → 横屏 → 跟随。默认「跟随」即按被控端尺寸自动改方向。
                // 行为一字不改（仍然只是 cycleOrientationMode），只把视觉对齐 M3E：
                // 胶囊形 ripple（ShapeFull）+ labelMedium 的 600 字重正字距（规格 §1.4）。
                TextButton(onClick = onOrientation, shape = ShapeFull) {
                    Text(
                        text = orientationLabel,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                ToolbarDivider()
                ToolbarIcon(
                    vector = AppIcon.Fullscreen,
                    description = TEXT_ACTION_FULLSCREEN,
                    onClick = onFullscreen
                )
                ToolbarIcon(
                    vector = AppIcon.Close,
                    description = TEXT_ACTION_EXIT,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onExit
                )
            }
        }
    }
}

/**
 * 底部安卓三键导航条（返回 / 主页 / 最近任务），**固定显示**。
 *
 * 放在底部而不是顶部：三键是被控端系统导航的高频操作，拇指自然落点在屏幕下缘；
 * 顶部留给「控制端自身」的开关（音量 / 电源 / 旋转 / 全屏 / 退出）。
 *
 * ⚠️ 必须是 [Modifier.fillMaxSize] 而不是 `fillMaxWidth`：
 * 本组件的父容器 `BoxWithConstraints` 的默认 `contentAlignment` 是 `Alignment.TopStart`，
 * 若只用 `fillMaxWidth`，这个 Box 的高度就只包住 Surface 那一层，于是：
 *   ① 它被父容器按 TopStart 摆在**顶部**（和顶部工具条叠在一起）；
 *   ② `Alignment.BottomCenter` 因为没有多余垂直空间而**完全失效**。
 * 撑满全高后，BottomCenter 才真正把 Surface 压到屏幕底边。
 * （原来的 `GestureHint` 也是靠 `fillMaxSize` 才贴在边角上的。）
 */
@Composable
private fun MirrorNavBar(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 同上：Android 15+ 强制 edge-to-edge，必须避开底部导航栏（手势条 / 三键条），
            // 否则这三个键会被系统导航条压住、点不到。
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Button Group 药丸：黑 0.55 + `2xl`=28dp 圆角，键位容器 48dp / 图标 24dp、
        // 三键间距 12dp（规格 §1.2.5 / §1.3 / §4.3.1 Z2）—— 均已是既有值，此处换用
        // 统一令牌表达，不再手写魔数。
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = Shape2xl,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                // 三键之间拉宽：挨太近容易误触「主页」和「最近任务」
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ToolbarIcon(
                    vector = AppIcon.Back,
                    description = TEXT_ACTION_BACK,
                    size = NAV_KEY_SIZE_DP,
                    onClick = onBack
                )
                ToolbarIcon(
                    vector = AppIcon.NavHome,
                    description = TEXT_ACTION_HOME,
                    size = NAV_KEY_SIZE_DP,
                    onClick = onHome
                )
                ToolbarIcon(
                    vector = AppIcon.NavRecents,
                    description = TEXT_ACTION_RECENT,
                    size = NAV_KEY_SIZE_DP,
                    onClick = onRecents
                )
            }
        }
    }
}

@Composable
private fun ToolbarIcon(
    vector: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color = Color.White,
    rotation: Float = 0f,
    size: Int = TOOLBAR_ICON_BOX_DP,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(size.dp)) {
        Icon(
            imageVector = vector,
            contentDescription = description,
            tint = tint,
            modifier = Modifier
                .size((size * TOOLBAR_ICON_RATIO).dp)
                .rotate(rotation)
        )
    }
}

@Composable
private fun ToolbarDivider() {
    Spacer(modifier = Modifier.width(2.dp))
    Surface(
        modifier = Modifier
            .size(width = 1.dp, height = 18.dp),
        color = Color.White.copy(alpha = 0.35f),
        content = {}
    )
    Spacer(modifier = Modifier.width(2.dp))
}

/**
 * 左上角半透明统计叠层。
 *
 * @param audioLabel 音频状态短标签（如 `A:48k×2` / `A:关`）；为 null 表示本会话没有音频信息，
 *                   此时**不追加任何内容**，避免出现空占位「· 」
 */
@Composable
private fun StatsOverlay(
    fps: Int,
    kbps: Int,
    latencyMs: Int,
    dropped: Int,
    audioLabel: String?
) {
    // ★ M5 形状变形（规格 §1.3 / §4.3.1 Z3）：由**内容宽度变化**驱动 ——
    // 追加了音频项（"· A:48k×2" / "· A:关"）时叠层偏宽 → 10dp 圆角矩形；
    // 没有音频项时变窄 → full 胶囊。spring 300ms（Motion.springDefault）。
    // 9999dp（full 胶囊）→ 10dp 的 spring 下冲幅度是按**整段行程**算的，
    // 会一路冲到 -100dp 量级；负圆角在 Skia 上是未定义行为，必须夹住。
    val statsCornerRaw: Dp by animateDpAsState(
        targetValue = if (audioLabel != null) STATS_CORNER_WIDE else Radius.Full,
        animationSpec = Motion.springDefault(),
        label = "statsMorph"
    )
    val statsCorner: Dp = maxOf(0.dp, statsCornerRaw)
    Box(
        modifier = Modifier
            // Android 15+ 强制 edge-to-edge：统计叠层同样要避开状态栏，
            // 否则和工具条一起被压到状态栏底下。
            .windowInsetsPadding(WindowInsets.statusBars)
            // 52dp ≈ 顶部工具条的 8dp 上边距 + 约 44dp 条身，让叠层落在工具条正下方
            .padding(top = 52.dp, start = 12.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            shape = RoundedCornerShape(statsCorner)
        ) {
            Text(
                text = buildString {
                    append("$fps $TEXT_STAT_FPS · ${bitrateText(kbps)} · ")
                    append("$TEXT_STAT_LATENCY $latencyMs ms · $TEXT_STAT_DROPPED $dropped")
                    if (audioLabel != null) {
                        append(" · ").append(audioLabel)
                    }
                },
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * 统计叠层里的音频短标签。
 *
 * `48k×2` 不是从流里读出来的：采样率 / 声道数是双侧硬编码约定（48000Hz / 2ch / 16-bit LE，
 * 见 README §3.6），所以音频一旦真正出声就恒为该值。
 */
private fun audioStatLabel(active: Boolean): String =
    if (active) "A:48k×2" else TEXT_AUDIO_STAT_OFF

/**
 * 音频状态徽标（左上角，统计叠层下方）。
 *
 * - [active] 为 true →「音频开」；
 * - [note] 非空 → 原样显示（排查用原文，最多 2 行 + 省略号）；
 * - 由调用方保证「两者都空则不渲染」，所以这里不再判空。
 *
 * 只做渲染：纯展示，不可点击（控制栏已固定常显，无需「点一下把工具条唤出来」）。
 */
@Composable
private fun AudioBadge(
    active: Boolean,
    note: String,
    topPadding: Int
) {
    Box(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = topPadding.dp, start = 12.dp),
        contentAlignment = Alignment.TopStart
    ) {
        // 黑 0.42 + `sm`=8dp 圆角（规格 §1.2.5 / §1.3）
        Surface(
            color = Color.Black.copy(alpha = 0.42f),
            shape = ShapeSm
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (active) {
                    Text(
                        text = TEXT_AUDIO_ON,
                        color = Color.White.copy(alpha = 0.80f),
                        fontSize = 11.sp
                    )
                }
                if (note.isNotBlank()) {
                    Text(
                        text = note,
                        color = Color.White.copy(alpha = 0.66f),
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 进行中的居中提示。 */
@Composable
private fun CenterHint(message: String, spinning: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = ShapeLg
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (spinning) {
                    // ★ M3E 波浪形不定进度指示器（规格 §1.5.4）：投屏居中卡内 28dp、白色。
                    WavyProgressIndicator(size = 28.dp, color = Color.White)
                }
                Text(text = message, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

/** 失败态：给可读文案 + 重试 / 退出两个出口。 */
@Composable
private fun CenterFailure(message: String, onRetry: () -> Unit, onExit: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = AppIcon.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
                // 诊断文案可能是十几行（含设备 logcat 片段）：限高 + 可滚动，
                // 否则会把「重试/退出」按钮挤出屏幕，用户既看不到结论也点不到按钮。
                Box(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onRetry) {
                        Text(text = TEXT_RETRY)
                    }
                    TextButton(onClick = onExit) {
                        Text(text = TEXT_CONFIRM)
                    }
                }
            }
        }
    }
}

/** 投屏期间保持屏幕常亮（需求 E6）。 */
@Composable
private fun KeepScreenOnEffect(activity: Activity?) {
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * 把「方向策略 + 被控端画面尺寸」翻译成窗口方向常量。
 *
 * 用 `SENSOR_*` 而不是固定的 `LANDSCAPE` / `PORTRAIT`：既能锁住横或竖，
 * 又允许把手机翻到另一侧（横屏的两个方向），不会出现「必须倒着拿」的别扭感。
 */
private fun pickOrientation(mode: OrientationMode, remote: RemoteSize): Int = when {
    mode == OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    mode == OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    // 跟随模式但还没拿到被控端尺寸：先不干预系统默认行为
    !remote.isValid -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    remote.isLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
}

/** 方向策略按钮上的标签。 */
private fun orientationLabel(mode: OrientationMode): String = when (mode) {
    OrientationMode.FOLLOW_REMOTE -> TEXT_ORIENT_FOLLOW
    OrientationMode.PORTRAIT -> TEXT_ORIENT_PORTRAIT
    OrientationMode.LANDSCAPE -> TEXT_ORIENT_LANDSCAPE
}

/** 全屏开关：隐藏/恢复系统栏。失败不影响画面。 */
private fun setFullscreen(activity: Activity?, enabled: Boolean) {
    val window = activity?.window ?: return
    try {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    } catch (t: Throwable) {
        // 某些定制 ROM 上操作 window flag 可能抛异常，忽略即可
    }
}

/** 从 Compose 的 Context 往上找回宿主 Activity。 */
private fun android.content.Context.findActivity(): Activity? {
    var ctx: android.content.Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
