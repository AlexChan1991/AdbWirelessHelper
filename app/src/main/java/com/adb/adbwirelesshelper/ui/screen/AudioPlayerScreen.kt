package com.adb.adbwirelesshelper.ui.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adb.adbwirelesshelper.data.media.RemoteFileMediaDataSource
import com.adb.adbwirelesshelper.data.media.RemoteFileStream
import com.adb.adbwirelesshelper.domain.model.FileEntry
import com.adb.adbwirelesshelper.ui.components.WavyProgressIndicator
import com.adb.adbwirelesshelper.ui.theme.AppIcon
import com.adb.adbwirelesshelper.ui.theme.Motion
import com.adb.adbwirelesshelper.ui.theme.Shape3xl
import com.adb.adbwirelesshelper.ui.theme.ShapeMd
import com.adb.adbwirelesshelper.util.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

// ---------------------------------------------------------------- 文案常量

private const val TXT_CLOSE = "关闭"
private const val TXT_PLAY = "播放"
private const val TXT_PAUSE = "暂停"
private const val TXT_PREV = "上一首"
private const val TXT_NEXT = "下一首"
private const val TXT_PLAYLIST = "播放列表"
private const val TXT_SPEED = "播放速度"
private const val TXT_UNKNOWN = "未知"
private const val TXT_PLAYING_NOW = "正在播放"
private const val TXT_COVER = "专辑封面"
private const val TXT_SPEED_DEFAULT = "默认"
private const val TXT_NO_EXT = "无扩展名"

/** 读取（adb）阶段失败：这是「拿不到字节」，与「解不开」是两回事，文案必须分开 */
private const val TXT_READ_FAILED = "无法读取该音频"

/**
 * 流式播放**中途**断流（> 8 MB 那条路径）。
 *
 * 刻意与开局就读不到（[TXT_READ_FAILED]）分开写：后者是「这首歌打不开」，
 * 前者是「已经播了一半，然后远端读不动了」—— 用户据此判断要不要重开连接。
 */
private const val TXT_AUDIO_STREAM_BROKEN = "播放中断：远端读取已断开"

/**
 * 解码失败文案（铁律）。
 *
 * 不能只写「播放失败」——那会把「缺少系统解码器 / 容器嗅探不出来」和
 * 「adb 断流」混为一谈，用户根本没法判断下一步该怎么办。
 */
private const val TXT_DECODE_FAILED = "无法解码该音频（可能缺少系统解码器）"

private const val TXT_BTN_NEXT = "下一个"
private const val TXT_BTN_BACK_TO_FILES = "返回文件列表"
private const val TXT_LOADING = "正在从被控端读取音频…"

/** 倍速不被支持时的提示：`该音频不支持 1.5 倍速` */
private const val TXT_SPEED_UNSUPPORTED_PREFIX = "该音频不支持 "
private const val TXT_SPEED_UNSUPPORTED_SUFFIX = " 倍速"

/** 面板标题单独存一份，退场动画期间标题还得显示 */
private const val TXT_PANEL_PLAYLIST = TXT_PLAYLIST
private const val TXT_PANEL_SPEED = TXT_SPEED

// ---------------------------------------------------------------- 参数常量

private const val TAG = "AudioPlayerOverlay"

/**
 * 「整读进内存」的体积上限：8 MB。
 *
 * ★ 为什么要有这条快捷路径：音频用户**拖进度条非常频繁**，而 `MediaDataSource` 的每次
 *   `readAt` 落在流式实现上就是一次 adb 往返。8 MB 以内的音频（绝大多数 MP3 / M4A / OGG，
 *   以及相当一部分 FLAC）一次性读全量塞进内存后，拖动进度条是**纯内存随机读**，
 *   零 adb 抖动 —— 这个体验差别是真实存在的，不是心理作用。
 *
 * 8 MB 是按「最坏情况常驻内存」定的：整读的 ByteArray 与解码器缓冲同时驻留，
 * 在低端机（单进程堆 128–256MB）上仍留有余量。
 */
private const val WHOLE_READ_LIMIT_BYTES: Long = 8L * 1024L * 1024L

/** 整读时的单次 `readAt` 块大小（256 KB） */
private const val READ_CHUNK_BYTES: Int = 256 * 1024

/** 进度轮询周期（毫秒）；用 `LaunchedEffect + delay`，绝不用 runBlocking */
private const val POLL_INTERVAL_MS: Long = 200L

/** 覆盖层进出场时长（毫秒） */
private const val OVERLAY_ANIM_MS: Int = Motion.DURATION_CONTAINER

/** 倍速档位（1× 为默认） */
private val AUDIO_SPEED_STEPS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** 倍速合法区间（传给 MediaPlayer 前夹取，防 IllegalArgumentException） */
private const val AUDIO_SPEED_MIN: Float = 0.5f
private const val AUDIO_SPEED_MAX: Float = 2f

/**
 * duck（可降音量型焦点丢失，如导航播报）时的音量：原音量的 30%。
 *
 * 语义上 duck 就该降音量而不是暂停 —— 暂停会让「导航一句话说完」变成一次打断。
 */
private const val AUDIO_DUCK_VOLUME: Float = 0.3f

/** 覆盖层强调色：黑底上够亮 */
private val AudioAccent = Color(0xFF8AB4F8)

/** 覆盖层卡片底色 */
private val AudioSheetColor = Color(0xFF16181D)

/** 封面边长上限 */
private val AUDIO_COVER_MAX: Dp = 240.dp

// ---------------------------------------------------------------- 枚举 / 数据

/** 右侧面板两种：播放列表 / 倍速 */
private enum class AudioPanelKind(val title: String) {
    PLAYLIST(TXT_PANEL_PLAYLIST),
    SPEED(TXT_PANEL_SPEED),
}

/**
 * 失败种类。
 *
 * 必须区分：**READ** = adb 拉不到字节（断流 / 权限 / 文件被删），
 * **DECODE** = 字节拿到了但 stagefright 解不开（缺解码器 / 容器嗅探失败）。
 * 两者的用户动作完全不同：前者该检查连接，后者该换格式或换播放器。
 */
private enum class AudioErrorKind {
    /** 读取阶段失败 */
    READ,

    /** 解码阶段失败 */
    DECODE,
}

/**
 * 从 `MediaMetadataRetriever` 取到的音频元数据。
 *
 * ⚠️ 取不到的字段一律为 null / 0，UI 层显示「未知」，**不编造**：
 * - 标题可以退回去掉扩展名的文件名（那是真实信息）；
 * - 艺术家 / 专辑取不到就是「未知」；
 * - 封面取不到就画音符图标，**绝不画假的彩色渐变封面**。
 */
private data class AudioMeta(
    val durationMs: Long = 0L,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val cover: Bitmap? = null,
)

/**
 * 一首曲目已就绪的播放素材。
 *
 * @param playerSource 交给 `MediaPlayer.setDataSource(MediaDataSource)` 的数据源
 * @param stream       流式路径下**正在被 [playerSource] 使用**的远端流，释放时要 close；
 *                      整读路径下为 null（字节已全在内存里，流当时就关掉了）
 * @param inMemory     整读路径下的完整字节；供元数据复用（**不额外发 adb**）。流式路径下为 null
 */
private class AudioSource(
    val playerSource: MediaDataSource,
    val stream: RemoteFileStream?,
    val inMemory: ByteArray?,
)

/**
 * 一首歌「读字节」这一步的产出。
 *
 * ★ 存在的理由：整读路径要做**阻塞式** adb 读（见 [readWhole]），必须在 IO 线程上跑完再把
 * 结果带回主线程落 Compose state。而 `withContext` 不是 inline，lambda 里没法
 * `return@LaunchedEffect`，所以三个分支得先封装成值再让调用方分派。
 */
private sealed class AudioLoadOutcome {

    /** 读取失败（adb 断流 / 权限 / 文件已不在）：必须按 READ 报，绝不能说成「缺解码器」 */
    class ReadFailed(val reason: String) : AudioLoadOutcome()

    /** 整读成功：完整字节已在内存，流已关闭 */
    class Whole(val bytes: ByteArray) : AudioLoadOutcome()

    /** 走流式：流仍归调用方持有，切歌 / 关闭覆盖层时 close */
    class Streamed(val stream: RemoteFileStream) : AudioLoadOutcome()
}

// ---------------------------------------------------------------- 自绘图标

/**
 * 暂停（两根竖线）。
 *
 * [AppIcon] 里只有 `Play` 与 `Stop`（实心方块 = 停止语义），没有「暂停」。
 * 这里仿照 `ui/theme/AppIcons.kt` 自绘：24dp 视口、stroke = 黑色（由 `Icon` 的 tint 覆盖）、
 * 线宽 2f、圆头端点 —— 与图标库其余线性图标视觉重量一致。
 */
private val AudioIconPause: ImageVector = ImageVector.Builder(
    name = "AudioPause",
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
    moveTo(9f, 5f)
    lineTo(9f, 19f)
    moveTo(15f, 5f)
    lineTo(15f, 19f)
}.build()

/** 上一首（左竖线 + 左三角） */
private val AudioIconPrev: ImageVector = ImageVector.Builder(
    name = "AudioPrev",
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
    moveTo(6f, 5f)
    lineTo(6f, 19f)
    moveTo(18f, 5f)
    lineTo(10f, 12f)
    lineTo(18f, 19f)
    close()
}.build()

/** 下一首（右竖线 + 右三角） */
private val AudioIconNext: ImageVector = ImageVector.Builder(
    name = "AudioNext",
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
    moveTo(18f, 5f)
    lineTo(18f, 19f)
    moveTo(6f, 5f)
    lineTo(14f, 12f)
    lineTo(6f, 19f)
    close()
}.build()

/**
 * 音符（八分音符：实心符头 + 符干 + 符尾）。
 *
 * 只在**取不到内嵌封面**时用作占位图形。刻意用中性单色 ——
 * 绝不画「看起来像专辑封面」的彩色渐变，那是在对没有的信息撒谎。
 */
private val AudioIconMusicNote: ImageVector = ImageVector.Builder(
    name = "AudioMusicNote",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
)
    // 符头：实心椭圆（填充路径，与描边路径分开画）
    .path(
        fill = SolidColor(Color.Black),
        stroke = null,
        strokeLineWidth = 0f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        oval(9f, 17.6f, 3.2f)
    }
    // 符干 + 符尾
    .path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(12.1f, 17.6f)
        lineTo(12.1f, 5.5f)
        moveTo(12.1f, 5.5f)
        lineTo(18.4f, 8.2f)
        lineTo(17.3f, 13f)
    }
    .build()

/** PathBuilder 扩展：画一个完整椭圆（4 段椭圆弧 + close）。写法同 AppIcons.kt。 */
private fun PathBuilder.oval(cx: Float, cy: Float, r: Float) {
    moveTo(cx, cy - r)
    arcTo(r, r, 0f, false, true, cx + r, cy)
    arcTo(r, r, 0f, false, true, cx, cy + r)
    arcTo(r, r, 0f, false, true, cx - r, cy)
    arcTo(r, r, 0f, false, true, cx, cy - r)
    close()
}

// ---------------------------------------------------------------- 内存数据源

/**
 *  ByteArray 版 [MediaDataSource]，供「整读进内存」快捷路径使用。
 *
 *  ★ 存在的意义：整读路径下 `MediaPlayer` 的每次 `readAt`（尤其是**拖动进度条**引发的
 *    随机读）都只是 `System.arraycopy`，不触碰 adb。
 *
 *  ⚠️ `readAt` 的契约：到达 EOF（`position >= size`）返回 **-1**，不是 0。
 */
private class AudioMemoryDataSource(private val data: ByteArray) : MediaDataSource() {

    override fun close() {
        // 字节归调用方所有，这里不释放任何东西
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        if (position < 0L) return -1
        // 整读路径下文件 ≤ 8 MB，Long → Int 一定安全
        if (position >= data.size) return -1
        // ★ 入参校验与 `RemoteFileSource.readAt` 对齐：写出界一律返回 -1（EOF 语义），
        //   绝不能让 `System.arraycopy` 抛 IndexOutOfBoundsException ——
        //   这条回调跑在 MediaPlayer 自己的读线程上，抛出去没人接，等于把播放器直接打死。
        //   不能只靠「MediaPlayer 会守约」：ROM 实现 / retriever 都会喂不同的 offset。
        if (offset < 0 || offset >= buffer.size) return -1
        val start: Int = position.toInt()
        val available: Int = data.size - start
        // 同样与 `RemoteFileSource.readAt` 一致：三个上限取最小（要的、源剩下的、buffer 装得下的）
        val count: Int = minOf(size, available, buffer.size - offset)
        System.arraycopy(data, start, buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()
}

// ---------------------------------------------------------------- 播放器控制

/**
 * `MediaPlayer` 生命周期管理器（音频版，照 `VideoPlayerScreen.PlayerController` 的写法）。
 *
 * 与视频版的唯一实质差别：数据源是 [MediaDataSource]（远端流 / 内存字节），
 * 没有 Surface、没有画面尺寸回调。
 *
 * ## 音频焦点（AudioFocus）
 * 不申请焦点的话，来电话 / 别的 App 放音乐时会**叠音**，这是真实缺陷，所以这里接了
 * `AudioManager.requestAudioFocus`。三条纪律：
 * 1. **只在真正起播时申请**（[start]），打开覆盖层就申请会把别的 App 的音乐直接掐掉，
 *    反而更糟；[pause] / [release] 时 abandon；
 * 2. 三种丢失类型分开处理：永久丢失 = 暂停 + abandon；瞬时丢失 = 暂停但保留焦点；
 *    可 duck = **降音量不停播**（duck 的语义就是降音量，暂停属于过度反应）；
 * 3. 申请失败**不阻止播放** —— 只是没有焦点协调，照样能出声，记一条日志即可。
 *
 * 状态字段用 Compose 的 `mutableStateOf` 表达，UI 直接读，不需要额外的 Flow 桥接。
 *
 * @param context 用 **applicationContext**，避免持有 Activity 引用
 */
private class AudioPlayerController(private val context: Context) {

    private var player: MediaPlayer? = null

    /** 用户意图：prepare 完成后要不要自动起播 */
    private var wantPlay: Boolean = true

    /** prepare 完成前设置的倍速，prepare 后补套 */
    private var pendingSpeed: Float = 1f

    // ---- 音频焦点 ----
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** 焦点请求（可复用于 abandon 后再 request） */
    private var focusRequest: AudioFocusRequest? = null

    /** 当前是否持有焦点 */
    private var focusHeld: Boolean = false

    /** 是否正处于 duck（降音量）状态 */
    private var ducking: Boolean = false

    /** 已 prepare，可以 seek / 改倍速 */
    var prepared: Boolean by mutableStateOf(false)
        private set

    /** 正在播放 */
    var playing: Boolean by mutableStateOf(false)
        private set

    /** 总时长（毫秒）；未 prepare 时为 0 */
    var durationMs: Int by mutableIntStateOf(0)
        private set

    /** 当前生效倍速 */
    var speed: Float by mutableFloatStateOf(1f)
        private set

    /** 错误回调（UI 收到后切到错误态） */
    var onError: (() -> Unit)? = null

    /** 播完回调（UI 决定「下一个」） */
    var onCompletion: (() -> Unit)? = null

    /** prepare 后才发现的「倍速不被支持」回调（此时已经静默回退 1×，这里只负责提示） */
    var onSpeedRejected: ((Float) -> Unit)? = null

    /**
     * 打开数据源。
     *
     * ⚠️ 监听器必须在 `prepareAsync()` **之前**全部挂好，否则 prepare 与监听之间会出现竞态
     * （先 prepare 后挂监听会漏掉 onPrepared）。
     */
    fun open(source: MediaDataSource) {
        release()
        prepared = false
        playing = false
        durationMs = 0
        wantPlay = true

        val mp = MediaPlayer()
        try {
            mp.setDataSource(source)
        } catch (t: Throwable) {
            Logx.w(TAG, "setDataSource(MediaDataSource) 失败：${t.message}")
            mp.release()
            onError?.invoke()
            return
        }

        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        } catch (t: Throwable) {
            Logx.w(TAG, "setAudioAttributes 失败：${t.message}")
        }

        mp.setOnPreparedListener { p ->
            prepared = true
            // ⚠️ `getDuration()` 在状态失效时会抛 IllegalStateException（「异步回调 vs 播放器
            //    状态」的竞态是真存在的）。这是系统回调里抛出的异常，外面没人接得住，
            //    必须就地兜住 —— 时长取不到就按 0（UI 显示「未知」），不能把整个 prepare 打断。
            durationMs = try {
                p.duration.coerceAtLeast(0)
            } catch (t: Throwable) {
                Logx.w(TAG, "读取时长失败：${t.message}")
                0
            }
            if (pendingSpeed != 1f && !applySpeed(pendingSpeed)) {
                // 该音频 / 该 ROM 吃不下这个倍速：回退 1×，不要带着一个假倍速继续跑
                val rejected: Float = pendingSpeed
                pendingSpeed = 1f
                speed = 1f
                onSpeedRejected?.invoke(rejected)
            }
            if (wantPlay) start()
            Logx.d(TAG, "已 prepare：${durationMs}ms")
        }

        mp.setOnErrorListener { _, what, extra ->
            Logx.w(TAG, "MediaPlayer 错误：what=$what, extra=$extra")
            prepared = false
            playing = false
            onError?.invoke()
            true // 已处理，避免系统再弹一次
        }

        mp.setOnCompletionListener {
            playing = false
            onCompletion?.invoke()
        }

        player = mp
        try {
            mp.prepareAsync()
        } catch (t: Throwable) {
            // 同步抛出 = 连异步 prepare 都没起来（容器嗅探失败 / 数据源不可用）
            Logx.w(TAG, "prepareAsync 失败：${t.message}")
            onError?.invoke()
            release()
        }
    }

    /** 释放播放器（幂等，可重复调用） */
    fun release() {
        prepared = false
        playing = false
        // ⚠️ 焦点与音量的收尾必须放在 `mp == null` 提前返回**之前**：
        //    否则「播放器已被 release 过一次」时再调，焦点会永远留在手里。
        restoreVolume()
        abandonFocus()
        val mp: MediaPlayer? = player
        player = null
        if (mp == null) return
        try {
            mp.setOnPreparedListener(null)
            mp.setOnErrorListener(null)
            mp.setOnCompletionListener(null)
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

    fun start() {
        wantPlay = true
        val mp: MediaPlayer? = player
        if (mp == null || !prepared) return
        // 焦点在**真正起播**时才申请：打开覆盖层就申请会把别的 App 的音乐掐掉
        requestFocus()
        try {
            mp.start()
            playing = true
            // 从 duck 恢复（例如用户手动点了播放）：音量也一起还原
            restoreVolume()
        } catch (t: Throwable) {
            Logx.w(TAG, "start 失败：${t.message}")
            playing = false
        }
    }

    /**
     * 用户主动暂停：停播 **+ 让出焦点**（别人可以立刻出声）。
     *
     * ⚠️ 与 [stopPlayback] 的区别就是这一步 abandon：焦点丢失导致的暂停
     * （尤其是 `LOSS_TRANSIENT`）**不能**走这里，否则焦点被放弃后系统不会在
     * 瞬态结束时再通知我们，等于把「保留焦点」这条要求做没了。
     */
    fun pause() {
        stopPlayback()
        abandonFocus()
    }

    /** 只停止播放，**不动焦点**（幂等）。焦点回调里的暂停一律走这里。 */
    private fun stopPlayback() {
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
     * 跳转（毫秒）。prepare 前调用会被忽略。
     *
     * ⚠️ `MediaPlayer` **没有**单参 `seekTo(Long)`：只有 `seekTo(Int)` 与
     * API 26+ 的 `seekTo(Long, Int)`。这里用后者 + `SEEK_CLOSEST`（minSdk 26 刚好够）。
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
     * ⚠️ 部分 ROM / 编码只支持 0.5–2×，超出会在 `setPlaybackParams` 时抛
     * `IllegalStateException` / `IllegalArgumentException`。这里一律 try/catch，
     * **绝不把异常抛给 UI**。
     *
     * @return true = 生效；false = 该音频不支持，调用方应回退 1× 并提示
     */
    fun applySpeed(value: Float): Boolean {
        val v: Float = value.coerceIn(AUDIO_SPEED_MIN, AUDIO_SPEED_MAX)
        val mp: MediaPlayer? = player
        if (mp == null || !prepared) {
            // 还没 prepare 好：先记下来，prepare 完成后补套（此时视为「成功」，
            // 真正的不支持会在补套失败时通过 [onSpeedRejected] 上报）
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

    // ---------------------------------------------------------------- 音频焦点

    /**
     * 构造（或复用）焦点请求。
     *
     * 用 [AudioAttributes]（CONTENT_TYPE_MUSIC / USAGE_MEDIA）构造，**不用**过时的
     * `AudioManager.STREAM_MUSIC` 那套 stream type 常量。
     *
     * 监听器显式绑到主线程 Handler：焦点回调里会改 Compose 状态并调 `MediaPlayer`，
     * 固定在主线程可以避免「哪个线程会回调」在不同 ROM 上不一致。
     */
    private fun focusRequestOrCreate(): AudioFocusRequest? {
        focusRequest?.let { return it }
        if (audioManager == null) return null
        val attrs: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(
                { change -> onFocusChange(change) },
                Handler(Looper.getMainLooper()),
            )
            // 不接受到期的延迟焦点：我们宁可「没焦点也照播」，也不要进入
            // 「已请求但未获得」这个对音乐播放器没有意义的中间态
            .setAcceptsDelayedFocusGain(false)
            .build()
        focusRequest = request
        return request
    }

    /**
     * 申请焦点。
     *
     * ⚠️ 失败（[AudioManager.AUDIOFOCUS_REQUEST_FAILED]）**不阻止播放** ——
     * 拿不到焦点只是意味着不参与系统协调，声音照样出得来。这里只记一条日志。
     */
    private fun requestFocus() {
        if (focusHeld) return
        val manager: AudioManager = audioManager ?: return
        val request: AudioFocusRequest = focusRequestOrCreate() ?: return
        val result: Int = try {
            manager.requestAudioFocus(request)
        } catch (t: Throwable) {
            Logx.w(TAG, "requestAudioFocus 抛出：${t.message}")
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusHeld = true
            Logx.d(TAG, "音频焦点已获得")
        } else {
            focusHeld = false
            Logx.w(TAG, "音频焦点申请失败（code=$result），继续播放但不参与焦点协调")
        }
    }

    /** 让出焦点并还原音量（幂等）。 */
    private fun abandonFocus() {
        restoreVolume()
        if (!focusHeld) return
        val manager: AudioManager = audioManager ?: return
        val request: AudioFocusRequest = focusRequest ?: return
        try {
            manager.abandonAudioFocusRequest(request)
        } catch (t: Throwable) {
            Logx.w(TAG, "abandonAudioFocusRequest 失败：${t.message}")
        }
        focusHeld = false
        Logx.d(TAG, "音频焦点已释放")
    }

    /**
     * 焦点变化的三种丢失类型**必须分开处理**：
     * - [AudioManager.AUDIOFOCUS_LOSS]（永久丢失，如别的 App 开始放歌）→ 暂停 + 让出焦点；
     * - [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT]（瞬时，如来电）→ 暂停但**保留焦点**，
     *   通话结束后**不自动恢复播放** —— 自动恢复会吓用户一跳，让他自己点播放；
     * - [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK]（可降音量，如导航播报）→
     *   **降到 30% 音量，不停播**（duck 的语义就是降音量，暂停属于过度反应）。
     */
    private fun onFocusChange(change: Int) {
        Logx.d(TAG, "音频焦点变化：code=$change")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 从 duck 回到正常音量；**不自动恢复播放**（瞬时丢失时用户没要求继续）
                restoreVolume()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久丢失：停播并彻底让出焦点
                stopPlayback()
                abandonFocus()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 只停播，**焦点留着**：abandon 之后系统不会再在瞬态结束时通知我们
                stopPlayback()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duck()

            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> stopPlayback()

            else -> Unit
        }
    }

    /** 降到 30% 音量（duck）。失败只记日志，绝不因降音量把播放打断。 */
    private fun duck() {
        if (ducking) return
        val mp: MediaPlayer? = player
        if (mp == null || !prepared) return
        try {
            mp.setVolume(AUDIO_DUCK_VOLUME, AUDIO_DUCK_VOLUME)
            ducking = true
        } catch (t: Throwable) {
            Logx.w(TAG, "duck 失败：${t.message}")
        }
    }

    /** 还原音量（幂等）。 */
    private fun restoreVolume() {
        if (!ducking) return
        val mp: MediaPlayer? = player
        ducking = false
        if (mp == null) return
        try {
            mp.setVolume(1f, 1f)
        } catch (t: Throwable) {
            Logx.w(TAG, "还原音量失败：${t.message}")
        }
    }
}

// ---------------------------------------------------------------- 覆盖层

/**
 * ★ 全屏音乐播放**覆盖层**（文件管理页增量需求）。
 *
 * 这是**覆盖层不是路由**：不进返回栈，关闭即回到文件管理页（由调用方决定如何移除）。
 *
 * ## 播放管线（不写任何临时文件、不落盘）
 * 音频物理上在**被控端设备**，本播放器通过 [openStream] 拿到 [RemoteFileStream]，
 * 包成 `MediaDataSource` 后直接 `MediaPlayer.setDataSource(MediaDataSource)`（API 23+，
 * minSdk 26 够）。两条路径：
 * 1. **≤ 8 MB → 整读进内存**：一次读全量塞进 [AudioMemoryDataSource]，之后拖进度条
 *    是纯内存随机读，**零 adb 抖动**（音频用户拖进度条很频繁，这个差别是真实的）；
 * 2. **> 8 MB 或大小未知 → 流式**：用 `RemoteFileMediaDataSource(stream)`，播放器按需随机读。
 *
 * ## 元数据
 * 用 `MediaMetadataRetriever.setDataSource(MediaDataSource)` 读时长 / 标题 / 艺术家 / 专辑 /
 * 内嵌封面。取不到的一律显示「未知」；标题退回去掉扩展名的文件名；
 * **封面取不到就画音符图标，绝不画假的彩色渐变封面**。
 *
 * ⚠️ `MediaMetadataRetriever` 在 API 29+ 的 `release()` 会顺手 close 传进去的 `MediaDataSource`。
 * 整读路径下每次都新建 [AudioMemoryDataSource]（close 是无害空操作）；
 * 流式路径下则**另开一条独立的流**给 retriever，绝不复用播放器那条 ——
 * 否则会把播放中的数据源一起关掉。
 *
 * @param entries    当前目录的音频条目（按调用方的排序原样传入）
 * @param startIndex 起始下标；越界会被夹到合法范围
 * @param openStream 打开远端音频流的挂起函数
 * @param onClose    关闭覆盖层（回到文件管理页）
 */
@Composable
fun AudioPlayerOverlay(
    entries: List<FileEntry>,
    startIndex: Int,
    openStream: suspend (FileEntry) -> Result<RemoteFileStream>,
    onClose: () -> Unit,
) {
    val context: Context = LocalContext.current

    // 内部维护一份可变副本：调用方刷新目录时以实例变化为准重建
    val items: SnapshotStateList<FileEntry> = remember(entries) {
        mutableStateListOf<FileEntry>().apply { addAll(entries) }
    }

    if (items.isEmpty()) {
        // 空列表没有可播放的内容，直接退出
        LaunchedEffect(Unit) { onClose() }
        return
    }

    // ---------------------------------------------------------------- 状态
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, items.size - 1)) }
    var source by remember { mutableStateOf<AudioSource?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorKind by remember { mutableStateOf<AudioErrorKind?>(null) }
    var errorDetail by remember { mutableStateOf("") }
    var meta by remember { mutableStateOf<AudioMeta?>(null) }
    var retryToken by remember { mutableIntStateOf(0) }

    var currentMs by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var panel by remember { mutableStateOf<AudioPanelKind?>(null) }
    var closing by remember { mutableStateOf(false) }

    /**
     * 缓冲进度（0–1）；**null = 不画缓冲条**。
     *
     * ⚠️ 只在流式路径（> 8 MB）有值：整读路径下整个音频已经在内存 ByteArray 里，
     * 画「缓冲进度」是**假信息**（永远 100%，或者一条不动的灰条让人以为在等），
     * 所以那种情况下一律不画。
     */
    var bufferedFraction by remember { mutableStateOf<Float?>(null) }

    // 用 applicationContext：controller 的生命周期可能长于当前 Activity 的一次配置变更
    val controller: AudioPlayerController = remember(context) {
        AudioPlayerController(context.applicationContext)
    }

    /** 短提示：倍速不支持等反馈走这里 */
    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- 加载
    LaunchedEffect(index, retryToken, items.size) {
        val entry: FileEntry = items.getOrNull(index) ?: return@LaunchedEffect

        // ★ 第一件事就是置空 source：`DisposableEffect(source)` 的 onDispose 会**同步**
        //   release 旧播放器并 close 旧流（顺序固定「先 release 再 close」，因为播放器可能
        //   正在 readAt）。不做这一步，新歌开流期间旧歌还在响 —— 用户眼前是
        //   「正在从被控端读取音频…」，耳朵里却是上一首。
        //   ⚠️ 必须赶在下面任何阻塞读取**之前**；这一句本身不碰 IO，留在主线程即可。
        source = null
        loading = true
        errorKind = null
        errorDetail = ""
        meta = null
        currentMs = 0
        scrubMs = 0
        scrubbing = false
        bufferedFraction = null

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
            errorKind = AudioErrorKind.READ
            errorDetail = if (reason.isBlank()) entry.name else "${entry.name} · $reason"
            loading = false
            closeQuietly(stream)
            return@LaunchedEffect
        }

        // ★★ 这一段必须切到 IO 线程。
        //
        // 链路：`readWhole` → `RemoteFileStream.readAt` → `RemoteFileSource.blockFor`，
        // 而后者是 **`runBlocking { … }`** —— 一次块未命中就是一次阻塞的 adb 往返。
        // 本协程跑在 `AndroidUiDispatcher.Main`（Compose 的 LaunchedEffect 默认），
        // 不切线程直接在主线程读满 8 MB 会把 UI 冻住 3–6 秒，慢 Wi-Fi 上必然 ANR。
        //
        // ⚠️ 与 `RemoteFileSource.readAt` KDoc 里那句「不指定 Dispatchers.IO 是有意为之」
        //    **不矛盾**：那说的是 readAt **内部**桥接 `runBlocking` 时不额外占一个 IO 池线程
        //    （避免并发读把 IO 池堵死），并没有免除**调用方**把自己放到非主线程的责任。
        //    别把这里"优化"回主线程。
        //
        // 只切做阻塞 IO 的这一段：上面的 `openStream` 与下面的元数据读取各有自己的调度，
        // 不跟着一起搬。写 Compose state 不要求主线程，但这里仍把结果**带出去**再落 state ——
        // 因为 `withContext` 不是 inline，lambda 里没法 `return@LaunchedEffect`。
        val outcome: AudioLoadOutcome = withContext(Dispatchers.IO) {
            val total: Long = stream.sizeBytes
            // 快捷路径：大小已知且 ≤ 8 MB → 整读进内存
            val bytes: ByteArray? = if (total > 0L && total <= WHOLE_READ_LIMIT_BYTES) {
                readWhole(stream, total)
            } else {
                null
            }

            // 整读半途失败且源已不可用：这是「读取」失败，不是「解码」失败，
            // 必须按 READ 报 —— 否则用户会以为缺解码器，实际是 adb 断了。
            val readFailure: String? = stream.failure
            when {
                bytes != null -> {
                    // 整读成功：字节全在内存里，流立刻关掉，后续播放不再碰 adb
                    closeQuietly(stream)
                    AudioLoadOutcome.Whole(bytes)
                }

                readFailure != null -> {
                    closeQuietly(stream)
                    AudioLoadOutcome.ReadFailed(readFailure)
                }

                else -> {
                    // 流式：保留流直到覆盖层 / 切歌时释放
                    try {
                        stream.prefetch(0L)
                    } catch (t: Throwable) {
                        Logx.w(TAG, "预取失败：${t.message}")
                    }
                    AudioLoadOutcome.Streamed(stream)
                }
            }
        }

        when (outcome) {
            is AudioLoadOutcome.ReadFailed -> {
                errorKind = AudioErrorKind.READ
                errorDetail = "${entry.name} · ${outcome.reason}"
                loading = false
                return@LaunchedEffect
            }

            is AudioLoadOutcome.Whole -> source = AudioSource(
                playerSource = AudioMemoryDataSource(outcome.bytes),
                stream = null,
                inMemory = outcome.bytes,
            )

            is AudioLoadOutcome.Streamed -> source = AudioSource(
                playerSource = RemoteFileMediaDataSource(stream),
                stream = stream,
                inMemory = null,
            )
        }
        loading = false
    }

    // 元数据：整读路径复用内存字节（零额外 IO）；流式路径另开一条独立流
    LaunchedEffect(source) {
        val src: AudioSource = source ?: return@LaunchedEffect
        val entry: FileEntry = items.getOrNull(index) ?: return@LaunchedEffect
        meta = withContext(Dispatchers.IO) {
            readAudioMeta(entry = entry, inMemory = src.inMemory, openStream = openStream)
        }
    }

    // 列表被外部换掉后下标可能越界
    LaunchedEffect(items.size) {
        if (index >= items.size) index = (items.size - 1).coerceAtLeast(0)
    }

    // 相对跳曲（+1 / -1，两端循环）。用 rememberUpdatedState 包住，
    // 保证 MediaPlayer 的 onCompletion 回调拿到的是最新的 items / index。
    // 显式写出 `(Int) -> Unit`：下面两个分支的「最后一句」类型不同（一个是赋值、一个是 ++），
    // 不写死的话 Kotlin 会把 T 推成 `(Int) -> Any`，虽然也能编译，但这个 state 的语义就是
    // 「一个动作」，类型不该跟着分支漂移。
    val goRelativeNow = rememberUpdatedState<(Int) -> Unit> { delta: Int ->
        val size: Int = items.size
        if (size > 1) {
            index = ((index + delta) % size + size) % size
        } else if (size == 1) {
            // ★ 只有一首时「切下一首」算出来还是自己：`(0 + 1) % 1 == 0`，index 不变 →
            //   `LaunchedEffect(index, …)` 的键不变 → 加载协程根本不会重跑，播完就停在末尾，
            //   必须手动再点一次播放。所以这里改走 `retryToken++`：它是加载协程的**另一个**键，
            //   自增必然触发一次重新加载（重开流 + 自动起播），观感与多首时切歌一致。
            retryToken++
        }
    }

    // ---------------------------------------------------------------- 播放器生命周期
    //
    // ⚠️ 回调挂在这里而不是 LaunchedEffect：DisposableEffect 的副作用块在 apply 阶段
    //   **同步**执行，而 LaunchedEffect 的协程体还要等一次调度 —— 若用后者，
    //   `controller.open()` 可能在 onError / onCompletion 挂好之前就跑完，
    //   同步失败时（setDataSource 抛异常）回调会是 null，UI 卡在「读取中」。
    DisposableEffect(controller) {
        controller.onCompletion = { goRelativeNow.value.invoke(1) }
        controller.onError = {
            val failed: FileEntry? = items.getOrNull(index)
            errorKind = AudioErrorKind.DECODE
            errorDetail = if (failed == null) "" else "${failed.name} · ${extLabelOf(failed.name)}"
        }
        controller.onSpeedRejected = { rejected ->
            speed = 1f
            toast(
                TXT_SPEED_UNSUPPORTED_PREFIX +
                    formatAudioSpeed(rejected) +
                    TXT_SPEED_UNSUPPORTED_SUFFIX
            )
        }
        onDispose {
            controller.onCompletion = null
            controller.onError = null
            controller.onSpeedRejected = null
            controller.release()
        }
    }

    DisposableEffect(source) {
        val src: AudioSource? = source
        if (src != null) controller.open(src.playerSource)
        onDispose {
            // ⚠️ 顺序固定：先 release 播放器，再关流。
            // 反过来的话播放器可能正在 readAt，而流已经关了。
            controller.release()
            closeQuietly(src?.stream)
        }
    }

    // ---------------------------------------------------------------- 进度推进
    //
    // 总时长：优先播放器实时值（prepare 后才有），其次元数据里读到的。
    // 进度协程要读它，所以放在协程**之前**声明。
    val durationMs: Int = if (controller.durationMs > 0) {
        controller.durationMs
    } else {
        (meta?.durationMs ?: 0L).toInt()
    }
    val durationNow = rememberUpdatedState(durationMs)

    // 缓冲进度只在**流式路径**计算：整读路径不画（见 [bufferedFraction] 的说明）。
    // durationMs 每次重组都会变（prepare 完成前是 0），用 rememberUpdatedState 包住，
    // 否则协程里拿到的是启动那一刻的旧值，缓冲条会永远算不出来。
    val scrubbingNow = rememberUpdatedState(scrubbing)
    LaunchedEffect(controller, source) {
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            val pos: Int = controller.currentPosition()
            if (!scrubbingNow.value) currentMs = pos
            val stream: RemoteFileStream? = source?.stream
            if (stream == null) {
                bufferedFraction = null // 整读路径（或还没加载完）：不画缓冲条
            } else {
                // ★ 流式路径（> 8 MB）必须自己轮询「源是否已废」，照视频播放器的做法：
                //   中途 adb 断流时 MediaPlayer 只会给一个通用错误码，落到 `onError` 里会被
                //   归成 DECODE → 文案暗示「缺解码器」，而真实原因是**读不到字节**。
                //   那是在编造诊断，必须避免，所以这里直接判成 READ。
                //   ⚠️ 源读失败过一次就**永久置位**（不自动恢复）—— 这条流已经废了，
                //   重试必须重开一条全新的流，不能拿废流继续 readAt。
                val reason: String? = stream.failure
                if (reason != null && errorKind == null) {
                    controller.pause()
                    errorKind = AudioErrorKind.READ
                    errorDetail = if (reason.isBlank()) {
                        TXT_AUDIO_STREAM_BROKEN
                    } else {
                        "$TXT_AUDIO_STREAM_BROKEN：$reason"
                    }
                    toast(TXT_AUDIO_STREAM_BROKEN)
                    bufferedFraction = null
                    // 置空 source：`DisposableEffect(source)` 会立刻 release 播放器并 close 废流，
                    // 否则废流的预取协程会一直挂着，而界面此时已经切到错误态。
                    source = null
                    break
                }
                bufferedFraction = computeBufferedFraction(
                    stream = stream,
                    positionMs = if (scrubbingNow.value) scrubMs else pos,
                    durationMs = durationNow.value,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 进出场
    // spring 是欠阻尼的（本项目已因「12dp → 0dp 下冲到负值」崩过一次），
    // 这里统一用补间；alpha 仍然先夹取再使用。
    val overlayAlpha: Float by animateFloatAsState(
        targetValue = if (closing) 0f else 1f,
        animationSpec = tween(durationMillis = OVERLAY_ANIM_MS, easing = Motion.EasingEmphasized),
        label = "audioOverlayAlpha",
    )
    LaunchedEffect(closing) {
        if (closing) {
            delay(OVERLAY_ANIM_MS.toLong())
            onClose()
        }
    }

    // ---------------------------------------------------------------- 交互
    val entry: FileEntry = items.getOrNull(index) ?: items[0]

    fun requestClose() {
        if (!closing) closing = true
    }

    fun goTo(target: Int) {
        val size: Int = items.size
        if (size <= 0) return
        index = ((target % size) + size) % size
        currentMs = 0
        scrubMs = 0
        scrubbing = false
        panel = null
        bufferedFraction = null
        errorKind = null
        errorDetail = ""
        // ★ 与 `goRelativeNow` 同理：只有一首时上面算出来的 index 还是自己，
        //   加载协程的键没变 → 不会重载，「下一首 / 上一个 / 列表里点它」全部变成死按钮。
        //   单曲场景下用户最常点的就是这几个，所以这里补一刀 retryToken++ 让它从头重播。
        if (size == 1) retryToken++
    }

    fun applySpeed(v: Float) {
        if (controller.applySpeed(v)) {
            speed = v
        } else {
            // 该音频 / ROM 吃不下这个倍速：回退 1× 并提示，绝不把播放器留在异常态
            controller.applySpeed(1f)
            speed = 1f
            toast(TXT_SPEED_UNSUPPORTED_PREFIX + formatAudioSpeed(v) + TXT_SPEED_UNSUPPORTED_SUFFIX)
        }
    }

    // ---------------------------------------------------------------- UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(overlayAlpha.coerceIn(0f, 1f)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Z1 顶栏：关闭 / 文件名 / 「n/N · 时长」
            AudioTopBar(
                title = entry.name,
                subtitle = "${index + 1}/${items.size} · ${durationTextOf(durationMs)}",
                onClose = { requestClose() },
            )

            // Z0 舞台：封面 / 标题·艺术家·专辑
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (errorKind != null) {
                    AudioErrorLayer(
                        kind = errorKind ?: AudioErrorKind.DECODE,
                        detail = errorDetail,
                        onNext = { goTo(index + 1) },
                        onClose = { requestClose() },
                    )
                } else if (loading) {
                    AudioLoadingLayer(entry = entry)
                } else {
                    AudioStage(
                        entry = entry,
                        meta = meta,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Z2 底部控制区（错误态下隐藏：此时按钮只有「下一个 / 关闭」）
            if (errorKind == null && !loading) {
                AudioControls(
                    playing = controller.playing,
                    currentMs = if (scrubbing) scrubMs else currentMs,
                    durationMs = durationMs,
                    scrubbing = scrubbing,
                    // 整读路径下恒为 null → 不画缓冲条（那样画出来是假信息）
                    bufferedFraction = bufferedFraction,
                    speed = speed,
                    onScrubStart = { scrubbing = true },
                    onScrub = { fraction ->
                        val d: Int = durationMs
                        scrubMs = (fraction * d).toInt().coerceIn(0, d.coerceAtLeast(0))
                    },
                    onScrubEnd = {
                        scrubbing = false
                        controller.seekTo(scrubMs)
                        currentMs = scrubMs
                    },
                    onTogglePlay = {
                        if (controller.playing) controller.pause() else controller.start()
                    },
                    onPrev = { goTo(index - 1) },
                    onNext = { goTo(index + 1) },
                    onPlaylist = { panel = AudioPanelKind.PLAYLIST },
                    onSpeed = { panel = AudioPanelKind.SPEED },
                )
            }
        }

        // Z3 右侧面板
        AudioPanelSheet(
            visible = panel != null && !closing,
            title = when (panel) {
                AudioPanelKind.PLAYLIST -> TXT_PLAYLIST
                AudioPanelKind.SPEED -> TXT_SPEED
                null -> ""
            },
            onDismiss = { panel = null },
        ) {
            when (panel) {
                AudioPanelKind.PLAYLIST -> AudioPlaylistPanel(
                    items = items,
                    currentIndex = index,
                    currentDurationMs = durationMs,
                    onPick = { i -> goTo(i) },
                )

                AudioPanelKind.SPEED -> AudioSpeedPanel(
                    current = speed,
                    onPick = { v -> applySpeed(v) },
                )

                null -> Unit
            }
        }

        // 退场动画期间盖一层「吃触摸」层，别让淡出的 400ms 里还能拖进度条
        if (closing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { /* 吞掉触摸 */ },
            )
        }
    }
}

// ---------------------------------------------------------------- Z1：顶栏

/**
 * 顶栏：关闭 / 文件名 + 副行「n/N · 时长」。
 *
 * Android 15（targetSdk 35）起强制 edge-to-edge，自定义顶栏必须自己
 * `windowInsetsPadding(WindowInsets.statusBars)`，否则会画到状态栏底下。
 */
@Composable
private fun AudioTopBar(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(AppIcon.Close, contentDescription = TXT_CLOSE, tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
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
    }
}

// ---------------------------------------------------------------- Z0：舞台

/**
 * 舞台：封面区 + 标题 / 艺术家 / 专辑。
 *
 * ⚠️ 封面为 null（无内嵌图片）时画**中性底 + 音符图标**：
 * 绝不画假的彩色渐变「封面」，那是在对不存在的信息撒谎。
 *
 * 标题取不到时退回去掉扩展名的文件名（那是真实信息），艺术家 / 专辑取不到就是「未知」。
 */
@Composable
private fun AudioStage(
    entry: FileEntry,
    meta: AudioMeta?,
    modifier: Modifier = Modifier,
) {
    val cover: Bitmap? = meta?.cover
    val title: String = meta?.title?.takeIf { it.isNotBlank() }
        ?: entry.name.substringBeforeLast('.').takeIf { it.isNotBlank() }
        ?: entry.name
    val artist: String = meta?.artist?.takeIf { it.isNotBlank() } ?: TXT_UNKNOWN
    val album: String = meta?.album?.takeIf { it.isNotBlank() } ?: TXT_UNKNOWN

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // 封面边长：屏宽 78% 与 240dp 取小，避免小屏溢出
            val side: Dp = minOf(maxWidth * 0.78f, AUDIO_COVER_MAX)
            Box(
                modifier = Modifier
                    .size(side)
                    .clip(Shape3xl)
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null && !cover.isRecycled) {
                    Image(
                        bitmap = cover.asImageBitmap(),
                        contentDescription = TXT_COVER,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = AudioIconMusicNote,
                        contentDescription = TXT_COVER,
                        tint = Color.White.copy(alpha = 0.42f),
                        modifier = Modifier.size(side * 0.44f),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = album,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.52f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 读取中：波浪指示器 + 语义说明 */
@Composable
private fun AudioLoadingLayer(
    entry: FileEntry,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        WavyProgressIndicator(size = 34.dp, color = Color.White)
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "$TXT_LOADING（${FileBrowserViewModel.formatSize(entry.sizeBytes)}）",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 错误层。
 *
 * ⚠️ 文案按失败种类分开（[AudioErrorKind]）：
 * - READ → 「无法读取该音频」+ 文件名 + 原因（断流 / 权限 / 文件已不在）；
 * - DECODE → 「无法解码该音频（可能缺少系统解码器）」+ 文件名 + 扩展名。
 *
 * 绝不笼统写「播放失败」——用户据此无法判断该去查连接还是查格式。
 */
@Composable
private fun AudioErrorLayer(
    kind: AudioErrorKind,
    detail: String,
    onNext: () -> Unit,
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
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (kind == AudioErrorKind.READ) TXT_READ_FAILED else TXT_DECODE_FAILED,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        if (detail.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.62f),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.clickable { onNext() },
                shape = ShapeMd,
                color = Color.White.copy(alpha = 0.14f),
                contentColor = Color.White,
            ) {
                Text(
                    text = TXT_BTN_NEXT,
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
                    text = TXT_BTN_BACK_TO_FILES,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Z2：底部控制区

/**
 * 底部控制区 4 行：进度条 / 时间行 / 主控制（上一首·播放暂停·下一首）/ 工具（播放列表·倍速）。
 *
 * 规格 P3 原则：**常驻不自动隐藏**。
 */
@Composable
private fun AudioControls(
    playing: Boolean,
    currentMs: Int,
    durationMs: Int,
    scrubbing: Boolean,
    bufferedFraction: Float?,
    speed: Float,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPlaylist: () -> Unit,
    onSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.68f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        // 第 1 行：进度条（可拖动）
        AudioSeekBar(
            progress = if (durationMs > 0) currentMs.toFloat() / durationMs.toFloat() else 0f,
            durationMs = durationMs,
            bufferedFraction = bufferedFraction,
            scrubbing = scrubbing,
            onScrubStart = onScrubStart,
            onScrub = onScrub,
            onScrubEnd = onScrubEnd,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )

        // 第 2 行：当前时间 · 总时长
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatAudioDuration(currentMs.toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatAudioDuration(durationMs.toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.72f),
            )
        }

        // 第 3 行：上一首 / 播放·暂停 / 下一首
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            AudioIconButton(icon = AudioIconPrev, desc = TXT_PREV, onClick = onPrev, size = 52.dp)
            Spacer(modifier = Modifier.width(20.dp))
            AudioIconButton(
                icon = if (playing) AudioIconPause else AppIcon.Play,
                desc = if (playing) TXT_PAUSE else TXT_PLAY,
                onClick = onTogglePlay,
                size = 64.dp,
            )
            Spacer(modifier = Modifier.width(20.dp))
            AudioIconButton(icon = AudioIconNext, desc = TXT_NEXT, onClick = onNext, size = 52.dp)
        }

        // 第 4 行：播放列表 / 倍速
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AudioTextButton(
                text = TXT_PLAYLIST,
                onClick = onPlaylist,
                modifier = Modifier.weight(1f),
            )
            AudioTextButton(
                text = formatAudioSpeed(speed),
                onClick = onSpeed,
                highlight = !audioSpeedEquals(speed, 1f),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 底部控制区的图标按钮 */
@Composable
private fun AudioIconButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = Color.White,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** 底部控制区的文本按钮（播放列表 / 倍速） */
@Composable
private fun AudioTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (highlight) AudioAccent else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 进度条（可拖动）。
 *
 * 用 `BoxWithConstraints` 拿轨道宽度做把手偏移；拖动期间由调用方接管显示位置（[scrubbing]），
 * 避免「拖动中被 200ms 轮询的真实位置拉回去」。
 *
 * @param bufferedFraction 缓冲进度 0–1；**null = 不画缓冲条**。
 *                         只有流式路径（> 8 MB）才传非 null —— 整读路径下整个音频已在内存里，
 *                         画缓冲条是假信息（永远 100%，或一条不动的灰条让人以为在等）。
 *                         ⚠️ 没有缓存数据时也不画（绝不默认画个固定百分比充数）。
 */
@Composable
private fun AudioSeekBar(
    progress: Float,
    durationMs: Int,
    bufferedFraction: Float?,
    scrubbing: Boolean,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction: Float = progress.coerceIn(0f, 1f)
    val buffered: Float = bufferedFraction?.coerceIn(0f, 1f) ?: 0f
    BoxWithConstraints(
        modifier = modifier
            .height(28.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onScrubStart()
                        onScrub((offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onScrubEnd() },
                    onDragCancel = { onScrubEnd() },
                    onDrag = { change, _ ->
                        onScrub(
                            (change.position.x / size.width.toFloat().coerceAtLeast(1f))
                                .coerceIn(0f, 1f)
                        )
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
        // 缓冲（灰色）：**只有流式路径且确实有缓存时才画**；画在已播之下，被已播覆盖
        if (bufferedFraction != null && buffered > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(buffered)
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.44f), CircleShape),
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
        // 拖动气泡（纯时间）
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
                    text = formatAudioDuration((fraction * durationMs.toFloat()).toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Z3：右侧面板

/** 右侧面板：侧滑入（400ms EasingEmphasized）+ Shape3xl 圆角 */
@Composable
private fun AudioPanelSheet(
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
                color = AudioSheetColor,
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
private fun AudioOptionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    selected: Boolean = false,
) {
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
                color = Color.White,
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
                color = AudioAccent,
                modifier = Modifier.width(20.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 播放列表：当前目录的音频清单，点击直切。
 *
 * ⚠️ 「大小」是**远端大小**（`FileEntry.sizeBytes`）；「时长」只有**当前正在播的那个**
 * 能从播放器 / 已读元数据取到，其余条目显示「—」（要拿它们的时长得逐个 openStream +
 * 起 retriever，代价是每条一次 adb 往返，不做）。
 */
@Composable
private fun AudioPlaylistPanel(
    items: List<FileEntry>,
    currentIndex: Int,
    currentDurationMs: Int,
    onPick: (Int) -> Unit,
) {
    // ⚠️ 不能用 LazyColumn：面板内容已经包在 `verticalScroll` 的 Column 里，
    //    同方向滚动嵌套会被 foundation 直接抛异常。单目录音频量级小，直接铺开。
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEachIndexed { i, item ->
            val isCurrent: Boolean = i == currentIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isCurrent) Color.White.copy(alpha = 0.10f) else Color.Transparent
                    )
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
                        color = if (isCurrent) AudioAccent else Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${FileBrowserViewModel.formatSize(item.sizeBytes)} · " +
                            (if (isCurrent && currentDurationMs > 0) {
                                formatAudioDuration(currentDurationMs.toLong())
                            } else {
                                "—"
                            }),
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
                        color = AudioAccent,
                    )
                }
            }
        }
    }
}

/**
 * 倍速 6 档（真实现：`MediaPlayer.playbackParams`）。
 *
 * ⚠️ 部分 ROM / 编码不支持某些档位，由 `AudioPlayerController.applySpeed` 捕获并
 * 回退 1× + toast「该音频不支持 X 倍速」，**不崩**。
 */
@Composable
private fun AudioSpeedPanel(
    current: Float,
    onPick: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        for (v in AUDIO_SPEED_STEPS) {
            AudioOptionRow(
                label = formatAudioSpeed(v),
                value = if (audioSpeedEquals(v, 1f)) TXT_SPEED_DEFAULT else null,
                selected = audioSpeedEquals(current, v),
                onClick = { onPick(v) },
            )
        }
    }
}

// ---------------------------------------------------------------- 工具

/**
 * 整读远端流：**一次读全量**，供 ≤ 8 MB 的音频走内存快捷路径。
 *
 * ⚠️ **阻塞调用**：内部经 `RemoteFileStream.readAt` → `RemoteFileSource` 的 `runBlocking`，
 * 一次块未命中就是一次阻塞的 adb 往返。**调用方必须自己在 IO 线程上调**，
 * 与 `readAudioMeta` 的要求一致 —— 在主线程调会把 UI 冻住几秒（慢 Wi-Fi 上直接 ANR）。
 *
 * @return 完整字节；读不满（EOF / 出错 / [RemoteFileStream.failure] 非 null）返回 null，
 *         调用方据此**降级为流式**，而不是判成失败。
 */
private fun readWhole(stream: RemoteFileStream, total: Long): ByteArray? {
    if (total <= 0L || total > Int.MAX_VALUE.toLong()) return null
    val size: Int = total.toInt()
    val buffer = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val want: Int = minOf(READ_CHUNK_BYTES, size - offset)
        val read: Int = try {
            stream.readAt(offset.toLong(), buffer, offset, want)
        } catch (t: Throwable) {
            Logx.w(TAG, "整读失败 @$offset：${t.message}")
            return null
        }
        if (read <= 0) break // EOF 或出错
        offset += read
    }
    if (offset < size) return null
    if (stream.failure != null) return null
    return buffer
}

/** 关闭远端流（幂等，失败只记日志） */
private fun closeQuietly(stream: RemoteFileStream?) {
    if (stream == null) return
    try {
        stream.close()
    } catch (t: Throwable) {
        Logx.w(TAG, "关闭远端流失败：${t.message}")
    }
}

/**
 * 算缓冲进度（0–1）；**没有可用缓存数据就返回 null（调用方据此不画缓冲条）**。
 *
 * 语义：取**覆盖当前播放位置的那一段**缓存，把它的一直到结尾当成「能连续播到哪里」。
 * 这比「总覆盖率」更贴近用户关心的事 —— 覆盖率 60% 但都不在播放头附近，等于还是要等。
 *
 * ⚠️ `RemoteFileSource.cachedRanges()` 返回的是 `start until end`（**右开区间**），
 * 所以段尾要 `+1` 才是真正的结束字节。
 *
 * ⚠️ 时间 → 字节的换算是**按 CBR 线性近似**（`pos / duration * size`）：
 * VBR 音频会有偏差，但缓冲条本来就是估计值，不值得为它去解真正的帧索引。
 */
private fun computeBufferedFraction(
    stream: RemoteFileStream,
    positionMs: Int,
    durationMs: Int,
): Float? {
    if (durationMs <= 0) return null
    val total: Long = stream.sizeBytes
    if (total <= 0L) return null
    val ranges: List<LongRange> = try {
        stream.cachedRanges()
    } catch (t: Throwable) {
        return null
    }
    if (ranges.isEmpty()) return null

    val positionBytes: Long =
        (positionMs.toLong() * total / durationMs.toLong()).coerceIn(0L, total)
    val containing: LongRange? =
        ranges.firstOrNull { positionBytes >= it.first && positionBytes <= it.last }
    if (containing == null) return null // 播放头所在位置根本没缓存 → 不画
    val bufferedEnd: Long = (containing.last + 1L).coerceAtMost(total)
    return (bufferedEnd.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/**
 * 读音频元数据（阻塞调用，调用方负责切到 IO 线程）。
 *
 * ⚠️ 两个数据源都必须**新建独立实例**，绝不能复用播放器正在用的那个：
 * API 29+ 的 `MediaMetadataRetriever.release()` 会顺手 close 传进去的 `MediaDataSource`，
 * 复用会把播放器的数据源一起关掉。
 *
 * @param inMemory  整读路径下的完整字节（非 null 时直接用内存数据源，**零额外 adb**）
 * @param openStream 流式路径下另开一条独立流给 retriever 用
 */
private suspend fun readAudioMeta(
    entry: FileEntry,
    inMemory: ByteArray?,
    openStream: suspend (FileEntry) -> Result<RemoteFileStream>,
): AudioMeta {
    if (inMemory != null) {
        return readMetaFrom(AudioMemoryDataSource(inMemory))
    }
    val opened: Result<RemoteFileStream> = try {
        openStream(entry)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        Result.failure(t)
    }
    val stream: RemoteFileStream = opened.getOrNull() ?: return AudioMeta()
    return try {
        readMetaFrom(RemoteFileMediaDataSource(stream))
    } finally {
        closeQuietly(stream)
    }
}

/**
 * 从任意 [MediaDataSource] 抽取元数据；取不到的一律「空 / 0」，**不编造**。
 */
private fun readMetaFrom(source: MediaDataSource): AudioMeta {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(source)
        val duration: Long = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val title: String? = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val artist: String? = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val album: String? = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val coverBytes: ByteArray? = try {
            retriever.embeddedPicture
        } catch (t: Throwable) {
            null
        }
        val cover: Bitmap? = coverBytes?.let { bytes ->
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (oom: OutOfMemoryError) {
                null
            } catch (t: Throwable) {
                null
            }
        }
        AudioMeta(
            durationMs = duration,
            title = title,
            artist = artist,
            album = album,
            cover = cover,
        )
    } catch (t: Throwable) {
        Logx.w(TAG, "读取音频元数据失败：${t.message}")
        AudioMeta()
    } finally {
        try {
            retriever.release()
        } catch (t: Throwable) {
            /* 释放失败无需处理 */
        }
    }
}

/** `mm:ss` / `h:mm:ss`；0 或负返回 `00:00` */
private fun formatAudioDuration(ms: Long): String {
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

/** 总时长文案：拿不到就「未知」 */
private fun durationTextOf(durationMs: Int): String =
    if (durationMs > 0) formatAudioDuration(durationMs.toLong()) else TXT_UNKNOWN

/** 倍速文案：`1×` / `1.25×` */
private fun formatAudioSpeed(v: Float): String {
    val rounded: Float = (v * 100f).toInt() / 100f
    return if (rounded % 1f == 0f) "${rounded.toInt()}×" else "${rounded}×"
}

/** 浮点倍速比较（避免 1.25f 直接 == 的精度问题） */
private fun audioSpeedEquals(a: Float, b: Float): Boolean = abs(a - b) < 0.001f

/** 大写扩展名；没有扩展名时返回「无扩展名」 */
private fun extLabelOf(name: String): String {
    val dot: Int = name.lastIndexOf('.')
    if (dot <= 0 || dot >= name.length - 1) return TXT_NO_EXT
    return name.substring(dot + 1).uppercase(Locale.getDefault())
}
