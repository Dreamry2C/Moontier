package cn.moonflow.easytier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RootAutostartService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: RootTierController? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        AppDiagnostics.event("boot", "Root 自启动恢复开始")
        controller = RootTierController(applicationContext)
        scope.launch {
            delay(8_000)
            AppDiagnostics.event("boot", "Root 自启动恢复完成")
            controller?.release()
            controller = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_sync)
            .setContentTitle("MoonTier")
            .setContentText("正在恢复 Root 网络")
            .setOngoing(false)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(this)
            .setSmallIcon(android.R.drawable.stat_sys_data_sync)
            .setContentTitle("MoonTier")
            .setContentText("正在恢复 Root 网络")
            .setOngoing(false)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MoonTier 自启动", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_ID = "moontier_boot"
        private const val NOTIFICATION_ID = 1001
    }
}
