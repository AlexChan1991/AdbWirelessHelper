package com.adb.adbwirelesshelper.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 应用配色。Compose 侧统一从这里取色，避免 UI 层散落硬编码颜色。
 */

// —— 品牌色 ——
val BrandBlue = Color(0xFF1565C0)
val BrandBlueDark = Color(0xFF0D47A1)
val BrandBlueLight = Color(0xFF5E92F3)

// —— 语义色：状态指示（连接状态 / 成功 / 警告 / 危险）——
val StatusConnected = Color(0xFF2E7D32)
val StatusConnecting = Color(0xFFED6C02)
val StatusOffline = Color(0xFF9E9E9E)
val StatusError = Color(0xFFD32F2F)

/** 终端输出着色 */
val TerminalStdout = Color(0xFF1B1B1B)
val TerminalStderr = Color(0xFFD32F2F)
val TerminalMeta = Color(0xFF6A6A6A)

/** 仪表盘渐变端点（CPU / 内存环形进度） */
val GaugeCpuStart = Color(0xFF42A5F5)
val GaugeCpuEnd = Color(0xFF1E88E5)
val GaugeMemStart = Color(0xFF66BB6A)
val GaugeMemEnd = Color(0xFF2E7D32)

// —— Material3 浅色配色 ——
val PrimaryLight = Color(0xFF1565C0)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFD6E4FF)
val OnPrimaryContainerLight = Color(0xFF001A41)
val SecondaryLight = Color(0xFF54657B)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFD8E3F8)
val OnSecondaryContainerLight = Color(0xFF111C2B)
val TertiaryLight = Color(0xFF6F5673)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFF9D8FD)
val OnTertiaryContainerLight = Color(0xFF29132D)
val BackgroundLight = Color(0xFFF8F9FC)
val OnBackgroundLight = Color(0xFF191C1E)
val SurfaceLight = Color(0xFFFDFCFF)
val OnSurfaceLight = Color(0xFF191C1E)
val SurfaceVariantLight = Color(0xFFE1E2EC)
val OnSurfaceVariantLight = Color(0xFF44474F)
val OutlineLight = Color(0xFF74777F)
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)

// —— Material3 深色配色 ——
val PrimaryDark = Color(0xFFA9C7FF)
val OnPrimaryDark = Color(0xFF003063)
val PrimaryContainerDark = Color(0xFF00478D)
val OnPrimaryContainerDark = Color(0xFFD6E4FF)
val SecondaryDark = Color(0xFFBCC7DB)
val OnSecondaryDark = Color(0xFF263141)
val SecondaryContainerDark = Color(0xFF3C4859)
val OnSecondaryContainerDark = Color(0xFFD8E3F8)
val TertiaryDark = Color(0xFFDCBCE1)
val OnTertiaryDark = Color(0xFF402843)
val TertiaryContainerDark = Color(0xFF583E5B)
val OnTertiaryContainerDark = Color(0xFFF9D8FD)
val BackgroundDark = Color(0xFF111318)
val OnBackgroundDark = Color(0xFFE3E2E6)
val SurfaceDark = Color(0xFF111318)
val OnSurfaceDark = Color(0xFFE3E2E6)
val SurfaceVariantDark = Color(0xFF44474F)
val OnSurfaceVariantDark = Color(0xFFC4C6D0)
val OutlineDark = Color(0xFF8E9099)
val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)

/** 深色下的状态色（比浅色略亮，保证对比度） */
val StatusConnectedDark = Color(0xFF66BB6A)
val StatusConnectingDark = Color(0xFFFFB74D)
val StatusOfflineDark = Color(0xFF757575)
val StatusErrorDark = Color(0xFFEF9A9A)

val TerminalStdoutDark = Color(0xFFE3E2E6)
val TerminalStderrDark = Color(0xFFFFB4AB)
val TerminalMetaDark = Color(0xFF9E9E9E)

// —— M3 Expressive：多级 surface container（设计规格 §1.2.2）——
// 层次规则（自上而下）：
//   页面背景 surface → 分组卡 High → 条目卡 Container → 卡内嵌块 Highest
//   → 强调区（primaryContainer / errorContainer）
// 现有 Color.kt 只有 surface / surfaceVariant 两级，不足以表达四层，故补齐。
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF7F5F9)
val SurfaceContainerLight = Color(0xFFF1EFF4)
val SurfaceContainerHighLight = Color(0xFFEBE9EF)
val SurfaceContainerHighestLight = Color(0xFFE5E3EA)

val SurfaceContainerLowestDark = Color(0xFF0C0E12)
val SurfaceContainerLowDark = Color(0xFF191B1F)
val SurfaceContainerDark = Color(0xFF1D1F24)
val SurfaceContainerHighDark = Color(0xFF272A30)
val SurfaceContainerHighestDark = Color(0xFF32353C)

// 终端底色：深浅主题下都固定不跟随主题（刻意的终端语义，见 §1.2.4）
val TerminalBackground = Color(0xFF101014)
