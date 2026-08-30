package cn.moonflow.easytier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import org.json.JSONObject

class EasyTierVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppDiagnostics.initialize(applicationContext, ConfigStore(applicationContext).loadSettings().coreLogLevel)
        if (intent?.action == ACTION_STOP) {
            AppDiagnostics.event("vpn", "VPN service stop requested")
            shutdownVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        val json = intent?.getStringExtra(EXTRA_CONFIG).orEmpty()
        if (json.isBlank()) {
            AppDiagnostics.warn("vpn", "VPN service started without configuration")
            shutdownVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        disconnect()

        val config = runCatching { JSONObject(json) }.getOrNull()
        if (config == null) {
            AppDiagnostics.warn("vpn", "VPN configuration JSON is invalid")
            shutdownVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        vpnInterface = createVpnInterface(config)
        val pfd = vpnInterface
        if (pfd == null) {
            AppDiagnostics.error("vpn", "VPN interface creation returned null")
            shutdownVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        val instanceName = config.optString("instanceName")
        val code = NativeEasyTier.setTunFd(instanceName, pfd.fd)
        Log.i(TAG, "setTunFd(instance=$instanceName fd=${pfd.fd}) -> $code")
        if (code != 0) {
            val error = NativeEasyTier.getLastError().orEmpty()
            Log.e(TAG, "setTunFd failed: $error")
            AppDiagnostics.error("vpn", "setTunFd failed for $instanceName: $error")
            disconnect()
            NativeEasyTier.stopAllInstances()
            stopSelf()
            return START_NOT_STICKY
        }
        AppDiagnostics.event("vpn", "VPN interface attached to $instanceName")
        return START_STICKY
    }

    override fun onRevoke() {
        AppDiagnostics.warn("vpn", "VPN permission revoked by Android")
        shutdownVpn()
        stopSelf()
    }

    override fun onDestroy() {
        shutdownVpn()
        super.onDestroy()
    }

    private fun createVpnInterface(config: JSONObject): ParcelFileDescriptor? = runCatching {
        val address = config.optString("address", "10.0.0.2")
        val prefix = config.optInt("prefixLength", 24).coerceIn(1, 32)
        val mtu = config.optInt("mtu", 1400)
        val dns = config.optString("dns", "")
        val routes = config.optJSONArray("routes")
        val routePreview = buildList {
            if (routes != null) {
                for (i in 0 until minOf(routes.length(), 8)) add(routes.optString(i))
            }
        }
        Log.i(
            TAG,
                "createVpnInterface address=$address/$prefix mtu=$mtu routes=${routes?.length() ?: 0} " +
                "exitAuto=${config.optBoolean("exitNodeAutoRoutes", false)} " +
                "exitNodes=${config.optInt("exitNodeReachableCount", 0)}/${config.optInt("exitNodeCount", 0)} " +
                "preview=$routePreview"
        )
        AppDiagnostics.info(
            "vpn",
            "creating interface address=$address/$prefix mtu=$mtu routes=${routes?.length() ?: 0} exitAuto=${config.optBoolean("exitNodeAutoRoutes", false)}"
        )

        val builder = Builder()
            .setSession("MoonTier")
            .setMtu(mtu)
            .addAddress(address, prefix)
            .setBlocking(false)

        if (dns.isNotBlank()) builder.addDnsServer(dns)
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { Log.w(TAG, "addDisallowedApplication failed", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val directCoreIps = config.optJSONArray("directCoreIps")
            if (directCoreIps != null) {
                for (i in 0 until directCoreIps.length()) {
                    val ip = directCoreIps.optString(i)
                    if (ip.isNotBlank()) {
                        runCatching {
                            builder.excludeRoute(IpPrefix(java.net.InetAddress.getByName(ip), 32))
                        }
                            .onFailure { Log.w(TAG, "skip exclude route: $ip", it) }
                    }
                }
            }
        }

        if (routes != null) {
            for (i in 0 until routes.length()) {
                val cidr = routes.optString(i)
                val parts = cidr.split("/")
                if (parts.size == 2) {
                    runCatching { builder.addRoute(parts[0], parts[1].toInt()) }
                        .onFailure { Log.w(TAG, "skip invalid route: $cidr", it) }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        builder.establish()
    }.onFailure {
        Log.e(TAG, "createVpnInterface failed", it)
        AppDiagnostics.error("vpn", "VPN interface creation failed", it)
    }.getOrNull()

    private fun disconnect() {
        vpnInterface?.close()
        vpnInterface = null
    }

    private fun shutdownVpn() {
        disconnect()
        NativeEasyTier.stopAllInstances()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MoonTier VPN", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("MoonTier")
            .setContentText("VPN 正在运行")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MoonTierVpn"
        private const val CHANNEL_ID = "moontier_vpn"
        private const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "cn.moonflow.easytier.action.STOP_VPN"
        const val EXTRA_CONFIG = "vpn_config"
    }
}
