package com.adb.adbwirelesshelper.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adb.adbwirelesshelper.AdbWifiApp
import com.adb.adbwirelesshelper.data.settings.AppSettings
import com.adb.adbwirelesshelper.domain.model.ScrcpyConfig
import com.adb.adbwirelesshelper.ui.components.ExpressiveSelectChip
import com.adb.adbwirelesshelper.ui.theme.AppIcon
import com.adb.adbwirelesshelper.ui.theme.Radius
import com.adb.adbwirelesshelper.ui.theme.ShapeLg
import com.adb.adbwirelesshelper.ui.theme.ShapeXl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** ---------- 页面文案（集中在此，不新增 strings.xml 条目） ---------- */
private const val TEXT_TITLE: String = "设置"
private const val TEXT_GROUP_REFRESH: String = "设备刷新"
private const val TEXT_POLL: String = "轮询间隔"
// ⚠️ 这里是「暂停采集」而不是「降低频率」：DeviceDetailViewModel.pause() 是让轮询循环
//    整轮跳过（完全停止），不是放慢节奏。写成放慢会让用户以为后台还在慢慢刷新。
private const val TEXT_POLL_NOTE: String = "前台刷新频率；应用进入后台后暂停采集以省电，回到前台自动恢复。"
private const val TEXT_POLL_FOOTNOTE: String = "默认 1.5 秒。间隔越短，详情页的数据越实时，耗电也越高。"
private const val TEXT_GROUP_VIDEO: String = "投屏参数"
private const val TEXT_RESOLUTION: String = "分辨率上限"
private const val TEXT_RESOLUTION_NOTE: String =
    "限制视频较短边的像素数，再按被控端实际比例缩放。值越小越省带宽，画质也越低。"
private const val TEXT_BITRATE: String = "码率"
private const val TEXT_BITRATE_NOTE: String =
    "局域网推荐 4–8 Mbps。信号不好时提高码率会增加丢包，反而更容易卡。"
private const val TEXT_FPS: String = "帧率"
private const val TEXT_FPS_NOTE: String =
    "这只是「上限」。实际帧率还受被控端屏幕刷新率与控制端解码能力限制。"
private const val TEXT_VIDEO_NOTE: String =
    "默认值：分辨率上限 1280p、码率 8 Mbps、帧率 30 fps。" +
        "修改后在下次开始投屏时生效（也可中途停止后重新开始）。"
private const val TEXT_GROUP_AUDIO: String = "音频"
private const val TEXT_AUDIO_SWITCH: String = "转发被控端音频"
private const val TEXT_AUDIO_SWITCH_NOTE: String =
    "把被控端的声音实时转到控制端播放；被控端需 Android 11 及以上，且 Android 11 需先解锁屏幕。不支持时自动降级为无声投屏。"
private const val TEXT_AUDIO_CODEC: String = "音频编码"
private const val TEXT_AUDIO_CODEC_NOTE: String =
    "原始 PCM 零解码延迟；AAC / Opus 更省流量，但需要走解码，Opus 还要求控制端 Android 10+。"
private const val TEXT_AUDIO_BUFFER: String = "音频缓冲"
private const val TEXT_AUDIO_BUFFER_NOTE: String = "缓冲越大越抗网络抖动，代价是声音延迟更高。"
private const val TEXT_AUDIO_SOURCE: String = "音频来源"
private const val TEXT_AUDIO_SOURCE_NOTE: String =
    "默认采集设备整体输出；部分定制 ROM 下需要改选「仅媒体播放」才有声。"
private const val TEXT_AUDIO_RESTART_NOTE: String =
    "默认值：原始 PCM · 50 ms · 设备输出。音频参数在下次开始投屏时生效。"
private const val TEXT_GROUP_SECURITY: String = "连接与安全"
private const val TEXT_AUTO_RECONNECT: String = "自动重连"

/**
 * 自动重连说明文案 —— **已按源码校正**（规格 §4.5.4 第 2 条）。
 *
 * 原文案写的是「1s/2s/4s/8s/16s 指数退避，最多 5 次」，但 `MirrorViewModel.kt` 的实际实现是
 * `MAX_AUTO_RECONNECT = 3`（:1920）且退避为 `RECONNECT_BACKOFF_MS * attempt`（:1926，常数 1200ms），
 * 即 **线性** 1.2 / 2.4 / 3.6 秒、共 3 次。源码里的注释也明写「线性退避」。
 * 这里只改 UI 文案，**不动** `MirrorViewModel` 的常量。
 */
private const val TEXT_AUTO_RECONNECT_NOTE: String =
    "断开后按 1.2 / 2.4 / 3.6 秒的固定间隔重试，最多 3 次。"

private const val TEXT_DANGER_CONFIRM: String = "危险命令二次确认"

/**
 * 危险命令开关说明（规格 §4.5.1.1 方案 A）。
 *
 * 旧文案「关闭后仍会拦截高危命令并提示，但**不阻断执行**」与源码矛盾：
 * `ShellViewModel.run()` 里那段 `if (!dangerConfirmEnabled)` 只打了一行 log，随后的
 * `return` 在分支之外 —— 即**无论开关开或关都会阻断并弹确认**。故此处不写「不阻断执行」。
 */
private const val TEXT_DANGER_CONFIRM_NOTE: String =
    "高危命令（reboot / rm / pm uninstall 等）执行前会弹出确认框，并展示完整命令全文。"
private const val TEXT_DANGER_CONFIRM_FOOTNOTE: String =
    "危险命令必须逐条确认，关闭此开关不会跳过确认。"
private const val TEXT_GROUP_THEME: String = "外观"
private const val TEXT_THEME: String = "主题"
private const val TEXT_THEME_NOTE: String =
    "切换后立即生效。刻意不跟随壁纸取色，以保证状态色与终端配色的对比度可控。"
private const val TEXT_THEME_SYSTEM: String = "跟随系统"
private const val TEXT_THEME_LIGHT: String = "浅色"
private const val TEXT_THEME_DARK: String = "深色"
private const val TEXT_GROUP_ADVANCED: String = "高级"
private const val TEXT_IMPORT_ADB: String = "手动导入 adb 二进制"
private const val TEXT_IMPORT_ADB_NOTE: String = "当内置二进制与当前设备 ABI/系统版本不兼容时使用。"
private const val TEXT_LOGS: String = "日志导出"
private const val TEXT_LOGS_NOTE: String = "日志滚动写入 App 私有目录，导出内容仅限本机查看，不会上传。"
private const val TEXT_ABOUT: String = "关于与开源许可"
private const val TEXT_ABOUT_BODY: String =
    "本应用不使用云端，所有通信限于局域网，不收集、不上传任何数据。\n\n" +
        "内置的 scrcpy-server 来自开源项目 scrcpy：\n" +
        "Copyright © Genymobile\n" +
        "Licensed under the Apache License, Version 2.0（Apache-2.0）。\n\n" +
        "你可以从 https://github.com/Genymobile/scrcpy 获取其完整源码；" +
        "依据 Apache-2.0 第 4 条，重新分发时须保留上述版权声明与许可全文。\n\n" +
        "内置的 adb 来自 AOSP platform/system/core，同样遵循 Apache-2.0 许可。"
private const val TEXT_IMPORT_BODY: String =
    "导入步骤：\n" +
        "1. 准备与目标设备 ABI 一致、类型为 DYN（PIE）、无 TEXTREL 的 adb 可执行文件；\n" +
        "2. 若有 16KB 页对齐要求，请选用对应编译产物；\n" +
        "3. 通过系统文件选择器（ActivityResultContracts.GetContent）挑选该文件；\n" +
        "4. 本页当前版本给出操作说明，具体落地位置由「AdbRuntime」统一管理，避免私有目录 W^X 导致无法执行。"
private const val TEXT_LOGS_BODY: String =
    "导出步骤：\n" +
        "1. 日志文件位于 App 私有目录，滚动保留 ≤ 5MB × 3 个；\n" +
        "2. 通过系统分享把日志发送到本机其他应用查看；\n" +
        "3. 导出前会自动对配对码等敏感信息做脱敏；\n" +
        "4. 详细实现见「Logx」与 README 的「本地日志」小节。"
private const val TEXT_COPY: String = "复制说明"
private const val TEXT_CLOSE: String = "关闭"
private const val TEXT_NOT_READY: String = "设置服务尚未就绪（依赖注入未完成），以下开关暂不可修改。"
private const val TEXT_CURRENT_VALUE: String = "当前："

/** 未启用（依赖注入缺失）时的统一透明度，取值同 Material3 的 disabled alpha。 */
private const val DISABLED_ALPHA: Float = 0.38f

/** 轮询间隔可选值 */
private val POLL_OPTIONS: List<Pair<String, Long>> = listOf(
    "1.5 秒" to 1_500L,
    "3 秒" to 3_000L,
    "10 秒" to 10_000L
)

/**
 * 分辨率上限可选值。
 *
 * ⚠️ 规格 §4.5.4 第 3 条：`ScrcpyConfig.maxSize` 默认 **1280**（`AppSettings.DEFAULT_MAX_SIZE`），
 * 同对象的 `maxSizePreset` 默认却是 720（`DEFAULT_MAX_SIZE_PRESET`），两个字段初值互相矛盾；
 * 实际下发走 `cfg.maxSize.takeIf { > 0 } ?: cfg.maxSizePreset`（`ScrcpyServerDeployer.kt:362`），
 * 即**默认实际下发 1280**。本页选中态只看 `cfg.maxSize`，所以这里也不要把 720p 标成默认。
 * `maxSizePreset` 属历史遗留冗余字段，**本次不动它**。
 */
private val RESOLUTION_OPTIONS: List<Pair<String, Int>> = listOf(
    "480p" to 480,
    "720p" to 720,
    "1280p" to 1280
)

/**
 * 码率可选值（Mbps）。
 *
 * 上限受服务端 `video_bit_rate` 约束：`ScrcpyServerDeployer` 会 `coerceIn(1, 64)`，
 * 所以这里最大给到 50 留余量。按实用场景分档：
 * - **1 / 2 / 3**：弱网或 2.4GHz 保守档，画质明显下降但最不容易卡；
 * - **4 / 6 / 8**：局域网默认区间，**8 是出厂默认值**；
 * - **10 / 12 / 16 / 20**：5GHz 或信号好时的清晰档；
 * - **30 / 50**：追求画质，仅在 5GHz/6GHz 近距离无干扰时用；信号一般时反而会因为丢包更卡。
 *
 * 分组较多，所以 [ChipScrollRow] 是**可横向滚动**的（否则窄屏会被挤掉）。
 */
private val BITRATE_OPTIONS: List<Pair<String, Int>> = listOf(
    "1 Mbps" to 1,
    "2 Mbps" to 2,
    "3 Mbps" to 3,
    "4 Mbps" to 4,
    "6 Mbps" to 6,
    "8 Mbps" to 8,
    "10 Mbps" to 10,
    "12 Mbps" to 12,
    "16 Mbps" to 16,
    "20 Mbps" to 20,
    "30 Mbps" to 30,
    "50 Mbps" to 50
)

/**
 * 帧率可选值。
 *
 * ★ 这只是**上限**（`max_fps`），不是固定帧率：scrcpy-server 用 Android 的
 *   `SurfaceControl` / `MediaCodec` 按屏幕实际刷新产出，`max_fps` 只是给服务端
 *   一个上限阈值。所以选 120/165 要求**被控端屏幕本身支持**该刷新率，否则实际帧率
 *   仍会被硬件上限卡住 —— 这是正常的降级，不是参数没生效。
 *   另外一端的解码能力是硬门槛：1080p@120fps 的码流在老设备上解不动时就该退回 60。
 */
private val FPS_OPTIONS: List<Pair<String, Int>> = listOf(
    "30 fps" to 30,
    "60 fps" to 60,
    "120 fps" to 120,
    "165 fps" to 165
)

/**
 * 音频编码可选值。
 *
 * `raw` = 未压缩 PCM(16-bit LE)：控制端零解码、延迟最低，是本项目默认值；
 * AAC / Opus 省流量但要走 MediaCodec 解码，Opus 还需控制端 Android 10+。
 * 取值来自 [ScrcpyConfig] 的常量，与服务端 `audio_codec` 参数一一对应。
 */
private val AUDIO_CODEC_OPTIONS: List<Pair<String, String>> = listOf(
    "原始 PCM（最低延迟）" to ScrcpyConfig.AUDIO_CODEC_RAW,
    "AAC（省流量）" to ScrcpyConfig.AUDIO_CODEC_AAC,
    "Opus（省流量，需 Android 10+）" to ScrcpyConfig.AUDIO_CODEC_OPUS
)

/** 音频缓冲可选值（ms）。缓冲越大越抗抖动，代价是延迟增加。 */
private val AUDIO_BUFFER_OPTIONS: List<Pair<String, Int>> = listOf(
    "30 ms（更灵敏）" to 30,
    "50 ms（默认）" to 50,
    "100 ms（更稳）" to 100,
    "200 ms（弱网）" to 200
)

/** 音频来源可选值，对应服务端 `audio_source`。 */
private val AUDIO_SOURCE_OPTIONS: List<Pair<String, String>> = listOf(
    "设备输出" to ScrcpyConfig.AUDIO_SOURCE_OUTPUT,
    "仅媒体播放" to ScrcpyConfig.AUDIO_SOURCE_PLAYBACK,
    "麦克风" to ScrcpyConfig.AUDIO_SOURCE_MIC
)

/**
 * 设置页（对应原型图 ⑥，规格 §4.5）。
 *
 * Material 3 Expressive 改造点：
 * - 分节容器改用 `surfaceContainerLow` + **20 dp** 分组卡圆角（§1.2.2 / §1.3）；
 * - 各可选项从普通 Chip 升级到 [ExpressiveSelectChip]，选中态走 **M2 形变**（pill → 16 dp + 打勾）；
 *   ≤4 项的组套一层胶囊容器 = **Segmented Button**（§1.6），项数多的组保持横向滚动 Chip Group；
 * - 各设置项补 Info 说明，各分组补脚注；
 * - 「危险命令二次确认」按 §4.5.1.1 方案 A 诚实呈现（开关保留 + 灰字脚注，不承诺它没做的行为）。
 *
 * 所有偏好最终落到 [AppSettings]（DataStore preferences）。本页的组合签名被导航层固定为
 * `(onBack)`，拿不到 ViewModel，所以直接经 [AdbWifiApp.appContainer] 取全局唯一的
 * [AppSettings] —— 与 `AppNavHost` 走的是**同一处入口**。
 *
 * ⚠️ 取不到时（理论上不会发生）本页降级为「只读展示」并给出提示，不崩溃。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context: Context = LocalContext.current
    val settings: AppSettings? = remember(context) { findSettings(context) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val enabled: Boolean = settings != null

    var importDialog: Boolean by remember { mutableStateOf(false) }
    var logsDialog: Boolean by remember { mutableStateOf(false) }
    var aboutDialog: Boolean by remember { mutableStateOf(false) }

    val pollSource: Flow<Long> = settings?.pollIntervalMs ?: flowOf(1_500L)
    val pollIntervalMs: Long by pollSource.collectAsState(initial = 1_500L)

    val cfgSource: Flow<ScrcpyConfig> = settings?.scrcpyConfig ?: flowOf(ScrcpyConfig())
    val cfg: ScrcpyConfig by cfgSource.collectAsState(initial = ScrcpyConfig())

    val autoSource: Flow<Boolean> = settings?.autoReconnect ?: flowOf(true)
    val autoReconnect: Boolean by autoSource.collectAsState(initial = true)

    val dangerSource: Flow<Boolean> = settings?.dangerConfirm ?: flowOf(true)
    val dangerConfirm: Boolean by dangerSource.collectAsState(initial = true)

    val themeSource: Flow<Int> = settings?.themeMode ?: flowOf(0)
    val themeMode: Int by themeSource.collectAsState(initial = 0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = TEXT_TITLE) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = AppIcon.Back,
                            contentDescription = TEXT_TITLE,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (settings == null) {
                SettingsNoteCard(text = TEXT_NOT_READY, error = true)
            }

            // ---- 轮询间隔 ----
            SettingsSection(
                title = TEXT_GROUP_REFRESH,
                footnote = TEXT_POLL_FOOTNOTE
            ) {
                SettingsSubRow(label = TEXT_POLL, note = TEXT_POLL_NOTE)
                ChipScrollRow {
                    POLL_OPTIONS.forEach { option: Pair<String, Long> ->
                        SettingsChip(
                            label = option.first,
                            selected = pollIntervalMs == option.second,
                            enabled = enabled
                        ) {
                            scope.launch { settings?.setPollInterval(option.second) }
                        }
                    }
                }
                Text(
                    text = "${TEXT_CURRENT_VALUE}${pollIntervalMs} ms",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                )
            }

            // ---- 投屏参数 ----
            SettingsSection(
                title = TEXT_GROUP_VIDEO,
                footnote = TEXT_VIDEO_NOTE
            ) {
                SettingsSubRow(label = TEXT_RESOLUTION, note = TEXT_RESOLUTION_NOTE)
                SegmentedRow {
                    RESOLUTION_OPTIONS.forEach { option: Pair<String, Int> ->
                        SettingsChip(
                            label = option.first,
                            selected = cfg.maxSize == option.second,
                            enabled = enabled
                        ) {
                            scope.launch {
                                settings?.setScrcpyConfig(
                                    cfg.copy(maxSize = option.second, maxSizePreset = option.second)
                                )
                            }
                        }
                    }
                }
                DividerLine()
                SettingsSubRow(label = TEXT_BITRATE, note = TEXT_BITRATE_NOTE)
                ChipScrollRow {
                    BITRATE_OPTIONS.forEach { option: Pair<String, Int> ->
                        SettingsChip(
                            label = option.first,
                            selected = cfg.bitrateMbps == option.second,
                            enabled = enabled
                        ) {
                            scope.launch {
                                settings?.setScrcpyConfig(
                                    cfg.copy(
                                        bitrateMbps = option.second,
                                        videoBitRate = option.second * 1_000_000
                                    )
                                )
                            }
                        }
                    }
                }
                DividerLine()
                SettingsSubRow(label = TEXT_FPS, note = TEXT_FPS_NOTE)
                ChipScrollRow {
                    FPS_OPTIONS.forEach { option: Pair<String, Int> ->
                        SettingsChip(
                            label = option.first,
                            selected = cfg.maxFps == option.second,
                            enabled = enabled
                        ) {
                            scope.launch {
                                settings?.setScrcpyConfig(
                                    cfg.copy(maxFps = option.second, fpsCap = option.second)
                                )
                            }
                        }
                    }
                }
            }

            // ---- 音频 ----
            // 所有 chip 都只改「一个」字段：`ScrcpyConfig.copy(...)` 会保留其余字段
            // （含 videoBitRate 与 bitrateMbps —— 视频码率那段的双字段联动只属于视频）。
            SettingsSection(
                title = TEXT_GROUP_AUDIO,
                footnote = TEXT_AUDIO_RESTART_NOTE
            ) {
                SettingsSwitchRow(
                    label = TEXT_AUDIO_SWITCH,
                    note = TEXT_AUDIO_SWITCH_NOTE,
                    checked = cfg.audioEnabled,
                    enabled = enabled,
                    onCheckedChange = { value: Boolean ->
                        scope.launch { settings?.setScrcpyConfig(cfg.copy(audioEnabled = value)) }
                    }
                )
                DividerLine()
                SettingsSubRow(label = TEXT_AUDIO_CODEC, note = TEXT_AUDIO_CODEC_NOTE)
                SegmentedRow {
                    AUDIO_CODEC_OPTIONS.forEach { option: Pair<String, String> ->
                        SettingsChip(
                            label = option.first,
                            selected = cfg.audioCodec == option.second,
                            enabled = enabled
                        ) {
                            scope.launch {
                                settings?.setScrcpyConfig(cfg.copy(audioCodec = option.second))
                            }
                        }
                    }
                }
                DividerLine()
                SettingsSubRow(label = TEXT_AUDIO_BUFFER, note = TEXT_AUDIO_BUFFER_NOTE)
                SegmentedRow {
                    AUDIO_BUFFER_OPTIONS.forEach { option: Pair<String, Int> ->
                        SettingsChip(
                            label = option.first,
                            selected = cfg.audioBufferMs == option.second,
                            enabled = enabled
                        ) {
                            scope.launch {
                                settings?.setScrcpyConfig(cfg.copy(audioBufferMs = option.second))
                            }
                        }
                    }
                }
                DividerLine()
                SettingsSubRow(label = TEXT_AUDIO_SOURCE, note = TEXT_AUDIO_SOURCE_NOTE)
                SegmentedRow {
                    AUDIO_SOURCE_OPTIONS.forEach { option: Pair<String, String> ->
                        SettingsChip(
                            label = option.first,
                            selected = cfg.audioSource == option.second,
                            enabled = enabled
                        ) {
                            scope.launch {
                                settings?.setScrcpyConfig(cfg.copy(audioSource = option.second))
                            }
                        }
                    }
                }
            }

            // ---- 连接与安全 ----
            SettingsSection(title = TEXT_GROUP_SECURITY) {
                SettingsSwitchRow(
                    label = TEXT_AUTO_RECONNECT,
                    note = TEXT_AUTO_RECONNECT_NOTE,
                    checked = autoReconnect,
                    enabled = enabled,
                    onCheckedChange = { value: Boolean ->
                        scope.launch { settings?.setAutoReconnect(value) }
                    }
                )
                DividerLine()
                // §4.5.1.1 方案 A：开关保留 + 说明不撒谎 + 灰字脚注澄清
                SettingsSwitchRow(
                    label = TEXT_DANGER_CONFIRM,
                    note = TEXT_DANGER_CONFIRM_NOTE,
                    footnote = TEXT_DANGER_CONFIRM_FOOTNOTE,
                    checked = dangerConfirm,
                    enabled = enabled,
                    onCheckedChange = { value: Boolean ->
                        scope.launch { settings?.setDangerConfirm(value) }
                    }
                )
            }

            // ---- 外观 ----
            SettingsSection(title = TEXT_GROUP_THEME) {
                SettingsSubRow(label = TEXT_THEME, note = TEXT_THEME_NOTE)
                SegmentedRow {
                    SettingsChip(
                        label = TEXT_THEME_SYSTEM,
                        selected = themeMode == 0,
                        enabled = enabled
                    ) { scope.launch { settings?.setThemeMode(0) } }
                    SettingsChip(
                        label = TEXT_THEME_LIGHT,
                        selected = themeMode == 1,
                        enabled = enabled
                    ) { scope.launch { settings?.setThemeMode(1) } }
                    SettingsChip(
                        label = TEXT_THEME_DARK,
                        selected = themeMode == 2,
                        enabled = enabled
                    ) { scope.launch { settings?.setThemeMode(2) } }
                }
            }

            // ---- 高级 ----
            SettingsSection(title = TEXT_GROUP_ADVANCED) {
                SettingsClickRow(
                    icon = { AppIcon.Add },
                    label = TEXT_IMPORT_ADB,
                    note = TEXT_IMPORT_ADB_NOTE,
                    onClick = { importDialog = true }
                )
                DividerLine()
                SettingsClickRow(
                    icon = { AppIcon.Info },
                    label = TEXT_LOGS,
                    note = TEXT_LOGS_NOTE,
                    onClick = { logsDialog = true }
                )
                DividerLine()
                SettingsClickRow(
                    icon = { AppIcon.Warning },
                    label = TEXT_ABOUT,
                    note = "Apache-2.0 · Copyright © Genymobile",
                    onClick = { aboutDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (importDialog) {
        InfoDialog(
            title = TEXT_IMPORT_ADB,
            body = TEXT_IMPORT_BODY,
            onDismiss = { importDialog = false },
            onCopy = { clipboard.setText(AnnotatedString(TEXT_IMPORT_BODY)) }
        )
    }
    if (logsDialog) {
        InfoDialog(
            title = TEXT_LOGS,
            body = TEXT_LOGS_BODY,
            onDismiss = { logsDialog = false },
            onCopy = { clipboard.setText(AnnotatedString(TEXT_LOGS_BODY)) }
        )
    }
    if (aboutDialog) {
        InfoDialog(
            title = TEXT_ABOUT,
            body = TEXT_ABOUT_BODY,
            onDismiss = { aboutDialog = false },
            onCopy = { clipboard.setText(AnnotatedString(TEXT_ABOUT_BODY)) }
        )
    }
}

/**
 * 分节卡片容器。
 *
 * 规格 §1.2.2：设置页容器改 `surfaceContainerLow`（原来是 `surfaceVariant@0.45`，
 * 半透明叠在背景上会让层次发脏）；§1.3：分组卡统一 **xl = 20 dp**。
 *
 * @param footnote 分节底部脚注（11 sp 灰字），用于说明「什么时候生效」这类补充信息。
 */
@Composable
private fun SettingsSection(
    title: String,
    footnote: String? = null,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
        )
        Surface(
            shape = ShapeXl,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                content()
                if (footnote != null) {
                    Text(
                        text = footnote,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp)
                    )
                }
            }
        }
    }
}

/** 带标题与说明的小标题行。 */
@Composable
private fun SettingsSubRow(label: String, note: String? = null) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        if (note != null) {
            Text(
                text = note,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * ★ Segmented Button 容器（规格 §1.6）。
 *
 * 选项数 ≤ 4 的组（主题 / 分辨率 / 音频编码 / 音频缓冲 / 音频来源）装进一个胶囊容器：
 * 容器底色与未选中 Chip 的底色一致，视觉上连成一条；选中的那一项由 [ExpressiveSelectChip]
 * 自己变成 16 dp 圆角方块并上 primaryContainer，形成 M3E 的分段按钮观感。
 *
 * 内部仍然保留横向滚动：这几个组的中文标签偏长，窄屏放不下时不允许被裁掉。
 */
@Composable
private fun SegmentedRow(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(Radius.Full),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        ChipScrollRow(content = content)
    }
}

/**
 * Scrollable Chip Group 的横向滚动行（规格 §1.6：>4 项时横向滚动）。
 *
 * 码率有 12 个档位，窄屏一行放不下；横向滚动保证任何屏幕都能选到全部档位。
 * （没有用 `LazyRow` 是因为这些 chip 最多十几个，一次性布局可读性更好，也不依赖 lazy 重组。）
 */
@Composable
private fun ChipScrollRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

/**
 * 单个可选项 Chip：选中态走 [ExpressiveSelectChip] 的 **M2 形变**（pill → 16 dp + 左侧打勾）。
 *
 * [ExpressiveSelectChip] 没有 `enabled` 参数，这里用「禁用时降低透明度 + onClick 空转」
 * 实现 —— 与 `settings == null` 时整页只读的要求一致。
 */
@Composable
private fun SettingsChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    ExpressiveSelectChip(
        text = label,
        selected = selected,
        onClick = { if (enabled) onClick() },
        modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)
    )
}

/**
 * 开关行。
 *
 * @param footnote 开关下方的补充灰字（可选）。§4.5.1.1 用它澄清「危险命令二次确认」的真实行为。
 */
@Composable
private fun SettingsSwitchRow(
    label: String,
    note: String,
    footnote: String? = null,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = note,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
        if (footnote != null) {
            Text(
                text = footnote,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** 可点击的说明行（右侧 Chevron）。 */
@Composable
private fun SettingsClickRow(
    icon: () -> androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    note: String,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon(),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = note,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = AppIcon.ChevronRight,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 分节内分隔线（不用 Divider，避免依赖不同 Material3 版本的新 API）。 */
@Composable
private fun DividerLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    )
}

/** 提示卡片。 */
@Composable
private fun SettingsNoteCard(text: String, error: Boolean) {
    Surface(
        shape = ShapeLg,
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    ) {
        Text(
            text = text,
            color = if (error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

/** 通用说明弹窗（支持复制全文）。 */
@Composable
private fun InfoDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Text(
                text = body,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        },
        confirmButton = {
            TextButton(onClick = { onCopy(); onDismiss() }) {
                Text(text = TEXT_COPY)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = TEXT_CLOSE)
            }
        }
    )
}

/**
 * 取得全局唯一的 [AppSettings] 实例。
 *
 * 走 [AdbWifiApp.appContainer] → [com.adb.adbwirelesshelper.di.AppContainer.appSettings] 这条
 * **编译期可见的引用链**，与 `AppNavHost` 用的是同一个入口。
 *
 * ⚠️ 这里曾经用反射「按类型扫描 Application / 容器的字段」来查找 AppSettings，
 * 动机是「不想硬编码容器字段名，怕重构失效」。它有一个**确定性缺陷**，直接导致设置页
 * 长期显示「设置服务尚未就绪（依赖注入未完成）」并把**所有开关置灰**：
 *
 * `AdbWifiApp.appContainer` 与 `AppContainer.appSettings` **都是 `by lazy`**，
 * 即字段的**实际类型是 `Lazy<...>`**，真正的对象藏在委托对象的 `_value` 里。
 * 旧实现只解开「字段值里的 Lazy」，拿到 `Lazy<AppContainer>` 之后却把这个**委托对象本身**
 * 当成容器去扫它的字段 —— 于是扫到的是 `_value = AppContainer`（不是 AppSettings），
 * 而它**不会继续往下递归**，AppSettings 永远查不到 → 返回 null → 整页置灰。
 *
 * 结论：**依赖注入的获取路径不要用反射**。反射在这里既没有可验证性（编译器帮不上忙），
 * 也不比直接引用更难在重构时发现（改字段名时编译器会直接报错，反而更安全）。
 */
private fun findSettings(context: Context): AppSettings? {
    val app: Context = context.applicationContext ?: return null
    return (app as? AdbWifiApp)?.appContainer?.appSettings
}
