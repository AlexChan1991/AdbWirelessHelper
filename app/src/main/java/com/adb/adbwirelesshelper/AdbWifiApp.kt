package com.adb.adbwirelesshelper

import android.app.Application
import com.adb.adbwirelesshelper.di.AppContainer
import com.adb.adbwirelesshelper.util.Logx

/**
 * Application 子类：持有全局唯一的 [AppContainer]（懒加载），并初始化日志目录。
 *
 * AndroidManifest 中通过 android:name=".AdbWifiApp" 声明。
 */
class AdbWifiApp : Application() {

    /** 手写 DI 容器：首次访问时才创建，避免拖慢冷启动 */
    val appContainer: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Logx.init(this)
        Logx.i(TAG, "AdbWirelessHelper 启动")
    }

    companion object {
        private const val TAG = "AdbWifiApp"

        @Volatile
        lateinit var instance: AdbWifiApp
            private set
    }
}
