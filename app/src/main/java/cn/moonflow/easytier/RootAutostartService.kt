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
import kotlinx.coroutines.withContext

class RootAutostartService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: RootTierController? = null
    private var taskStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (taskStarted) return START_NOT_STICKY
        taskStarted = true
        scope.launch {
            val settings = ConfigStore(applicationContext).loadSettings()
            AppDiagnostics.initialize(applicationContext, settings.coreLogLevel)
            AppDiagnostics.event("boot", "开机任务开始")
            val root = withContext(Dispatchers.IO) { RootManager.probe(refresh = true) }
            if (!root.available) {
                AppDiagnostics.warn("boot", "未获得 Root，跳过开机任务")
            } else {
                if (settings.bootAdbEnabled) {
                    val result = withContext(Dispatchers.IO) { RootSystemManager.ensureAdbTcp() }
                    if (result.success) {
                        AppDiagnostics.event("boot", result.output.ifBlank { "ADB 5555 已就绪" })
                    } else {
                        AppDiagnostics.warn("boot", "ADB 5555 启动失败: ${result.output}")
                    }
                }
                if (settings.bootAutoStart && settings.rootModeEnabled) {
                    AppDiagnostics.event("boot", "Root 网络恢复开始")
                    controller = RootTierController(applicationContext)
                    delay(8_000)
                    AppDiagnostics.event("boot", "Root 网络恢复完成")
                }
            }
            controller?.release()
            controller = null
            AppDiagnostics.event("boot", "开机任务完成")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MoonTier")
            .setContentText("正在执行开机任务")
            .setOngoing(false)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MoonTier 开机任务", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_ID = "moontier_boot"
        private const val NOTIFICATION_ID = 1001
    }
}
