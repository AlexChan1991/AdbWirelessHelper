package com.adb.adbwirelesshelper.ui.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.adb.adbwirelesshelper.domain.model.DeviceInfo
import com.adb.adbwirelesshelper.domain.model.DeviceState
import com.adb.adbwirelesshelper.ui.components.ConnectionState
import com.adb.adbwirelesshelper.ui.components.CpuRing
import com.adb.adbwirelesshelper.ui.components.KeyValueGrid
import com.adb.adbwirelesshelper.ui.components.MemoryBar
import com.adb.adbwirelesshelper.ui.components.QuickChip
import com.adb.adbwirelesshelper.ui.components.SectionCard
import com.adb.adbwirelesshelper.ui.components.StatusStrip
import com.adb.adbwirelesshelper.ui.components.TopProcessRow
import com.adb.adbwirelesshelper.ui.components.WavyProgressIndicator
import com.adb.adbwirelesshelper.ui.components.formatKb
import com.adb.adbwirelesshelper.ui.components.orUnknown
import com.adb.adbwirelesshelper.ui.theme.AppIcon
import com.adb.adbwirelesshelper.ui.theme.Motion
import com.adb.adbwirelesshelper.ui.theme.Radius
import com.adb.adbwirelesshelper.ui.theme.Shape2xl

// ---------------------------------------------------------------- 文案常量

private const val TXT_BACK = "返回"
private const val TXT_REFRESH = "刷新"
private const val TXT_SEC_BASIC = "基础信息"
private const val TXT_SEC_RESOURCE = "实时资源"
private const val TXT_SEC_DISPLAY = "展示与能耗"
private const val TXT_SEC_QUICK = "快捷命令"
private const val TXT_LABEL_SERIAL = "序列号"
private const val TXT_LABEL_MANUFACTURER = "制造商"
private const val TXT_LABEL_MODEL = "型号"
private const val TXT_LABEL_DEVICE = "设备代号"
private const val TXT_LABEL_ANDROID = "Android 版本"
private const val TXT_LABEL_SDK = "SDK 等级"
private const val TXT_LABEL_BUILD = "Build 号"
private const val TXT_LABEL_PATCH = "安全补丁"
private const val TXT_LABEL_CPU = "CPU"
private const val TXT_LABEL_ABI = "ABI"
private const val TXT_LABEL_CORES = "核心数"
private const val TXT_LABEL_RESOLUTION = "分辨率"
private const val TXT_LABEL_SCREEN = "屏幕状态"
private const val TXT_LABEL_BATTERY = "电量"
private const val TXT_LABEL_STORAGE = "存储 /data"
private const val TXT_LABEL_UPTIME = "运行时长"
private const val TXT_LABEL_KERNEL = "内核"
private const val TXT_LABEL_IP = "Wi-Fi IP"
private const val TXT_LABEL_LOAD = "负载"
private const val TXT_LABEL_FINGERPRINT = "完整指纹"
private const val TXT_TOP_PROC = "Top 进程"
private const val TXT_DISCONNECT = "断开"
private const val TXT_MIRROR = "开始投屏"
private const val TXT_CONSOLE = "命令行"
private const val TXT_FILES = "文件管理"
private const val TXT_DISCONNECT_TITLE = "确认断开连接？"
private const val TXT_DISCONNECT_BODY = "断开后需要重新发现或手动输入 IP:端口才能再次连接。"
private const val TXT_CONFIRM = "确认断开"
private const val TXT_SCREEN_ON = "已亮屏"
private const val TXT_SCREEN_OFF = "已息屏"
private const val TXT_NOT_COLLECTED = "正在采集设备状态…"
private const val TXT_REFRESHED = "刚刚刷新"

/**
 * 详情页顶栏**内容**高度（不含状态栏）。
 *
 * 两行标题（32 sp 标题 + 副标题）比列表页高，取 88 dp。状态栏由
 * [FlexibleDetailTopAppBar] 里的 `windowInsetsPadding(WindowInsets.statusBars)` 另外让位，
 * 所以这里是「内容高度」而不是「含状态栏的总高度」。
 */
private val TOP_BAR_HEIGHT_DETAIL: Dp = 88.dp

/** 底部操作栏每一层的行高（两层各占一行，规格 §4.2.1 区块 7）。 */
private val ACTION_ROW_HEIGHT: Dp = 56.dp

/** 快捷命令 6 条（最后一条 reboot 为高危，标红）。 */
private val QUICK_COMMANDS: List<Pair<String, Boolean>> = listOf(
    "顶层 Activity" to false,
    "wm size" to false,
    "dumpsys meminfo" to false,
    "cat /proc/cpuinfo" to false,
    "ip addr show wlan0" to false,
    "reboot" to true,
)

// ---------------------------------------------------------------- 页面

/**
 * 设备详情 / 状态面板（对应原型图 ③）。
 *
 * 四张卡：基础信息 / 实时资源 / 展示与能耗 / 快捷命令；
 * 底部操作栏**两层**（规格 §4.2.1 区块 7）：
 *   第一层 = 开始投屏（独占整行），第二层 = 文件管理 / 命令行 / 断开。
 *
 * Material 3 Expressive 改造点（设计规格 §4.2）：
 * - ★ M1：Flexible Top App Bar —— 滚动时容器长出 28 dp 圆角，标题 32→22 sp，
 *   副标题（serial）同步淡出；
 * - ★ 手动刷新用波浪形不定进度（[WavyProgressIndicator]）替代转圈；
 * - ★ 快捷命令 6 条改为 Carousel（一屏 2.5 项、横向可滑）；
 * - ★ 底部操作栏两层：第一层「开始投屏」独占整行，**整块就是一个按钮**，
 *   内部除了「播放图标 + 文案」没有第二枚元素（代码只有一个 `onMirror`，
 *   没有参数菜单，画上去的箭头只会变成「看得见点不动」的假控件）；
 *   第二层三个等宽按钮，其中「文件管理」进 files 路由。
 *
 * ⚠️ 跨工程师契约（调用方 = AppNavHost）：
 *  - DeviceDetailScreen(vm, serial, onBack, onMirror, onConsole, onFiles)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    vm: DeviceDetailViewModel,
    serial: String,
    onBack: () -> Unit,
    onMirror: (String) -> Unit,
    onConsole: (String) -> Unit,
    onFiles: (String) -> Unit,
) {
    val info by vm.info.collectAsState()
    val device by vm.device.collectAsState()
    val message by vm.message.collectAsState()
    val refreshing by vm.refreshing.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // ★ M1 形变进度：与列表页同一套算法，只依赖滚动量，不和 AppBar 高度互相影响
    val collapseRangePx: Float = with(LocalDensity.current) { 48.dp.toPx() }
    val collapseFraction: Float by remember(collapseRangePx) {
        derivedStateOf {
            val scrolled: Float = if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat()
            } else {
                collapseRangePx + 1_000f
            }
            (scrolled / collapseRangePx).coerceIn(0f, 1f)
        }
    }

    LaunchedEffect(serial) { vm.bind(serial) }
    DisposableEffect(serial) {
        vm.resume()
        onDispose { vm.pause() }
    }

    LaunchedEffect(message) {
        val text = message
        if (!text.isNullOrBlank()) {
            snackbarHostState.showSnackbar(text)
            vm.consumeMessage()
        }
    }

    val title: String = info?.model
        ?: device?.model?.takeIf { it.isNotBlank() }
        ?: serial

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            FlexibleDetailTopAppBar(
                collapseFraction = collapseFraction,
                title = title,
                subtitle = serial,
                refreshing = refreshing,
                onBack = onBack,
                onRefresh = { vm.refresh() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            StatusStrip(
                state = stripStateOf(device?.state),
                text = stripTextOf(info, device?.state),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                item { Spacer(modifier = Modifier.height(2.dp)) }

                item {
                    SectionCard(title = TXT_SEC_BASIC) {
                        if (info == null) {
                            Text(
                                text = TXT_NOT_COLLECTED,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            KeyValueGrid(items = basicItems(info))
                        }
                    }
                }

                item {
                    SectionCard(title = TXT_SEC_RESOURCE) {
                        ResourceSection(info = info)
                    }
                }

                item {
                    SectionCard(title = TXT_SEC_DISPLAY) {
                        KeyValueGrid(items = displayItems(info))
                    }
                }

                item {
                    SectionCard(title = TXT_SEC_QUICK) {
                        QuickCommands(onConsole = { onConsole(serial) })
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            // 底部行动栏（★ 两层 Button Group + Split Button）
            DetailActionBar(
                onFiles = { onFiles(serial) },
                onConsole = { onConsole(serial) },
                onMirror = { onMirror(serial) },
                onDisconnect = { showDisconnectConfirm = true },
            )
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(TXT_DISCONNECT_TITLE) },
            text = { Text(TXT_DISCONNECT_BODY) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisconnectConfirm = false
                        vm.disconnect()
                    },
                ) {
                    Text(TXT_CONFIRM, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
}

// ---------------------------------------------------------------- 卡片内容

@Composable
private fun ResourceSection(info: DeviceInfo?) {
    if (info == null) {
        Text(
            text = TXT_NOT_COLLECTED,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpuRing(
            percent = info.cpuUsagePercent,
            label = orUnknown(info.cpuName),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$TXT_LABEL_CPU · ${info.cpuCores?.let { "$it 核" } ?: "核数未知"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            MemoryBar(
                usedKb = info.memTotalKb?.let { total ->
                    total - (info.memAvailableKb ?: 0L)
                },
                totalKb = info.memTotalKb,
            )
            if (info.memUsedPercent != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "已用 ${info.memUsedPercent?.toInt() ?: 0}% · 可用 ${formatKb(info.memAvailableKb)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (info.topProcesses.isNotEmpty()) {
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = TXT_TOP_PROC,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        for (proc in info.topProcesses.take(5)) {
            TopProcessRow(proc = proc)
        }
    }
}

/**
 * ★ Carousel（multi-browse）：6 条快捷命令横向排布，一屏只露出 2.5 项，
 * 用「露出一半」暗示右侧还能滑（规格 §1.6）。
 *
 * ⚠️ 交互事实：chip 点击**只做 `onConsole(serial)` 跳转，不预填命令**，
 * 原型与实现都不应表现为「把命令填进输入框」。
 */
@Composable
private fun QuickCommands(onConsole: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val itemWidth: Dp = (maxWidth - 24.dp) / 2.5f
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((label, danger) in QUICK_COMMANDS) {
                QuickChip(
                    text = label,
                    onClick = onConsole,
                    danger = danger,
                    modifier = Modifier.width(itemWidth),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 数据映射

private fun basicItems(info: DeviceInfo?): List<Pair<String, String>> {
    if (info == null) return emptyList()
    return listOf(
        TXT_LABEL_SERIAL to orUnknown(info.serial),
        TXT_LABEL_MANUFACTURER to orUnknown(info.manufacturer ?: info.brand),
        TXT_LABEL_MODEL to orUnknown(info.model),
        TXT_LABEL_DEVICE to orUnknown(info.deviceCode),
        TXT_LABEL_ANDROID to orUnknown(info.androidVersion),
        TXT_LABEL_SDK to orUnknown(info.sdkInt?.toString()),
        TXT_LABEL_BUILD to orUnknown(info.romBuildId),
        TXT_LABEL_PATCH to orUnknown(info.securityPatch),
        TXT_LABEL_CPU to orUnknown(info.cpuName),
        TXT_LABEL_ABI to orUnknown(info.abi),
        TXT_LABEL_CORES to orUnknown(info.cpuCores?.toString()),
        TXT_LABEL_FINGERPRINT to orUnknown(info.fingerprint),
    )
}

private fun displayItems(info: DeviceInfo?): List<Pair<String, String>> {
    if (info == null) return emptyList()
    val resolution: String = if (info.screenW != null && info.screenH != null) {
        val dpi = info.densityDpi?.let { " @ ${it}dpi" } ?: ""
        "${info.screenW} × ${info.screenH}$dpi"
    } else {
        "未知"
    }
    val screen: String = when (info.screenOn) {
        true -> TXT_SCREEN_ON
        false -> TXT_SCREEN_OFF
        null -> "未知"
    }
    val battery: String = info.batteryLevel?.let { "$it%" } ?: "未知"
    val storage: String = if (info.storageUsedPercent != null) {
        "已用 ${info.storageUsedPercent?.toInt() ?: 0}% · 剩 ${formatKb(freeStorageKb(info))}"
    } else {
        "未知"
    }
    val load: String = if (info.loadAvg.isNotEmpty()) {
        info.loadAvg.joinToString(" / ") { String.format("%.2f", it) }
    } else {
        "未知"
    }
    return listOf(
        TXT_LABEL_RESOLUTION to resolution,
        TXT_LABEL_SCREEN to screen,
        TXT_LABEL_BATTERY to battery,
        TXT_LABEL_STORAGE to storage,
        TXT_LABEL_UPTIME to formatUptime(info.uptimeSeconds),
        TXT_LABEL_KERNEL to orUnknown(info.kernelVersion),
        TXT_LABEL_IP to orUnknown(info.ipAddress),
        TXT_LABEL_LOAD to load,
    )
}

private fun freeStorageKb(info: DeviceInfo): Long? {
    val total = info.storageTotalKb ?: return null
    val usedPercent = info.storageUsedPercent ?: return null
    val free = total.toDouble() * (1.0 - (usedPercent.toDouble() / 100.0))
    return free.toLong().coerceAtLeast(0L)
}

private fun stripStateOf(state: DeviceState?): ConnectionState = when (state) {
    DeviceState.DEVICE -> ConnectionState.CONNECTED
    DeviceState.UNAUTHORIZED -> ConnectionState.WARNING
    DeviceState.OFFLINE -> ConnectionState.DISCONNECTED
    DeviceState.UNKNOWN -> ConnectionState.RECONNECTING
    null -> ConnectionState.RECONNECTING
}

private fun stripTextOf(info: DeviceInfo?, state: DeviceState?): String {
    val suffix = if (info != null) TXT_REFRESHED else TXT_NOT_COLLECTED
    return when (state) {
        DeviceState.DEVICE -> "已连接 · $suffix"
        DeviceState.UNAUTHORIZED -> "被控端未授权，请在被控端点「允许无线调试」"
        DeviceState.OFFLINE -> "已断开 · $suffix"
        DeviceState.UNKNOWN -> "正在获取连接状态 · $suffix"
        null -> "正在获取连接状态 · $suffix"
    }
}

/** 把秒格式化为「3 天 7 小时」这类可读文案。 */
private fun formatUptime(seconds: Long?): String {
    if (seconds == null || seconds < 0L) return "未知"
    val totalMinutes = seconds / 60L
    val days = totalMinutes / (60L * 24L)
    val hours = (totalMinutes % (60L * 24L)) / 60L
    val minutes = totalMinutes % 60L
    return when {
        days > 0L -> "$days 天 $hours 小时"
        hours > 0L -> "$hours 小时 $minutes 分"
        else -> "$minutes 分钟"
    }
}

// ---------------------------------------------------------------- 顶栏 / 底栏

/**
 * ★ Flexible Top App Bar（带副标题，规格 §1.6 / §4.2 区块 1）。
 *
 * 未滚动：直角、与页面同色、标题 32 sp、副标题（serial）完全不透明；
 * 滚动后：长出 28 dp 圆角悬浮容器，标题缩到 22 sp，副标题**淡出**（保留占位，避免高度跳动）。
 */
@Composable
private fun FlexibleDetailTopAppBar(
    collapseFraction: Float,
    title: String,
    subtitle: String,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val f: Float = collapseFraction.coerceIn(0f, 1f)
    // ★ spring 是欠阻尼的（dampingRatio 0.8）：目标值从 12dp 回到 0dp 时会**下冲到负值**。
    //   负值喂给 Modifier.padding 会直接抛
    //   IllegalArgumentException: Padding must be non-negative（真机滑动顶栏已复现崩溃）。
    //   所以动画值一律先夹到非负再用。
    val cornerRaw by animateDpAsState(
        targetValue = lerp(0.dp, Radius.Xl2, f),
        animationSpec = Motion.springDefault(),
        label = "detailAppBarCorner",
    )
    val corner: Dp = maxOf(0.dp, cornerRaw)
    // ★ 顶栏高度固定为 88 dp，不参与形变（Bug 2）。
    //
    // 这里原先做 88 → 64 dp 的高度动画，而 M3 Scaffold 的语义是「顶栏自己吃掉系统栏 inset，
    // content 的 padding.top 直接等于顶栏实测高度」。于是形成一个测量 ↔ 状态的回环：
    //   高度动画（本行）
    //   → Scaffold 重新测量 content，padding.top 变，LazyColumn 视口高度变（本文件 L214-217）
    //   → 视口变小而内容已滚到底时，LazyList 必须回锚 firstVisibleItemScrollOffset
    //   → collapseFraction 变（本文件 L162-171 正读这个值）
    //   → 高度动画重设目标 → 回到第一步
    // 详情页内容通常撑不满一屏、高度差又是三页里最大的 24 dp，所以最先在这页炸出来。
    // 圆角 / 字号 / 副标题淡出都不影响 content 的测量高度，保留；只有高度退出形变。
    val barHeight: Dp = TOP_BAR_HEIGHT_DETAIL
    val outerPaddingRaw by animateDpAsState(
        targetValue = lerp(0.dp, 12.dp, f),
        animationSpec = Motion.springDefault(),
        label = "detailAppBarPadding",
    )
    // 同上：spring 下冲会让它变成 -0.2dp，Modifier.padding 不接受负值。
    val outerPadding: Dp = maxOf(0.dp, outerPaddingRaw)
    val containerColor = if (f > 0.01f) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = outerPadding)
            // ★ Bug 1：本工程 targetSdk = 35，Android 15 起强制 edge-to-edge。
            //   M3 原生 TopAppBar 内部自带这层避让，换成自定义 Surface 后必须自己补回来，
            //   否则顶栏直接画到状态栏底下。
            //   放在 height() **之前**：padding 包住 height，顶栏总高 = 状态栏 + barHeight，
            //   barHeight 仍然只是内容高度，内容区不会被压扁。
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(barHeight),
        shape = RoundedCornerShape(corner),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = lerp(4.dp, 8.dp, f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(AppIcon.Back, contentDescription = TXT_BACK)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = lerp(32.sp, 22.sp, f),
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(1f - f),
                )
            }
            if (refreshing) {
                // 规格 §1.5.4：刷新中用波浪指示器，不再转圈
                WavyProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(AppIcon.Refresh, contentDescription = TXT_REFRESH)
                }
            }
        }
    }
}

/**
 * 文件管理图标（24 dp 线性风格，画法与 [AppIcon] 完全一致）。
 *
 * ⚠️ 为什么**不放进** `ui/theme/AppIcons.kt` 的 [AppIcon]：文件浏览页由另一位工程师并行开发，
 * 他也极可能需要一个文件夹图标并往 AppIcon 里加 `Folder`，两处同名声明会直接编译失败。
 * 这里做成**本文件私有**，零冲突；等对方落地后若要统一，再把这一个 val 搬进 AppIcon 即可。
 */
private val IconFolder: ImageVector = ImageVector.Builder(
    name = "Folder",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).path(
    fill = null,
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 2f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round
) {
    // 左上凸出的标签页 + 主体圆角矩形，一条闭合路径画完
    moveTo(3f, 8.5f)
    lineTo(9f, 8.5f)
    lineTo(10.8f, 10.5f)
    lineTo(19f, 10.5f)
    arcTo(2f, 2f, 0f, false, true, 21f, 12.5f)
    lineTo(21f, 18f)
    arcTo(2f, 2f, 0f, false, true, 19f, 20f)
    lineTo(5f, 20f)
    arcTo(2f, 2f, 0f, false, true, 3f, 18f)
    lineTo(3f, 8.5f)
    close()
}.build()

/**
 * ★ 底部操作栏（规格 §4.2.1 区块 7）：**两层** Button Group。
 *
 * - 第一层：「开始投屏」独占整行，**整块就是一个按钮** —— primary 实底、整行可点、
 *   内部只有「播放图标 + 文案」。⚠️ **不得再加任何元素**（没有 Split Button 的箭头区、
 *   没有尾部图标、没有次要热区）：想在按钮里再画一枚元素之前，先确认 ViewModel
 *   里真有对应的回调，否则做出来的就是「画得出来点不动」的控件。
 * - 第二层：文件管理 / 命令行 / 断开，三个等宽按钮；「断开」用 error 色文字。
 *
 * 第二层装进一个 28 dp 圆角 [Surface]（`surfaceContainerLow`），两层纵向 `spacedBy(8.dp)`，
 * 整体再让出 `horizontal = 12.dp, vertical = 8.dp` 的外边距。
 */
@Composable
private fun DetailActionBar(
    onFiles: () -> Unit,
    onConsole: () -> Unit,
    onMirror: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 第一层：开始投屏（独占整行） ----
        // ⚠️ 整块就是一个按钮，**不挂任何附加元素**（没有 Split Button 的箭头区、
        // 没有尾部图标、没有次要热区）。代码只有一个 `onMirror`，没有参数菜单，
        // 画在里面的第二枚元素必然是「看得见点不动」的假控件。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ACTION_ROW_HEIGHT)
                // clip 放在 background / clickable **之前**：连水波纹一起按 28 dp 圆角裁掉，
                // 否则直角矩形的涟漪会从圆角外溢出来
                .clip(Shape2xl)
                .background(color = MaterialTheme.colorScheme.primary)
                .clickable { onMirror() },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    AppIcon.Play,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = TXT_MIRROR,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                )
            }
        }

        // ---- 第二层：文件管理 / 命令行 / 断开 ----
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = Shape2xl,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ACTION_ROW_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionCell(
                    icon = IconFolder,
                    text = TXT_FILES,
                    onClick = onFiles,
                    modifier = Modifier.weight(1f),
                )
                ActionCell(
                    icon = AppIcon.Info,
                    text = TXT_CONSOLE,
                    onClick = onConsole,
                    modifier = Modifier.weight(1f),
                )
                ActionCell(
                    icon = null,
                    text = TXT_DISCONNECT,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDisconnect,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 第二层里的一个等宽按钮格：可选图标 + 文字，整格可点。
 *
 * @param icon 传 null 表示只要文字（如「断开」）。
 * @param tint 图标与文字统一取色，默认跟随 [Surface] 的 contentColor。
 */
@Composable
private fun ActionCell(
    icon: ImageVector?,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = tint,
                maxLines = 1,
            )
        }
    }
}
