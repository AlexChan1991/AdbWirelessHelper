package com.adb.adbwirelesshelper.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adb.adbwirelesshelper.domain.model.Capability
import com.adb.adbwirelesshelper.domain.model.DeviceState
import com.adb.adbwirelesshelper.domain.model.DiscoveredService
import com.adb.adbwirelesshelper.domain.model.ProcMem
import com.adb.adbwirelesshelper.domain.model.Source
import com.adb.adbwirelesshelper.ui.theme.AppIcon
import com.adb.adbwirelesshelper.ui.theme.MetricMonoTextStyle
import com.adb.adbwirelesshelper.ui.theme.StatusConnected
import com.adb.adbwirelesshelper.ui.theme.StatusConnectedDark
import com.adb.adbwirelesshelper.ui.theme.StatusConnecting
import com.adb.adbwirelesshelper.ui.theme.StatusConnectingDark
import com.adb.adbwirelesshelper.ui.theme.StatusOffline
import com.adb.adbwirelesshelper.ui.theme.StatusOfflineDark
import com.adb.adbwirelesshelper.ui.theme.Motion
import com.adb.adbwirelesshelper.ui.theme.Radius
import com.adb.adbwirelesshelper.ui.theme.ShapeLg
import com.adb.adbwirelesshelper.ui.theme.ShapeMd
import com.adb.adbwirelesshelper.ui.theme.ShapeXl

// ---------------------------------------------------------------- 文案常量

private const val TXT_UNKNOWN = "未知"
private const val TXT_PAIRED = "已配对"
private const val TXT_NOT_PAIRED = "未配对"
private const val TXT_CONNECTING = "连接中…"
private const val TXT_SOURCE_MDNS = "mDNS"
private const val TXT_SOURCE_SCAN = "端口扫描"
private const val TXT_SOURCE_MANUAL = "手动"
// 规格 §4.1.1 校正：列表页这个按钮的实际行为是 `vm.clearError()`（只关闭横幅），
// 并非重新扫描。文案必须诚实，不能再叫「重试」。
private const val TXT_DISMISS = "知道了"
private const val TXT_CPU = "CPU"
private const val TXT_MEM = "内存"
private const val TXT_MEM_TOTAL = "总量"

/** 状态条语义状态。 */
enum class ConnectionState {
    /** 已连接（绿）。 */
    CONNECTED,

    /** 正在重连（黄）。 */
    RECONNECTING,

    /** 已断开（灰）。 */
    DISCONNECTED,

    /** 正在扫描 / 连接中（蓝，带脉冲点）。 */
    SCANNING,

    /** 警告：组播受限 / 未连 Wi-Fi 等（橙）。 */
    WARNING,
}

// ---------------------------------------------------------------- 设备卡片

/**
 * 列表页的设备条目卡。
 *
 * @param svc        候选设备（已按 host 聚合，含 pairPort / connectPort）。
 * @param connecting 该条目是否正在连接中（显示 Loading）。
 * @param onClick    整行点击回调。
 */
@Composable
fun DeviceCard(
    svc: DiscoveredService,
    connecting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paired = svc.connectPort != null
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(enabled = !connecting) { onClick() },
        // 规格 §1.3：条目卡 16dp；§1.2.2：条目卡容器 = surfaceContainer
        shape = ShapeLg,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 首字母圆形头像
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initialOf(svc.instanceName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = svc.instanceName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${svc.host}:${svc.port} · ${sourceLabel(svc.source)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (svc.pairPort != null || svc.connectPort != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = portSummary(svc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (connecting) {
                // 规格 §1.5.4：条目连接中用 M3E 波浪形不定进度（20dp / stroke 2.5），不再转圈
                WavyProgressIndicator(size = 18.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = TXT_CONNECTING,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                StatusChip(
                    text = if (paired) TXT_PAIRED else TXT_NOT_PAIRED,
                    positive = paired,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    AppIcon.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 已连接设备（已知设备）条目卡。 */
@Composable
fun DeviceStateChip(state: DeviceState, modifier: Modifier = Modifier) {
    val (text, positive) = when (state) {
        DeviceState.DEVICE -> ("已连接" to true)
        DeviceState.UNAUTHORIZED -> ("未授权" to false)
        DeviceState.OFFLINE -> ("离线" to false)
        DeviceState.UNKNOWN -> (TXT_UNKNOWN to false)
    }
    StatusChip(text = text, positive = positive, modifier = modifier)
}

@Composable
private fun StatusChip(
    text: String,
    positive: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = if (positive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (positive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = bg,
        contentColor = fg,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- 通用容器

/**
 * 带标题的分区卡片。
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        // M3 Expressive：分组卡统一 20dp，容器取 surfaceContainerHigh（层次：页面→分组→条目→嵌块）
        shape = ShapeXl,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = title,
                // 规格 §1.4：分组卡标题 = headlineSmall 24/32/700
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/** 单行指标：左标签、右数值。 */
@Composable
fun MetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 两列键值网格。
 *
 * @param items 每项为「标签 → 数值」，缺失值请传入 `null` 的显示文案（如「未知」）。
 */
@Composable
fun KeyValueGrid(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val rows = (items.size + 1) / 2
    Column(modifier = modifier.fillMaxWidth()) {
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                val left = items.getOrNull(row * 2)
                val right = items.getOrNull(row * 2 + 1)
                if (left != null) {
                    KeyValueCell(
                        label = left.first,
                        value = left.second,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.width(10.dp))
                if (right != null) {
                    KeyValueCell(
                        label = right.first,
                        value = right.second,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun KeyValueCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- 状态条

/**
 * 顶部状态条（绿=已连接 / 黄=重连中 / 灰=已断开 / 蓝=扫描中 / 橙=警告）。
 *
 * @param state 语义状态。
 * @param text  覆盖默认文案的自定义描述。
 */
@Composable
fun StatusStrip(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    text: String? = null,
) {
    val (container, content) = when (state) {
        ConnectionState.CONNECTED ->
            (MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer)

        ConnectionState.RECONNECTING, ConnectionState.WARNING ->
            (MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer)

        ConnectionState.SCANNING ->
            (MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer)

        ConnectionState.DISCONNECTED ->
            (MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val label: String = text ?: when (state) {
        ConnectionState.CONNECTED -> "已连接"
        ConnectionState.RECONNECTING -> "正在重连…"
        ConnectionState.SCANNING -> "正在扫描…"
        ConnectionState.WARNING -> "网络异常，请检查 Wi-Fi"
        ConnectionState.DISCONNECTED -> "已断开"
    }

    // 规格 §1.2.3：状态条**容器**取 scheme container，**圆点**取状态色，两者不混用。
    // 混用会导致「绿底上又是绿点」的圆点不可见问题。
    //
    // ★ 状态色分 light / dark 两版，选哪版必须跟**实际生效**的主题走。
    //   这里不能用 isSystemInDarkTheme()：它只反映**系统**深浅，而设置页提供
    //   「跟随系统 / 强制浅色 / 强制深色」三档。手动档与系统相反时会选错版本 ——
    //   例：手动深色下容器取深色 primaryContainer，圆点却取了浅色版的
    //   StatusConnected #FF2E7D32（深绿）→ 深绿压深绿，圆点几乎看不见。
    //   改用实际生效的 colorScheme.surface 的感知亮度判断，三档都不会错。
    val surface: Color = MaterialTheme.colorScheme.surface
    val dark: Boolean =
        (surface.red * 0.299f + surface.green * 0.587f + surface.blue * 0.114f) < 0.5f
    val dotColor: Color = when (state) {
        ConnectionState.CONNECTED -> if (dark) StatusConnectedDark else StatusConnected
        // SCANNING / RECONNECTING / WARNING 都属「进行中」
        ConnectionState.SCANNING,
        ConnectionState.RECONNECTING,
        ConnectionState.WARNING,
        -> if (dark) StatusConnectingDark else StatusConnecting

        ConnectionState.DISCONNECTED -> if (dark) StatusOfflineDark else StatusOffline
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = ShapeMd,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 扫描中：圆点走 M3E Contingency 指示器的循环变形（圆点→短胶囊→波浪条）
            if (state == ConnectionState.SCANNING) {
                ContingencyDot(color = dotColor)
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(color = dotColor, shape = CircleShape),
                )
            }
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
    }
}

// ---------------------------------------------------------------- 仪表

/**
 * CPU 占用率环形仪表 —— **详情页唯一的 displayLarge 数值锚点**（规格 §1.4）。
 *
 * 两处 M3 Expressive 调整：
 * 1. 数值过渡从 `tween(300)` 改为 **Overshoot spring**（ζ=0.45 / k=320）：
 *    仪表指针带轻微过冲是 M3E「有生命力」的表达，且 300ms 采样周期下过冲 ≤2% 数值，
 *    不会被误读为数据抖动。
 * 2. 中心百分比改用 `displayLarge`（40/48/700/−1）。为容纳三位数「100%」，
 *    默认直径从 72dp 放大到 112dp。
 */
@Composable
fun CpuRing(
    percent: Float?,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 112.dp,
) {
    val target = (percent ?: 0f).coerceIn(0f, 100f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = Motion.springOvershoot(),
        label = "cpuRing",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = MaterialTheme.colorScheme.primary
    val strokeWidth = 11.dp

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val inset = strokeWidth.toPx() / 2f
            val arcSize = Size(
                width = this.size.width - strokeWidth.toPx(),
                height = this.size.height - strokeWidth.toPx(),
            )
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                // Overshoot spring 下冲会让数值短暂为负 → 圆弧反向甩一小段，
                // 这里夹到 0..100，只保留「到位的过冲」，去掉「回头的过冲」。
                sweepAngle = 360f * (animated.coerceIn(0f, 100f) / 100f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${target.toInt()}%",
                // 规格 §1.4：数值锚点 = displayLarge 40/48/700/−1，一屏仅此一个
                style = MaterialTheme.typography.displayLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 内存占用横条：显示「已用 / 总量」，带 300ms 过渡动画。
 */
@Composable
fun MemoryBar(
    usedKb: Long?,
    totalKb: Long?,
    modifier: Modifier = Modifier,
) {
    val total = totalKb ?: 0L
    val used = usedKb ?: 0L
    val ratio = if (total > 0L) {
        (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    // 与 CpuRing 一致：数值过渡改用 Overshoot spring（规格 §1.5.1）
    val animated by animateFloatAsState(
        targetValue = ratio,
        animationSpec = Motion.springOvershoot(),
        label = "memoryBar",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = TXT_MEM,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (total > 0L) {
                    "${formatKb(used)} / ${formatKb(total)} · ${(ratio * 100).toInt()}%"
                } else {
                    TXT_UNKNOWN
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(4.dp),
                    ),
            )
        }
    }
}

// ---------------------------------------------------------------- Top 进程

/** Top 进程单行。 */
@Composable
fun TopProcessRow(
    proc: ProcMem,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = proc.name,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatKb(proc.pssKb),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- 空态 / 错误

/**
 * 空状态提示。
 *
 * @param text        说明文案。
 * @param actionLabel 行动按钮文案（null 时不显示按钮）。
 * @param onAction    行动按钮回调。
 */
@Composable
fun EmptyHint(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            AppIcon.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onAction) {
                Icon(
                    AppIcon.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = actionLabel)
            }
        }
    }
}

/**
 * 错误横幅，可选行动入口。
 *
 * @param actionLabel 行动按钮文案。默认「知道了」而不是「重试」——列表页传入的
 *                    回调实际只做 `clearError()`（关闭横幅），标注成「重试」会误导用户。
 * @param onAction    行动按钮回调；null 时不显示按钮。
 */
@Composable
fun ErrorBanner(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String = TXT_DISMISS,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcon.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (onAction != null) {
                Spacer(modifier = Modifier.width(6.dp))
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 32.dp)) {
                    Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * 原型图风格的「快捷命令」Chip（自绘，避免依赖实验性 API）。
 */
@Composable
fun QuickChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val borderColor = if (danger) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.outline
    }
    val textColor = if (danger) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .border(
                width = 1.dp,
                color = borderColor,
                // 规格 §1.3：chip 统一 16dp（不再用 50 的胶囊）
                shape = ShapeLg,
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- M3E 新增组件

/**
 * ★ M3E Contingency Loading Indicator（规格 §1.5.4）。
 *
 * 圆点 → 短胶囊 → 波浪条 循环变形，1.2s 一轮，用于「正在扫描」等持续进行中的状态，
 * 替代静态圆点。
 */
@Composable
fun ContingencyDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "contingency")
    // 1.2s 一轮：宽度 7dp(圆点) → 14dp(短胶囊) → 波浪条(18dp)
    val width by transition.animateFloat(
        initialValue = 7f,
        targetValue = 18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "contingencyWidth",
    )
    Box(
        modifier = modifier
            .size(width = width.dp, height = 7.dp)
            .background(color = color, shape = CircleShape),
    )
}

/**
 * ★ M3E 波浪形不定进度指示器（规格 §1.5.4），替代 `CircularProgressIndicator`。
 *
 * 用「旋转 + 弧长周期变化」合成波浪感：描边弧长在 0.15~0.85 圈之间往复，
 * 同时整体匀速旋转。
 *
 * @param size   直径，条目内 20dp、投屏居中卡内 28dp
 * @param stroke 描边宽度，默认 2.5dp
 * @param color  前景色（投屏页传 White）
 */
@Composable
fun WavyProgressIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    stroke: Dp = 2.5.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "wavy")
    val sweep by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wavySweep",
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
        ),
        label = "wavyRotation",
    )
    Canvas(modifier = modifier.size(size)) {
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = 360f * sweep,
            useCenter = false,
            style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round),
        )
    }
}

/**
 * ★ M3E 选择态 Chip，选中时走 **M2 形状变形**（规格 §1.3）：
 * 未选中 = 胶囊（pill），选中 = 16dp 圆角方形 + 左侧打勾。
 *
 * 用于设置页的 Segmented Button 与各组 Chip（主题 / 分辨率 / 码率 / 帧率 / 音频…）。
 */
@Composable
fun ExpressiveSelectChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // M2 形变：pill → 16dp，spring 200ms（SpatialFast）
    val corner by animateDpAsState(
        targetValue = if (selected) Radius.Lg else Radius.Full,
        animationSpec = Motion.springFast(),
        label = "chipMorph",
    )
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(corner),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 选中时左侧长出打勾，宽度 0 → 16dp
            val leading by animateDpAsState(
                targetValue = if (selected) 16.dp else 0.dp,
                animationSpec = Motion.springFast(),
                label = "chipCheck",
            )
            if (leading > 0.dp) {
                // 工程图标库未提供 check 图标，且不想为此引入 material-icons-extended，
                // 此处用字符实现（宽度与 [leading] 动画一致：0 → 16dp）
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// ---------------------------------------------------------------- 格式化工具

/**
 * 把 kB 格式化为易读容量：`412 MB` / `6.2 GB`。
 */
fun formatKb(kb: Long?): String {
    if (kb == null || kb < 0L) return TXT_UNKNOWN
    val kbValue = kb.toDouble()
    return when {
        kbValue >= 1024.0 * 1024.0 -> String.format("%.1f GB", kbValue / 1024.0 / 1024.0)
        kbValue >= 1024.0 -> String.format("%.0f MB", kbValue / 1024.0)
        else -> String.format("%d KB", kb)
    }
}

/** 取实例名的首个字符（大写），用于圆形头像。 */
private fun initialOf(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    return trimmed.first().uppercaseChar().toString()
}

/** 来源标签。 */
private fun sourceLabel(source: Source): String = when (source) {
    Source.MDNS -> TXT_SOURCE_MDNS
    Source.PORT_SCAN -> TXT_SOURCE_SCAN
    Source.MANUAL -> TXT_SOURCE_MANUAL
}

/** 「配对端口 / 连接端口」摘要，强调二者不同。 */
private fun portSummary(svc: DiscoveredService): String {
    val pair = svc.pairPort?.let { "配对口 $it" }
    val connect = svc.connectPort?.let { "连接口 $it" }
    return listOfNotNull(pair, connect).joinToString(" · ").ifEmpty { TXT_UNKNOWN }
}

/** 能力标签。 */
fun capabilityLabel(capability: Capability): String = when (capability) {
    Capability.CONNECT -> "可连接"
    Capability.PAIRING_ONLY -> "需配对"
    Capability.UNKNOWN -> TXT_UNKNOWN
}

/** 通用：把可能为空的值转成展示文案。 */
fun orUnknown(value: String?): String = if (value.isNullOrBlank()) TXT_UNKNOWN else value

/** 供外部复用的「未知」文案。 */
const val UNKNOWN_TEXT: String = TXT_UNKNOWN

/** CPU 环的默认标签。 */
const val CPU_RING_LABEL: String = TXT_CPU

/** 内存条默认标签与总量文案。 */
const val MEM_LABEL: String = TXT_MEM
const val MEM_TOTAL_LABEL: String = TXT_MEM_TOTAL
