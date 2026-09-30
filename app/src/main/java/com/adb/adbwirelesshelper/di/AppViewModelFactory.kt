package com.adb.adbwirelesshelper.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.adb.adbwirelesshelper.ui.screen.DeviceDetailViewModel
import com.adb.adbwirelesshelper.ui.screen.DeviceListViewModel
import com.adb.adbwirelesshelper.ui.screen.FileBrowserViewModel
import com.adb.adbwirelesshelper.ui.screen.MirrorViewModel
import com.adb.adbwirelesshelper.ui.screen.ShellViewModel

/**
 * 统一的 ViewModel 工厂。
 *
 * ⚠️ 跨工程师契约：各 ViewModel 的构造参数顺序必须与此处一致：
 *  - DeviceListViewModel(discoveryRepository, pairingManager, deviceRepository,
 *                         connectionHistoryRepository, deviceAliasRepository)
 *  - DeviceDetailViewModel(deviceRepository, deviceInfoCollector, appSettings)
 *  - MirrorViewModel(scrcpyDeployer, deviceRepository, appSettings)
 *  - ShellViewModel(shellExecutor, deviceRepository, appSettings)
 *  - FileBrowserViewModel(fileRepository, deviceRepository)
 *
 * 第二个参数 deviceRepository 的用途：文件管理页顶栏副标题要显示**设备名**（而不是裸 serial），
 * 且掉线判定必须去 `adb devices -l` 探测真实连接状态 —— 「列目录失败」也可能是权限问题，
 * 不能等同于掉线。两件事都需要 DeviceRepository。
 */
class AppViewModelFactory(private val c: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(DeviceListViewModel::class.java) ->
            DeviceListViewModel(
                c.discoveryRepository,
                c.pairingManager,
                c.deviceRepository,
                c.connectionHistoryRepository,
                c.deviceAliasRepository
            ) as T

        modelClass.isAssignableFrom(DeviceDetailViewModel::class.java) ->
            DeviceDetailViewModel(c.deviceRepository, c.deviceInfoCollector, c.appSettings) as T

        modelClass.isAssignableFrom(MirrorViewModel::class.java) ->
            MirrorViewModel(c.scrcpyDeployer, c.deviceRepository, c.appSettings) as T

        modelClass.isAssignableFrom(ShellViewModel::class.java) ->
            ShellViewModel(c.shellExecutor, c.deviceRepository, c.appSettings) as T

        modelClass.isAssignableFrom(FileBrowserViewModel::class.java) ->
            FileBrowserViewModel(c.fileRepository, c.deviceRepository) as T

        else -> throw IllegalArgumentException("Unknown VM: $modelClass")
    }
}
