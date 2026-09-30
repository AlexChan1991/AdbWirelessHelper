package com.adb.adbwirelesshelper.di

import android.content.Context
import com.adb.adbwirelesshelper.data.adb.AdbClient
import com.adb.adbwirelesshelper.data.adb.AdbRuntime
import com.adb.adbwirelesshelper.data.adb.NativeAdbClient
import com.adb.adbwirelesshelper.data.adb.PairingManager
import com.adb.adbwirelesshelper.data.adb.ShellExecutor
import com.adb.adbwirelesshelper.data.deviceinfo.DeviceInfoCollector
import com.adb.adbwirelesshelper.data.discovery.DiscoveryRepository
import com.adb.adbwirelesshelper.data.file.FileRepository
import com.adb.adbwirelesshelper.data.repository.ConnectionHistoryRepository
import com.adb.adbwirelesshelper.data.repository.DeviceAliasRepository
import com.adb.adbwirelesshelper.data.repository.DeviceRepository
import com.adb.adbwirelesshelper.data.scrcpy.ScrcpyServerDeployer
import com.adb.adbwirelesshelper.data.settings.AppSettings

/**
 * 手写 DI 容器（不使用 Hilt —— 见需求 3.4：单例约 10 个，手写容器足够，
 * 且能规避 kapt/KSP 与 AGP/Kotlin 版本矩阵带来的编译风险）。
 *
 * 全部成员懒加载，首次访问时才创建；进程内唯一实例，由 [com.adb.adbwirelesshelper.AdbWifiApp] 持有。
 *
 * ⚠️ 跨工程师契约：
 *  - DiscoveryRepository(context)            —— 由发现层工程师实现（单参构造）
 *  - DeviceInfoCollector(shellExecutor)      —— 由发现层工程师实现（单参构造）
 *  - ScrcpyServerDeployer(context, adbClient)—— 由 scrcpy 层工程师实现（双参构造）
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 偏好设置（DataStore） */
    val appSettings: AppSettings by lazy { AppSettings(appContext) }

    /** adb 运行时：二进制定位 / 密钥目录 / 环境变量 */
    val adbRuntime: AdbRuntime by lazy { AdbRuntime(appContext) }

    /** adb 命令门面 */
    val adbClient: AdbClient by lazy { NativeAdbClient(adbRuntime) }

    /** shell 执行器 */
    val shellExecutor: ShellExecutor by lazy { ShellExecutor(adbClient) }

    /** 配对状态机 */
    val pairingManager: PairingManager by lazy { PairingManager(adbClient) }

    /** 历史连接记录（曾经连过的设备，持久化到 DataStore） */
    val connectionHistoryRepository: ConnectionHistoryRepository by lazy {
        ConnectionHistoryRepository(appContext)
    }

    /**
     * 设备别名（持久化到 DataStore，与 [connectionHistoryRepository] 用**不同的文件**）。
     *
     * 分开的理由：历史会被用户「清空」，别名不该跟着一起没；且历史的落盘格式是定长
     * 5 字段，塞进别名就要处理旧数据兼容。
     */
    val deviceAliasRepository: DeviceAliasRepository by lazy {
        DeviceAliasRepository(appContext)
    }

    /** 已连接设备仓储（连上后自动往 [connectionHistoryRepository] 记一笔） */
    val deviceRepository: DeviceRepository by lazy {
        DeviceRepository(adbClient, connectionHistoryRepository)
    }

    /** 设备发现聚合（mDNS + 端口扫描 + 手动） */
    val discoveryRepository: DiscoveryRepository by lazy { DiscoveryRepository(appContext) }

    /** 设备信息采集器 */
    val deviceInfoCollector: DeviceInfoCollector by lazy { DeviceInfoCollector(shellExecutor) }

    /** scrcpy-server 部署器 */
    val scrcpyDeployer: ScrcpyServerDeployer by lazy {
        ScrcpyServerDeployer(appContext, adbClient)
    }

    /** 被控端文件管理（规格 §4.6） */
    val fileRepository: FileRepository by lazy { FileRepository(adbClient, shellExecutor) }
}
