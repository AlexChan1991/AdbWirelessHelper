package com.adb.adbwirelesshelper.ui.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import com.adb.adbwirelesshelper.data.discovery.DiscoveryRepository
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.adb.adbwirelesshelper.domain.model.AdbDevice
import com.adb.adbwirelesshelper.domain.model.DiscoveredService
import com.adb.adbwirelesshelper.domain.model.HistoryDevice
import com.adb.adbwirelesshelper.domain.model.deviceAliasKey
import com.adb.adbwirelesshelper.domain.model.serialToHost
import com.adb.adbwirelesshelper.ui.components.ConnectionState
import com.adb.adbwirelesshelper.ui.components.DeviceCard
import com.adb.adbwirelesshelper.ui.components.DeviceStateChip
import com.adb.adbwirelesshelper.ui.components.EmptyHint
import com.adb.adbwirelesshelper.ui.components.ErrorBanner
import com.adb.adbwirelesshelper.ui.components.StatusStrip
import com.adb.adbwirelesshelper.ui.components.WavyProgressIndicator
import com.adb.adbwirelesshelper.ui.theme.AppIcon
import com.adb.adbwirelesshelper.ui.theme.Motion
import com.adb.adbwirelesshelper.ui.theme.Radius
import com.adb.adbwirelesshelper.ui.theme.Shape2xl
import com.adb.adbwirelesshelper.ui.theme.ShapeLg
import com.adb.adbwirelesshelper.ui.theme.ShapeMd

// ---------------------------------------------------------------- 文案常量

private const val TXT_TITLE = "附近设备"
private const val TXT_SCAN = "开始扫描"
private const val TXT_STOP = "停止扫描"
private const val TXT_KNOWN = "已知设备"
private const val TXT_SETTINGS = "设置"
private const val TXT_GROUP_AVAILABLE = "可用设备 · 发现结果"
private const val TXT_GROUP_KNOWN = "已知设备"
private const val TXT_MANUAL = "手动添加 IP:端口"
private const val TXT_MANUAL_TITLE = "手动添加设备"
private const val TXT_MANUAL_HINT = "192.168.1.42:39621"
private const val TXT_MANUAL_TIP = "组播被路由器过滤时可用。此处应填「连接端口」，不是配对端口。"
private const val TXT_ADD = "添加并连接"
private const val TXT_CANCEL = "取消"
private const val TXT_EMPTY = "未发现设备？\n请检查被控端是否已开启「开发者选项 → 无线调试」，并与本机处于同一 Wi-Fi。"
private const val TXT_EMPTY_ACTION = "手动输入"
private const val TXT_DISCONNECT = "断开"
private const val TXT_PAIR_TITLE = "需要配对"
private const val TXT_GUIDE_TITLE = "在被控端获取配对码（三步）"
private const val TXT_GUIDE_1 = "设置 → 开发者选项 → 无线调试"
private const val TXT_GUIDE_2 = "点击「使用配对码配对设备」"
private const val TXT_GUIDE_3 = "记下显示的 6 位数字，约 1 分钟内有效"
private const val TXT_PAIR_PORT_LABEL = "配对端口（≠ 连接端口）"
private const val TXT_CODE_LABEL = "配对码"
private const val TXT_PAIR_ACTION = "配对"
private const val TXT_SCANNING_PREFIX = "正在扫描"
private const val TXT_PAIRING = "配对中…"

// 历史设备分组
private const val TXT_GROUP_HISTORY = "历史设备 · 点击直接连接"
private const val TXT_HISTORY_CLEAR = "清空"
private const val TXT_HISTORY_CLEAR_TITLE = "清空历史设备"
private const val TXT_HISTORY_CLEAR_BODY_PREFIX = "将删除全部 "
private const val TXT_HISTORY_CLEAR_BODY_SUFFIX = " 条历史连接记录，且无法恢复。已连接的设备不受影响。"
private const val TXT_HISTORY_EMPTY = "还没有历史记录。连接过的设备会自动出现在这里，下次打开 App 可直接点击连接。"
private const val TXT_HISTORY_DELETE = "删除这条历史记录"
private const val TXT_HISTORY_LAST_PREFIX = "上次连接 · "
private const val TXT_HISTORY_JUST_NOW = "刚刚"
private const val TXT_KNOWN_NONE = "暂无已连接设备"

// 设备别名（长按「已知设备」/「历史设备」条目 → 编辑别名）
private const val TXT_ALIAS_TITLE = "设置设备别名"
private const val TXT_ALIAS_IDENTITY_PREFIX = "设备标识："
private const val TXT_ALIAS_LABEL = "别名"
private const val TXT_ALIAS_TIP = "保存后，这台设备下次出现时会自动显示这个名字（无线调试端口变化也不受影响）。"
private const val TXT_ALIAS_SAVE = "保存"
/** 「移除别名」只清名字，**不动**历史记录；与上面的「删除这条历史记录」是两件事。 */
private const val TXT_ALIAS_REMOVE = "移除别名（不清历史记录）"
private const val TXT_ALIAS_MARK = "已设置别名"
/** 长按条目的语义标签（TalkBack 用；M3 的 Card(onClick) 原本会给 Button 角色，不能丢） */
private const val TXT_ALIAS_EDIT_LABEL = "编辑别名"
private const val TXT_KNOWN_OPEN_LABEL = "打开设备详情"
private const val TXT_HISTORY_CONNECT_LABEL = "连接该设备"

/** 副标题里各段的连接符。 */
private const val ALIAS_JOIN_SEP = " · "

/** 别名最大长度。M3 的 TextField 没有 maxLength 属性，只能在 onValueChange 里截断。 */
private const val ALIAS_MAX_LEN: Int = 32

/**
 * 按最多 [max] 个 UTF-16 码元截断，**但不把代理对截成半个**。
 *
 * `String.take(n)` 按码元计数，而 emoji 占 2 个码元：第 32 个码元可能正好落在某个
 * emoji 的高位代理上。孤立代理本身不崩溃（Kotlin String 装得下），但落盘时走的
 * `String.toByteArray(Charsets.UTF_8)` 会把孤立代理**静默**编成 `?` ——
 * 用户的 emoji 永久变成问号，且全程不报错。发现末位是高位代理就整个丢掉。
 */
private fun String.truncateForAlias(max: Int): String {
    if (max <= 0 || length <= max) {
        return this
    }
    val cut: String = take(max)
    return if (cut[max - 1].isHighSurrogate()) cut.dropLast(1) else cut
}

// ---------------------------------------------------------------- 页面

/**
 * 设备发现列表页（对应原型图 ①）。
 *
 * Material 3 Expressive 改造点（设计规格 §4.1）：
 * - ★ M1：Flexible Top App Bar —— 滚动时容器圆角 0→28 dp、标题 32→22 sp、高度 64→56 dp；
 * - ★ 状态条圆点在扫描态走 Contingency 变形（由 [StatusStrip] 内部处理）；
 * - ★ 条目连接中用波浪形不定进度（[WavyProgressIndicator]）替代转圈；
 * - ★ 「＋ 手动添加」由全宽 Button 升级为 **Extended FAB**，按下时 28→16 dp 形变（M3），
 *   列表滚动时收缩为纯图标 FAB，并由 Scaffold 自动与 Snackbar 联动避让；
 * - ★ 「清空历史」二次确认由居中 AlertDialog 改为底部 Sheet（3xl 顶部圆角）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeviceListScreen(
    vm: DeviceListViewModel,
    onDevice: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val ui by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val clearSheetState = rememberModalBottomSheetState()
    var showManualAdd by remember { mutableStateOf(false) }
    var knownExpanded by remember { mutableStateOf(true) }
    var clearConfirming by remember { mutableStateOf(false) }
    // 长按条目 → 编辑别名。状态提升到这里，对话框与 Scaffold 同级渲染。
    var aliasTarget: AliasTarget? by remember { mutableStateOf(null) }

    // 「已知设备」分组已经列的（**任何状态**，含 offline / unauthorized）都不在历史里重复展示：
    // 同一台手机在上下两组各出一次，用户只会困惑该点哪一个。
    // 代价是 offline / unauthorized 的设备暂时点不到「重连」——但它在「已知设备」里本来就有
    // 「断开」按钮，断开后 adb 列表里就没它了，历史条目随即回来，届时点击即连。
    //
    // ★ 必须走 serialToHost（与历史库同一个函数）：这里曾经用 substringBefore(':')，
    //   IPv6 设备的 serial `[::1]:5555` 会被切成 `[`，比不上历史条目的 host `[::1]`，
    //   于是同一台 IPv6 设备在上下两组各出现一次（去重失效）。
    val visibleHistory: List<HistoryDevice> = remember(ui.knownDevices, ui.historyDevices) {
        val knownHosts: Set<String> = ui.knownDevices
            .asSequence()
            .map { serialToHost(it.serial) }
            .toSet()
        ui.historyDevices.filter { it.host !in knownHosts }
    }

    // ★ M1 形变进度：列表向下滚过 48 dp 的过程中 0 → 1。
    // 只依赖 LazyListState 的滚动量，不依赖 AppBar 自身高度，因此不会和「高度动画」互相打架。
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
    // FAB 在滚过一半时收成纯图标
    val fabExpanded: Boolean by remember { derivedStateOf { collapseFraction < 0.5f } }

    // 首次进入自动发起一轮发现
    LaunchedEffect(Unit) {
        if (!ui.scanning) vm.startScan()
    }

    // 一次性提示
    LaunchedEffect(ui.message) {
        val message = ui.message
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            vm.clearError()
        }
    }

    // 导航事件（连接 / 配对成功后跳详情）
    LaunchedEffect(ui.navigateTo) {
        val serial = ui.navigateTo
        if (!serial.isNullOrBlank()) {
            vm.consumeNavigation()
            onDevice(serial)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            FlexibleTopAppBar(
                collapseFraction = collapseFraction,
                scanning = ui.scanning,
                knownExpanded = knownExpanded,
                onToggleScan = { if (ui.scanning) vm.stopScan() else vm.startScan() },
                onToggleKnown = { knownExpanded = !knownExpanded },
                onSettings = onSettings,
            )
        },
        floatingActionButton = {
            AddDeviceFab(expanded = fabExpanded, onClick = { showManualAdd = true })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 状态条
            // ⚠️ mode 现在是**三态组合**："mDNS" / "端口扫描" / "mDNS + 端口扫描"（两条路径并行）。
            //    判断是否涉及端口扫描不能用等值比较，否则并行阶段会漏掉橙色提示。
            val portScanInvolved: Boolean = ui.mode.contains(DiscoveryRepository.MODE_PORT_SCAN)
            val stripState: ConnectionState = when {
                !ui.scanning && ui.mode == DiscoveryRepository.MODE_STOPPED -> ConnectionState.DISCONNECTED
                portScanInvolved -> ConnectionState.WARNING
                else -> ConnectionState.SCANNING
            }
            val stripText: String = when {
                !ui.scanning -> "扫描已停止 · 共 ${ui.candidates.size} 台"
                ui.mode == DiscoveryRepository.MODE_BOTH ->
                    "$TXT_SCANNING_PREFIX（mDNS + 端口扫描并行，避免组播漏设备）· 已发现 ${ui.candidates.size} 台"

                ui.mode == DiscoveryRepository.MODE_PORT_SCAN ->
                    "$TXT_SCANNING_PREFIX（端口扫描中，组播可能被过滤）· 已发现 ${ui.candidates.size} 台"

                else -> "$TXT_SCANNING_PREFIX（${ui.mode}）· 已发现 ${ui.candidates.size} 台"
            }
            StatusStrip(state = stripState, text = stripText)

            if (!ui.error.isNullOrBlank()) {
                ErrorBanner(
                    text = ui.error ?: "",
                    // 规格 §4.1.1：这里的回调只做 clearError()，文案必须是「知道了」
                    onAction = { vm.clearError() },
                )
            }

            // 列表
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                item { GroupHeader(title = TXT_GROUP_AVAILABLE) }

                if (ui.candidates.isEmpty()) {
                    item {
                        EmptyHint(
                            text = TXT_EMPTY,
                            actionLabel = TXT_EMPTY_ACTION,
                            onAction = { showManualAdd = true },
                        )
                    }
                } else {
                    items(items = ui.candidates, key = { it.host }) { service ->
                        DeviceCard(
                            svc = service,
                            connecting = ui.connectingHost == service.host,
                            onClick = { vm.onDeviceClick(service) },
                            modifier = Modifier.animateItem(placementSpec = Motion.springDefault()),
                        )
                    }
                }

                item { GroupHeader(title = TXT_GROUP_KNOWN, count = ui.knownDevices.size) }

                if (knownExpanded && ui.knownDevices.isNotEmpty()) {
                    items(items = ui.knownDevices, key = { it.serial }) { device ->
                        KnownDeviceRow(
                            device = device,
                            // 按稳定标识查别名：无线设备换端口后仍然命中
                            alias = ui.aliases[deviceAliasKey(device.serial)]?.takeIf { it.isNotBlank() },
                            connecting = ui.connectingHost == device.serial,
                            onClick = { vm.onKnownDeviceClick(device.serial) },
                            onLongClick = { aliasTarget = AliasTarget.Known(device.serial) },
                            onDisconnect = { vm.disconnect(device.serial) },
                            modifier = Modifier.animateItem(placementSpec = Motion.springDefault()),
                        )
                    }
                } else if (knownExpanded) {
                    item { GroupEmptyText(text = TXT_KNOWN_NONE) }
                }

                // ---- 历史设备（持久化，App 冷启动即显示；点击直接连接）----
                item {
                    GroupHeader(
                        title = TXT_GROUP_HISTORY,
                        count = visibleHistory.size,
                        action = if (visibleHistory.isNotEmpty()) {
                            {
                                TextButton(onClick = { clearConfirming = true }) {
                                    Text(
                                        text = TXT_HISTORY_CLEAR,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )
                }

                if (visibleHistory.isEmpty()) {
                    item { GroupEmptyText(text = TXT_HISTORY_EMPTY) }
                } else {
                    // key 前缀 "h:" —— 历史条目按 host 建键，可能和「已知设备」里
                    // 按 serial 建的键不同，加前缀避免 LazyColumn 撞 key 崩溃。
                    items(items = visibleHistory, key = { "h:${it.host}" }) { entry ->
                        HistoryDeviceRow(
                            entry = entry,
                            // 历史条目本来就按 host 去重，host 即别名主键
                            alias = ui.aliases[entry.host]?.takeIf { it.isNotBlank() },
                            connecting = ui.connectingHost == entry.host,
                            onClick = { vm.onHistoryClick(entry) },
                            onLongClick = { aliasTarget = AliasTarget.History(entry.host) },
                            onDelete = { vm.removeHistory(entry) },
                            modifier = Modifier.animateItem(placementSpec = Motion.springDefault()),
                        )
                    }
                }

                // 给底部悬浮 FAB 留出避让空间（56 dp FAB + 16 dp 边距 + 余量）
                item { Spacer(modifier = Modifier.height(88.dp)) }
            }
        }
    }

    // 手动添加对话框
    if (showManualAdd) {
        ManualAddDialog(
            onDismiss = { showManualAdd = false },
            onConfirm = { input ->
                showManualAdd = false
                vm.onManualAdd(input)
            },
        )
    }

    // 清空历史二次确认（不可撤销）—— M3E：居中对话框改为底部 Sheet
    if (clearConfirming) {
        ModalBottomSheet(
            onDismissRequest = { clearConfirming = false },
            sheetState = clearSheetState,
            // 规格 §1.3：底部 Sheet 顶部圆角 32 dp
            shape = RoundedCornerShape(topStart = Radius.Xl3, topEnd = Radius.Xl3),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = TXT_HISTORY_CLEAR_TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = TXT_HISTORY_CLEAR_BODY_PREFIX +
                        "${visibleHistory.size}" +
                        TXT_HISTORY_CLEAR_BODY_SUFFIX,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { clearConfirming = false }) { Text(TXT_CANCEL) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            clearConfirming = false
                            vm.clearHistory()
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(TXT_HISTORY_CLEAR)
                    }
                }
            }
        }
    }

    // 配对对话框
    val target = ui.pairTarget
    if (target != null) {
        PairDialog(
            target = target,
            pairing = ui.connectingHost == target.host,
            errorText = ui.error,
            onDismiss = { vm.dismissPairDialog() },
            onPair = { port, code -> vm.onPair(target.host, port, code) },
        )
    }

    // 别名编辑对话框（长按「已知设备」/「历史设备」条目）
    val editing: AliasTarget? = aliasTarget
    if (editing != null) {
        val (editKey: String, identity: String) = when (editing) {
            // 已知设备：键 = 稳定标识（去掉端口），展示 = 完整 serial
            is AliasTarget.Known -> deviceAliasKey(editing.serial) to editing.serial
            // 历史设备：键与展示都是 host（端口每次变，不展示）
            is AliasTarget.History -> editing.host to editing.host
        }
        AliasEditDialog(
            identity = identity,
            initialAlias = ui.aliases[editKey].orEmpty(),
            onDismiss = { aliasTarget = null },
            onSave = { value ->
                aliasTarget = null
                vm.setDeviceAlias(editKey, value)
            },
            onRemove = {
                aliasTarget = null
                vm.clearDeviceAlias(editKey)
            },
        )
    }
}

// ---------------------------------------------------------------- 顶栏 / FAB

/**
 * 列表页顶栏**内容**高度（不含状态栏）。
 *
 * 状态栏由 [FlexibleTopAppBar] 里的 `windowInsetsPadding(WindowInsets.statusBars)` 另外让位。
 */
private val TOP_BAR_HEIGHT_LIST: Dp = 64.dp

/**
 * ★ Flexible Top App Bar（规格 §1.3 M1）。
 *
 * 未滚动时：与页面同色、直角、标题 32 sp、内容高 64 dp —— 视觉上「没有」App Bar；
 * 滚动后：长出 28 dp 圆角的悬浮容器、缩进 12 dp、标题缩到 22 sp。
 *
 * ⚠️ 高度**不参与**形变：M3 Scaffold 把顶栏实测高度直接作为 content 的 padding.top，
 * 高度若随滚动动画，会和 LazyColumn 的视口 / 滚动偏移形成测量回环（详情页已因此崩溃）。
 */
@Composable
private fun FlexibleTopAppBar(
    collapseFraction: Float,
    scanning: Boolean,
    knownExpanded: Boolean,
    onToggleScan: () -> Unit,
    onToggleKnown: () -> Unit,
    onSettings: () -> Unit,
) {
    val f: Float = collapseFraction.coerceIn(0f, 1f)
    // ★ spring 欠阻尼下冲会产出负值，Modifier.padding 不接受（真机已在详情页复现崩溃）。
    val cornerRaw by animateDpAsState(
        targetValue = lerp(0.dp, Radius.Xl2, f),
        animationSpec = Motion.springDefault(),
        label = "appBarCorner",
    )
    val corner: Dp = maxOf(0.dp, cornerRaw)
    // ★ 顶栏高度固定为 64 dp，不参与形变。
    //   与详情页同因：顶栏高度会被 Scaffold 直接当成 content 的 padding.top，
    //   高度一旦随滚动动画，就形成「高度 → 视口 → 滚动偏移 → 折叠进度 → 高度」的回环。
    //   列表页高度差只有 8 dp、内容通常能撑满，所以不如详情页容易触发，但结构上是同一个隐患。
    val barHeight: Dp = TOP_BAR_HEIGHT_LIST
    val outerPaddingRaw by animateDpAsState(
        targetValue = lerp(0.dp, 12.dp, f),
        animationSpec = Motion.springDefault(),
        label = "appBarPadding",
    )
    // 同上：spring 下冲会让 padding 变成 -0.2dp → Padding must be non-negative。
    val outerPadding: Dp = maxOf(0.dp, outerPaddingRaw)
    val containerColor: androidx.compose.ui.graphics.Color = if (f > 0.01f) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = outerPadding)
            // ★ Bug 1：targetSdk = 35 + Android 15 强制 edge-to-edge，自定义顶栏必须自己避让状态栏。
            //   放在 height() **之前** → 顶栏总高 = 状态栏 + barHeight，内容区不被压扁。
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(barHeight),
        shape = RoundedCornerShape(corner),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = lerp(20.dp, 12.dp, f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = TXT_TITLE,
                // 规格 §1.4：Top App Bar 大标题 32 sp → 22 sp，滚动缩放
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = lerp(32.sp, 22.sp, f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = lerp((-0.5).sp, 0.sp, f),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleScan) {
                Icon(
                    if (scanning) AppIcon.Stop else AppIcon.Search,
                    contentDescription = if (scanning) TXT_STOP else TXT_SCAN,
                )
            }
            IconButton(onClick = onToggleKnown) {
                Icon(
                    AppIcon.Bookmark,
                    contentDescription = TXT_KNOWN,
                    tint = if (knownExpanded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onSettings) {
                Icon(AppIcon.Tune, contentDescription = TXT_SETTINGS)
            }
        }
    }
}

/**
 * ★ Extended FAB（规格 §1.6 / §4.1 区块 10）。
 *
 * @param expanded 列表滚动时收成纯图标 FAB，回到顶部再展开成「图标 + 文字」。
 *                 按压时圆角 28 → 16 dp（M3 形变），松开用 SpatialFast 回弹。
 */
@Composable
private fun AddDeviceFab(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) Radius.Lg else Radius.Xl2,
        animationSpec = Motion.springFast(),
        label = "fabMorph",
    )

    // 注意：material3 1.7 里带 `expanded` 的重载要求显式传 text/icon（不是尾随 lambda 的
    // RowScope 版本，后者没有 expanded 参数，写错会落到「按下无收缩」的旧形态）。
    ExtendedFloatingActionButton(
        text = { Text(text = TXT_MANUAL, fontWeight = FontWeight.SemiBold) },
        icon = { Icon(AppIcon.Add, contentDescription = null) },
        onClick = onClick,
        modifier = Modifier.padding(bottom = 16.dp),
        expanded = expanded,
        shape = RoundedCornerShape(corner),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        interactionSource = interactionSource,
    )
}

// ---------------------------------------------------------------- 分组标题 / 空态

/** 分组标题行：左侧标题，右侧可选「N 台」与行动按钮（规格 §4.1：高 40 dp、600 字重、正字距）。 */
@Composable
private fun GroupHeader(
    title: String,
    count: Int? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Text(
                text = "$count 台",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) {
            Spacer(modifier = Modifier.width(4.dp))
            action()
        }
    }
}

/** 分组内的一行灰字空态。 */
@Composable
private fun GroupEmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
    )
}

// ---------------------------------------------------------------- 别名

/**
 * 条目标题行。
 *
 * ★ 「有别名」的视觉标识：标题前加一支 12 dp 的小铅笔（primary 色），
 *   让用户一眼看出「这个名字是我自己改的」，而不是 adb 报上来的原始型号。
 *
 * 为什么用图标而不是把标题染成 primary：
 *  - 形状差异 > 颜色差异：色觉障碍用户、深色主题下 primary 的高亮度都让颜色方案不可靠；
 *  - 顺带解决可发现性 —— 铅笔本身就是「这里能改名字」的提示，
 *    否则「长按可以改别名」这件事没有任何入口暗示。
 *
 * @param aliased 该条目是否设了别名
 */
@Composable
private fun AliasTitle(
    text: String,
    aliased: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (aliased) {
            Icon(
                imageVector = AppIcon.Edit,
                contentDescription = TXT_ALIAS_MARK,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // weight 让 Row 撑满父列，过长标题才会省略号而不是把右侧按钮挤出去
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 别名编辑的目标。密封类而不是两个 nullable 字段：
 * 「改哪一台」只有这两种来源，编译期就排除掉「两个都为 null / 都非 null」的状态。
 */
private sealed class AliasTarget {
    /** 「已知设备」里的一台：键由 serial 现算（[deviceAliasKey]）。 */
    data class Known(val serial: String) : AliasTarget()

    /** 「历史设备」里的一条：键就是 [HistoryDevice.host]。 */
    data class History(val host: String) : AliasTarget()
}

/**
 * 别名编辑对话框（骨架与 [ManualAddDialog] 一致：Dialog + 2xl Surface）。
 *
 * @param identity     真实标识（serial / host），让用户在改名时知道自己改的是哪一台
 * @param initialAlias 当前别名；非空时才会显示「移除别名」
 * @param onSave       回调的是 **trim 后**的原文；空白由 ViewModel 走清除路径
 */
@Composable
private fun AliasEditDialog(
    identity: String,
    initialAlias: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var text by remember(identity) { mutableStateOf(initialAlias) }
    val trimmed: String = text.trim()
    val hasAlias: Boolean = initialAlias.isNotBlank()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            // 规格 §1.3：对话框 28 dp
            shape = Shape2xl,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = TXT_ALIAS_TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 真实标识：改名前确认自己改的是哪一台（端口会变，所以历史条目只展示 host）
                Text(
                    text = TXT_ALIAS_IDENTITY_PREFIX + identity,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    // M3 的 TextField 没有 maxLength，只能在这里截断
                    onValueChange = { input -> text = input.truncateForAlias(ALIAS_MAX_LEN) },
                    label = { Text(TXT_ALIAS_LABEL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = TXT_ALIAS_TIP,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasAlias) {
                    Spacer(modifier = Modifier.height(2.dp))
                    TextButton(onClick = onRemove) {
                        Text(
                            text = TXT_ALIAS_REMOVE,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(TXT_CANCEL) }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onSave(trimmed) }) {
                        Text(TXT_ALIAS_SAVE, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 已知设备行

/**
 * 「已知设备」分组里的一台。
 *
 * 点击 → 进详情页；**长按** → 改别名；右侧「断开」独占一个按钮，不会误触
 * （外层 [Modifier.combinedClickable] 与内部 TextButton 是两个独立的可点击区域，
 * Compose 会优先派发给更内层）。
 *
 * @param alias      这台设备的别名（调用方已按 [deviceAliasKey] 查好）；null = 没设过
 * @param connecting 正在重连这一台时显示波浪指示器，并临时禁用整行，避免连点堆叠 `adb connect`
 * @param onLongClick 长按：打开别名编辑对话框
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KnownDeviceRow(
    device: AdbDevice,
    alias: String?,
    connecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 展示优先级：别名 > 型号 > serial
    val model: String? = device.model?.takeIf { it.isNotBlank() }
    val title: String = alias ?: (model ?: device.serial)
    // 副标题：只有在「标题被别名顶掉了」时才把型号补回来。
    // 没别名时标题本身就是型号，再拼一遍会变成「Pixel 7 / Pixel 7 · 192.168.1.5:37123」。
    val subtitle: String = buildList<String> {
        if (alias != null && model != null) {
            add(model)
        }
        add(device.serial)
    }.joinToString(ALIAS_JOIN_SEP)

    Card(
        // Card **不传 onClick**：点击与长按统一由 content 里的 combinedClickable 处理，
        // 两处都传会让一次点击被消费两次。外边距仍挂在 Card 上（卡片间距是布局属性）。
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        // 规格 §1.3 条目卡 16 dp / §1.2.2 条目卡容器 = surfaceContainer
        shape = ShapeLg,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            // ★ combinedClickable 必须挂在 Card 的 **content** 里，不能挂在 Card 的 modifier 上：
            //   M3 的 Card(无 onClick) 内部就是 Surface，它会把 shadow → border → background → clip
            //   追加在 Card 的 modifier **之后**。挂在 Card modifier 上的 clickable 落在
            //   background **之前** → 按下时水波纹被不透明的 surfaceContainer 完全盖住，
            //   而且不被 16 dp 圆角裁切（圆角外面露出直角）。放进 content 就在 background+clip 之后。
            //   padding 放在 combinedClickable **之后** → 可点区域 = 整张卡片本体。
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = !connecting,
                    role = Role.Button,
                    onClickLabel = TXT_KNOWN_OPEN_LABEL,
                    onLongClickLabel = TXT_ALIAS_EDIT_LABEL,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AliasTitle(text = title, aliased = alias != null)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (connecting) {
                // 规格 §1.5.4：条目内波浪指示器 20 dp
                WavyProgressIndicator()
                Spacer(modifier = Modifier.width(6.dp))
            }
            DeviceStateChip(state = device.state)
            Spacer(modifier = Modifier.width(6.dp))
            TextButton(onClick = onDisconnect) {
                Text(text = TXT_DISCONNECT, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ---------------------------------------------------------------- 历史设备行

/**
 * 一条历史设备。
 *
 * 点击 → 直接连接并跳转；**长按** → 改别名；右侧垃圾桶 → 只删这条记录
 * （不影响已建立的连接，也不影响已设的别名）。
 *
 * @param alias      这条记录的别名（调用方已按 [HistoryDevice.host] 查好）；null = 没设过
 * @param connecting 正在连这一条时显示波浪指示器，同时整行禁用，避免连点触发多次 `adb connect`
 * @param onLongClick 长按：打开别名编辑对话框
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryDeviceRow(
    entry: HistoryDevice,
    alias: String?,
    connecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 展示优先级：别名 > label（型号）> hostPort
    val title: String = alias ?: (entry.label?.takeIf { it.isNotBlank() } ?: entry.hostPort)

    Card(
        // 与 KnownDeviceRow 同因：Card 不传 onClick，外边距仍挂在 Card 上
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = ShapeLg,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            // 同 KnownDeviceRow：combinedClickable 放 content 内，才在 background+clip 之后，
            // 水波纹可见且被 16 dp 圆角裁切；padding 在其后，热区 = 整张卡片
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = !connecting,
                    role = Role.Button,
                    onClickLabel = TXT_HISTORY_CONNECT_LABEL,
                    onLongClickLabel = TXT_ALIAS_EDIT_LABEL,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AliasTitle(text = title, aliased = alias != null)
                Text(
                    text = entry.hostPort,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = TXT_HISTORY_LAST_PREFIX + lastSeenText(entry.lastConnectedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (connecting) {
                WavyProgressIndicator()
                Spacer(modifier = Modifier.width(6.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    AppIcon.Delete,
                    contentDescription = TXT_HISTORY_DELETE,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 把时间戳转成「刚刚 / N 分钟前 / N 小时前 / N 天前」。 */
private fun lastSeenText(timestamp: Long): String {
    val diffMs: Long = System.currentTimeMillis() - timestamp
    if (diffMs < 60_000L) {
        return TXT_HISTORY_JUST_NOW
    }
    val minutes: Long = diffMs / 60_000L
    if (minutes < 60) {
        return "$minutes 分钟前"
    }
    val hours: Long = minutes / 60
    if (hours < 24) {
        return "$hours 小时前"
    }
    val days: Long = hours / 24
    return "$days 天前"
}

// ---------------------------------------------------------------- 手动添加

@Composable
private fun ManualAddDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            // 规格 §1.3：对话框 28 dp
            shape = Shape2xl,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = TXT_MANUAL_TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(TXT_MANUAL_HINT) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = TXT_MANUAL_TIP,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(TXT_CANCEL) }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { if (text.isNotBlank()) onConfirm(text) },
                        enabled = text.isNotBlank(),
                    ) { Text(TXT_ADD) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 配对对话框

/**
 * 配对对话框（对应原型图 ②）：三步引导 + 配对端口 + 6 位配对码 + 分类错误文案。
 */
@Composable
private fun PairDialog(
    target: DiscoveredService,
    pairing: Boolean,
    errorText: String?,
    onDismiss: () -> Unit,
    onPair: (Int, String) -> Unit,
) {
    val defaultPort: String = (target.pairPort ?: target.port).toString()
    var portText by remember(target.host + defaultPort) { mutableStateOf(defaultPort) }
    var codeText by remember(target.host + defaultPort) { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val codeReady: Boolean = codeText.length == 6 && codeText.all { it.isDigit() }
    val portValue: Int? = portText.trim().toIntOrNull()

    Dialog(onDismissRequest = { if (!pairing) onDismiss() }) {
        Surface(
            shape = Shape2xl,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                // 标题
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = TXT_PAIR_TITLE,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${target.instanceName} · ${target.host}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { if (!pairing) onDismiss() }) {
                        Icon(AppIcon.Close, contentDescription = TXT_CANCEL)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 三步引导
                Surface(
                    shape = ShapeMd,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            text = TXT_GUIDE_TITLE,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "1. $TXT_GUIDE_1", style = MaterialTheme.typography.bodySmall)
                        Text(text = "2. $TXT_GUIDE_2", style = MaterialTheme.typography.bodySmall)
                        Text(text = "3. $TXT_GUIDE_3", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 配对端口
                OutlinedTextField(
                    value = portText,
                    onValueChange = { input -> portText = input.filter { it.isDigit() }.take(5) },
                    label = { Text(TXT_PAIR_PORT_LABEL) },
                    singleLine = true,
                    enabled = !pairing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 6 位配对码
                OutlinedTextField(
                    value = codeText,
                    onValueChange = { input ->
                        codeText = input.filter { it.isDigit() }.take(6)
                    },
                    label = { Text(TXT_CODE_LABEL) },
                    singleLine = true,
                    enabled = !pairing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )

                // 错误文案
                if (!errorText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        // 规格 §1.3：卡内嵌块 12 dp
                        shape = ShapeMd,
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                AppIcon.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = errorText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 操作
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pairing) {
                        // 规格 §1.5.4：波浪指示器替代转圈
                        WavyProgressIndicator()
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = TXT_PAIRING, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (!pairing) onDismiss() }, enabled = !pairing) {
                        Text(TXT_CANCEL)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val port = portValue
                            if (port != null && codeReady) onPair(port, codeText)
                        },
                        enabled = !pairing && codeReady && portValue != null,
                    ) {
                        Text(TXT_PAIR_ACTION, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
