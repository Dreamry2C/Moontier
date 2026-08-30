package cn.moonflow.easytier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class KeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ConfigStore(applicationContext).loadSettings().keepAliveNotification) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, createNotification())
        AppDiagnostics.event("keepalive", "增强保活通知已启动")
        return START_STICKY
    }

    override fun onDestroy() {
        AppDiagnostics.event("keepalive", "增强保活通知已停止")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("MoonTier")
            .setContentText("增强保活已开启")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MoonTier 增强保活", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_ID = "moontier_keepalive"
        private const val NOTIFICATION_ID = 2002

        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (!enabled) {
                context.stopService(intent)
                return
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                AppDiagnostics.error("keepalive", "增强保活服务启动失败", it)
            }
        }
    }
}
