package com.adb.adbwirelesshelper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角阶梯（Material 3 Expressive，设计规格 §1.3）。
 *
 * 统一覆盖散落的 4/8/10/12/14/16/18/22/28 dp。
 * **容器圆角分级（自上而下递增再收敛）**：
 * 页面背景 `0` → 分组卡 `20` → 条目卡 `16` → 卡内嵌块 `12` → Chip `16 / full`。
 */
object Radius {
    /** 内存条内条、分隔块 */
    val Xs = 4.dp
    /** 音频徽标、KeyValue 微块 */
    val Sm = 8.dp
    /** 状态条、错误横幅、历史命令 chip */
    val Md = 12.dp
    /** 条目卡、chip（选中/未选中）、预设 chip */
    val Lg = 16.dp
    /** 分组卡（SectionCard） */
    val Xl = 20.dp
    /** 悬浮顶栏/底栏药丸、对话框、滚动后的 Top App Bar */
    val Xl2 = 28.dp
    /** 底部 Sheet 顶部、Carousel 卡片 */
    val Xl3 = 32.dp
    /** 状态 chip、圆形头像 */
    val Full = 9999.dp
}

/** 与 [Radius] 一一对应的 Shape 实例，避免 UI 层反复构造。 */
val ShapeXs = RoundedCornerShape(Radius.Xs)
val ShapeSm = RoundedCornerShape(Radius.Sm)
val ShapeMd = RoundedCornerShape(Radius.Md)
val ShapeLg = RoundedCornerShape(Radius.Lg)
val ShapeXl = RoundedCornerShape(Radius.Xl)
val Shape2xl = RoundedCornerShape(Radius.Xl2)
val Shape3xl = RoundedCornerShape(Radius.Xl3)
val ShapeFull = RoundedCornerShape(Radius.Full)

/**
 * 注入 MaterialTheme 的形状体系。
 * Material3 只有 extraSmall/small/medium/large/extraLarge 五档，此处按 M3E 用法映射：
 * small→sm(8)、medium→md(12)、large→lg(16)、extraLarge→xl(20)。
 */
val AppShapes = Shapes(
    extraSmall = ShapeXs,
    small = ShapeSm,
    medium = ShapeMd,
    large = ShapeLg,
    extraLarge = ShapeXl
)
