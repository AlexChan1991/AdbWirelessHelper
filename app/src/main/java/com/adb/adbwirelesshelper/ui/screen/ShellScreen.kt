package com.adb.adbwirelesshelper.ui.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.adb.adbwirelesshelper.ui.theme.AppIcon
import com.adb.adbwirelesshelper.ui.theme.Motion
import com.adb.adbwirelesshelper.ui.theme.Radius
import com.adb.adbwirelesshelper.ui.theme.ShapeLg
import com.adb.adbwirelesshelper.ui.theme.ShapeMd
import com.adb.adbwirelesshelper.ui.theme.TerminalBackground
import com.adb.adbwirelesshelper.ui.theme.TerminalTextStyle
import kotlinx.coroutines.launch

/** ---------- 页面文案（集中在此，不新增 strings.xml 条目） ---------- */
private const val TEXT_TITLE: String = "命令行"
private const val TEXT_UNKNOWN_DEVICE: String = "未知设备"
private const val TEXT_TIMEOUT_PREFIX: String = "超时 "
private const val TEXT_PLACEHOLDER: String = "输入 adb shell 命令，如 getprop ro.product.model"
private const val TEXT_EXECUTE: String = "执行"
private const val TEXT_STOP: String = "停止"
private const val TEXT_MENU_EXPORT: String = "复制全部输出"
private const val TEXT_MENU_CLEAR: String = "清空输出"
private const val TEXT_MENU_TIMEOUT10: String = "超时 10 秒"
private const val TEXT_MENU_TIMEOUT30: String = "超时 30 秒"
private const val TEXT_MENU_TIMEOUT_NO: String = "超时无限制"
private const val TEXT_COPIED: String = "已复制全部输出"
private const val TEXT_PRESET_HINT: String = "预设命令"
private const val TEXT_PRESET_NOTE: String = "点击填入输入框；最前三条会改动设备状态，高危命令会先弹确认"
private const val TEXT_DANGER_TITLE: String = "高危命令确认"
private const val TEXT_DANGER_BODY: String =
    "该命令可能造成不可逆影响，请确认将被执行的完整命令全文："
private const val TEXT_DANGER_CONFIRM: String = "确认执行"
private const val TEXT_DANGER_CANCEL: String = "取消"
private const val TEXT_PROMPT: String = "$ "

/**
 * 高危确认框里的补充说明（规格 §4.5.1.1「配套表达」）。
 *
 * 命令行页与设置页是两处独立的入口，用户在设置页关掉「危险命令二次确认」后，
 * 这里依然会弹 —— 就地解释这个落差，避免用户以为 App 出了 bug。
 */
private const val TEXT_DANGER_LOCKED: String = "此确认无法通过设置关闭，危险命令必须逐条确认。"

/**
 * 终端行着色（规格 §1.2.4）。
 *
 * 这两枚 + `TerminalBackground`（终端底）合起来就是命令行页的全部专用色，
 * **深浅主题下都固定不变，故意不跟随 `colorScheme`** —— 终端就该是终端的样子，
 * 否则用户在深色主题下会遇到「终端比 App 还亮」的错乱感。
 */
private val TERMINAL_STDOUT: Color = Color(0xFFD6D6DC)
private val TERMINAL_COMMAND: Color = Color(0xFF8BC34A)

/**
 * 命令行页（对应原型图 ⑤，规格 §4.4）。
 *
 * - 顶部：★ Flexible Top App Bar（M1 形变）+ 返回 + 三点菜单 → **底部 Sheet**；
 * - 预设区：★ Scrollable Chip Group（横向滚动），命中高危的命令标红；
 * - 输出区：等宽字体 + 可选中复制，stderr 行按启发式判红，自动滚到底；
 * - 底部：命令输入行 + 执行/停止；
 * - 结果：Snackbar 显示「退出码 · 耗时」，高危命令弹 Dialog 展示完整命令全文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(
    vm: ShellViewModel,
    serial: String,
    onBack: () -> Unit
) {
    LaunchedEffect(serial) { vm.bind(serial) }

    val output: List<ShellLine> by vm.output.collectAsState()
    val running: Boolean by vm.running.collectAsState()
    val lastResult: String? by vm.lastResult.collectAsState()
    val pendingDanger: String? by vm.pendingDanger.collectAsState()
    val history: List<String> by vm.history.collectAsState()
    val timeoutMs: Long by vm.timeoutMs.collectAsState()

    var input: String by remember { mutableStateOf("") }
    var menuOpen: Boolean by remember { mutableStateOf(false) }
    var followOutput: Boolean by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()
    val menuSheetState = rememberModalBottomSheetState()
    val snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(lastResult) {
        val message: String = lastResult ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
    }

    LaunchedEffect(output.size) {
        if (followOutput && output.isNotEmpty()) {
            listState.scrollToItem(maxOf(0, output.lastIndex))
        }
    }

    // ★ M1 折叠信号（规格 §1.3 M1 / §1.6 Flexible Top App Bar）。
    //
    // 本页唯一可滚动的内容是终端输出区，而它会自动跟随到最新一行 —— 所以**不能**
    // 套用列表页那种「滚过若干像素 → 0→1」的判定：输出一旦超过一屏就会永久收起，
    // 形变失去意义。这里改用「用户主动向上翻阅历史输出」作为折叠信号：
    // 末行不在可视范围（= 离开了底部）→ 折叠；跟随到底部 → 展开。
    val scrolledUp: Boolean by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible: Int = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val lastIndex: Int = info.totalItemsCount - 1
            lastIndex > 0 && lastVisible >= 0 && lastVisible < lastIndex
        }
    }
    val collapseFraction: Float by animateFloatAsState(
        targetValue = if (scrolledUp) 1f else 0f,
        animationSpec = Motion.springDefault(),
        label = "shellAppBarCollapse"
    )

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            FlexibleShellTopAppBar(
                collapseFraction = collapseFraction,
                title = TEXT_TITLE,
                subtitle = deviceTitle(vm) + " · " + TEXT_TIMEOUT_PREFIX + vm.describeTimeout(timeoutMs),
                onBack = onBack,
                onMenu = { menuOpen = true }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ---- 预设命令：Scrollable Chip Group（§1.6） ----
            Text(
                text = TEXT_PRESET_HINT,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 2.dp)
            )
            Text(
                text = TEXT_PRESET_NOTE,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 2.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                vm.presets.forEach { preset: Preset ->
                    PresetChip(
                        preset = preset,
                        onClick = { input = preset.command }
                    )
                }
            }

            // ---- 终端输出区（底色 / 行色均为终端语义色，不跟随主题） ----
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(TerminalBackground)
            ) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        items(output) { line: ShellLine ->
                            Text(
                                text = line.text,
                                style = TerminalTextStyle,
                                color = lineColor(line),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // ---- 底部输入行 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    placeholder = {
                        Text(text = TEXT_PLACEHOLDER, style = TerminalTextStyle)
                    },
                    textStyle = TerminalTextStyle.copy(fontSize = 13.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (running) {
                    IconButton(onClick = { vm.stop() }) {
                        Icon(
                            imageVector = AppIcon.Stop,
                            contentDescription = TEXT_STOP,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    // 只在真的受理时才清空输入框：空命令 / 未绑定设备 / 已有命令在跑 /
                    // 等二次确认这几种「点了没跑起来」的情形必须把内容留在框里。
                    // 清空不等于丢失 —— 命令已回显到输出区（`$ xxx`）并存进下方历史 chip。
                    IconButton(onClick = { if (vm.run(input)) input = "" }) {
                        Icon(
                            imageVector = AppIcon.Play,
                            contentDescription = TEXT_EXECUTE,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // ---- 最近命令历史（辅助输入） ----
            if (history.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    history.take(8).forEach { command: String ->
                        Surface(
                            onClick = { input = command },
                            // 规格 §1.3：历史命令 chip = md(12 dp)
                            shape = ShapeMd,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = TEXT_PROMPT + command.take(28),
                                style = TerminalTextStyle,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Sheet 收起：先跑完隐藏动画再从组合里移除，避免「点一下直接跳没」的生硬中断。
    // runCatching 是必要的：某些 Material3 版本会对「已经处于 Hidden 状态还调用 hide()」抛
    // IllegalStateException，而这里的 launch 没有自定义 CoroutineExceptionHandler，
    // 未捕获异常会直接冒到 Thread.uncaughtExceptionHandler 炸掉整个 App。
    val dismissMenu: () -> Unit = {
        coroutineScope
            .launch { runCatching { menuSheetState.hide() } }
            .invokeOnCompletion { menuOpen = false }
    }

    // ---- 三点菜单：M3E 改为底部 Sheet（§1.6），**功能与文案一字不改** ----
    if (menuOpen) {
        ModalBottomSheet(
            onDismissRequest = dismissMenu,
            sheetState = menuSheetState,
            // 规格 §1.3：底部 Sheet 顶部圆角 32 dp
            shape = RoundedCornerShape(topStart = Radius.Xl3, topEnd = Radius.Xl3)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                MenuSheetRow(
                    label = TEXT_MENU_EXPORT,
                    checked = false,
                    onClick = {
                        dismissMenu()
                        clipboard.setText(AnnotatedString(vm.exportText()))
                        coroutineScope.launch { snackbarHostState.showSnackbar(TEXT_COPIED) }
                    }
                )
                MenuSheetRow(
                    label = TEXT_MENU_CLEAR,
                    checked = false,
                    onClick = {
                        dismissMenu()
                        vm.clear()
                    }
                )
                MenuSheetRow(
                    label = TEXT_MENU_TIMEOUT10,
                    checked = sameTimeout(timeoutMs, 10_000L),
                    onClick = {
                        dismissMenu()
                        vm.setTimeout(10_000L)
                    }
                )
                MenuSheetRow(
                    label = TEXT_MENU_TIMEOUT30,
                    checked = sameTimeout(timeoutMs, 30_000L),
                    onClick = {
                        dismissMenu()
                        vm.setTimeout(30_000L)
                    }
                )
                MenuSheetRow(
                    label = TEXT_MENU_TIMEOUT_NO,
                    checked = timeoutMs == Long.MAX_VALUE,
                    onClick = {
                        dismissMenu()
                        vm.setTimeout(0L)
                    }
                )
            }
        }
    }

    val danger: String? = pendingDanger
    if (danger != null) {
        AlertDialog(
            onDismissRequest = { vm.cancelDanger() },
            title = { Text(text = TEXT_DANGER_TITLE) },
            text = {
                Column {
                    Text(text = TEXT_DANGER_BODY, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = ShapeMd
                    ) {
                        Text(
                            text = danger,
                            style = TerminalTextStyle,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // 规格 §4.5.1.1：就地解释「设置里关了开关，这里还是会弹」
                    Text(
                        text = TEXT_DANGER_LOCKED,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { if (vm.confirmDanger()) input = "" }) {
                    Text(text = TEXT_DANGER_CONFIRM, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelDanger() }) {
                    Text(text = TEXT_DANGER_CANCEL)
                }
            }
        )
    }
}

/**
 * 命令行页顶栏**内容**高度（不含状态栏）。
 *
 * 两行标题（32 sp 标题 + 副标题）取 88 dp；状态栏由 [FlexibleShellTopAppBar] 里的
 * `windowInsetsPadding(WindowInsets.statusBars)` 另外让位。
 */
private val TOP_BAR_HEIGHT_SHELL: Dp = 88.dp

/**
 * ★ Flexible Top App Bar（规格 §1.3 M1 / §4.4 区块 1）。
 *
 * 与列表页 / 详情页共用同一套形变：未折叠时直角、与页面同色（视觉上「没有」顶栏）、
 * 标题 32 sp；折叠后长出 28 dp 圆角的悬浮容器、缩进 12 dp、标题缩到 22 sp，
 * 副标题「`<设备名>` · 超时 N 秒」淡出（保留占位，避免高度跳动）。
 *
 * ⚠️ 高度**不参与**形变：M3 Scaffold 把顶栏实测高度直接作为 content 的 padding.top，
 * 高度若随滚动动画，会和终端输出区 LazyColumn 的视口 / 滚动偏移形成测量回环。
 */
@Composable
private fun FlexibleShellTopAppBar(
    collapseFraction: Float,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onMenu: () -> Unit
) {
    val f: Float = collapseFraction.coerceIn(0f, 1f)
    // ★ spring 欠阻尼下冲会产出负值，Modifier.padding 不接受（真机已在详情页复现崩溃）。
    val cornerRaw by animateDpAsState(
        targetValue = lerp(0.dp, Radius.Xl2, f),
        animationSpec = Motion.springDefault(),
        label = "shellAppBarCorner"
    )
    val corner: Dp = maxOf(0.dp, cornerRaw)
    // ★ 顶栏高度固定为 88 dp，不参与形变（与详情页 / 列表页同因）。
    //   Scaffold 把顶栏实测高度直接作为 content 的 padding.top，高度动画会和终端输出区
    //   LazyColumn 的视口 / 滚动偏移形成测量回环。
    val barHeight: Dp = TOP_BAR_HEIGHT_SHELL
    val outerPaddingRaw by animateDpAsState(
        targetValue = lerp(0.dp, 12.dp, f),
        animationSpec = Motion.springDefault(),
        label = "shellAppBarPadding"
    )
    // 同上：spring 下冲会让 padding 变成 -0.2dp → Padding must be non-negative。
    val outerPadding: Dp = maxOf(0.dp, outerPaddingRaw)
    val containerColor: Color = if (f > 0.01f) {
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
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = lerp(4.dp, 8.dp, f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = AppIcon.Back, contentDescription = TEXT_TITLE)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = lerp(32.sp, 22.sp, f),
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(1f - f)
                )
            }
            IconButton(onClick = onMenu) {
                Icon(imageVector = AppIcon.MoreVert, contentDescription = TEXT_TITLE)
            }
        }
    }
}

/**
 * 底部 Sheet 里的菜单行。
 *
 * 原来的 `DropdownMenuItem` 换成行式布局（拇指更好点），但**文案与功能一字未改**。
 * 超时三档在当前生效的那一项右侧显示打勾 —— 工程图标库没有 check 图标，
 * 沿用了 [com.adb.adbwirelesshelper.ui.components.ExpressiveSelectChip] 的字符方案。
 */
@Composable
private fun MenuSheetRow(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(onClick = onClick, modifier = modifier, color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = CHECK_MARK,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                // 未选中也占位，避免勾选出现/消失时文字左右跳动
                modifier = Modifier.width(20.dp).alpha(if (checked) 1f else 0f)
            )
        }
    }
}

/** 打勾字符：[AppIcon] 未提供 check 图标，且不为此引入 material-icons-extended。 */
private const val CHECK_MARK: String = "✓"

/** 预设 Chip（Scrollable Chip Group 的成员）。高危命令用 error 色容器并加警告图标。 */
@Composable
private fun PresetChip(preset: Preset, onClick: () -> Unit) {
    val containerColor: Color = if (preset.danger) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor: Color = if (preset.danger) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        onClick = onClick,
        // 规格 §1.3：chip 统一 lg(16 dp)
        shape = ShapeLg,
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (preset.danger) {
                Icon(
                    imageVector = AppIcon.Warning,
                    contentDescription = preset.label,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = preset.label,
                color = contentColor,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/** 行着色：命令绿，stderr 用 scheme error，其余为终端 stdout 灰。 */
@Composable
private fun lineColor(line: ShellLine): Color {
    if (line.isError) return MaterialTheme.colorScheme.error
    if (line.isCommand) return TERMINAL_COMMAND
    return TERMINAL_STDOUT
}

/** 顶部副标题里的设备名。 */
private fun deviceTitle(vm: ShellViewModel): String {
    return vm.deviceLabel().takeIf { it.isNotBlank() } ?: TEXT_UNKNOWN_DEVICE
}

/**
 * 判断当前超时档位是否等于菜单里的某一档。
 *
 * 刻意不改动 [ShellViewModel]：VM 的 `setTimeout(0L)` 内部会被映射成 `Long.MAX_VALUE`
 * 表示「无限制」，所以这里要把同样的映射反过来算一遍。
 */
private fun sameTimeout(current: Long, menuValue: Long): Boolean {
    return if (menuValue <= 0L) current == Long.MAX_VALUE else current == menuValue
}
