package com.adb.adbwirelesshelper.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * 动效令牌（Material 3 Expressive，设计规格 §1.5）。
 *
 * M3E 主推 **spring 弹性**替代线性补间。用法：
 * ```kotlin
 * val radius by animateDpAsState(target, springOvershoot())
 * val alpha  by animateFloatAsState(target, springFast())
 * ```
 */
object Motion {
    /** chip 选中、按钮按下回弹、chip 形变（M2/M3）—— 约 200ms */
    fun <T> springFast(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)

    /** 卡片入场、列表项增删、对话框出现 —— 约 300ms */
    fun <T> springDefault(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 600f)

    /** 空态 / 错误横幅入场、底部 Sheet —— 约 500ms */
    fun <T> springSlow(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 380f)

    /**
     * 过冲（Overshoot）—— 约 400ms。
     * 用于空态图标、配对成功勾选、投屏首帧淡入，以及 **CPU 环 / 内存条数值过渡**：
     * 数值仪表带轻微过冲正是 M3E 的「有生命力」表达，且 300ms 采样周期下
     * 过冲幅度 ≤2% 数值，不会被误读为数据抖动。
     */
    fun <T> springOvershoot(): SpringSpec<T> = spring(dampingRatio = 0.45f, stiffness = 320f)

    // ——— 非线性补间 ———

    /** 页面级转场（列表→详情→投屏）400ms */
    val EasingEmphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 元素进入（对话框、Snackbar、状态条）300ms */
    val EasingEmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 元素退出 200ms */
    val EasingEmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 颜色 / 透明度切换 150ms */
    val EasingStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    // ——— 时长分级（§1.5.3）———

    /** micro：按压反馈、ripple */
    const val DURATION_MICRO: Int = 100
    /** short：chip 形变、按钮形变（M2/M3） */
    const val DURATION_SHORT: Int = 200
    /** medium：卡片入场、状态切换 */
    const val DURATION_MEDIUM: Int = 300
    /** container transform / 页面转场 */
    const val DURATION_CONTAINER: Int = 400
    /** long：空态、失败态、投屏首帧 */
    const val DURATION_LONG: Int = 500
}
