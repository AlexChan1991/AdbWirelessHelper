package com.adb.adbwirelesshelper.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.adb.adbwirelesshelper.MainActivity
import com.adb.adbwirelesshelper.R
import com.adb.adbwirelesshelper.util.Logx

/**
 * 连接保活前台服务（需求 A4）。
 *
 * - Android 8+ 用 startForegroundService 启动；
 * - 通知带「断开全部」动作，点击后本服务会广播 [BROADCAST_DISCONNECT_ALL]，
 *   由 App 侧（ViewModel / Repository）订阅后执行真正的断连；
 * - START_STICKY：被系统杀掉后尽量重建。
 */
class ConnectionForegroundService : Service() {

    private var currentText: String = ""

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        currentText = getString(R.string.notification_text_idle)
        try {
            startForeground(NOTIFICATION_ID, buildNotification(currentText))
            Logx.i(TAG, "前台服务已启动")
        } catch (t: Throwable) {
            // Android 12+ 后台启动限制 / 12 以下缺失通知权限时可能抛异常，不能让 App 崩
            Logx.e(TAG, "startForeground 失败: ${t.message}")
        }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (!text.isNullOrEmpty()) {
                    applyText(text)
                }
            }

            ACTION_DISCONNECT_ALL -> {
                Logx.i(TAG, "收到「断开全部」动作")
                applyText(getString(R.string.notification_text_idle))
                val broadcast = Intent(BROADCAST_DISCONNECT_ALL).setPackage(packageName)
                sendBroadcast(broadcast)
            }

            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Logx.i(TAG, "前台服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 更新通知文案（实例方法） */
    private fun applyText(text: String) {
        currentText = text
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null) {
            Logx.w(TAG, "NotificationManager 不可用，跳过通知更新")
            return
        }
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (t: Throwable) {
            Logx.e(TAG, "更新通知失败: ${t.message}")
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQ_CONTENT,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingIntentFlags()
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            REQ_DISCONNECT,
            Intent(this, ConnectionForegroundService::class.java).setAction(ACTION_DISCONNECT_ALL),
            pendingIntentFlags()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notification_title_prefix))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(android.app.Notification.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_launcher,
                getString(R.string.service_stop_action),
                disconnectIntent
            )
            .build()
    }

    private fun pendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    companion object {
        private const val TAG = "ConnectionForegroundService"

        /** 通知渠道 ID */
        const val CHANNEL_ID = "adb_wireless_connection"

        /** 常驻通知 ID */
        const val NOTIFICATION_ID = 1001

        /** 动作：更新通知文案 */
        const val ACTION_UPDATE = "com.adb.adbwirelesshelper.action.UPDATE"

        /** 动作：断开全部（通知按钮） */
        const val ACTION_DISCONNECT_ALL = "com.adb.adbwirelesshelper.action.DISCONNECT_ALL"

        /** 动作：停止服务 */
        const val ACTION_STOP = "com.adb.adbwirelesshelper.action.STOP"

        /** 广播：App 内组件订阅后执行真正断连 */
        const val BROADCAST_DISCONNECT_ALL = "com.adb.adbwirelesshelper.broadcast.DISCONNECT_ALL"

        /** Intent 附加参数：通知文案 */
        const val EXTRA_TEXT = "extra_text"

        private const val REQ_CONTENT = 11
        private const val REQ_DISCONNECT = 12

        @Volatile
        private var instance: ConnectionForegroundService? = null

        /** 创建通知渠道（IMPORTANCE_LOW，不发声音）。幂等，可重复调用。 */
        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) {
                return
            }
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        /** 启动前台服务。Android 8+ 自动走 startForegroundService。 */
        fun start(context: Context, text: String) {
            createChannel(context)
            val intent = Intent(context, ConnectionForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TEXT, text)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                // Android 12+ 若 App 处于后台，startForegroundService 会抛
                // ForegroundServiceStartNotAllowedException；这里降级为普通启动，避免崩溃。
                Logx.e(TAG, "startForegroundService 失败: ${t.message}")
                runCatching { context.startService(intent) }
            }
        }

        /** 停止前台服务 */
        fun stop(context: Context) {
            val intent = Intent(context, ConnectionForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.stopService(intent) }
                .onFailure { Logx.e(TAG, "stopService 失败: ${it.message}") }
        }

        /**
         * 更新通知文案（无 Context 版本）：服务在运行时直接改通知。
         * 若服务未运行，本调用为空操作（请先 [start]）。
         */
        fun update(text: String) {
            val svc = instance
            if (svc == null) {
                Logx.w(TAG, "服务未运行，update(\"$text\") 被忽略")
                return
            }
            svc.applyText(text)
        }

        /** 更新通知文案（带 Context 版本）：服务未运行时会先拉起服务。 */
        fun update(context: Context, text: String) {
            val svc = instance
            if (svc == null) {
                start(context, text)
            } else {
                svc.applyText(text)
            }
        }
    }
}
