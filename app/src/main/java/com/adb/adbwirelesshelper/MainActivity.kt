package com.adb.adbwirelesshelper

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.adb.adbwirelesshelper.di.AppContainer
import com.adb.adbwirelesshelper.service.ConnectionForegroundService
import com.adb.adbwirelesshelper.ui.nav.AdbWifiNavHost
import com.adb.adbwirelesshelper.ui.theme.AdbWifiTheme
import com.adb.adbwirelesshelper.util.Logx

/**
 * 单 Activity 入口。
 *
 * 职责：
 * 1. 创建前台服务通知渠道（需求 A4）；
 * 2. 动态申请运行时权限（POST_NOTIFICATIONS 等，需求 A1）；
 * 3. setContent 挂载 Compose 导航宿主。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 通知渠道必须在任何 startForeground 之前建好
        ConnectionForegroundService.createChannel(this)

        // ★ 主题三档必须在这里接线：设置页那 3 个 chip 只是把选中态写进 AppSettings，
        //   之前 AdbWifiTheme 一直用默认 themeMode = 0（跟随系统），等于 chip 是装饰品。
        //   不接线的话「强制深色 / 强制浅色」对整 App 都不生效，
        //   SystemBarsAppearance 拿到的 useDark 也永远等于 isSystemInDarkTheme()。
        val container: AppContainer = (applicationContext as AdbWifiApp).appContainer

        setContent {
            val themeMode: Int by container.appSettings.themeMode.collectAsState(initial = 0)
            AdbWifiTheme(themeMode = themeMode) {
                RequestRuntimePermissions()
                AdbWifiNavHost()
            }
        }
    }
}

/**
 * 运行时权限申请（需求 A1）。
 *
 * 说明：
 * - INTERNET / ACCESS_WIFI_STATE / ACCESS_NETWORK_STATE / CHANGE_WIFI_MULTICAST_STATE
 *   均为普通权限，安装即授予，无需动态申请；
 * - POST_NOTIFICATIONS 是 Android 13（API 33）起的危险权限，必须动态申请；
 * - 若用户在系统设置里永久拒绝，这里只记录结果，由各功能页在使用时再提示引导。
 */
@Composable
private fun RequestRuntimePermissions() {
    val permissions: List<String> = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result: Map<String, Boolean> ->
        val denied = result.filterValues { !it }.keys
        if (denied.isEmpty()) {
            Logx.i(TAG, "权限已全部授予: ${result.keys}")
        } else {
            Logx.w(TAG, "以下权限被拒绝: $denied（相关功能将降级，不阻断主流程）")
        }
    }

    LaunchedEffect(permissions) {
        if (permissions.isNotEmpty()) {
            launcher.launch(permissions.toTypedArray())
        }
    }
}

private const val TAG = "MainActivity"
