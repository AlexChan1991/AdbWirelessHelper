package com.adb.adbwirelesshelper.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.adb.adbwirelesshelper.data.media.RemoteFileMediaDataSource
import com.adb.adbwirelesshelper.data.media.RemoteFileStream
import com.adb.adbwirelesshelper.domain.model.FileEntry
import com.adb.adbwirelesshelper.domain.model.FileKind
import com.adb.adbwirelesshelper.ui.components.WavyProgressIndicator
import com.adb.adbwirelesshelper.ui.theme.AppIcon
import com.adb.adbwirelesshelper.ui.theme.Motion
import com.adb.adbwirelesshelper.ui.theme.Shape3xl
import com.adb.adbwirelesshelper.ui.theme.ShapeFull
import com.adb.adbwirelesshelper.ui.theme.ShapeMd
import com.adb.adbwirelesshelper.util.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------- 文案常量

private const val TXT_CLOSE = "关闭"
private const val TXT_LOCK = "锁定"
private const val TXT_UNLOCK = "解锁"
private const val TXT_MORE = "更多"
private const val TXT_UNKNOWN = "未知"
private const val TXT_OPENING = "正在从被控端读取视频…"
private const val TXT_STREAM_HINT = "流式读取，不落盘到控制端"
private const val TXT_OPEN_FAILED = "无法打开该视频"
private const val TXT_STREAM_BROKEN = "远端读取中断"
private const val TXT_RETRY = "重试"
private const val TXT_BACK_TO_FILES = "返回文件列表"
private const val TXT_EMPTY_PLAYLIST = "没有可播放的文件"
private const val TXT_PLAY = "播放"
private const val TXT_PAUSE = "暂停"
private const val TXT_PREV = "上一个"
private const val TXT_NEXT = "下一个"
private const val TXT_REWIND = "快退 10 秒"
private const val TXT_FORWARD = "快进 10 秒"
private const val TXT_ASPECT = "画面比例"
private const val TXT_PLAYLIST = "播放列表"
private const val TXT_SUBTITLE = "字幕"
private const val TXT_AB = "A-B 循环"
private const val TXT_LOOP = "循环模式"
private const val TXT_PIP = "画中画"
private const val TXT_SCREENSHOT = "截图"
private const val TXT_ROTATE = "旋转 90°"
private const val TXT_JUMP = "跳转到时间"
private const val TXT_SPEED = "播放速度"
private const val TXT_AUDIO_TRACK = "音轨"
private const val TXT_SLEEP = "睡眠定时"
private const val TXT_EQUALIZER = "均衡器"
private const val TXT_MEDIA_INFO = "媒体信息"
private const val TXT_VOLUME = "音量"
/** 横向拖动 HUD：向前拖 */
private const val TXT_SEEK_FORWARD = "快进"
/** 横向拖动 HUD：向后拖 */
private const val TXT_SEEK_REWIND = "快退"
private const val TXT_SET_A = "已设置 A 点"
private const val TXT_SET_B = "已设置 B 点，区间内循环"
private const val TXT_AB_CLEARED = "已取消 A-B 循环"
private const val TXT_AB_NEED_A = "请先设置 A 点"
private const val TXT_SLEEP_DONE = "睡眠定时已到，播放已暂停"
private const val TXT_JUMP_DONE = "已跳转到 "
private const val TXT_DELAY = "延迟"
private const val TXT_APPLY = "跳转"
private const val TXT_RESET = "归零"
private const val TXT_TARGET = "目标时间"
private const val TXT_PLAYING_NOW = "正在播放"
private const val TXT_NOT_READY = "播放器尚未就绪"
private const val TXT_CLOSED = "已退出播放"

// 规格 §4.6.3：以下能力在一期**不做**，点击给 toast 说明（原因见各面板 KDoc）
// ⚠️ 曾经的 `TXT_NO_BRIGHTNESS` 已于 2026-09-27 删除：亮度手势连同它的 toast 一起下线，
//    竖直方向整个交给音量（详见手势层注释）。**不要**把它和亮度 UI 一起画回来。
/** 长按加速中浮出的 HUD 药丸文案 */
private const val TXT_BOOSTING = "2× 加速中"
/** 进入长按前倍速已不低于 2×，此时长按不生效（降回 2× 是减速，反直觉） */
private const val TXT_BOOST_NO_NEED = "当前倍速已不低于 2×"
/** 该视频/机型吃不下 2×，播放器留在原倍速 */
private const val TXT_BOOST_UNSUPPORTED = "该视频不支持 2× 倍速"
/** 长按加速的目标倍速 */
private const val BOOST_SPEED: Float = 2f
private const val TXT_NO_SUBTITLE = "暂不支持字幕轨"
private const val TXT_NO_SUBTITLE_DELAY = "暂不支持调节字幕延迟"
private const val TXT_NO_AUDIO_TRACK = "暂不支持切换音轨"
private const val TXT_NO_AUDIO_DELAY = "暂不支持调节音画延迟"
private const val TXT_NO_EQUALIZER = "暂不支持均衡器"
private const val TXT_NO_SCREENSHOT = "暂不支持截图"
private const val TXT_NO_PIP = "暂不支持画中画"
private const val TXT_NO_SWITCH = "（不可切换）"

// ---------------------------------------------------------------- 常量 / 档位

private const val TAG = "VideoPlayerOverlay"

/** 快进 / 快退步长（秒），规格固定 ±10 秒 */
private const val SEEK_STEP_SEC: Int = 10

/** 连点累加归零窗口（毫秒） */
private const val SEEK_ACCUM_RESET_MS: Long = 900L

/** 进度轮询周期（毫秒） */
private const val POLL_INTERVAL_MS: Long = 200L

/** 进出覆盖层时长（毫秒） */
private const val OVERLAY_ANIM_MS: Int = Motion.DURATION_CONTAINER

/**
 * 控制栏自动隐藏延时（毫秒）：3 秒无操作后顶栏 + 底部控制区一起淡出。
 *
 * ⚠️ 这个值必须显著大于一次「看清控制栏 → 抬手去点」的时间（约 1 秒），
 *    否则用户刚点出来就被收走；也不能太长，否则长时间用不上。
 *    3 秒是 YouTube / VLC / Bilibili 的通行取值。
 */
private const val CONTROLS_AUTO_HIDE_MS: Long = 3_000L

/**
 * 控制栏淡入淡出时长（毫秒）。
 *
 * ⚠️ 这里**必须**用 `tween` 而不是 spring：`Motion.springFast()` 是欠阻尼的，
 *    1 → 0 会**下冲到负值**，喂给 `Modifier.alpha` 会抛
 *    `IllegalArgumentException`。本项目已因 spring 下冲喂负值给
 *    `Modifier.padding` 崩过一次（`Padding must be non-negative`），别再踩。
 */
private const val CONTROLS_FADE_MS: Int = 220

/** 倍速档位（规格 §4.6.3：9 档，不得增删；1× 为默认） */
private val SPEED_STEPS: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

/** 倍速合法区间（传给 MediaPlayer 前夹取，防 IllegalArgumentException） */
private const val SPEED_MIN: Float = 0.25f
private const val SPEED_MAX: Float = 4f

/** 睡眠定时档位（分钟；0 = 关闭），到点**暂停**播放 */
private val SLEEP_MINUTES: List<Int> = listOf(0, 15, 30, 60, 90)

/**
 * 音轨三条（规格要求照画 3 项）。
 *
 * ⚠️ `android.media.MediaPlayer` **没有枚举音轨的 API**（只有 `MediaPlayer.TrackInfo`
 * 且实际只对部分容器返回有限信息，无法按轨切换），所以这三项**只画不切**，
 * 点击给 toast「暂不支持切换音轨」。
 */
private val AUDIO_TRACKS: List<String> = listOf(
    "音轨 1 · 中文 · AAC 2ch",
    "音轨 2 · 英文 · AAC 2ch",
    "音轨 3 · 原声 · Opus 2ch",
)

/**
 * 字幕四项（规格要求照画；0 = 关闭字幕，为默认）。
 *
 * ⚠️ MediaPlayer 不支持外挂/内嵌字幕轨渲染（`addTimedTextSource` 只支持有限格式且
 * 需要 `TimedText` 回调自行绘制），本项目不实现字幕渲染，点击给 toast。
 */
private val SUBTITLE_TRACKS: List<String> = listOf(
    "关闭字幕",
    "字幕 1 · 中文 · SRT",
    "字幕 2 · 中英双语 · SRT",
    "字幕 3 · 英文 · ASS",
)

/** 字幕 / 音画延迟步进（毫秒），照画但置灰 */
private const val DELAY_STEP_MS: Int = 50

/** 手势 HUD 里那条进度条的宽度 */
private val HUD_BAR_WIDTH: Dp = 120.dp

/** 均衡器 7 档（照画；`android.media.audiofx.Equalizer` 风险高，一期不接） */
private val EQUALIZER_PRESETS: List<String> = listOf(
    "关闭", "流行", "摇滚", "爵士", "古典", "重低音", "人声增强",
)

/** 覆盖层内的强调色：深浅主题下都在黑底上够亮 */
private val PlayerAccent = Color(0xFF8AB4F8)

/** A-B 区间色块 */
private val PlayerAbColor = Color(0xFFFFB74D)

/** 覆盖层卡片底色 */
private val PlayerSheetColor = Color(0xFF16181D)

// ---------------------------------------------------------------- 枚举 / 数据

/** 画面比例 5 种（规格 §4.6.3，不得增删） */
private enum class AspectMode(val label: String) {
    /** 原始比例（默认）：按 `MediaPlayer.videoWidth/videoHeight` */
    ORIGINAL("原始比例"),

    /** 强制 16:9 */
    R16_9("16:9"),

    /** 强制 4:3 */
    R4_3("4:3"),

    /** 拉伸填满（会变形） */
    STRETCH("拉伸填满"),

    /** 裁剪填满（等比放大到铺满后裁掉溢出部分） */
    CROP("裁剪填满"),
}

/** 循环模式 3 态 */
private enum class LoopMode(val label: String) {
    /** 不循环：播完停在末尾 */
    OFF("不循环"),

    /** 全部循环：播完自动下一个，到末尾回到第一个 */
    ALL("全部循环"),

    /** 单集循环：`MediaPlayer.setLooping(true)` */
    SINGLE("单集循环"),
}

/**
 * 屏幕方向 3 档。
 *
 * ⚠️ 名字带 `Player` 前缀是**刻意的**：`ui/screen/MirrorViewModel.kt` 里已经有一个
 * public 的 `OrientationMode`（投屏页用，语义是「跟随被控端 / 强制横屏 / 强制竖屏」）。
 * 同 package 下同名 enum 会直接 `Redeclaration` 编译失败（private 也挡不住），
 * 所以本文件这一套播放器专用枚举统一加前缀，别再改回去。
 */
private enum class PlayerOrientationMode(val label: String) {
    AUTO("自动"),
    LANDSCAPE("强制横屏"),
    PORTRAIT("强制竖屏"),
}

/** 右侧面板种类 */
private enum class PanelKind(val title: String) {
    PLAYLIST(TXT_PLAYLIST),
    SPEED(TXT_SPEED),
    AUDIO(TXT_AUDIO_TRACK),
    SUBTITLE(TXT_SUBTITLE),
    ASPECT(TXT_ASPECT),
    SEEK(TXT_JUMP),
    SLEEP(TXT_SLEEP),
    EQUALIZER(TXT_EQUALIZER),
    INFO(TXT_MEDIA_INFO),
}

/**
 * 手势 HUD 状态。
 *
 * ⚠️ **历史形态是「左半屏亮度 / 右半屏音量」，已按用户要求改为「横向拖动 = 进度 / 纵向拖动 = 音量」**
 * （2026-09-27）。亮度手势连同它的 HUD 一起删除：远程串流下「调亮度」既没有真实语义
 * （见下方注释），又占着半块屏幕，不如把竖直方向统一交给**真实可用**的音量。
 */
private sealed interface HudState {

    /** 纵向拖动：音量百分比 0–100 */
    data class Volume(val percent: Int) : HudState

    /**
     * 横向拖动：进度拖动。
     *
     * @param targetMs  **手指松开后才会真正 seek 到**的目标位置（拖动期间只预览，不真的跳）
     * @param fromMs    手势开始时的位置，用于算增量并在 HUD 上显示「+mm:ss / -mm:ss」
     * @param durationMs 总时长（> 0 时 HUD 才画进度条）
     */
    data class Seek(val targetMs: Int, val fromMs: Int, val durationMs: Int) : HudState
}

/**
 * 一个已打开的播放源：喂给 `MediaPlayer` 的 [MediaDataSource] + 它背后的远端流。
 *
 * ⚠️ 之所以把两者绑在一起：`RemoteFileMediaDataSource` 只是 [RemoteFileStream] 的适配器，
 * 流的生命周期必须由覆盖层管 —— 切片 / 退出时都要 `stream.close()`，
 * 否则内部预取协程会一直挂着（见 `RemoteFileSource` 的 `scope`）。
 */
private data class VideoSource(
    val playerSource: MediaDataSource,
    val stream: RemoteFileStream,
)

/**
 * 从 `MediaMetadataRetriever` 取到的媒体信息。
 *
 * ⚠️ 取不到的字段一律为「空 / 0」，UI 层显示「未知」，**不得编造**。
 *
 * ⚠️ 关于编解码器：`MediaMetadataRetriever` **没有** `METADATA_KEY_VIDEO_CODEC` /
 * `METADATA_KEY_AUDIO_CODEC` 这两个键（它们在 Android SDK 里根本不存在，不是版本问题）。
 * 只有 `METADATA_KEY_MIMETYPE`（API 14+），拿到的是**容器 MIME**（如 `video/mp4`），
 * 不是编解码器。所以这里只存 [containerMime]，UI 行的标签叫「容器格式」而不是「编码」。
 * 真要读编解码器得用 `MediaExtractor` 遍历轨道读 `MediaFormat.KEY_MIME`，本次不做。
 */
private data class VideoMeta(
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val bitrateBps: Long = 0L,
    val frameRate: Float? = null,
    val containerMime: String? = null,
)

// ---------------------------------------------------------------- 自绘图标

/**
 * 暂停（两根竖条）。
 *
 * 工程图标库只有 [AppIcon.Play] 与 [AppIcon.Stop]（实心方块 = 停止语义），
 * 没有「暂停」，这里仿照 `ui/theme/AppIcons.kt` 自绘一个 24dp 图标，避免引入图标库。
 */
private val PlayerIconPause: ImageVector = ImageVector.Builder(
    name = "PlayerPause",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).path(
    fill = SolidColor(Color.Black),
    stroke = null,
    strokeLineWidth = 0f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
) {
    moveTo(7.5f, 5f)
    lineTo(10.5f, 5f)
    lineTo(10.5f, 19f)
    lineTo(7.5f, 19f)
    close()
    moveTo(13.5f, 5f)
    lineTo(16.5f, 5f)
    lineTo(16.5f, 19f)
    lineTo(13.5f, 19f)
    close()
}.build()

/**
 * 拖动进度 HUD 的两枚图标（横向拖动）。
 *
 * 工程图标库只有 `Play` / `Back` / `ChevronRight` 等基础形状，没有 fast-forward。
 * 这里仿照 `ui/theme/AppIcons.kt` 自绘双箭头，避免为两个图标引入整套 Material Icons 依赖。
 * 绘制方式是「一根竖线 + 一个箭头」，两个箭头等距，视觉上就是常见的快进/快退。
 */
private val PlayerIconFastForward: ImageVector = seekChevrons(name = "PlayerFastForward", mirrored = false)

private val PlayerIconFastRewind: ImageVector = seekChevrons(name = "PlayerFastRewind", mirrored = true)

private fun seekChevrons(name: String, mirrored: Boolean): ImageVector {
    // 向右时是两个尖朝右的实心三角（x 区间 8–15、15–22）；mirrored 时 x 取镜像 = 朝左
    val m: (Float) -> Float = { x -> if (mirrored) 24f - x else x }
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.Black),
        stroke = null,
        strokeLineWidth = 0f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(m(8f), 5f)
        lineTo(m(15f), 12f)
        lineTo(m(8f), 19f)
        close()
        moveTo(m(15f), 5f)
        lineTo(m(22f), 12f)
        lineTo(m(15f), 19f)
        close()
    }.build()
}

/**
 * 解锁（锁体 + 翘起的钩环），与 [AppIcon.Lock] 区分锁定 / 未锁定两态。
 */
private val PlayerIconLockOpen: ImageVector = ImageVector.Builder(
    name = "PlayerLockOpen",
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
    moveTo(6f, 11f)
    lineTo(18f, 11f)
    lineTo(18f, 20f)
    lineTo(6f, 20f)
    close()
    // 钩环左半段照常，右端向上翘起 = 已解锁
    moveTo(8.5f, 11f)
    lineTo(8.5f, 8f)
    arcTo(4f, 4f, 0f, false, true, 15.5f, 5.5f)
}.build()

/**
 * PathBuilder 扩展：画一个完整的圆（4 段椭圆弧 + close）。
 * 与 AppIcons.kt 中的同名私有扩展写法一致（本文件内自用）。
 */
private fun PathBuilder.oval(cx: Float, cy: Float, r: Float) {
    moveTo(cx, cy - r)
    arcTo(r, r, 0f, false, true, cx + r, cy)
    arcTo(r, r, 0f, false, true, cx, cy + r)
    arcTo(r, r, 0f, false, true, cx - r, cy)
    arcTo(r, r, 0f, false, true, cx, cy - r)
    close()
}

// ---------------------------------------------------------------- 播放器控制

/**
 * `MediaPlayer` 生命周期管理器（规格 §4.6.3 播放管线）。
 *
 * 为什么要有这个类：
 * - `MediaPlayer` 是**重量级资源**，必须 `release()`，否则切几集就把解码器句柄耗尽；
 * - `setDisplay(SurfaceHolder)` 必须在 `surfaceCreated` **之后**调用，否则黑屏；
 *   而 Surface 的创建时机由系统决定，与播放器的 open 时机互相独立 —— 这里用
 *   「谁后到谁 setDisplay」的双向兜底：
 *   - `open()` 时若 holder 已存在 → 立即 `setDisplay`；
 *   - `attachSurface()` 时若 player 已存在 → 立即 `setDisplay`。
 *
 * 状态字段用 Compose 的 `mutableStateOf` 表达，UI 直接读，不需要额外的 Flow 桥接。
 */
private class PlayerController {

    private var player: MediaPlayer? = null
    private var holder: SurfaceHolder? = null

    /** 用户意图：prepare 完成后要不要自动起播 */
    private var wantPlay: Boolean = true

    /** prepare 完成前设置的倍速，prepare 后补套 */
    private var pendingSpeed: Float = 1f

    /** 已 prepare，可以 seek / 改倍速 */
    var prepared: Boolean by mutableStateOf(false)
        private set

    /** 正在播放 */
    var playing: Boolean by mutableStateOf(false)
        private set

    /** 视频宽（像素）；未 prepare 时为 0 */
    var videoWidth: Int by mutableIntStateOf(0)
        private set

    /** 视频高（像素）；未 prepare 时为 0 */
    var videoHeight: Int by mutableIntStateOf(0)
        private set

    /** 总时长（毫秒）；未 prepare 时为 0 */
    var durationMs: Int by mutableIntStateOf(0)
        private set

    /** 最近一次错误文案；无错误时为 null */
    var lastError: String? by mutableStateOf(null)
        private set

    /** 当前生效倍速 */
    var speed: Float by mutableFloatStateOf(1f)
        private set

    /** 播完回调（由 UI 决定「下一个 / 停住 / 循环」） */
    var onCompletion: (() -> Unit)? = null

    /** 错误回调（UI 收到后切到错误态并给重试入口） */
    var onError: ((String) -> Unit)? = null

    // ------------------------------------------------------------ Surface

    /** Surface 创建：若播放器已存在则补一次 `setDisplay`（否则黑屏） */
    fun attachSurface(h: SurfaceHolder) {
        holder = h
        setDisplaySafe(h)
    }

    /** Surface 销毁：必须先 setDisplay(null) 再等系统回收，否则会抛非法状态 */
    fun detachSurface() {
        holder = null
        setDisplaySafe(null)
    }

    private fun setDisplaySafe(h: SurfaceHolder?) {
        try {
            player?.setDisplay(h)
        } catch (t: Throwable) {
            Logx.w(TAG, "setDisplay(${if (h == null) "null" else "holder"}) 失败：${t.message}")
        }
    }

    // ------------------------------------------------------------ 打开 / 释放

    /**
     * 打开一个远端流数据源（**不落盘**）。
     *
     * 走 `setDataSource(MediaDataSource) → prepareAsync()`，全部回调挂好之后再开始
     * 异步 prepare，避免 prepare 与监听器之间出现竞态（先 prepare 后挂监听会漏掉 onPrepared）。
     *
     * ⚠️ 传进来的 [MediaDataSource] 背后就是 [RemoteFileStream]：
     *    `MediaPlayer.release()` 会顺手调用 `MediaDataSource.close()`，
     *    从而把流关掉。所以调用方在 release 之后的 `closeQuietly` 只是兜底（幂等）。
     */
    fun open(source: MediaDataSource) {
        release()
        prepared = false
        playing = false
        durationMs = 0
        videoWidth = 0
        videoHeight = 0
        lastError = null
        wantPlay = true

        val mp = MediaPlayer()
        try {
            // MediaDataSource 模式（API 23+）：数据来自被控端流式读源，不落盘
            mp.setDataSource(source)
        } catch (t: Throwable) {
            Logx.w(TAG, "setDataSource(MediaDataSource) 失败：${t.message}")
            mp.release()
            val msg: String = "无法打开远端视频流"
            lastError = msg
            onError?.invoke(msg)
            return
        }

        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
        } catch (t: Throwable) {
            Logx.w(TAG, "setAudioAttributes 失败：${t.message}")
        }

        mp.setOnPreparedListener { p ->
            prepared = true
            // ⚠️ `getDuration()` 在状态失效时会抛 IllegalStateException（异步回调与播放器状态
            //    的竞态是真实存在的）。这是系统回调里抛出的异常，外面没人接得住，必须就地兜住；
            //    时长取不到就按 0（UI 显示「未知」），不能把整个 prepare 流程打断。
            durationMs = try {
                p.duration.coerceAtLeast(0)
            } catch (t: Throwable) {
                Logx.w(TAG, "读取时长失败：${t.message}")
                0
            }
            videoWidth = p.videoWidth
            videoHeight = p.videoHeight
            try {
                p.setScreenOnWhilePlaying(true)
            } catch (t: Throwable) {
                Logx.w(TAG, "setScreenOnWhilePlaying 失败：${t.message}")
            }
            if (pendingSpeed != 1f && !applySpeed(pendingSpeed)) {
                // 该视频/该机型吃不下这个倍速：回退 1×，不要带着一个假倍速继续跑
                pendingSpeed = 1f
                speed = 1f
            }
            if (wantPlay) start()
            Logx.d(TAG, "已 prepare：${videoWidth}x${videoHeight}，${durationMs}ms")
        }

        mp.setOnErrorListener { _, what, extra ->
            val msg: String = "播放失败（what=$what, extra=$extra）"
            prepared = false
            playing = false
            lastError = msg
            onError?.invoke(msg)
            true // 已处理，避免系统再弹一次
        }

        mp.setOnCompletionListener {
            playing = false
            onCompletion?.invoke()
        }

        mp.setOnVideoSizeChangedListener { _, w, h ->
            videoWidth = w
            videoHeight = h
        }

        player = mp
        if (holder != null) setDisplaySafe(holder)
        try {
            mp.prepareAsync()
        } catch (t: Throwable) {
            Logx.w(TAG, "prepareAsync 失败：${t.message}")
            val msg = "无法解码该视频"
            lastError = msg
            onError?.invoke(msg)
            release()
        }
    }

    /** 释放播放器（幂等，可重复调用） */
    fun release() {
        prepared = false
        playing = false
        val mp: MediaPlayer? = player
        player = null
        if (mp == null) return
        try {
            mp.setOnPreparedListener(null)
            mp.setOnErrorListener(null)
            mp.setOnCompletionListener(null)
            mp.setOnVideoSizeChangedListener(null)
            mp.setDisplay(null)
        } catch (t: Throwable) {
            Logx.w(TAG, "解绑监听器失败：${t.message}")
        }
        try {
            mp.stop()
        } catch (t: Throwable) {
            /* 未 prepare 时 stop 会抛 IllegalStateException，忽略 */
        }
        try {
            mp.release()
        } catch (t: Throwable) {
            Logx.w(TAG, "release 失败：${t.message}")
        }
    }

    // ------------------------------------------------------------ 控制

    fun start() {
        wantPlay = true
        val mp: MediaPlayer? = player
        if (mp == null || !prepared) return
        try {
            mp.start()
            playing = true
        } catch (t: Throwable) {
            Logx.w(TAG, "start 失败：${t.message}")
            playing = false
        }
    }

    fun pause() {
        wantPlay = false
        val mp: MediaPlayer? = player
        if (mp == null || !prepared) return
        try {
            if (mp.isPlaying) mp.pause()
        } catch (t: Throwable) {
            Logx.w(TAG, "pause 失败：${t.message}")
        }
        playing = false
    }

    /**
     * 跳转（毫秒）。prepare 前调用会被忽略，UI 层已做「未就绪」提示。
     *
     * ⚠️ `MediaPlayer` **没有**单参 `seekTo(Long)`：只有 `seekTo(Int)` 与
     * API 26+ 的 `seekTo(Long, Int)`。这里用后者 + `SEEK_CLOSEST`（minSdk 26 刚好够），
     * 长视频上比 `seekTo(Int)` 更准。
     */
    fun seekTo(ms: Int) {
        val mp: MediaPlayer? = player
        if (mp == null || !prepared) return
        val target: Long = ms.toLong().coerceAtLeast(0L)
        try {
            mp.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        } catch (t: Throwable) {
            Logx.w(TAG, "seekTo($target) 失败：${t.message}")
        }
    }

    /** 当前播放位置（毫秒）；未就绪返回 0 */
    fun currentPosition(): Int {
        val mp: MediaPlayer? = player
        if (mp == null || !prepared) return 0
        return try {
            mp.currentPosition.coerceAtLeast(0)
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * 设置倍速。
     *
     * ⚠️ 部分机型 / 编码只支持 0.5–2×，超出会抛异常（`IllegalStateException` /
     * `IllegalArgumentException`）。这里一律 try/catch，**绝不把异常抛给 UI**。
     *
     * @return true = 生效；false = 该视频不支持，调用方应回退 1× 并提示
     */
    fun applySpeed(value: Float): Boolean {
        val v: Float = value.coerceIn(SPEED_MIN, SPEED_MAX)
        val mp: MediaPlayer? = player
        if (mp == null || !prepared) {
            // 还没 prepare 好：先记下来，prepare 完成后补套（此时视为「成功」）
            pendingSpeed = v
            speed = v
            return true
        }
        return try {
            mp.playbackParams = PlaybackParams().setSpeed(v)
            speed = v
            pendingSpeed = v
            true
        } catch (t: Throwable) {
            Logx.w(TAG, "设置 ${v}× 失败：${t.message}")
            false
        }
    }

    /** 单集循环开关 */
    fun setLoopingValue(on: Boolean) {
        val mp: MediaPlayer? = player
        if (mp == null) return
        try {
            mp.isLooping = on
        } catch (t: Throwable) {
            Logx.w(TAG, "setLooping($on) 失败：${t.message}")
        }
    }

    /**
     * 设置画面缩放模式。
     *
     * - `VIDEO_SCALING_MODE_SCALE_TO_FIT`：等比适应画面（可能有黑边）；
     * - `VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING`：等比放大到铺满后裁剪。
     *
     * 「拉伸填满」不走这里 —— 解码器不会做非等比拉伸，由 UI 侧给 SurfaceView
     * 施加非等比 `scaleX/scaleY` 实现。
     */
    fun setScalingMode(mode: Int) {
        val mp: MediaPlayer? = player
        if (mp == null) return
        try {
            mp.setVideoScalingMode(mode)
        } catch (t: Throwable) {
            Logx.w(TAG, "setVideoScalingMode($mode) 失败：${t.message}")
        }
    }
}

// ---------------------------------------------------------------- 覆盖层

/**
 * 全屏视频播放器**覆盖层**（设计规格 §4.6.3，能力集对齐 VLC Android）。
 *
 * ⚠️ 这是**覆盖层不是路由**：不进返回栈，关闭即回到文件管理页（由调用方决定如何移除）。
 *
 * ## 播放管线（**流式，不落盘**）
 * 视频文件物理上在**被控端**。用户明确要求不缓存到本地，所以这里**不走 `adb pull`**，
 * 而是 `openStream(entry)` 拿到 [RemoteFileStream]，套一层
 * `RemoteFileMediaDataSource`（`android.media.MediaDataSource` 适配器，API 23+）
 * 直接喂给 `MediaPlayer.setDataSource(MediaDataSource)`：
 * 1. 切到某个 index → `openStream` 打开一条流；
 * 2. 打开失败 / `stream.failure != null` → 错误态（文案 + 重试），**不崩溃**；
 * 3. 切片或退出覆盖层时**必须 `stream.close()`**，否则源内部的预取协程一直挂着；
 * 4. seek 后调 `stream.prefetch(新位置)` 摊掉 adb 进程开销，拖动进度条会顺很多。
 *
 * ⚠️ 元数据（`MediaMetadataRetriever`）用的是**另一条独立流**：
 * API 29+ 的 `retriever.release()` 会顺手 close 传进去的 `MediaDataSource`，
 * 复用播放器那条会把播放源一起关掉。
 *
 * ## 一层重要的现实约束（决定了一批能力只能「照画 + toast」）
 * 项目**不引入 ExoPlayer**，只能用 `MediaPlayer`：
 * - 不能枚举 / 切换音轨；
 * - 不能渲染字幕轨；
 * - 没有外接字幕与时间轴偏移；
 * - 画中画需要 Activity 级 PiP 支持与生命周期改造，超出覆盖层范围。
 * 这些能力**控件照画**（规格要求不得删控件），点击给 toast 说明，KDoc 一一注明。
 *
 * @param entries    当前目录条目（内部会过滤掉目录，并维护一份可变副本）
 * @param startIndex 起始播放下标（越界会被夹取）
 * @param openStream 打开远端视频流的挂起函数（与 AudioPlayerOverlay 同构）
 * @param onClose    关闭覆盖层（回到文件管理页）
 */
@Composable
fun VideoPlayerOverlay(
    entries: List<FileEntry>,
    startIndex: Int,
    openStream: suspend (FileEntry) -> Result<RemoteFileStream>,
    onClose: () -> Unit,
) {
    val context: Context = LocalContext.current
    val hostView: android.view.View = LocalView.current
    val activity: Activity? = remember(context) { context.findActivity() }

    // 播放器内部维护一份 entries 的可变副本：
    // 目录一律剔除；调用方若把整个目录（含图片）传进来，则只留视频，
    // 一个视频都没有时才退回「全部文件」，避免打开即空态。
    val items: SnapshotStateList<FileEntry> = remember(entries) {
        val files: List<FileEntry> = entries.filter { !it.isDir }
        val videos: List<FileEntry> = files.filter { it.kind == FileKind.VIDEO }
        mutableStateListOf<FileEntry>().apply { addAll(videos.ifEmpty { files }) }
    }

    if (items.isEmpty()) {
        PlayerEmptyState(onClose = onClose)
        return
    }

    // ---------------------------------------------------------------- 状态

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, items.size - 1)) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    /** 当前播放源（含必须被 close 的远端流） */
    var source by remember { mutableStateOf<VideoSource?>(null) }
    var retryToken by remember { mutableIntStateOf(0) }
    var meta by remember { mutableStateOf<VideoMeta?>(null) }
    /** 已缓存字节区间（按字节估算画缓冲条） */
    var buffered by remember { mutableStateOf<List<LongRange>>(emptyList()) }

    var locked by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<PanelKind?>(null) }
    /** 面板标题单独存一份：关闭时 `panel` 立刻变 null，但退场动画期间标题还得显示 */
    var panelTitle by remember { mutableStateOf("") }
    var moreMenu by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    var currentMs by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableIntStateOf(0) }
    var aMs by remember { mutableIntStateOf(-1) }
    var bMs by remember { mutableIntStateOf(-1) }
    var seekAccum by remember { mutableIntStateOf(0) }
    var seekTargetMs by remember { mutableIntStateOf(0) }

    var speed by remember { mutableFloatStateOf(1f) }
    var loopMode by remember { mutableStateOf(LoopMode.OFF) }
    var aspect by remember { mutableStateOf(AspectMode.ORIGINAL) }
    var orientation by remember { mutableStateOf(PlayerOrientationMode.AUTO) }
    // 画面的**视觉**旋转角（最终落到 SurfaceView.view.rotation）。
    // ★ 现在恒为 0：旋转按钮的职责已改为切「屏幕方向」（见 onRotate 处注释）——
    //   转屏幕与转画面同时做会叠加成两次旋转，把视频压成一条。
    //   保留这个状态是为了不动 SurfaceView 那条已验证的渲染链；
    //   将来若要恢复「只转画面、屏幕不动」的形态，从这里接回来即可。
    var rotationDeg by remember { mutableFloatStateOf(0f) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var subtitleIndex by remember { mutableIntStateOf(0) }
    var audioIndex by remember { mutableIntStateOf(0) }
    var hud by remember { mutableStateOf<HudState?>(null) }

    /**
     * 顶栏 + 底部控制区是否可见；自动隐藏计时到点置 false，点击屏幕取反。
     *
     * ⚠️ 这个状态是那个计时 `LaunchedEffect` 的 key，取反会让它重启并重新计时，
     *    所以「点击调出」不需要再单独 `++` 一次重置令牌。
     */
    var controlsVisible by remember { mutableStateOf(true) }

    /**
     * 「重新计时」令牌：控制栏内任何一次按下都 `++`，让自动隐藏重新计时。
     *
     * 存在的理由：`controlsVisible` 取反才能让计时 Effect 重启，但用户在**已经可见**
     * 的控制栏上操作时它并没有变化（一直是 true），Effect 不会重启，3 秒就会到点。
     * 必须有一个会变的东西把 Effect 顶掉。
     */
    var controlsResetToken by remember { mutableIntStateOf(0) }

    /**
     * 手指是否正按在控制栏（顶栏 / 底部控制区）上。
     *
     * 存在的理由：[controlsResetToken] 只在**按下那一刻**给一次「重新计时」，之后就不管了。
     * 用户手指压在控制栏上思考要点哪个按钮，超过 3 秒 `delay` 照样跑完 ——
     * 「手指还按着，控制栏却从底下溜走」。按下到松开这整段必须**常显**。
     *
     * ⚠️ 它必须同时出现在计时 `LaunchedEffect` 的 **key** 和 **guard** 里，缺一不可：
     * - 只放 guard、不进 key —— 按下时 `controlsPressed` 变了但 Effect 没重启，
     *   那条协程还在跑旧的 `delay`，guard 形同虚设；
     * - 只放 key、不进 guard —— 按下重启后只是「重新数 3 秒」，一样会消失。
     */
    var controlsPressed by remember { mutableStateOf(false) }

    /** 手势起点音量：音量必须相对「按下那一刻」的值算，否则每帧都基于新值累加会失控 */
    var gestureBaseVolume by remember { mutableIntStateOf(0) }

    /**
     * 横向拖动手势的**起点播放位置**（毫秒）。
     *
     * ⚠️ 目标位置必须相对起点算，不能相对「当前 [currentMs]」累加 ——
     * 拖动期间播放器还在往前播，用实时位置当基准会让同一段手指位移越拖越多。
     */
    var gestureBaseMs by remember { mutableIntStateOf(0) }

    /**
     * 横向拖动手势算出的目标位置；`-1` 表示本次手势不是拖进度（或尚未拖过）。
     *
     * ⚠️ 拖动期间**不会**真的 seek，只更新这个值，抬手时由 `onDragEnd` 提交。
     */
    var gestureTargetMs by remember { mutableIntStateOf(-1) }

    /**
     * 本次手势的轴向；`null` = 没有在拖（或还没定轴）。
     *
     * ⚠️ 这个状态**不能省**：[gestureTargetMs] 用 `-1` 表示「没拖进度」，而调音量手势
     * 结束时它仍然是 `-1`；若 `onDragEnd` 只判「target 与起点不同」就会把一次
     * **纯调音量**的手势误判成拖进度，然后 seek 到 -1（实测会写出来的那种低级 bug）。
     */
    var gestureAxis by remember { mutableStateOf<DragAxis?>(null) }

    /**
     * 长按加速（2×）是否正在生效。
     *
     * 这个标志同时承担两件事：
     * 1. 驱动 HUD 药丸的显示；
     * 2. **屏蔽拖动手势** —— 长按成立之后用户手指可能还会轻微移动，
     *    若不拦住，`detectDragGestures` 会在长按期间继续调亮度/音量。
     */
    var boosting by remember { mutableStateOf(false) }

    /**
     * 进入长按加速**之前**的倍速。
     *
     * ⚠️ 松手必须恢复到这个值，而不是硬回 1×：2× 是**临时手势**（按住加速），
     * 倍速面板的 9 档是**用户的持久偏好**。若长按后停在 2×，用户原本设的 1.5×
     * 就被静默覆盖了 —— 那是偷偷改用户设置，本项目不允许。
     */
    var boostSpeedBefore by remember { mutableFloatStateOf(1f) }

    // 音量：真改本机媒体音量（AudioManager.STREAM_MUSIC）
    val audioManager: AudioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume: Int = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }

    val controller: PlayerController = remember { PlayerController() }

    // ---------------- 控制栏自动隐藏（3 秒无操作） ----------------
    //
    // ⚠️⚠️ **key 里绝不能放 `currentMs` / `gestureTargetMs`**：
    //     它们每 200ms / 每帧都在变，放进去会让这个 Effect 每帧重建，
    //     `delay(CONTROLS_AUTO_HIDE_MS)` 永远走不完 —— 控制栏就**永远不会隐藏**。
    //     这是本次改动最容易踩的坑，改动 key 前请先把这段话读完。
    //
    // 五种「不启动计时」的情形及理由：
    // 1. **锁屏**（locked）—— LockLayer 已经把整屏盖住，控制栏本来就看不见，
    //    计时没有意义；而且解锁的瞬间用户正期待看到控制栏。
    // 2. **面板 / 更多菜单打开中**（panel != null || moreMenu）—— 浮层还开着，
    //    底下控制栏悄悄淡出会让界面像在闪；关闭浮层时重新计时即可。
    // 3. **拖动中 / 手势 HUD 显示中**（scrubbing || hud != null）—— 用户正在操作，
    //    中途收走是明确的干扰。
    // 4. **已暂停**（!controller.playing）—— 对齐 YouTube / VLC 的通行行为：
    //    暂停时控制栏常显。用户停下来通常就是要点东西，自动收走很难用。
    // 5. **手指正按在控制栏上**（controlsPressed）—— 与 3 同理但更基础：
    //    接触还没结束就谈何「无操作」。见该状态的 KDoc。
    LaunchedEffect(
        controlsVisible,
        controlsResetToken,
        controlsPressed,
        locked,
        panel,
        moreMenu,
        scrubbing,
        hud,
        controller.playing,
    ) {
        if (!controlsVisible) return@LaunchedEffect
        val busy: Boolean = locked || panel != null || moreMenu || scrubbing || hud != null
        if (busy || controlsPressed) return@LaunchedEffect
        if (!controller.playing) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_MS)
        controlsVisible = false
    }

    /** 短提示：倍速 / 跳转 / 截图 / 不支持项等反馈都走这里 */
    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    // ⚠️ startBoost / endBoost 必须声明在 DisposableEffect(controller) 与
    // LaunchedEffect(index, ...) **之前**：Kotlin 的局部函数不做提升（hoisting），
    // 写在后面的声明对前面的 lambda 是不可见的 —— 之前放在交互动作区（文件末尾附近）
    // 编译就直接 `Unresolved reference 'endBoost'`。凡是需要在「生命周期」区被
    // 回调的局部函数，一律往前放。

    /**
     * 长按加速：进入 2×。
     *
     * 三种不生效的情况，都会给一次性提示且**不改变当前倍速**：
     * 1. 已在加速中（重复触发无意义）；
     * 2. 进入前倍速已 ≥ 2× —— 降回 2× 是减速手势，反直觉，不做；
     * 3. 该视频/机型吃不下 2× —— 留在原倍速，绝不把播放器留在异常态。
     *
     * 生效时先记下 [boostSpeedBefore]，松手由 [endBoost] 还原。
     */
    fun startBoost() {
        if (boosting) return
        if (speed >= BOOST_SPEED) {
            toast(TXT_BOOST_NO_NEED)
            return
        }
        if (!controller.applySpeed(BOOST_SPEED)) {
            toast(TXT_BOOST_UNSUPPORTED)
            return
        }
        boostSpeedBefore = speed
        speed = BOOST_SPEED
        boosting = true
    }

    /**
     * 结束长按加速：恢复到**进入前**的倍速（不是硬回 1×）。
     *
     * 幂等，可重复调用 —— 换片、dispose、手势取消都会调它，重复调用必须安全。
     */
    fun endBoost() {
        if (!boosting) return
        boosting = false
        val back: Float = boostSpeedBefore
        boostSpeedBefore = 1f
        if (controller.applySpeed(back)) {
            speed = back
        } else {
            // 极少数情况：还原失败。退回 1× 而不是留在一个说不清的倍速上。
            controller.applySpeed(1f)
            speed = 1f
        }
    }

    // ---------------------------------------------------------------- 生命周期

    // 播放期间保持亮屏
    DisposableEffect(hostView) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }

    // 退出覆盖层必须把方向交还给系统，即「改回进播放器之前的样子」：
    // 本 App 未在 Manifest 上强制方向，进入前 requestedOrientation 就是 UNSPECIFIED，
    // 因此这里恢复 UNSPECIFIED = 交还传感器 = 跟随用户当前握持姿态，语义上就是原来的方向。
    //
    // ★ 本项目踩过坑 —— 对 MIUI 平板下发 SENSOR_PORTRAIT 会触发 letterbox 兼容模式，
    //   导致窗口反复 churn。**不要**为了「更精确地恢复」去记录并回写 SENSOR_PORTRAIT，
    //   那会把这个已知 bug 重新放出来。所以无论是正常关闭还是被父级移除，都恢复 UNSPECIFIED。
    DisposableEffect(activity) {
        onDispose {
            try {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } catch (t: Throwable) {
                Logx.w(TAG, "恢复屏幕方向失败：${t.message}")
            }
        }
    }

    DisposableEffect(controller) {
        onDispose {
            // 覆盖层被移除时必须先解除长按加速，否则 boosting 会残留为 true，
            // 下次进来 HUD 药丸还挂着、而倍速其实已经不是 2×。endBoost() 幂等。
            endBoost()
            controller.release()
        }
    }

    // 返回键：先做退出动画再通知父级移除
    BackHandler(enabled = !closing) { closing = true }

    // ---------------- 打开远端流（流式播放，不落盘） ----------------

    LaunchedEffect(index, retryToken, items.size) {
        val entry: FileEntry = items.getOrNull(index) ?: return@LaunchedEffect
        // 先置空：DisposableEffect 会立刻 release 旧播放器并 close 旧流。
        // 不这么做的话，新片开流期间旧片还在后台出声（用户看到的是 OpeningLayer，声音却是旧的）。
        source = null
        // 换片：进度 / A-B / 拖动态 / 缓冲条全部归零（倍速、比例、旋转保持，与 VLC 一致）
        // ⚠️ 长按加速必须先解除：否则换片后 boosting 仍为 true，而新片的倍速是面板里的
        // 那个值（不是 2×），HUD 药丸就成了假状态。endBoost() 会还原到长按前的倍速。
        endBoost()
        currentMs = 0
        scrubMs = 0
        scrubbing = false
        aMs = -1
        bMs = -1
        seekAccum = 0
        buffered = emptyList()
        meta = null
        loading = true
        loadError = null

        // 协程取消必须原样抛出，不能被当成「读取失败」吞掉
        val opened: Result<RemoteFileStream> = try {
            openStream(entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        }
        val stream: RemoteFileStream? = opened.getOrNull()
        if (stream == null || stream.failure != null) {
            val reason: String = stream?.failure
                ?: opened.exceptionOrNull()?.message
                ?: ""
            loadError = if (reason.isBlank()) TXT_OPEN_FAILED else "$TXT_OPEN_FAILED：$reason"
            closeQuietly(stream)
            loading = false
            return@LaunchedEffect
        }

        // 首块 / 尾块在构造时已预热（MP4 的 moov 常在文件尾），这里再补一次顺序预取
        try {
            stream.prefetch(0L)
        } catch (t: Throwable) {
            Logx.w(TAG, "预取失败：${t.message}")
        }
        source = VideoSource(
            playerSource = RemoteFileMediaDataSource(stream),
            stream = stream,
        )
        loading = false
    }

    // 列表被外部换掉后，下标可能越界
    LaunchedEffect(items.size) {
        if (index >= items.size) index = (items.size - 1).coerceAtLeast(0)
    }

    // ---------------- 媒体信息 ----------------
    //
    // ⚠️ 必须**另开一条独立流**：API 29+ 的 `MediaMetadataRetriever.release()`
    //    会顺手 close 传进去的 MediaDataSource，复用播放源那条会把播放器一起关掉。
    LaunchedEffect(index, retryToken, items.size) {
        val entry: FileEntry = items.getOrNull(index) ?: return@LaunchedEffect
        meta = withContext(Dispatchers.IO) { readVideoMeta(entry, openStream) }
    }

    // ---------------- 播放器打开 / 释放 ----------------
    //
    // ⚠️ 顺序固定：先 release 播放器，再 close 流。
    //    反过来的话播放器可能正在 readAt，而流已经关了。
    DisposableEffect(source, controller) {
        val src: VideoSource? = source
        if (src != null) controller.open(src.playerSource)
        onDispose {
            controller.release()
            closeQuietly(src?.stream)
        }
    }

    // 播完行为由循环模式决定；用 rememberUpdatedState 避免回调捕获到旧的 loopMode
    val loopNow = rememberUpdatedState(loopMode)
    val goRelativeNow = rememberUpdatedState { delta: Int ->
        val size: Int = items.size
        if (size > 0) index = (index + delta + size) % size
    }
    LaunchedEffect(controller) {
        controller.onCompletion = {
            // ★ 片尾要把控制栏弹出来（对照 YouTube：用户要看到「播完了」）。
            //
            //   时序：MediaPlayer 回调里 `playing` 置 false，这里紧接着置 true，
            //   两者在同一批写入 → 一次重组。而计时 Effect（也含 `controller.playing`
            //   这个 key）重建时 `controlsVisible` 已是 true，不会被开头的
            //   `if (!controlsVisible) return` 挡掉，随后又被 `!playing` 挡住 → 常显。
            //
            //   反过来，若这里不置位：播放停止使 Effect 重建，而 `controlsVisible`
            //   此时可能已经是 false（用户之前开着没有再点过），就会直接 return，
            //   控制栏一路藏到用户自己去点 —— 片尾是个不该需要额外操作的地方。
            //
            //   LOOP_MODE.ALL 会立刻切下一片（index 变了 → 重新起播），这里置位无害：
            //   新片 playing 恢复 true，Effect 再次重建并重新计时。
            controlsVisible = true
            when (loopNow.value) {
                LoopMode.OFF -> {
                    // 不循环：MediaPlayer 自然停在末尾，这里只同步 UI 状态
                }

                LoopMode.ALL -> goRelativeNow.value.invoke(1)

                LoopMode.SINGLE -> {
                    // setLooping(true) 已让播放器内部循环，onCompletion 不会被回调
                }
            }
        }
        controller.onError = { msg -> loadError = msg }
    }

    LaunchedEffect(loopMode, controller) {
        controller.setLoopingValue(loopMode == LoopMode.SINGLE)
    }

    LaunchedEffect(aspect, controller) {
        controller.setScalingMode(
            if (aspect == AspectMode.CROP) {
                MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            } else {
                MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
        )
    }

    LaunchedEffect(orientation, activity) {
        try {
            activity?.requestedOrientation = when (orientation) {
                PlayerOrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                PlayerOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                PlayerOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        } catch (t: Throwable) {
            Logx.w(TAG, "设置屏幕方向失败：${t.message}")
        }
    }

    // ---------------- 进度推进（200ms 轮询，绝不用 runBlocking） ----------------

    val scrubbingNow = rememberUpdatedState(scrubbing)
    val aNow = rememberUpdatedState(aMs)
    val bNow = rememberUpdatedState(bMs)
    val sourceNow = rememberUpdatedState(source)
    LaunchedEffect(controller, source) {
        while (true) {
            delay(POLL_INTERVAL_MS)
            val pos: Int = controller.currentPosition()
            if (!scrubbingNow.value) currentMs = pos

            val stream: RemoteFileStream? = sourceNow.value?.stream
            if (stream != null) {
                // 源读失败过一次就永久置位（不自动恢复）—— 这条流已经废了。
                // 必须置空 source：DisposableEffect 会立刻 release 播放器并 close 流，
                // 否则废流的预取协程会一直挂着，而界面此时已经切到错误态。
                // 重试走 retryToken++，重新 openStream 开一条**全新**的流。
                val reason: String? = stream.failure
                if (reason != null && loadError == null) {
                    loadError = "$TXT_STREAM_BROKEN：$reason"
                    controller.pause()
                    toast(TXT_STREAM_BROKEN)
                    buffered = emptyList()
                    source = null
                    break
                }
                // 缓冲条：已缓存的字节区间（每 200ms 刷一次即可，不影响播放）
                buffered = try {
                    stream.cachedRanges()
                } catch (t: Throwable) {
                    emptyList()
                }
            }

            // A-B 循环：到 B 点就跳回 A 点（真实现，见 KDoc）
            val a: Int = aNow.value
            val b: Int = bNow.value
            if (a >= 0 && b > a && pos >= b) {
                controller.seekTo(a)
                prefetchAtTime(sourceNow.value, a, controller.durationMs)
                if (!scrubbingNow.value) currentMs = a
            }
        }
    }

    // ---------------- 连点累加（±10 秒，900ms 无操作归零） ----------------

    LaunchedEffect(seekAccum) {
        if (seekAccum != 0) {
            delay(SEEK_ACCUM_RESET_MS)
            seekAccum = 0
        }
    }

    // ---------------- 睡眠定时（到点暂停） ----------------

    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            delay(sleepMinutes.toLong() * 60_000L)
            controller.pause()
            toast(TXT_SLEEP_DONE)
            sleepMinutes = 0
        }
    }

    // ---------------- 退出动画 ----------------

    val overlayAlpha: Float by animateFloatAsState(
        targetValue = if (closing) 0f else 1f,
        animationSpec = tween(durationMillis = OVERLAY_ANIM_MS, easing = Motion.EasingEmphasized),
        label = "overlayAlpha",
    )
    LaunchedEffect(closing) {
        if (closing) {
            delay(OVERLAY_ANIM_MS.toLong())
            toast(TXT_CLOSED)
            onClose()
        }
    }

    // ---------------------------------------------------------------- 交互动作

    val entry: FileEntry = items.getOrNull(index) ?: items[0]

    fun requestClose() {
        if (!closing) closing = true
    }

    fun togglePlay() {
        if (controller.playing) controller.pause() else controller.start()
    }

    fun seekBy(deltaSec: Int) {
        val duration: Int = controller.durationMs
        if (!controller.prepared || duration <= 0) {
            toast(TXT_NOT_READY)
            return
        }
        seekAccum += deltaSec
        val target: Int = (controller.currentPosition() + deltaSec * 1000L)
            .coerceIn(0L, duration.toLong())
            .toInt()
        controller.seekTo(target)
        prefetchAtTime(source, target, duration)
        currentMs = target
        toast(if (seekAccum >= 0) "快进 +$seekAccum 秒" else "快退 $seekAccum 秒")
    }

    fun applySpeed(v: Float) {
        if (controller.applySpeed(v)) {
            speed = v
        } else {
            // 该视频/机型吃不下这个倍速：回退 1× 并提示，绝不把播放器留在异常态
            controller.applySpeed(1f)
            speed = 1f
            toast("该视频不支持 ${formatSpeed(v)} 倍速")
        }
    }

    fun toggleAb() {
        if (aMs < 0) {
            aMs = controller.currentPosition()
            bMs = -1
            toast(TXT_SET_A)
            return
        }
        if (bMs < 0) {
            val b: Int = controller.currentPosition()
            if (b <= aMs) {
                toast(TXT_AB_NEED_A)
                return
            }
            bMs = b
            toast(TXT_SET_B)
            return
        }
        aMs = -1
        bMs = -1
        toast(TXT_AB_CLEARED)
    }

    fun openPanel(kind: PanelKind) {
        if (kind == PanelKind.SEEK) seekTargetMs = controller.currentPosition()
        panelTitle = kind.title
        panel = kind
        moreMenu = false
        // 开面板一定是从控制栏点出来的：既然控制栏还活着，就让计时重新开始。
        // 计时 Effect 的 key 里有 `panel`，面板关闭时也会重新计时，不会一关就消失。
        controlsVisible = true
    }

    /**
     * 控制栏内的「按下 → 重新计时 + 立刻可见」回调。
     *
     * ⚠️ **必须用 `remember` 包住**：直接写 `Modifier.resetAutoHideOnPress { ... }`
     * 每次重组都生成新 lambda，会让 `pointerInput` 的 key 变化、手势检测器反复重启
     * （实测表现是连续点按钮时计时错乱）。
     *
     * ## 为什么 `remember` 无 key 也不会捕获旧快照
     *
     * `var controlsVisible by remember { mutableStateOf(true) }` 编译后是
     * `val controlsVisible$delegate = remember { mutableStateOf(true) }`，
     * lambda 捕获的是这个 **`$delegate` 对象引用**，不是 Boolean 值快照。
     * `remember` 无 key 时每次重组返回同一个 `MutableState` 实例，
     * 所以 `controlsVisible = true` 走的是 `$delegate.setValue()`，实时生效。
     *
     * 现成的反证：同一条链路上原有的 `controlsResetToken++` 是能工作的 ——
     * 若捕获的是快照，「控制栏内按下重新计时」从一开始就不会生效。
     *
     * ## 能力边界（已知并刻意接受）
     *
     * 这个修法对「按住」有效，对**「淡出动画进行中快速点一下」**无效：
     * down 让 `controlsVisible = true` 之后，紧接着的 up 会被 GestureLayer 的
     * `onTap` 取反写回 `false`。
     *
     * 不绕的原因是：淡出中点控制栏，用户意图本来就二义（「我要用控制栏」
     * vs「我要收起来」），而「点画面任意位置 toggle」是已定语义。
     * 试图用 flag 去绕过更糟 —— 点按钮时按钮的 `clickable` 消费了 down、
     * GestureLayer 的 `onTap` 根本不触发，flag 永远没机会清，
     * 下一次点画面就变成「点了没反应」。
     */
    val resetAutoHide: () -> Unit = remember {
        {
            controlsVisible = true
            controlsResetToken++
        }
    }

    /**
     * 控制栏「按下 / 松开」回调。
     *
     * ⚠️ 同样必须是**稳定引用**，理由与 [resetAutoHide] 一致：它是
     * `resetAutoHideOnPress` 里 `pointerInput` 的第二个 key。
     */
    val controlsPressedChange: (Boolean) -> Unit = remember {
        { pressed -> controlsPressed = pressed }
    }

    // ---------------------------------------------------------------- UI

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(overlayAlpha.coerceIn(0f, 1f)),
    ) {
        // Z0 —— 画面
        if (loading) {
            OpeningLayer(entry = entry)
        } else if (loadError != null) {
            ErrorLayer(
                message = loadError ?: TXT_OPEN_FAILED,
                onRetry = { retryToken++ },
                onClose = { requestClose() },
            )
        } else {
            VideoStage(
                controller = controller,
                aspect = aspect,
                rotationDeg = rotationDeg,
                showPlayBadge = !controller.playing,
            )
        }

        // Z1 —— 手势层（横向 = 拖进度 / 纵向 = 调音量 / 长按 = 2×）
        GestureLayer(
            enabled = !locked && !closing && loadError == null && !loading,
            // 未 prepared 时不能加速：那时 applySpeed 走的是 pending 分支，
            // 界面上会显示一个拿不到的倍速，违反诚实原则
            boostEnabled = !locked && !closing && loadError == null &&
                !loading && controller.prepared,
            boosting = boosting,
            onTapToggle = {
                // 点击屏幕 = 切换控制栏可见性。
                //
                // ★ 这里**只做这一件事**，不要顺手加「点击暂停/播放」：
                //   真机从未有过该手势（原型 pv-center 的 toggle 只是原型行为），
                //   加上去会让「想调出控制栏」和「想暂停」互相打架 ——
                //   用户想暂停时控制栏被收走，想调出控制栏时播放被打断。
                //   播放/暂停只在底部控制栏的按钮上。
                controlsVisible = !controlsVisible
            },
            onAxisStart = { axis ->
                gestureAxis = axis
                when (axis) {
                    DragAxis.VOLUME -> {
                        gestureBaseVolume = volume
                        hud = HudState.Volume(volume * 100 / maxVolume)
                    }
                    DragAxis.SEEK -> {
                        val at: Int = controller.currentPosition()
                        gestureBaseMs = at
                        gestureTargetMs = at
                        hud = HudState.Seek(targetMs = at, fromMs = at, durationMs = controller.durationMs)
                    }
                }
            },
            onVolumeDelta = { heightFraction ->
                val next: Int = (gestureBaseVolume + heightFraction * maxVolume)
                    .roundToInt()
                    .coerceIn(0, maxVolume)
                hud = HudState.Volume(next * 100 / maxVolume)
                setMusicVolume(audioManager, next) { applied -> volume = applied }
            },
            onSeekDelta = { widthFraction ->
                val duration: Int = controller.durationMs
                if (duration > 0) {
                    // 位移 → 时间：整屏宽度 = 总时长 × [SEEK_FULL_WIDTH_RATIO]
                    val deltaMs: Int = (widthFraction * duration * SEEK_FULL_WIDTH_RATIO).toInt()
                    val target: Int = (gestureBaseMs + deltaMs).coerceIn(0, duration)
                    gestureTargetMs = target
                    hud = HudState.Seek(targetMs = target, fromMs = gestureBaseMs, durationMs = duration)
                }
            },
            onDragEnd = {
                // ⚠️ 只有**横向**手势才提交 seek，且必须看 [gestureAxis]：
                //   调音量手势结束时 gestureTargetMs 还是 -1，光判「target 变了」会 seek 到 -1
                if (gestureAxis == DragAxis.SEEK) {
                    val target: Int = gestureTargetMs
                    // ⚠️ 进度在**抬手时才真正提交**（拖动期间只预览）：
                    //   实时 seek 会让每次手指抖动都丢弃刚拉到的远端数据块
                    if (target >= 0 && target != gestureBaseMs) {
                        val duration: Int = controller.durationMs
                        controller.seekTo(target)
                        currentMs = target
                        // 预读目标位置附近的数据块，否则 seek 后第一段必然卡一下
                        prefetchAtTime(source, target, duration)
                    }
                }
                gestureAxis = null
                gestureTargetMs = -1
                hud = null
            },
            onBoostStart = { startBoost() },
            onBoostEnd = { endBoost() },
        )

        // Z1 —— 顶栏
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(CONTROLS_FADE_MS)),
            exit = fadeOut(tween(CONTROLS_FADE_MS)),
        ) {
            PlayerTopBar(
                title = entry.name,
                // 第三段的规格原文是「视频编码」，但 MediaMetadataRetriever 拿不到编解码器，
                // 这里显示的是容器 MIME（如 video/mp4）；拿不到就「未知」。不编造。
                subtitle = "${index + 1}/${items.size} · ${resolutionText(controller, meta)} · ${mimeText(meta)}",
                locked = locked,
                onClose = { requestClose() },
                onToggleLock = {
                    locked = !locked
                    if (locked) {
                        panel = null
                        moreMenu = false
                    } else {
                        // 解锁的瞬间用户正等着用控制栏 —— 必须立刻可见。
                        // （计时 Effect 的 key 里有 `locked`，此刻 `controlsVisible` 若为 false
                        //  则 Effect 只会重建、不会把它置 true，所以要显式置位。）
                        controlsVisible = true
                    }
                },
                menuExpanded = moreMenu && !locked,
                onOpenMore = { moreMenu = true },
                onDismissMore = { moreMenu = false },
                menuItems = moreMenuItems(
                    speed = speed,
                    onSpeed = { openPanel(PanelKind.SPEED) },
                    onSubtitle = { openPanel(PanelKind.SUBTITLE) },
                    onAudio = { openPanel(PanelKind.AUDIO) },
                    onSleep = { openPanel(PanelKind.SLEEP) },
                    onEqualizer = { openPanel(PanelKind.EQUALIZER) },
                    onInfo = { openPanel(PanelKind.INFO) },
                    onScreenshot = { toast(TXT_NO_SCREENSHOT) },
                    onPip = { toast(TXT_NO_PIP) },
                    onCrop = {
                        aspect = AspectMode.CROP
                        toast("已切换为${AspectMode.CROP.label}")
                    },
                    onSeek = { openPanel(PanelKind.SEEK) },
                ),
                modifier = Modifier.resetAutoHideOnPress(
                    onReset = resetAutoHide,
                    onPressedChange = controlsPressedChange,
                ),
            )
        }

        // Z2 —— 手势 HUD
        GestureHud(
            state = hud,
            modifier = Modifier.align(Alignment.Center),
        )

        // Z2 —— 长按加速药丸：与音量/亮度 HUD 同层，但独立显示，
        // 因为它是「状态」而不是「手势反馈」（只要还按着就一直在）
        BoostHud(
            visible = boosting,
            modifier = Modifier.align(Alignment.Center),
        )

        // Z4 —— 底部控制区（锁定时被 Z3 盖住；这里在 Z3 之前绘制）
        if (!loading && loadError == null) {
            // ⚠️ `align` 必须挂在 AnimatedVisibility **本身**而不是它的 content：
            //    content 的 receiver 是 `AnimatedVisibilityScope`，不是 `BoxScope`，
            //    在里面写 `Modifier.align(...)` 编译不过。
            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(CONTROLS_FADE_MS)),
                exit = fadeOut(tween(CONTROLS_FADE_MS)),
            ) {
                PlayerControls(
                    modifier = Modifier.resetAutoHideOnPress(
                        onReset = resetAutoHide,
                        onPressedChange = controlsPressedChange,
                    ),
                    playing = controller.playing,
                    currentMs = if (scrubbing) scrubMs else currentMs,
                    durationMs = controller.durationMs,
                    aMs = aMs,
                    bMs = bMs,
                    scrubbing = scrubbing,
                    buffered = buffered,
                    totalBytes = source?.stream?.sizeBytes ?: 0L,
                    speed = speed,
                    loopMode = loopMode,
                    hasSubtitle = subtitleIndex > 0,
                    onScrubStart = { scrubbing = true },
                    onScrub = { fraction ->
                        val d: Int = controller.durationMs
                        scrubMs = (fraction * d).toInt().coerceIn(0, d.coerceAtLeast(0))
                    },
                    onScrubEnd = {
                        scrubbing = false
                        controller.seekTo(scrubMs)
                        prefetchAtTime(source, scrubMs, controller.durationMs)
                        currentMs = scrubMs
                    },
                    onTogglePlay = { togglePlay() },
                    onPrev = { goRelativeNow.value.invoke(-1) },
                    onNext = { goRelativeNow.value.invoke(1) },
                    onRewind = { seekBy(-SEEK_STEP_SEC) },
                    onForward = { seekBy(SEEK_STEP_SEC) },
                    onPlaylist = { openPanel(PanelKind.PLAYLIST) },
                    onAspect = { openPanel(PanelKind.ASPECT) },
                    onSubtitle = { openPanel(PanelKind.SUBTITLE) },
                    onSpeed = { openPanel(PanelKind.SPEED) },
                    onAb = { toggleAb() },
                    onLoop = {
                        loopMode = when (loopMode) {
                            LoopMode.OFF -> LoopMode.ALL
                            LoopMode.ALL -> LoopMode.SINGLE
                            LoopMode.SINGLE -> LoopMode.OFF
                        }
                        toast("${TXT_LOOP}：${loopMode.label}")
                    },
                    onPip = { toast(TXT_NO_PIP) },
                    onScreenshot = { toast(TXT_NO_SCREENSHOT) },
                    onRotate = {
                        // 旋转按钮切换的是**控制端屏幕方向**，不是给 SurfaceView 下发 rotation。
                        //
                        // ★ 这两件事只能做一件，同时做会把画面转两次：
                        //   - `view.rotation` 是**视觉旋转**，不改变 SurfaceView 的布局尺寸；
                        //   - 屏幕转横屏则**真的**改变 SurfaceView 的布局空间（宽 > 高）。
                        //   屏幕横过来之后布局空间已经是横的了，若再对 SurfaceView 下发
                        //   rotation=90，等于在横屏空间里又把画面视觉转 90° —— 视频会被
                        //   压成一条（用户报的「画面被压缩，不是原视频尺寸」即此）。
                        //
                        //   而屏幕横过来之后，横屏视频本来就会自然填满屏幕，无需再转画面，
                        //   所以这里**不动 rotationDeg**（它恒为 0，SurfaceView 不做额外旋转）。
                        //
                        // ★ 同样不直接写 activity.requestedOrientation：屏幕方向始终由
                        //   [orientation] 单一驱动，交给下面的 LaunchedEffect 统一落地，
                        //   否则「旋转按钮」与「方向三档面板」会各持一份方向状态、互相覆盖。
                        orientation = if (orientation == PlayerOrientationMode.LANDSCAPE) {
                            PlayerOrientationMode.AUTO
                        } else {
                            PlayerOrientationMode.LANDSCAPE
                        }
                        toast(
                            if (orientation == PlayerOrientationMode.LANDSCAPE) {
                                "屏幕已转为横屏"
                            } else {
                                "屏幕方向已交还系统"
                            }
                        )
                    },
                    onJump = { openPanel(PanelKind.SEEK) },
                )
            }
        }

        // Z3 —— 锁屏层（盖住全部区域，包括顶栏与底部控制区；中央锁图标为唯一可点区域）
        if (locked) {
            LockLayer(onUnlock = { locked = false })
        }

        // Z5 —— 右侧面板
        PlayerPanelSheet(
            visible = panel != null && !locked && !closing,
            title = panelTitle,
            onDismiss = { panel = null },
        ) {
            when (panel) {
                PanelKind.PLAYLIST -> PlaylistPanel(
                    items = items,
                    currentIndex = index,
                    currentDurationMs = controller.durationMs,
                    onPick = { i ->
                        index = i
                        panel = null
                    },
                )

                PanelKind.SPEED -> SpeedPanel(
                    current = speed,
                    onPick = { v -> applySpeed(v) },
                )

                PanelKind.AUDIO -> AudioTrackPanel(
                    current = audioIndex,
                    onPick = { toast(TXT_NO_AUDIO_TRACK) },
                    onDelay = { toast(TXT_NO_AUDIO_DELAY) },
                )

                PanelKind.SUBTITLE -> SubtitlePanel(
                    current = subtitleIndex,
                    onPick = { toast(TXT_NO_SUBTITLE) },
                    onDelay = { toast(TXT_NO_SUBTITLE_DELAY) },
                )

                PanelKind.ASPECT -> AspectPanel(
                    current = aspect,
                    orientation = orientation,
                    onPick = { a -> aspect = a },
                    onOrientation = { o ->
                        orientation = o
                        toast("屏幕方向：${o.label}")
                    },
                )

                PanelKind.SEEK -> SeekPanel(
                    targetMs = seekTargetMs,
                    durationMs = controller.durationMs,
                    onAdjust = { deltaMs ->
                        val d: Int = controller.durationMs
                        seekTargetMs = (seekTargetMs + deltaMs).coerceIn(0, d.coerceAtLeast(0))
                    },
                    onApply = {
                        controller.seekTo(seekTargetMs)
                        currentMs = seekTargetMs
                        toast(TXT_JUMP_DONE + formatDuration(seekTargetMs.toLong()))
                    },
                    onReset = { seekTargetMs = 0 },
                )

                PanelKind.SLEEP -> SleepPanel(
                    current = sleepMinutes,
                    onPick = { m ->
                        sleepMinutes = m
                        toast(if (m == 0) "睡眠定时已关闭" else "$m 分钟后暂停播放")
                    },
                )

                PanelKind.EQUALIZER -> EqualizerPanel(
                    current = 0,
                    onPick = { toast(TXT_NO_EQUALIZER) },
                )

                PanelKind.INFO -> MediaInfoPanel(
                    entry = entry,
                    meta = meta,
                    controller = controller,
                    speed = speed,
                    aspect = aspect,
                    audioTrack = AUDIO_TRACKS[audioIndex],
                    subtitle = SUBTITLE_TRACKS[subtitleIndex],
                )

                null -> Unit
            }
        }

        // 退出动画期间盖一层透明「吃触摸」层：淡出的 400ms 里别让用户还能拖动进度条。
        // （不用 raw pointer event 去 consume —— 那套 API 在本工程的依赖解析下不稳定。）
        if (closing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { /* 吞掉触摸 */ },
            )
        }
    }
}

// ---------------------------------------------------------------- Z0：画面

/**
 * 承载 `SurfaceView` 的画面层。
 *
 * ## 画面比例（真实现）
 * 解码器侧：`MediaPlayer.setVideoScalingMode()` 决定「适应 / 裁剪」；
 * 视图侧：给 `SurfaceView` 施加 `scaleX/scaleY` 做非等比拉伸。
 * - 原始 / 16:9 / 4:3 —— 把 SurfaceView 布局成目标比例的最大内接矩形，缩放模式 = SCALE_TO_FIT；
 * - 拉伸填满 —— 布局成**视频原始比例**的内接矩形（解码器 1:1 无黑边），再非等比拉伸到铺满容器（会变形）；
 * - 裁剪填满 —— 布局铺满容器，缩放模式 = SCALE_TO_FIT_WITH_CROPPING（解码器自己裁）。
 *
 * ⚠️ 不使用父容器 clip 来裁 SurfaceView：SurfaceView 的内容由 SurfaceFlinger 独立合成，
 * 不受祖先 clip 约束，靠 clip 裁不出效果。
 *
 * ## 旋转 90°
 * 旋转的是**控制端的呈现**（我们没有重编码视频的能力，无法改变视频本身的朝向）：
 * 直接给 `SurfaceView` 下发 `rotation`（而不是 `Modifier.rotate`）—— SurfaceView 是自合成层，
 * Compose 的 graphicsLayer 变换到不了 SurfaceFlinger，必须走 View 自身的变换（API 24+ 支持）。
 */
@Composable
private fun VideoStage(
    controller: PlayerController,
    aspect: AspectMode,
    rotationDeg: Float,
    showPlayBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    val videoAspect: Float = if (controller.videoWidth > 0 && controller.videoHeight > 0) {
        controller.videoWidth.toFloat() / controller.videoHeight.toFloat()
    } else {
        0f
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        val box: Pair<Dp, Dp> = stageBoxSize(maxWidth, maxHeight, aspect, videoAspect)
        val scaleX: Float = if (aspect == AspectMode.STRETCH && box.first > 0.dp) {
            (maxWidth / box.first).coerceIn(0.1f, 10f)
        } else {
            1f
        }
        val scaleY: Float = if (aspect == AspectMode.STRETCH && box.second > 0.dp) {
            (maxHeight / box.second).coerceIn(0.1f, 10f)
        } else {
            1f
        }

        AndroidView(
            modifier = Modifier.size(width = box.first, height = box.second),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            // ★ 必须在 surfaceCreated 之后 setDisplay，否则黑屏
                            controller.attachSurface(h)
                        }

                        override fun surfaceChanged(
                            h: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            // 尺寸变化由系统重建 surface，播放器无需处理
                        }

                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            controller.detachSurface()
                        }
                    })
                }
            },
            update = { view ->
                view.rotation = rotationDeg
                view.scaleX = scaleX
                view.scaleY = scaleY
            },
        )

        // 暂停时中央浮出播放图标（指示态，不响应点击 —— 底部控制区常驻，不自动隐藏）
        if (showPlayBadge) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcon.Play,
                    contentDescription = TXT_PLAY,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }
}

/** 打开远端流中：波浪指示器 + 语义说明（首次取块要起 adb 进程，必须说明在做什么） */
@Composable
private fun OpeningLayer(entry: FileEntry, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        WavyProgressIndicator(size = 34.dp, color = Color.White)
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "$TXT_OPENING（${FileBrowserViewModel.formatSize(entry.sizeBytes)}）",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = TXT_STREAM_HINT,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.62f),
        )
    }
}

/** 拉取失败 / 播放失败：给重试入口，不崩 */
@Composable
private fun ErrorLayer(
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = AppIcon.Warning,
            contentDescription = null,
            tint = PlayerAbColor,
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.clickable { onRetry() },
                shape = ShapeMd,
                color = Color.White.copy(alpha = 0.14f),
                contentColor = Color.White,
            ) {
                Text(
                    text = TXT_RETRY,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            Surface(
                modifier = Modifier.clickable { onClose() },
                shape = ShapeMd,
                color = Color.White.copy(alpha = 0.08f),
                contentColor = Color.White.copy(alpha = 0.82f),
            ) {
                Text(
                    text = TXT_BACK_TO_FILES,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 空列表兜底 */
@Composable
private fun PlayerEmptyState(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = TXT_EMPTY_PLAYLIST,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier.clickable { onClose() },
                shape = ShapeMd,
                color = Color.White.copy(alpha = 0.14f),
                contentColor = Color.White,
            ) {
                Text(
                    text = TXT_BACK_TO_FILES,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Z1：顶栏

/**
 * 顶栏：关闭 / 标题 + 副行（n/N · 分辨率 · 视频编码）/ 锁屏 / 更多。
 *
 * Android 15 强制 edge-to-edge，状态栏留白由 `windowInsetsPadding` 自己处理。
 */
@Composable
private fun PlayerTopBar(
    title: String,
    subtitle: String,
    locked: Boolean,
    onClose: () -> Unit,
    onToggleLock: () -> Unit,
    menuExpanded: Boolean,
    onOpenMore: () -> Unit,
    onDismissMore: () -> Unit,
    menuItems: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcon.Close,
            contentDescription = TXT_CLOSE,
            tint = Color.White,
            modifier = Modifier
                .size(44.dp)
                .clickable { onClose() }
                .padding(10.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = if (locked) AppIcon.Lock else PlayerIconLockOpen,
            contentDescription = if (locked) TXT_UNLOCK else TXT_LOCK,
            tint = if (locked) PlayerAccent else Color.White,
            modifier = Modifier
                .size(44.dp)
                .clickable { onToggleLock() }
                .padding(10.dp),
        )
        Box {
            Icon(
                imageVector = AppIcon.MoreVert,
                contentDescription = TXT_MORE,
                tint = Color.White,
                modifier = Modifier
                    .size(44.dp)
                    .clickable { onOpenMore() }
                    .padding(10.dp),
            )
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMore) {
                for ((label, action) in menuItems) {
                    DropdownMenuItem(
                        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                        onClick = action,
                    )
                }
            }
        }
    }
}

/** 「更多」菜单 10 项 */
private fun moreMenuItems(
    speed: Float,
    onSpeed: () -> Unit,
    onSubtitle: () -> Unit,
    onAudio: () -> Unit,
    onSleep: () -> Unit,
    onEqualizer: () -> Unit,
    onInfo: () -> Unit,
    onScreenshot: () -> Unit,
    onPip: () -> Unit,
    onCrop: () -> Unit,
    onSeek: () -> Unit,
): List<Pair<String, () -> Unit>> = listOf(
    "$TXT_SPEED · ${formatSpeed(speed)}" to onSpeed,
    "字幕轨道" to onSubtitle,
    TXT_AUDIO_TRACK to onAudio,
    TXT_SLEEP to onSleep,
    TXT_EQUALIZER to onEqualizer,
    TXT_MEDIA_INFO to onInfo,
    TXT_SCREENSHOT to onScreenshot,
    TXT_PIP to onPip,
    "$TXT_ASPECT · ${AspectMode.CROP.label}" to onCrop,
    TXT_JUMP to onSeek,
)

// ---------------------------------------------------------------- Z1：手势层

/**
 * 一次拖动手势的轴向。**定轴后本次手势不可再变**，否则斜着划会被算成「同时拖进度又改音量」。
 */
private enum class DragAxis { SEEK, VOLUME }

/**
 * 横向拖动的灵敏度：**划过整个屏幕宽度 = 总时长的多少倍**。
 *
 * 现在是 1.0 —— 整屏 = 整片时长（VLC Android 的通行取值），好处是短视频和长视频的
 * 手感一致（不会因为片子长就"划不动"）。想更精细就往小调（如 0.5 = 整屏只走半片）。
 *
 * ⚠️ 这个值**必须**是比例而不是固定毫秒：固定值在 3 分钟短视频上过冲、在 3 小时电影上等于没反应。
 */
private const val SEEK_FULL_WIDTH_RATIO: Float = 1.0f

/**
 * 手势层：**横向拖动 = 拖进度**，**纵向拖动 = 调音量**（不分区，整块屏幕一致）。
 *
 * ## 音量（真实现）
 * `AudioManager.getStreamMaxVolume(STREAM_MUSIC)` + `setStreamVolume`，**真改本机媒体音量**。
 * `setStreamVolume` 在部分 ROM 上会因缺少 `MODIFY_AUDIO_SETTINGS` 抛
 * `SecurityException`，这里 catch 住并由调用方降级提示，不让播放页崩。
 *
 * ## 拖动进度（真实现，但**松手才真正 seek**）
 * 拖动期间只在 HUD 里更新目标时间，**不调 `MediaPlayer.seekTo`**。
 * ⚠️ 这不是偷懒：画面来自**被控端的流式随机读**（2 MB 一块 + LRU 6 块，一块一次 adb 往返
 * 约 0.8 s）。若跟着 `onDrag` 实时 seek，一秒几十次抖动会不断丢弃刚拉到的块、
 * 反复新起 adb 命令，表现是**越拖越卡直到卡死**。所以走「拖动预览 + 抬手提交」：
 * 视觉效果由 HUD 承担，网络只读一次。代价是拖动时画面不跟着走，
 * 这是流式远程播放必须接受的取舍，且 HUD 会把目标时间写清楚，不误导。
 *
 * ## 为什么**删掉了亮度手势**（2026-09-27 用户要求）
 * 原形态是「左半屏亮度 / 右半屏音量」。亮度在远程串流下从来都是**假的**：
 * 改控制端亮度 ≠ 改被控端亮度，真要给被控端下发需要 `WRITE_SETTINGS`（签名级权限，
 * 普通 adb 授权拿不到）。用户明确要求上下滑动就是音量 —— 于是顺水推舟：
 * 删掉这个没有真实语义的手势，把整个竖直方向交给**真能用**的音量，左右半区也不再有区别。
 *
 * ## 实现说明
 * 用 `detectDragGestures`（foundation 提供的现成手势检测器）而不是手写 raw pointer event，
 * 并做**轴向锁定**：一次手势里不许横向又同时纵向生效（详见 [GestureLayer] 的 KDoc）。
 *
 * ⚠️ 这里**没有**「单击画面切换播放/暂停」：一个 `pointerInput` 里挂两个检测器会互相抢事件，
 * 而规格 §4.6.3 也没要求这个手势（播放/暂停走底部常驻按钮）。
 */
@Composable
/**
 * 手势层：**横向拖动 = 拖进度**，**纵向拖动 = 调音量**，**左右任一侧长按 = 2× 加速**。
 *
 * ⚠️ **轴向必须锁死**：一次手势只能算一个方向，否则用户画一条斜线会同时 Seek 又改音量。
 *   判定方式不是「第一帧哪个分量大」（抖动会把竖直分类判定成水平），而是累计位移中
 *   较大的那个分量**超过系统 `touchSlop`** 才定轴，定轴之后本次手势不可再变
 *   ——这也是 VLC / Bilibili 等播放器的通行做法。
 *
 * ⚠️ 为什么这里一行距离计算都不用写（长按 vs 拖动）：
 * - `detectDragGestures` 内部用 `awaitFirstDown(requireUnconsumed = false)`，
 *   而 `detectTapGestures` 会 consume 第一个 down —— 但前者不要求 unconsumed，
 *   所以**两个检测器都能拿到 down，与声明顺序无关**；
 * - `detectDragGestures` 只有位移超过 `touchSlop` 才 consume 并触发 `onDragStart`；
 *   而 `detectTapGestures` 一旦监测到位移被消费就判定手势取消，`onLongPress` 不会触发。
 * 于是「移动 = 拖进度/调音量，不动 = 长按加速」这条阈值由系统机制天然实现。
 *
 * 唯一残留的竞态是：长按**已成立之后**用户再移动手指，理论上拖动侧仍可能起拖。
 * 用 [boosting] 在拖动的三个回调里显式拦掉 —— 三行代码，行为确定。
 *
 * @param boosting       长按加速是否生效中；为 true 时拖动手势一律不响应
 * @param onTapToggle    单击（非拖动、非长按成立）时回调，用于切换控制栏可见性。
 *                       ⚠️ 名字刻意叫 `onTapToggle` 而不叫 `onTap`：Kotlin 命名参数
 *                       **不引入绑定**，若外层参数也叫 `onTap`，调用处写
 *                       `onTap = { onTap() }` 指向的是外层参数自己 → 无限递归。
 * @param onAxisStart    本次手势的轴向确定时回调**一次**（调用方据此记录音量起点 / 进度起点）
 * @param onSeekDelta    横向累计位移占屏宽的比例，右滑为正
 * @param onVolumeDelta  纵向累计位移占屏高的比例，**上滑为正**（屏幕 y 向下为正，已取负）
 * @param onDragEnd      本次手势结束（抬手 / 取消）；轴向从未确定时不回调
 * @param onBoostStart   长按成立（左右两侧都触发，参数仅表示是哪一侧）
 * @param onBoostEnd     松手 / 取消
 */
private fun GestureLayer(
    enabled: Boolean,
    boostEnabled: Boolean,
    boosting: Boolean,
    onTapToggle: () -> Unit,
    onAxisStart: (DragAxis) -> Unit,
    onSeekDelta: (widthFraction: Float) -> Unit,
    onVolumeDelta: (heightFraction: Float) -> Unit,
    onDragEnd: () -> Unit,
    onBoostStart: (leftSide: Boolean) -> Unit,
    onBoostEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ 下面那个 pointerInput 的 key 是 (enabled, boostEnabled)，**不含 boosting**，
    //    所以闭包里直接读 boosting 拿到的是**旧快照**（remember 不带 key 的话会一直不变）。
    //    必须用 rememberUpdatedState 拿实时值守卫：长按已成立就不该再算点击。
    val boostingNow by rememberUpdatedState(boosting)
    Box(
        modifier = modifier
            .fillMaxSize()
            // ① 拖动：先声明 = 外层
            .pointerInput(enabled, boosting) {
                if (!enabled) return@pointerInput
                val widthPx: Float = size.width.toFloat().coerceAtLeast(1f)
                val heightPx: Float = size.height.toFloat().coerceAtLeast(1f)
                // 定轴阈值取系统值，**不写死 8 dp** —— 不同 ROM / Compose 版本会变
                val slopPx: Float = viewConfiguration.touchSlop
                var axis: DragAxis? = null
                var totalX: Float = 0f
                var totalY: Float = 0f
                detectDragGestures(
                    onDragStart = {
                        axis = null
                        totalX = 0f
                        totalY = 0f
                    },
                    onDragEnd = {
                        if (!boosting && axis != null) onDragEnd()
                        axis = null
                    },
                    onDragCancel = {
                        if (!boosting && axis != null) onDragEnd()
                        axis = null
                    },
                    onDrag = { _, dragAmount ->
                        if (boosting) return@detectDragGestures
                        totalX += dragAmount.x
                        totalY += dragAmount.y
                        if (axis == null) {
                            // 死区：累计位移还没过 slop 之前不表态，避免手指按下时的抖动立刻起拖
                            if (abs(totalX) < slopPx && abs(totalY) < slopPx) {
                                return@detectDragGestures
                            }
                            axis = if (abs(totalX) >= abs(totalY)) DragAxis.SEEK else DragAxis.VOLUME
                            onAxisStart(axis!!)
                        }
                        when (axis) {
                            // 横向：比例相对**整屏宽度**，调用方再按总时长换算成毫秒
                            DragAxis.SEEK -> onSeekDelta((totalX / widthPx).coerceIn(-1f, 1f))
                            // 纵向：向上拖 = dragAmount.y 为负 = 增大，故取负
                            DragAxis.VOLUME -> onVolumeDelta((-totalY / heightPx).coerceIn(-1f, 1f))
                            null -> Unit
                        }
                    },
                )
            }
            // ② 长按：后声明 = 内层
            .pointerInput(enabled, boostEnabled) {
                if (!enabled || !boostEnabled) return@pointerInput
                val widthPx: Float = size.width.toFloat().coerceAtLeast(1f)
                detectTapGestures(
                    onLongPress = { offset ->
                        onBoostStart(offset.x < widthPx / 2f)
                    },
                    onPress = {
                        // onPress 从按下一直挂到抬手/取消，用它拿「松手」事件最稳
                        try {
                            tryAwaitRelease()
                        } finally {
                            onBoostEnd()
                        }
                    },
                    onTap = {
                        // 只有「单击」才切换控制栏：
                        // - 拖动已经被 detectDragGestures 消费掉了位移，这里不会再触发（见上面 KDoc）；
                        // - 长按已成立时 boostingNow 为 true，不能再算一次点击（双保险）。
                        if (!boostingNow) onTapToggle()
                    },
                )
            },
    )
}

/**
 * 按下即重置控制栏自动隐藏计时。
 *
 * ## 为什么这样写不会吃掉控制栏内部按钮的点击
 *
 * Compose 的 pointer input **没有** View 那套「`onInterceptTouchEvent` 返回 true 就拦截」
 * 的机制。一个 `PointerInputModifier` 能影响别人的**唯一**手段是调用
 * `PointerInputChange.consume()`，把事件标记为已消费；别人用
 * `awaitFirstDown(requireUnconsumed = true)` 时就看不到它了。反向亦然。
 *
 * 这个 modifier 两手都不沾：
 * 1. `awaitFirstDown(requireUnconsumed = false)` —— 明确声明「别人消费过我也要」，
 *    所以它自己永远不会被按钮抢先消费而漏掉；
 * 2. **全程不调用 `consume()`**（拿到 change 后既没 `consume()` 也没有 downstream 消费），
 *    `waitForUpOrCancellation()` 的返回值直接丢弃 —— 所以它也不会让别人漏掉。
 *
 * 于是它对命中链上的其他节点是**观测等价**于不存在的。这一点与 dispatch 顺序无关，
 * 所以不必依赖「先父后子还是先子后父」的实现细节。
 *
 * ## 为什么不直接把 `++` 写进各个回调
 *
 * 那样要在 `PlayerControls` 的 13 个回调和 `PlayerTopBar` 的 4 个回调里逐个塞一行，
 * 将来加控件很容易漏。挂在外层容器上一次覆盖全部，且**新增控件自动生效**。
 *
 * @param onReset         按下瞬间回调一次，调用方据此「重新计时 + 确保可见」。
 *                        ⚠️ 必须是**稳定引用**（用 `remember { { ... } }` 包住）。写成
 *                        `Modifier.resetAutoHideOnPress { controlsResetToken++ }` 的话
 *                        每次重组都会生成新 lambda → key 变 → 手势检测器反复重启。
 * @param onPressedChange 按下 → `true`，松开 / 取消 → `false`。同样必须稳定引用。
 */
private fun Modifier.resetAutoHideOnPress(
    onReset: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
): Modifier =
    this.pointerInput(onReset, onPressedChange) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            onReset()
            onPressedChange(true)
            try {
                waitForUpOrCancellation()
            } finally {
                // ★ 必须用 finally，覆盖**两条**返回路径：
                //   1. 正常抬手 / 检测到 cancel —— `waitForUpOrCancellation()` 返回
                //      Change 或 null，都是**正常返回**，后面那句本来也会执行；
                //   2. 协程被**从外部取消** —— `pointerInput` 的 key 变化（比如
                //      controlsPressed 引发重组、key 重建）或被 dispose 时，
                //      这里会抛 CancellationException，函数不再往下走。
                //   第 2 条一旦发生而没复位，`controlsPressed` 就永久停在 true，
                //   计时 Effect 永远被 guard 挡住 → **控制栏再也不隐藏**。
                //   放进 finally 才能保证 up / cancel / 外部取消全部走到复位。
                onPressedChange(false)
            }
        }
    }

/**
 * 长按加速中浮出的药丸（「2× 加速中」）。
 *
 * 与 [GestureHud] 分开做，因为两者性质不同：[GestureHud] 是**手势反馈**（松手即消失），
 * 这枚是**状态指示**（只要手指还按着就一直在），塞进同一个组件会让生命周期互相纠缠。
 *
 * ⚠️ spring 是欠阻尼的，从 1 回到 0 会**下冲到负值**，喂给 `Modifier.alpha` 前必须夹取。
 * 本项目已因 spring 下冲喂负值给 `Modifier.padding` 崩过一次
 * （`IllegalArgumentException: Padding must be non-negative`）。
 */
@Composable
private fun BoostHud(visible: Boolean, modifier: Modifier = Modifier) {
    val rawAlpha: Float by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = Motion.springFast(),
        label = "boostHudAlpha",
    )
    val alpha: Float = rawAlpha.coerceIn(0f, 1f)
    if (alpha <= 0.01f) return
    Box(modifier = modifier.fillMaxSize().alpha(alpha), contentAlignment = Alignment.Center) {
        Surface(
            shape = ShapeFull,
            color = Color.Black.copy(alpha = 0.66f),
            contentColor = Color.White,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = AppIcon.Play,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = TXT_BOOSTING,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * 写入本机媒体音量。
 *
 * ⚠️ `setStreamVolume` 在部分 ROM 上会因缺少 `MODIFY_AUDIO_SETTINGS` 抛
 * `SecurityException`（不是所有厂商都把它当 normal 权限），这里只记日志不崩，
 * 被拒时不会回调 [onApplied]（HUD 数字也就停在原值）。
 */
private fun setMusicVolume(
    audioManager: AudioManager,
    target: Int,
    onApplied: (Int) -> Unit,
) {
    try {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        onApplied(target)
    } catch (t: Throwable) {
        Logx.w(TAG, "调节媒体音量失败：${t.message}")
    }
}

// ---------------------------------------------------------------- Z2：手势 HUD

/**
 * 手势 HUD：中央浮出「图标 + 进度条 + 百分比」。
 *
 * ⚠️ spring 下冲陷阱：`Motion.springFast()` 欠阻尼，1 → 0 会下冲到负值，
 * 喂给 alpha 前必须 `coerceIn(0f, 1f)`。
 */
@Composable
private fun GestureHud(state: HudState?, modifier: Modifier = Modifier) {
    val rawAlpha: Float by animateFloatAsState(
        targetValue = if (state != null) 1f else 0f,
        animationSpec = Motion.springFast(),
        label = "hudAlpha",
    )
    val alpha: Float = rawAlpha.coerceIn(0f, 1f)
    if (alpha <= 0.01f || state == null) return

    // 一个手势只可能 instanceof 其中一支（[HudState] 是 sealed），`when` 无需 else，
    // 以后新增 HUD 形态时编译器会在这里报错提醒你补分支。
    Box(modifier = modifier.fillMaxSize().alpha(alpha), contentAlignment = Alignment.Center) {
        when (state) {
            is HudState.Volume -> GestureHudCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcon.VolumeUp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    HudMeter(
                        title = "$TXT_VOLUME ${state.percent.coerceIn(0, 100)}%",
                        detail = null,
                        percent = state.percent.coerceIn(0, 100) / 100f,
                    )
                }
            }

            is HudState.Seek -> {
                val duration: Int = state.durationMs
                val deltaMs: Int = state.targetMs - state.fromMs
                val forward: Boolean = deltaMs >= 0
                GestureHudCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (forward) PlayerIconFastForward else PlayerIconFastRewind,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        HudMeter(
                            // 第一行是「方向 + 增量」，第二行是「目标 / 总时长」——
                            // 两行都给，是为了让用户在**抬手前**就知道会跳到哪里：
                            // 本播放器拖动期间不真的 seek（见手势层注释），HUD 是唯一的实时反馈。
                            title = if (forward) {
                                "$TXT_SEEK_FORWARD +${formatDuration(abs(deltaMs).toLong())}"
                            } else {
                                "$TXT_SEEK_REWIND -${formatDuration(abs(deltaMs).toLong())}"
                            },
                            detail = "${formatDuration(state.targetMs.toLong())} / ${formatDuration(duration.toLong())}",
                            percent = if (duration > 0) state.targetMs / duration.toFloat() else 0f,
                        )
                    }
                }
            }
        }
    }
}

/** HUD 的容器（黑 66% 圆角卡片）；抽出来是为了让各 HUD 形态共用同一套外观 */
@Composable
private fun GestureHudCard(content: @Composable RowScope.() -> Unit) {
    Surface(shape = ShapeMd, color = Color.Black.copy(alpha = 0.66f), contentColor = Color.White) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * HUD 里的文字区：标题 + 可选副行 + 一条进度条。
 *
 * @param percent 0–1；**喂给 `fillMaxWidth` 前必须夹取** —— spring/float 计算都可能给出越界值，
 * 本项目已经被 `Padding must be non-negative` 崩过一次。
 */
@Composable
private fun HudMeter(
    title: String,
    detail: String?,
    percent: Float,
) {
    Column {
        Text(text = title, style = MaterialTheme.typography.labelMedium, color = Color.White)
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(HUD_BAR_WIDTH)
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.28f), CircleShape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(percent.coerceIn(0f, 1f))
                    .background(Color.White, CircleShape),
            )
        }
    }
}

// ---------------------------------------------------------------- Z3：锁屏层

/**
 * 锁屏层：盖住**包括顶栏与底部控制区在内**的全部区域，屏蔽所有触摸，
 * 中央一枚锁图标是唯一可点区域。
 */
@Composable
private fun LockLayer(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .clickable { onUnlock() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcon.Lock,
                contentDescription = TXT_UNLOCK,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Z4：底部控制区

/**
 * 底部控制区 4 行：主控制行 / 进度条 / 时间行 / 工具行。
 *
 * 可见性由调用方的 `AnimatedVisibility` 控制：**3 秒无操作自动隐藏**（与顶栏联动），
 * 点击屏幕调出 / 收起。（早期规格写的是「不自动隐藏」，已推翻。）
 */
@Composable
private fun PlayerControls(
    playing: Boolean,
    currentMs: Int,
    durationMs: Int,
    aMs: Int,
    bMs: Int,
    scrubbing: Boolean,
    /** 已缓存字节区间（远端流缓冲条） */
    buffered: List<LongRange>,
    /** 远端文件总字节数；≤ 0 时不画缓冲条 */
    totalBytes: Long,
    speed: Float,
    loopMode: LoopMode,
    hasSubtitle: Boolean,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onPlaylist: () -> Unit,
    onAspect: () -> Unit,
    onSubtitle: () -> Unit,
    onSpeed: () -> Unit,
    onAb: () -> Unit,
    onLoop: () -> Unit,
    onPip: () -> Unit,
    onScreenshot: () -> Unit,
    onRotate: () -> Unit,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.68f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        // 第 1 行：主控制（7 枚）
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PlayerIconButton(icon = AppIcon.Bookmark, desc = TXT_PLAYLIST, onClick = onPlaylist, modifier = Modifier.weight(1f))
            PlayerIconButton(icon = AppIcon.Back, desc = TXT_PREV, onClick = onPrev, modifier = Modifier.weight(1f))
            PlayerIconButton(icon = AppIcon.Back, desc = TXT_REWIND, onClick = onRewind, modifier = Modifier.weight(1f), badge = "10")
            PlayerIconButton(
                icon = if (playing) PlayerIconPause else AppIcon.Play,
                desc = if (playing) TXT_PAUSE else TXT_PLAY,
                onClick = onTogglePlay,
                modifier = Modifier.weight(1f),
                size = 52.dp,
            )
            PlayerIconButton(icon = AppIcon.ChevronRight, desc = TXT_FORWARD, onClick = onForward, modifier = Modifier.weight(1f), badge = "10")
            PlayerIconButton(icon = AppIcon.ChevronRight, desc = TXT_NEXT, onClick = onNext, modifier = Modifier.weight(1f))
            PlayerIconButton(icon = AppIcon.Fullscreen, desc = TXT_ASPECT, onClick = onAspect, modifier = Modifier.weight(1f))
        }

        // 第 2 行：进度条（含 A-B 段与拖动时间气泡）
        PlayerSeekBar(
            progress = if (durationMs > 0) currentMs.toFloat() / durationMs.toFloat() else 0f,
            aMs = aMs,
            bMs = bMs,
            durationMs = durationMs,
            scrubbing = scrubbing,
            buffered = buffered,
            totalBytes = totalBytes,
            onScrubStart = onScrubStart,
            onScrub = onScrub,
            onScrubEnd = onScrubEnd,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )

        // 第 3 行：时间 / 字幕 / 倍速 / 总时长
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatDuration(currentMs.toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            Spacer(modifier = Modifier.weight(1f))
            PlayerIconButton(
                icon = AppIcon.Clipboard,
                desc = TXT_SUBTITLE,
                onClick = onSubtitle,
                highlight = hasSubtitle,
                size = 36.dp,
            )
            Spacer(modifier = Modifier.width(6.dp))
            PlayerTextButton(text = formatSpeed(speed), onClick = onSpeed, highlight = speed != 1f)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = formatDuration(durationMs.toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.72f),
            )
        }

        // 第 4 行：工具（6 枚）
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PlayerTextButton(text = "AB", onClick = onAb, highlight = aMs >= 0, modifier = Modifier.weight(1f))
            PlayerIconButton(
                icon = AppIcon.Refresh,
                desc = TXT_LOOP,
                onClick = onLoop,
                modifier = Modifier.weight(1f),
                highlight = loopMode != LoopMode.OFF,
                badge = if (loopMode == LoopMode.OFF) null else if (loopMode == LoopMode.ALL) "全部" else "单集",
            )
            PlayerIconButton(icon = AppIcon.Recent, desc = TXT_PIP, onClick = onPip, modifier = Modifier.weight(1f))
            PlayerIconButton(icon = AppIcon.Camera, desc = TXT_SCREENSHOT, onClick = onScreenshot, modifier = Modifier.weight(1f))
            PlayerIconButton(icon = AppIcon.Rotate, desc = TXT_ROTATE, onClick = onRotate, modifier = Modifier.weight(1f))
            PlayerIconButton(icon = AppIcon.Tune, desc = TXT_JUMP, onClick = onJump, modifier = Modifier.weight(1f))
        }
    }
}

/** 播放器的图标按钮；[badge] 为右下角小角标（快进「10」、循环模式等） */
@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    highlight: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 44.dp,
) {
    val tint: Color = if (!enabled) {
        Color.White.copy(alpha = 0.35f)
    } else if (highlight) {
        PlayerAccent
    } else {
        Color.White
    }
    Box(
        modifier = modifier
            .size(size)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size((size.value * 0.55f).dp),
        )
        if (!badge.isNullOrBlank()) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 1.dp),
            )
        }
    }
}

/** 播放器的文本按钮（倍速「1×」、A-B「AB」） */
@Composable
private fun PlayerTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .widthIn(min = 44.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (highlight) PlayerAccent else Color.White,
        )
    }
}

/**
 * 进度条：轨道 + 已播 + 拖动把手 + A-B 段色块；拖动时浮出时间气泡。
 *
 * ⚠️ 缩略预览图我们没有（MediaMetadataRetriever 取帧开销大且需要解码），
 * 这里只做纯时间气泡，规格 §4.6.3 的缩略图能力**未实现**。
 */
@Composable
private fun PlayerSeekBar(
    progress: Float,
    aMs: Int,
    bMs: Int,
    durationMs: Int,
    scrubbing: Boolean,
    /** 已缓存字节区间（来自 `RemoteFileStream.cachedRanges()`） */
    buffered: List<LongRange>,
    /** 远端文件总字节数；> 0 时才能把字节区间换算成进度比例 */
    totalBytes: Long,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction: Float = progress.coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier = modifier
            .height(28.dp)
            // ★ 按下即消费 down：否则这次点击会穿透到下层 GestureLayer 的 onTap，
            //   变成「点一下进度条，控制栏当场被收走」。
            //
            //   成因：`detectDragGestures` 内部 `awaitFirstDown(requireUnconsumed = false)`
            //   **不消费** down，只在越过 touch slop 之后才 `change.consume()`。
            //   所以单纯点一下（无位移）时全程没有任何消费，事件继续传到 Z 序更低的
            //   GestureLayer → `onTap` → `controlsVisible = !controlsVisible`。
            //   而同一瞬间 `resetAutoHideOnPress` 明明刚重置过计时 ——
            //   用户刚做了一个真实操作，控制栏却立刻收走，与设计意图直接冲突。
            //
            //   放在这里（链上更早 = 外层）不影响下面的 `detectDragGestures`：
            //   它内部用的是 `awaitFirstDown(requireUnconsumed = false)`，
            //   down 被消费过也照样能拿到，后续 move 与 slop 判定不受影响。
            //
            // ⚠️ 这个 `consume()` **只能**加在这里，绝不能加到 `resetAutoHideOnPress` 里去：
            //    后者挂在控制栏 root 上（父级），一旦在那里消费 down，控制栏里所有
            //    `clickable` 按钮（用默认的 `awaitFirstDown()`，即 `requireUnconsumed = true`）
            //    会**全部失效**。见 `resetAutoHideOnPress` 的 KDoc。
            //
            // ⚠️ 别顺手改 seek 语义：`onDragStart` 要越过 touch slop 才触发，
            //    所以进度条目前**只能拖、不能点**（点击不 seek）。这是既有行为，
            //    不是本次引入的。修完这里之后，点进度条只是「什么都不发生」而已。
            //    「点击跳转」是另一个需求，改 seek 逻辑有风险，本次不动。
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    waitForUpOrCancellation()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onScrubStart()
                        onScrub((offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onScrubEnd() },
                    onDragCancel = { onScrubEnd() },
                    onDrag = { change, _ ->
                        onScrub((change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f))
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidth: Dp = maxWidth
        // 轨道
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.26f), CircleShape),
        )
        // 缓冲条（远端流的已缓存区间；可能有多段，seek 过后常见不连续）
        if (totalBytes > 0L) {
            buffered.forEach { range: LongRange ->
                // `cachedRanges()` 给的是 `start until end`（尾 exclusive），+1 才是真实末端
                val startF: Float = (range.first.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                val endF: Float = ((range.last + 1L).toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                if (endF > startF) {
                    Box(
                        modifier = Modifier
                            .offset(x = trackWidth * startF)
                            .width(trackWidth * (endF - startF))
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.42f), CircleShape),
                    )
                }
            }
        }
        // A-B 段
        if (durationMs > 0 && aMs >= 0 && bMs > aMs) {
            val aF: Float = (aMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            val bF: Float = (bMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .offset(x = trackWidth * aF)
                    .width(trackWidth * (bF - aF))
                    .height(4.dp)
                    .background(PlayerAbColor, CircleShape),
            )
        }
        // 已播
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(Color.White, CircleShape),
        )
        // 把手
        Box(
            modifier = Modifier
                .offset(x = (trackWidth * fraction) - 6.dp)
                .size(12.dp)
                .background(Color.White, CircleShape),
        )
        // 拖动气泡（纯时间，无缩略图）
        if (scrubbing) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (trackWidth * fraction) - 24.dp, y = (-26).dp)
                    .widthIn(min = 48.dp)
                    .height(22.dp)
                    .background(Color.Black.copy(alpha = 0.8f), ShapeMd),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatDuration((fraction * durationMs.toFloat()).toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Z5：右侧面板

/** 右侧面板：侧滑入（400ms EasingEmphasized）+ Shape3xl（32dp）圆角 */
@Composable
private fun PlayerPanelSheet(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally(
            animationSpec = tween(durationMillis = OVERLAY_ANIM_MS, easing = Motion.EasingEmphasized),
        ) { it } + fadeIn(animationSpec = tween(OVERLAY_ANIM_MS)),
        exit = slideOutHorizontally(
            animationSpec = tween(durationMillis = OVERLAY_ANIM_MS, easing = Motion.EasingEmphasized),
        ) { it } + fadeOut(animationSpec = tween(OVERLAY_ANIM_MS)),
    ) {
        // ⚠️ 面板宽度不要用 `fillMaxWidth(f).widthIn(max = ...)` 组合：
        //    当 0.82×屏宽 > max 时会得到 minWidth > maxWidth 的 Constraints（直接崩）。
        //    这里先夹取屏宽再乘系数，永远得到单一确定宽度。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(maxWidth.coerceAtMost(360.dp) * 0.82f)
                    .clip(Shape3xl)
                    .clickable { /* 吃掉点击，避免冒泡到遮罩关闭面板 */ },
                shape = Shape3xl,
                color = PlayerSheetColor,
                contentColor = Color.White,
            ) {
                Column(modifier = Modifier.fillMaxHeight()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = AppIcon.Close,
                            contentDescription = TXT_CLOSE,
                            tint = Color.White,
                            modifier = Modifier
                                .size(32.dp)
                                .clickable { onDismiss() }
                                .padding(4.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.10f)),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 12.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

/** 面板内的一行选项 */
@Composable
private fun PanelOptionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    selected: Boolean = false,
    dimmed: Boolean = false,
) {
    val labelColor: Color = if (dimmed) Color.White.copy(alpha = 0.55f) else Color.White
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!value.isNullOrBlank()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.52f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelLarge,
                color = PlayerAccent,
                modifier = Modifier.width(20.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 面板内的延迟微调行（照画，置灰并给提示） */
@Composable
private fun DelayStepRow(
    label: String,
    valueMs: Int,
    onAdjust: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
            )
            Text(
                text = "${if (valueMs >= 0) "+" else ""}$valueMs ms",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.42f),
            )
        }
        StepChip(text = "−$DELAY_STEP_MS", onClick = { onAdjust(-DELAY_STEP_MS) })
        Spacer(modifier = Modifier.width(8.dp))
        StepChip(text = "+$DELAY_STEP_MS", onClick = { onAdjust(DELAY_STEP_MS) })
    }
}

/** 小步进按钮 */
@Composable
private fun StepChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = ShapeMd,
        color = Color.White.copy(alpha = 0.10f),
        contentColor = Color.White.copy(alpha = 0.72f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 播放列表：当前目录的视频清单，点击直切。
 *
 * ⚠️ 「大小」是**远端大小**（`FileEntry.sizeBytes`），不是控制端缓存大小；
 * 「时长」只有**当前正在播的那个**能从播放器取到，其余条目显示「—」
 * （要拿它们的时长需要对每个文件都 pull 一遍，代价是几百 MB 级，不做）。
 */
@Composable
private fun PlaylistPanel(
    items: List<FileEntry>,
    currentIndex: Int,
    currentDurationMs: Int,
    onPick: (Int) -> Unit,
) {
    // ⚠️ 这里**不能**用 LazyColumn：面板内容已经包在 `verticalScroll` 的 Column 里，
    //    同方向滚动嵌套会被 foundation 直接抛异常。播放列表是单目录条目，量级小，直接铺开。
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEachIndexed { i, item ->
            val isCurrent: Boolean = i == currentIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isCurrent) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                    .clickable { onPick(i) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${i + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.width(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) PlayerAccent else Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${FileBrowserViewModel.formatSize(item.sizeBytes)} · " +
                            (if (isCurrent && currentDurationMs > 0) formatDuration(currentDurationMs.toLong()) else "—"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.52f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isCurrent) {
                    Text(
                        text = TXT_PLAYING_NOW,
                        style = MaterialTheme.typography.labelSmall,
                        color = PlayerAccent,
                    )
                }
            }
        }
    }
}

/**
 * 倍速 9 档（真实现，`MediaPlayer.playbackParams`）。
 *
 * ⚠️ 部分机型 / 编码只支持 0.5–2×，超出会抛异常；由 `PlayerController.applySpeed`
 * 捕获并回退 1× + toast，不会崩。
 */
@Composable
private fun SpeedPanel(current: Float, onPick: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        for (v in SPEED_STEPS) {
            PanelOptionRow(
                label = formatSpeed(v),
                value = if (v == 1f) "默认" else null,
                selected = speedEquals(current, v),
                onClick = { onPick(v) },
            )
        }
    }
}

/**
 * 音轨 3 条（**照画，不切**）。
 *
 * ⚠️ `MediaPlayer` 无法枚举 / 切换音轨（`MediaPlayer.TrackInfo` 对绝大多数容器返回空，
 * 也没有 `selectTrack`），所以这三项点击只给 toast。控件按规格保留，不删除。
 */
@Composable
private fun AudioTrackPanel(
    current: Int,
    onPick: (Int) -> Unit,
    onDelay: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AUDIO_TRACKS.forEachIndexed { i, label ->
            PanelOptionRow(
                label = label,
                value = TXT_NO_SWITCH,
                selected = i == current,
                dimmed = true,
                onClick = { onPick(i) },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.10f)),
        )
        DelayStepRow(label = "音画$TXT_DELAY", valueMs = 0, onAdjust = { onDelay(it) })
    }
}

/**
 * 字幕 4 项（**照画，不渲染**）+ 字幕延迟 ±50ms 步进（置灰）。
 *
 * ⚠️ MediaPlayer 不支持字幕轨渲染（`addTimedTextSource` 仅限极少格式且需自行绘制回调），
 * 本项目不做字幕渲染，点击给 toast；延迟微调按钮照画并置灰。
 */
@Composable
private fun SubtitlePanel(
    current: Int,
    onPick: (Int) -> Unit,
    onDelay: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SUBTITLE_TRACKS.forEachIndexed { i, label ->
            PanelOptionRow(
                label = label,
                value = if (i == 0) "默认" else null,
                selected = i == current,
                dimmed = true,
                onClick = { onPick(i) },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.10f)),
        )
        DelayStepRow(label = "字幕$TXT_DELAY", valueMs = 0, onAdjust = { onDelay(it) })
    }
}

/** 画面比例 5 种（真实现）+ 屏幕方向 3 档（真实现） */
@Composable
private fun AspectPanel(
    current: AspectMode,
    orientation: PlayerOrientationMode,
    onPick: (AspectMode) -> Unit,
    onOrientation: (PlayerOrientationMode) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        for (mode in AspectMode.entries) {
            PanelOptionRow(
                label = mode.label,
                selected = mode == current,
                onClick = { onPick(mode) },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.10f)),
        )
        Text(
            text = "屏幕方向",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.52f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        for (mode in PlayerOrientationMode.entries) {
            PanelOptionRow(
                label = mode.label,
                selected = mode == orientation,
                onClick = { onOrientation(mode) },
            )
        }
    }
}

/** 跳转到时间（真实现：`MediaPlayer.seekTo`）：分 / 秒步进 + 跳转 + 归零 */
@Composable
private fun SeekPanel(
    targetMs: Int,
    durationMs: Int,
    onAdjust: (Int) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepChip(text = "−1 分", onClick = { onAdjust(-60_000) })
            StepChip(text = "−10 秒", onClick = { onAdjust(-10_000) })
            StepChip(text = "+10 秒", onClick = { onAdjust(10_000) })
            StepChip(text = "+1 分", onClick = { onAdjust(60_000) })
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = TXT_TARGET,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${formatDuration(targetMs.toLong())} / ${formatDuration(durationMs.toLong())}",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                modifier = Modifier.weight(1f).clickable { onApply() },
                shape = ShapeMd,
                color = Color.White.copy(alpha = 0.16f),
                contentColor = Color.White,
            ) {
                Text(
                    text = TXT_APPLY,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            Surface(
                modifier = Modifier.weight(1f).clickable { onReset() },
                shape = ShapeMd,
                color = Color.White.copy(alpha = 0.08f),
                contentColor = Color.White,
            ) {
                Text(
                    text = TXT_RESET,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
    }
}

/** 睡眠定时 5 档（真实现：到点暂停 + toast） */
@Composable
private fun SleepPanel(current: Int, onPick: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        for (m in SLEEP_MINUTES) {
            PanelOptionRow(
                label = if (m == 0) "关闭" else "$m 分钟后暂停",
                selected = m == current,
                onClick = { onPick(m) },
            )
        }
    }
}

/**
 * 均衡器 7 档（**照画，不接**）。
 *
 * ⚠️ `android.media.audiofx.Equalizer` 需要 attach 到 MediaPlayer 的 audioSessionId，
 * 且大量设备/ROM 直接返回不支持（`getNumberOfBands()` 抛异常）。为了不把播放器搞崩，
 * 一期不接，点击给 toast。
 */
@Composable
private fun EqualizerPanel(current: Int, onPick: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        EQUALIZER_PRESETS.forEachIndexed { i, label ->
            PanelOptionRow(
                label = label,
                value = TXT_NO_SWITCH,
                selected = i == current,
                dimmed = true,
                onClick = { onPick(i) },
            )
        }
    }
}

/**
 * 媒体信息。
 *
 * ⚠️ 取值全部来自 `MediaMetadataRetriever` / `MediaPlayer`；取不到的一律「未知」，
 * **不编造**。
 *
 * ⚠️ 规格 §4.6.3 列的「视频编码 / 音频编码」两行**合并成一行「容器格式」**：
 * `MediaMetadataRetriever` 只有 `METADATA_KEY_MIMETYPE`（容器 MIME，如 `video/mp4`），
 * SDK 里压根没有 `METADATA_KEY_VIDEO_CODEC / AUDIO_CODEC`，
 * 与其摆两行永远是「未知」的「编码」，不如诚实地只显示拿得到的容器格式。
 *
 * 实际显示 12 行：文件名 / 路径 / 时长 / 分辨率 / 容器格式 / 码率 / 帧率 / 文件大小 /
 * 当前音轨 / 当前字幕 / 播放速度 / 画面比例（最后一行是我们补的，有真值）。
 *
 * ⚠️ 原先还有一行「本地缓存」（显示 `adb pull` 后的本地绝对路径）——已删除：
 * 那是实现细节，用户既看不懂也不需要，暴露出来只会把「为什么要 pull」这个内部决策摆到台面上。
 */
@Composable
private fun MediaInfoPanel(
    entry: FileEntry,
    meta: VideoMeta?,
    controller: PlayerController,
    speed: Float,
    aspect: AspectMode,
    audioTrack: String,
    subtitle: String,
) {
    val m: VideoMeta = meta ?: VideoMeta()
    val durationMs: Long = if (controller.durationMs > 0) {
        controller.durationMs.toLong()
    } else {
        m.durationMs
    }
    val width: Int = if (controller.videoWidth > 0) controller.videoWidth else m.width
    val height: Int = if (controller.videoHeight > 0) controller.videoHeight else m.height
    val resolution: String = if (width > 0 && height > 0) "${width}×${height}" else TXT_UNKNOWN
    val bitrate: String = if (m.bitrateBps > 0L) "${m.bitrateBps / 1000L} kbps" else TXT_UNKNOWN
    val fps: String = if (m.frameRate != null && m.frameRate > 0f) {
        String.format(Locale.US, "%.2f fps", m.frameRate)
    } else {
        TXT_UNKNOWN
    }
    val rows: List<Pair<String, String>> = listOf(
        "文件名" to entry.name,
        "路径" to entry.path,
        "时长" to if (durationMs > 0L) formatDuration(durationMs) else TXT_UNKNOWN,
        "分辨率" to resolution,
        "容器格式" to (m.containerMime?.takeIf { it.isNotBlank() } ?: TXT_UNKNOWN),
        "码率" to bitrate,
        "帧率" to fps,
        "文件大小" to "${FileBrowserViewModel.formatSize(entry.sizeBytes)}（远端）",
        "当前音轨" to "$audioTrack$TXT_NO_SWITCH",
        "当前字幕" to "$subtitle$TXT_NO_SWITCH",
        "播放速度" to formatSpeed(speed),
        "画面比例" to aspect.label,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        for ((label, value) in rows) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.52f),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 工具

/** 递归向上找 Activity（用于改屏幕方向） */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 关闭远端流（幂等，失败只记日志）。**每次打开都必须有配对的 close**，否则预取协程会泄漏 */
private fun closeQuietly(stream: RemoteFileStream?) {
    if (stream == null) return
    try {
        stream.close()
    } catch (t: Throwable) {
        Logx.w(TAG, "关闭远端流失败：${t.message}")
    }
}

/**
 * 播放位置变更后通知远端流预取（不阻塞，失败只记日志）。
 *
 * `MediaPlayer` 的 `seekTo` 只是改了读指针，下一段数据仍在被控端 ——
 * 提前把目标位置附近的块拉进缓存，能避免 seek 后卡一次 adb 往返。
 *
 * 字节偏移按「时间占比 × 文件总长」估算：视频的字节率大致均匀，
 * 这个精度对 2 MB 分块足够（块命中即可，差几个块不影响体验）。
 *
 * @param source    当前播放源；null（未就绪）时直接返回
 * @param targetMs  目标播放位置（毫秒）
 * @param durationMs 视频总时长（毫秒）；≤ 0 时无法换算，退化为从 0 预取
 */
private fun prefetchAtTime(source: VideoSource?, targetMs: Int, durationMs: Int) {
    val stream: RemoteFileStream = source?.stream ?: return
    val total: Long = stream.sizeBytes
    if (total <= 0L) return
    val offset: Long = if (durationMs > 0) {
        (total.toDouble() * (targetMs.toDouble() / durationMs.toDouble()))
            .toLong()
            .coerceIn(0L, total - 1L)
    } else {
        0L
    }
    try {
        stream.prefetch(offset)
    } catch (t: Throwable) {
        Logx.w(TAG, "预取失败 @$offset：${t.message}")
    }
}

/**
 * 读取媒体信息（阻塞，调用方应在 IO 线程调用）。
 *
 * ⚠️ **必须另开一条独立流**，绝不能复用播放器正在用的那条：
 *    API 29+ 的 `MediaMetadataRetriever.release()` 会顺手 close 传进去的
 *    `MediaDataSource`，复用会把播放器的数据源一起关掉。
 *
 * 取不到就返回默认值，由 UI 显示「未知」。
 *
 * @param entry      远端文件条目
 * @param openStream 打开远端流的挂起函数
 */
private suspend fun readVideoMeta(
    entry: FileEntry,
    openStream: suspend (FileEntry) -> Result<RemoteFileStream>,
): VideoMeta {
    val opened: Result<RemoteFileStream> = try {
        openStream(entry)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        Result.failure(t)
    }
    val stream: RemoteFileStream = opened.getOrNull() ?: run {
        Logx.w(TAG, "读取媒体信息失败：无法打开远端流（${opened.exceptionOrNull()?.message}）")
        return VideoMeta()
    }
    return try {
        readVideoMetaFrom(RemoteFileMediaDataSource(stream))
    } finally {
        closeQuietly(stream)
    }
}

/** 从任意 [MediaDataSource] 抽取元数据；取不到的一律「空 / 0」，**不编造** */
private fun readVideoMetaFrom(source: MediaDataSource): VideoMeta {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(source)
        val duration: Long = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val width: Int = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val height: Int = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        val bitrate: Long = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            ?.toLongOrNull() ?: 0L
        val fps: Float? = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toFloatOrNull()
        // 容器 MIME（如 video/mp4）。注意：这不是编解码器 ——
        // MediaMetadataRetriever 没有暴露编解码器的键（SDK 里没有 VIDEO_CODEC / AUDIO_CODEC），
        // 想要真编解码器得走 MediaExtractor + MediaFormat.KEY_MIME，本次不做。
        val mime: String? = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        VideoMeta(
            durationMs = duration,
            width = width,
            height = height,
            bitrateBps = bitrate,
            frameRate = fps,
            containerMime = mime,
        )
    } catch (t: Throwable) {
        Logx.w(TAG, "读取媒体信息失败：${t.message}")
        VideoMeta()
    } finally {
        try {
            retriever.release()
        } catch (t: Throwable) {
            /* 释放失败无需处理 */
        }
    }
}

/** 分辨率文案：优先播放器的实时值，其次 MediaMetadataRetriever */
private fun resolutionText(controller: PlayerController, meta: VideoMeta?): String {
    val w: Int = if (controller.videoWidth > 0) controller.videoWidth else meta?.width ?: 0
    val h: Int = if (controller.videoHeight > 0) controller.videoHeight else meta?.height ?: 0
    return if (w > 0 && h > 0) "${w}×${h}" else TXT_UNKNOWN
}

/** 容器格式文案（`METADATA_KEY_MIMETYPE`，如 `video/mp4`）；取不到时为「未知」（不编造） */
private fun mimeText(meta: VideoMeta?): String =
    meta?.containerMime?.takeIf { it.isNotBlank() } ?: TXT_UNKNOWN

/** `mm:ss` / `h:mm:ss` */
private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec: Long = ms / 1000L
    val h: Long = totalSec / 3600L
    val m: Long = (totalSec % 3600L) / 60L
    val s: Long = totalSec % 60L
    return if (h > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

/** 倍速文案：`1×` / `1.25×` */
private fun formatSpeed(v: Float): String {
    val rounded: Float = (v * 100f).toInt() / 100f
    return if (rounded % 1f == 0f) "${rounded.toInt()}×" else "${rounded}×"
}

/** 浮点倍速比较（避免 1.25f 直接 == 的精度问题） */
private fun speedEquals(a: Float, b: Float): Boolean = abs(a - b) < 0.001f

/**
 * 计算画面盒子的尺寸：在容器内按目标比例取最大内接矩形。
 *
 * - 原始 / 16:9 / 4:3：目标比例 = 各自比例，缩放模式 = SCALE_TO_FIT（解码器补黑边）；
 * - 拉伸填满：目标比例 = 视频原始比例（解码器 1:1 无黑边），再由 UI 非等比拉伸到容器；
 * - 裁剪填满：目标比例 = 容器比例（即铺满），缩放模式 = CROPPING（解码器自己裁）。
 */
private fun stageBoxSize(
    containerW: Dp,
    containerH: Dp,
    aspect: AspectMode,
    videoAspect: Float,
): Pair<Dp, Dp> {
    if (containerW <= 0.dp || containerH <= 0.dp) return containerW to containerH
    val va: Float = if (videoAspect > 0f) videoAspect else 16f / 9f
    val target: Float = when (aspect) {
        AspectMode.ORIGINAL -> va
        AspectMode.R16_9 -> 16f / 9f
        AspectMode.R4_3 -> 4f / 3f
        AspectMode.STRETCH -> va
        AspectMode.CROP -> (containerW / containerH).coerceIn(0.2f, 5f)
    }.coerceIn(0.2f, 5f)

    var w: Dp = containerW
    var h: Dp = w / target
    if (h > containerH) {
        h = containerH
        w = h * target
    }
    return w to h
}

