package com.adb.adbwirelesshelper.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.adb.adbwirelesshelper.AdbWifiApp
import com.adb.adbwirelesshelper.di.AppViewModelFactory
import com.adb.adbwirelesshelper.ui.screen.DeviceDetailScreen
import com.adb.adbwirelesshelper.ui.screen.DeviceDetailViewModel
import com.adb.adbwirelesshelper.ui.screen.DeviceListScreen
import com.adb.adbwirelesshelper.ui.screen.DeviceListViewModel
import com.adb.adbwirelesshelper.ui.screen.FileBrowserScreen
import com.adb.adbwirelesshelper.ui.screen.FileBrowserViewModel
import com.adb.adbwirelesshelper.ui.screen.MirrorScreen
import com.adb.adbwirelesshelper.ui.screen.MirrorViewModel
import com.adb.adbwirelesshelper.ui.screen.SettingsScreen
import com.adb.adbwirelesshelper.ui.screen.ShellScreen
import com.adb.adbwirelesshelper.ui.screen.ShellViewModel

/**
 * 路由定义（单 Activity + Navigation Compose）。
 *
 * 说明：serial 形如 `192.168.1.5:37123`，不含 `/`，可直接拼进路径，无需 URL 编码，
 * 也就避免了 Navigation 对路径参数做解码时与编码不一致的坑。
 */
sealed class Screen(val route: String) {
    data object DeviceList : Screen("devices")
    data object Detail : Screen("detail/{serial}")
    data object Mirror : Screen("mirror/{serial}")
    data object Shell : Screen("shell/{serial}")
    data object Files : Screen("files/{serial}")
    data object Settings : Screen("settings")
}

/**
 * 应用导航宿主。
 *
 * ⚠️ 跨工程师契约：各 Screen 的签名如下，请严格按此实现 Composable：
 *  - DeviceListScreen(vm, onDevice: (serial) -> Unit, onSettings: () -> Unit)
 *  - DeviceDetailScreen(vm, serial, onBack, onMirror, onConsole, onFiles)
 *  - MirrorScreen(vm, serial, onExit)
 *  - ShellScreen(vm, serial, onBack)
 *  - FileBrowserScreen(vm, serial, onBack)
 *  - SettingsScreen(onBack)
 */
@Composable
fun AdbWifiNavHost(
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current
    val container = (context.applicationContext as AdbWifiApp).appContainer

    NavHost(
        navController = navController,
        startDestination = Screen.DeviceList.route
    ) {
        composable(Screen.DeviceList.route) {
            val vm: DeviceListViewModel = viewModel(factory = AppViewModelFactory(container))
            DeviceListScreen(
                vm = vm,
                onDevice = { serial -> navController.navigate("detail/$serial") },
                onSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(
            route = Screen.Detail.route,
            arguments = listOf(navArgument("serial") { type = NavType.StringType })
        ) { backStackEntry ->
            val serial = backStackEntry.arguments?.getString("serial").orEmpty()
            val vm: DeviceDetailViewModel = viewModel(factory = AppViewModelFactory(container))
            DeviceDetailScreen(
                vm = vm,
                serial = serial,
                onBack = { navController.popBackStack() },
                onMirror = { navController.navigate("mirror/$serial") },
                onConsole = { navController.navigate("shell/$serial") },
                onFiles = { navController.navigate("files/$serial") }
            )
        }

        composable(
            route = Screen.Mirror.route,
            arguments = listOf(navArgument("serial") { type = NavType.StringType })
        ) { backStackEntry ->
            val serial = backStackEntry.arguments?.getString("serial").orEmpty()
            val vm: MirrorViewModel = viewModel(factory = AppViewModelFactory(container))
            MirrorScreen(
                vm = vm,
                serial = serial,
                onExit = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Shell.route,
            arguments = listOf(navArgument("serial") { type = NavType.StringType })
        ) { backStackEntry ->
            val serial = backStackEntry.arguments?.getString("serial").orEmpty()
            val vm: ShellViewModel = viewModel(factory = AppViewModelFactory(container))
            ShellScreen(
                vm = vm,
                serial = serial,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Files.route,
            arguments = listOf(navArgument("serial") { type = NavType.StringType })
        ) { backStackEntry ->
            val serial = backStackEntry.arguments?.getString("serial").orEmpty()
            val vm: FileBrowserViewModel = viewModel(factory = AppViewModelFactory(container))
            FileBrowserScreen(
                vm = vm,
                serial = serial,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
