package com.adb.adbwirelesshelper.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自建图标集（24dp / 24 视口，线性风格）。
 *
 * ★ 本项目**不引入 material-icons-extended**，所有图标用 PathBuilder 手绘，
 *   避免为了几个图标把图标库整包打进 APK（也避免 Compose BOM 与图标库版本错配）。
 *
 * 用法：
 *   Icon(imageVector = AppIcon.Refresh, contentDescription = "刷新")
 *   Image(painter = rememberVectorPainter(AppIcon.Wifi), contentDescription = null)
 *
 * 描边色统一为 SolidColor(Color.Black)，实际呈现颜色由上层 tint 决定
 * （Icon 默认会用 LocalContentColor 做 tint）。
 */
object AppIcon {

    /** 返回 / 左箭头 */
    val Back: ImageVector = ImageVector.Builder(
        name = "Back",
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
        moveTo(15f, 5f)
        lineTo(8f, 12f)
        lineTo(15f, 19f)
    }.build()

    /** 右箭头（列表条目「进入」） */
    val ChevronRight: ImageVector = ImageVector.Builder(
        name = "ChevronRight",
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
        moveTo(9f, 5f)
        lineTo(16f, 12f)
        lineTo(9f, 19f)
    }.build()

    /** 关闭 */
    val Close: ImageVector = ImageVector.Builder(
        name = "Close",
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
        moveTo(6f, 6f)
        lineTo(18f, 18f)
        moveTo(18f, 6f)
        lineTo(6f, 18f)
    }.build()

    /** 搜索 / 发现 */
    val Search: ImageVector = ImageVector.Builder(
        name = "Search",
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
        oval(11f, 11f, 6f)
        moveTo(15.5f, 15.5f)
        lineTo(20f, 20f)
    }.build()

    /** 停止 */
    val Stop: ImageVector = ImageVector.Builder(
        name = "Stop",
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
        moveTo(7f, 7f)
        lineTo(17f, 7f)
        lineTo(17f, 17f)
        lineTo(7f, 17f)
        close()
    }.build()

    /** 书签 / 已知设备（收藏） */
    val Bookmark: ImageVector = ImageVector.Builder(
        name = "Bookmark",
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
        moveTo(7f, 4f)
        lineTo(17f, 4f)
        lineTo(17f, 20f)
        lineTo(12f, 15.5f)
        lineTo(7f, 20f)
        close()
    }.build()

    /** 调节 / 设置 */
    val Tune: ImageVector = ImageVector.Builder(
        name = "Tune",
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
        moveTo(4f, 8f)
        lineTo(14f, 8f)
        moveTo(18f, 8f)
        lineTo(20f, 8f)
        oval(16f, 8f, 2f)
        moveTo(4f, 16f)
        lineTo(10f, 16f)
        moveTo(14f, 16f)
        lineTo(20f, 16f)
        oval(12f, 16f, 2f)
    }.build()

    /** 竖向更多菜单（实心三点） */
    val MoreVert: ImageVector = ImageVector.Builder(
        name = "MoreVert",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        fill = SolidColor(Color.Black),
        stroke = null,
        strokeLineWidth = 0f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) {
        oval(12f, 5f, 1.6f)
        oval(12f, 12f, 1.6f)
        oval(12f, 19f, 1.6f)
    }.build()

    /** 刷新 / 重新扫描 */
    val Refresh: ImageVector = ImageVector.Builder(
        name = "Refresh",
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
        moveTo(20f, 12f)
        arcTo(8f, 8f, 0f, true, true, 12f, 4f)
        moveTo(12f, 4f)
        lineTo(12f, 9f)
        moveTo(12f, 4f)
        lineTo(17f, 4f)
    }.build()

    /** 首页 / 主屏键 */
    val Home: ImageVector = ImageVector.Builder(
        name = "Home",
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
        moveTo(4f, 11f)
        lineTo(12f, 4f)
        lineTo(20f, 11f)
        moveTo(6.5f, 10f)
        lineTo(6.5f, 20f)
        lineTo(17.5f, 20f)
        lineTo(17.5f, 10f)
    }.build()

    /** 最近任务 */
    val Recent: ImageVector = ImageVector.Builder(
        name = "Recent",
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
        moveTo(6f, 7f)
        lineTo(18f, 7f)
        lineTo(18f, 17f)
        lineTo(6f, 17f)
        close()
        moveTo(9f, 7f)
        lineTo(9f, 4f)
        lineTo(15f, 4f)
        lineTo(15f, 7f)
    }.build()

    // ------------------------------------------------------------------
    // 安卓系统导航键三件套（返回 ◁ / 主页 ○ / 最近任务 □）
    //
    // 刻意按 AOSP SystemUI 的**几何形状**来画，不用「房子」代表主页、「窗口」代表最近任务：
    // 用户盯着的是被控端的导航栏，投屏界面上的这三个键必须一眼认出是同一套东西。
    // 描边风格、线宽 2f 与本项目其余图标保持一致。
    // ------------------------------------------------------------------

    /**
     * 导航「主页」键：**空心圆**（AOSP 里是一个圆环，不是房子）。
     * 圆心 (12,12)、半径 7，描边 2 → 视觉直径约 16dp。
     */
    val NavHome: ImageVector = ImageVector.Builder(
        name = "NavHome",
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
        // 两段 180° 圆弧拼成整圆；isMoreThanHalf=false 表示每段扫过 ≤180°，
        // 若传 true 会走大弧那一侧，圆就画歪了。
        moveTo(12f, 5f)
        arcTo(7f, 7f, 0f, false, true, 12f, 19f)
        arcTo(7f, 7f, 0f, false, true, 12f, 5f)
        close()
    }.build()

    /**
     * 导航「最近任务」键：**空心圆角方形**（AOSP 里是一个圆角矩形，不带顶栏）。
     * 14×14 的方框 + 半径 2 的圆角，与 [NavHome] 的圆视觉重量接近。
     */
    val NavRecents: ImageVector = ImageVector.Builder(
        name = "NavRecents",
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
        moveTo(7f, 5f)
        lineTo(17f, 5f)
        // 四角各用一段 90° 圆弧做圆角（半径 2）
        arcTo(2f, 2f, 0f, false, true, 19f, 7f)
        lineTo(19f, 17f)
        arcTo(2f, 2f, 0f, false, true, 17f, 19f)
        lineTo(7f, 19f)
        arcTo(2f, 2f, 0f, false, true, 5f, 17f)
        lineTo(5f, 7f)
        arcTo(2f, 2f, 0f, false, true, 7f, 5f)
        close()
    }.build()

    /** 电源键 */
    val Power: ImageVector = ImageVector.Builder(
        name = "Power",
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
        moveTo(12f, 3f)
        lineTo(12f, 12f)
        moveTo(17.32f, 8.27f)
        arcTo(6.5f, 6.5f, 0f, true, true, 6.68f, 8.27f)
    }.build()

    /** 旋转屏幕 */
    val Rotate: ImageVector = ImageVector.Builder(
        name = "Rotate",
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
        moveTo(4f, 12f)
        arcTo(8f, 8f, 0f, false, true, 12f, 4f)
        moveTo(12f, 4f)
        lineTo(8.6f, 7.4f)
        moveTo(12f, 4f)
        lineTo(15.4f, 7.4f)
        moveTo(12f, 20f)
        arcTo(8f, 8f, 0f, false, true, 20f, 12f)
        moveTo(20f, 12f)
        lineTo(16.6f, 15.4f)
        moveTo(20f, 12f)
        lineTo(16.6f, 8.6f)
    }.build()

    /** 全屏切换 */
    val Fullscreen: ImageVector = ImageVector.Builder(
        name = "Fullscreen",
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
        moveTo(4f, 9f)
        lineTo(4f, 4f)
        lineTo(9f, 4f)
        moveTo(15f, 4f)
        lineTo(20f, 4f)
        lineTo(20f, 9f)
        moveTo(20f, 15f)
        lineTo(20f, 20f)
        lineTo(15f, 20f)
        moveTo(9f, 20f)
        lineTo(4f, 20f)
        lineTo(4f, 15f)
    }.build()

    /** 音量 + / 音量键 */
    val VolumeUp: ImageVector = ImageVector.Builder(
        name = "VolumeUp",
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
        moveTo(4f, 9f)
        lineTo(8f, 9f)
        lineTo(13f, 5f)
        lineTo(13f, 19f)
        lineTo(8f, 15f)
        lineTo(4f, 15f)
        close()
        moveTo(16.5f, 9f)
        arcTo(3f, 3f, 0f, false, true, 16.5f, 15f)
        moveTo(19f, 6.5f)
        arcTo(6f, 6f, 0f, false, true, 19f, 17.5f)
    }.build()

    /** 截图 / 相机 */
    val Camera: ImageVector = ImageVector.Builder(
        name = "Camera",
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
        moveTo(3f, 7f)
        lineTo(8f, 7f)
        lineTo(10f, 5f)
        lineTo(14f, 5f)
        lineTo(16f, 7f)
        lineTo(21f, 7f)
        lineTo(21f, 18f)
        lineTo(3f, 18f)
        close()
        oval(12f, 12.5f, 3.2f)
    }.build()

    /** 剪贴板 */
    val Clipboard: ImageVector = ImageVector.Builder(
        name = "Clipboard",
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
        moveTo(7f, 4f)
        lineTo(17f, 4f)
        lineTo(17f, 20f)
        lineTo(7f, 20f)
        close()
        moveTo(9.5f, 4f)
        lineTo(9.5f, 2.5f)
        lineTo(14.5f, 2.5f)
        lineTo(14.5f, 4f)
    }.build()

    /** 警告（危险命令 / 异常） */
    val Warning: ImageVector = ImageVector.Builder(
        name = "Warning",
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
        moveTo(12f, 4f)
        lineTo(21f, 20f)
        lineTo(3f, 20f)
        close()
        moveTo(12f, 10f)
        lineTo(12f, 14.5f)
        moveTo(12f, 17f)
        lineTo(12f, 17.01f)
    }.build()

    /** 播放 / 开始投屏 */
    val Play: ImageVector = ImageVector.Builder(
        name = "Play",
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
        moveTo(8f, 5f)
        lineTo(19f, 12f)
        lineTo(8f, 19f)
        close()
    }.build()

    /** 新增（手动添加设备） */
    val Add: ImageVector = ImageVector.Builder(
        name = "Add",
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
        moveTo(12f, 5f)
        lineTo(12f, 19f)
        moveTo(5f, 12f)
        lineTo(19f, 12f)
    }.build()

    /** 删除 / 忘记设备 */
    val Delete: ImageVector = ImageVector.Builder(
        name = "Delete",
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
        moveTo(6.5f, 7f)
        lineTo(17.5f, 7f)
        moveTo(9f, 7f)
        lineTo(9f, 19f)
        lineTo(15f, 19f)
        lineTo(15f, 7f)
        moveTo(7.5f, 7f)
        lineTo(8.5f, 4f)
        lineTo(15.5f, 4f)
        lineTo(16.5f, 7f)
    }.build()

    /**
     * 编辑 / 重命名（长按条目改别名用）。
     *
     * 一支指向左下角的铅笔：笔尾在右上（用一段半圆收顶），两条平行边斜向左下，
     * 笔尖收在 (2,22)。纯线条、与其余图标同为 2f 描边 + 圆头圆角。
     */
    val Edit: ImageVector = ImageVector.Builder(
        name = "Edit",
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
        // 笔尾：从 (17,3.5) 顺时针扫半圆到 (20.5,7)，弧顶朝右上（铅笔的橡皮端）
        moveTo(17f, 3.5f)
        arcTo(2.83f, 2.83f, 0f, false, true, 20.5f, 7f)
        // 右下侧笔杆 → 笔尖
        lineTo(7.5f, 20f)
        lineTo(2f, 22f)
        // 笔尖另一侧 → close() 回到起点，形成左上侧笔杆
        lineTo(3.5f, 16.5f)
        close()
    }.build()

    /** 复制 */
    val Copy: ImageVector = ImageVector.Builder(
        name = "Copy",
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
        moveTo(9f, 9f)
        lineTo(19f, 9f)
        lineTo(19f, 19f)
        lineTo(9f, 19f)
        close()
        moveTo(15f, 15f)
        lineTo(15f, 5f)
        lineTo(5f, 5f)
        lineTo(5f, 15f)
        lineTo(15f, 15f)
    }.build()

    /** Wi-Fi（发现页 / 网络状态） */
    val Wifi: ImageVector = ImageVector.Builder(
        name = "Wifi",
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
        oval(12f, 19f, 1.8f)
        moveTo(7.05f, 14.05f)
        arcTo(7f, 7f, 0f, false, true, 16.95f, 14.05f)
        moveTo(2.81f, 9.81f)
        arcTo(13f, 13f, 0f, false, true, 21.19f, 9.81f)
    }.build()

    /** 锁（配对 / TLS） */
    val Lock: ImageVector = ImageVector.Builder(
        name = "Lock",
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
        moveTo(6f, 11f)
        lineTo(18f, 11f)
        lineTo(18f, 20f)
        lineTo(6f, 20f)
        close()
        moveTo(8.5f, 11f)
        lineTo(8.5f, 8f)
        arcTo(3.5f, 3.5f, 0f, false, true, 15.5f, 8f)
        lineTo(15.5f, 11f)
    }.build()

    /** 信息 / 关于 */
    val Info: ImageVector = ImageVector.Builder(
        name = "Info",
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
        oval(12f, 12f, 8f)
        moveTo(12f, 11f)
        lineTo(12f, 16f)
        moveTo(12f, 8f)
        lineTo(12f, 8.01f)
    }.build()
}

/**
 * PathBuilder 扩展：画一个完整的圆（4 段椭圆弧 + close）。
 * 供上面各图标复用，避免到处手写 arcTo。
 */
private fun PathBuilder.oval(cx: Float, cy: Float, r: Float) {
    moveTo(cx, cy - r)
    arcTo(r, r, 0f, false, true, cx + r, cy)
    arcTo(r, r, 0f, false, true, cx, cy + r)
    arcTo(r, r, 0f, false, true, cx - r, cy)
    arcTo(r, r, 0f, false, true, cx, cy - r)
    close()
}
