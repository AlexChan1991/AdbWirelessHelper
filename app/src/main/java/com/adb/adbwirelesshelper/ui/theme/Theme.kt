package com.adb.adbwirelesshelper.ui.theme

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

// 形状体系（圆角阶梯，规格 §1.3）在同包下，无需额外 import

/**
 * 应用主题（Material3）。
 *
 * @param themeMode 0 = 跟随系统，1 = 强制浅色，2 = 强制深色（与 AppSettings.themeMode 对齐）
 * @param content   内容
 *
 * 说明：刻意不做动态取色（dynamicColor），避免不同机型配色差异导致的对比度问题。
 *
 * ⚠️ 需要「深/浅色分支的自定义色」时，**不要**用 `isSystemInDarkTheme()`：
 * 它只反映**系统**深浅，而本 App 有「跟随系统 / 强制浅色 / 强制深色」三档，
 * 手动档与系统相反时它会判反（典型表现：深色容器上取了浅色版的深绿状态色 → 看不见）。
 * 正确做法是从**实际生效的** `MaterialTheme.colorScheme` 推导（见 StatusStrip 里的
 * 感知亮度判断），或优先直接使用 colorScheme 的语义色
 * （primary / error / surfaceVariant …）。
 */
@Composable
fun AdbWifiTheme(
    themeMode: Int = 0,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        1 -> false
        2 -> true
        else -> systemDark
    }

    val colorScheme = if (useDark) {
        darkColorScheme(
            primary = PrimaryDark,
            onPrimary = OnPrimaryDark,
            primaryContainer = PrimaryContainerDark,
            onPrimaryContainer = OnPrimaryContainerDark,
            secondary = SecondaryDark,
            onSecondary = OnSecondaryDark,
            secondaryContainer = SecondaryContainerDark,
            onSecondaryContainer = OnSecondaryContainerDark,
            tertiary = TertiaryDark,
            onTertiary = OnTertiaryDark,
            tertiaryContainer = TertiaryContainerDark,
            onTertiaryContainer = OnTertiaryContainerDark,
            background = BackgroundDark,
            onBackground = OnBackgroundDark,
            surface = SurfaceDark,
            onSurface = OnSurfaceDark,
            surfaceVariant = SurfaceVariantDark,
            onSurfaceVariant = OnSurfaceVariantDark,
            // M3 Expressive 多级 container：层次来源（规格 §1.2.2）
            surfaceContainerLowest = SurfaceContainerLowestDark,
            surfaceContainerLow = SurfaceContainerLowDark,
            surfaceContainer = SurfaceContainerDark,
            surfaceContainerHigh = SurfaceContainerHighDark,
            surfaceContainerHighest = SurfaceContainerHighestDark,
            outline = OutlineDark,
            error = ErrorDark,
            onError = OnErrorDark
        )
    } else {
        lightColorScheme(
            primary = PrimaryLight,
            onPrimary = OnPrimaryLight,
            primaryContainer = PrimaryContainerLight,
            onPrimaryContainer = OnPrimaryContainerLight,
            secondary = SecondaryLight,
            onSecondary = OnSecondaryLight,
            secondaryContainer = SecondaryContainerLight,
            onSecondaryContainer = OnSecondaryContainerLight,
            tertiary = TertiaryLight,
            onTertiary = OnTertiaryLight,
            tertiaryContainer = TertiaryContainerLight,
            onTertiaryContainer = OnTertiaryContainerLight,
            background = BackgroundLight,
            onBackground = OnBackgroundLight,
            surface = SurfaceLight,
            onSurface = OnSurfaceLight,
            surfaceVariant = SurfaceVariantLight,
            onSurfaceVariant = OnSurfaceVariantLight,
            // M3 Expressive 多级 container：层次来源（规格 §1.2.2）
            surfaceContainerLowest = SurfaceContainerLowestLight,
            surfaceContainerLow = SurfaceContainerLowLight,
            surfaceContainer = SurfaceContainerLight,
            surfaceContainerHigh = SurfaceContainerHighLight,
            surfaceContainerHighest = SurfaceContainerHighestLight,
            outline = OutlineLight,
            error = ErrorLight,
            onError = OnErrorLight
        )
    }

    // 系统栏的前景色 / 底色都必须跟**实际生效**的主题走，详见 SystemBarsAppearance 的注释。
    SystemBarsAppearance(useDark = useDark, barsColor = colorScheme.surface)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

/**
 * 把系统栏（状态栏 / 导航栏）图标的前景色对齐到**实际生效**的主题。
 *
 * 为什么必须在 Compose 里做，而不是在 Activity 里读 `resources.configuration.uiMode`：
 * 设置页提供「跟随系统 / 强制浅色 / 强制深色」三档，而 `uiMode` 只反映**系统**的深浅。
 * 用户手动选了「深色」而系统仍是浅色时，`uiMode` 会判成浅色 → 图标仍是深色 →
 * 压在深色背景上几乎看不见。这里拿到的 [useDark] 是 [AdbWifiTheme] 真正选中的那一路，
 * 三档都不会判错。
 *
 * 为什么 themes.xml 里的 `statusBarColor` / `windowLightStatusBar` 必须删掉：
 * targetSdk = 35 在 Android 15+ 被强制 edge-to-edge，`statusBarColor` 已不再生效，
 * 系统栏背后画的是应用内容（Scaffold 背景 = `colorScheme.surface`，深色下 #FF111318）；
 * 而写死的 `windowLightStatusBar = true` 又强制深色图标 —— 深色图标压深色背景 = 看不见。
 *
 * @param useDark   实际生效的是否为深色主题（已合并「跟随系统 / 强制浅色 / 强制深色」三档）。
 * @param barsColor 系统栏底色，取实际生效的 `colorScheme.surface`。
 */
@Composable
private fun SystemBarsAppearance(
    useDark: Boolean,
    barsColor: Color,
) {
    val view: View = LocalView.current
    val context: Context = LocalContext.current

    DisposableEffect(useDark, barsColor, view, context) {
        val window = (context as? Activity)?.window
        if (window != null) {
            val controller: WindowInsetsControllerCompat =
                WindowCompat.getInsetsController(window, view)
            // 语义：isAppearanceLight* = true 表示「背景浅 → 用深色图标」，所以取 !useDark
            controller.isAppearanceLightStatusBars = !useDark
            controller.isAppearanceLightNavigationBars = !useDark

            // ★ Android ≤14 不强制 edge-to-edge，statusBarColor / navigationBarColor **仍然生效**。
            //   上一版把它们从 themes.xml 里删掉后，这里若不显式设，就会落到 parent
            //   `Theme.Material.Light.NoActionBar` 的默认色（大概率是深条）；
            //   浅色主题下图标是深色（isAppearanceLightStatusBars = true）
            //   → 深色图标压深色条 → 和 #31 同一类的可读性问题，只是搬到了 ≤14。
            //   取实际生效的 colorScheme.surface，两个 API 段都自洽：
            //     ≥15：这两个值被忽略，走上面 Scaffold / 顶栏的 inset 逻辑；
            //     ≤14：它们生效，底色与图标对比度一致。
            //   注：API 35 已废弃这两个属性，但**仍可调用且必须在 ≤14 生效**，
            //   所以刻意保留，不改用替代方案。
            window.statusBarColor = barsColor.toArgb()
            window.navigationBarColor = barsColor.toArgb()
        }
        // 无需回滚：下一次重组会重新设置，Activity 销毁时 window 一并失效。
        onDispose { }
    }
}
