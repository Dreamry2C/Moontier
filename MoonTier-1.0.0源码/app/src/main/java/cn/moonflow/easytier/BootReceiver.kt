package cn.moonflow.easytier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = ConfigStore(context.applicationContext).loadSettings()
        val restoreRootNetwork = settings.bootAutoStart && settings.rootModeEnabled
        if (!restoreRootNetwork && !settings.bootAdbEnabled) return
        AppDiagnostics.initialize(context.applicationContext, settings.coreLogLevel)
        val serviceIntent = Intent(context, RootAutostartService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
        }.onFailure { AppDiagnostics.error("boot", "Root 自启动服务启动失败", it) }
    }
}
