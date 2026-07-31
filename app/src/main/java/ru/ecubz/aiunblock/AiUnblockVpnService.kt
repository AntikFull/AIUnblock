package ru.ecubz.aiunblock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException

class AiUnblockVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "ru.ecubz.aiunblock.CONNECT"
        const val ACTION_DISCONNECT = "ru.ecubz.aiunblock.DISCONNECT"
        private const val NOTIFICATION_CHANNEL = "ai_unblock_active"
        private const val NOTIFICATION_ID = 1001

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }

    private external fun TProxyStartService(configPath: String, fd: Int): Boolean
    private external fun TProxyStopService(): Boolean
    private external fun TProxyIsRunning(): Boolean
    @Suppress("unused")
    private external fun TProxyGetStats(): LongArray

    private var tunInterface: ParcelFileDescriptor? = null
    private var socksRelay: LocalSocksRelay? = null
    private var gatewaySelector: GatewaySelector? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopTunnel()
            return Service.START_NOT_STICKY
        }

        startInForeground()
        startTunnel()
        return Service.START_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun startTunnel() {
        if (tunInterface != null || TProxyIsRunning()) return
        TunnelStateStore.update(TunnelState.Starting)

        try {
            val installedPackages = AppSelection.activePackages(this).filter(::isPackageInstalled)
            if (installedPackages.isEmpty()) {
                throw IllegalStateException("Не найдено ни одного выбранного приложения")
            }

            val connectivity = getSystemService(ConnectivityManager::class.java)
            val selector = GatewaySelector(
                context = this,
                vpnService = this,
                activeNetwork = { connectivity.activeNetwork },
                onSelectionUpdated = { summary ->
                    if (tunInterface != null) {
                        TunnelStateStore.update(TunnelState.On(summary))
                    }
                },
            )
            gatewaySelector = selector
            val relay = LocalSocksRelay(
                vpnService = this,
                activeNetwork = { connectivity.activeNetwork },
                gatewayAddress = selector::addressFor,
                geoHideRules = GeoHideRoutingRules.load(this),
            )
            relay.start()
            socksRelay = relay

            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(1500)
                .setBlocking(false)
                .addAddress("10.111.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd7a:115c:a1e0::1", 126)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")

            if (Build.VERSION.SDK_INT >= 29) {
                builder.setMetered(false)
            }
            installedPackages.forEach(builder::addAllowedApplication)

            val established = builder.establish()
                ?: throw IllegalStateException("Android не создал VPN-интерфейс")
            tunInterface = established

            val configFile = writeTunnelConfig(
                port = relay.port,
                username = relay.username,
                password = relay.password,
            )
            if (!TProxyStartService(configFile.absolutePath, established.fd)) {
                throw IllegalStateException("Не удалось запустить TUN-движок")
            }

            getSharedPreferences("state", MODE_PRIVATE)
                .edit()
                .putBoolean("enabled", true)
                .apply()
            TunnelStateStore.update(TunnelState.On(selector.summary()))
            selector.start()
        } catch (error: Exception) {
            cleanupTunnel()
            TunnelStateStore.update(
                TunnelState.Error(error.message ?: "Ошибка запуска VPN"),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopTunnel() {
        cleanupTunnel()
        getSharedPreferences("state", MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", false)
            .apply()
        TunnelStateStore.update(TunnelState.Off)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupTunnel() {
        gatewaySelector?.close()
        gatewaySelector = null
        if (TProxyIsRunning()) {
            TProxyStopService()
        }
        try {
            tunInterface?.close()
        } catch (_: IOException) {
            Unit
        }
        tunInterface = null
        socksRelay?.close()
        socksRelay = null
    }

    private fun writeTunnelConfig(port: Int, username: String, password: String): File {
        val config = """
            tunnel:
              mtu: 1500
              ipv4: 10.111.0.1
              ipv6: 'fd7a:115c:a1e0::1'
              icmp: 'reply'
            socks5:
              port: $port
              address: '127.0.0.1'
              udp: 'udp'
              udp-address: '127.0.0.1'
              username: '$username'
              password: '$password'
            misc:
              task-stack-size: 86016
              tcp-buffer-size: 65536
              max-session-count: 256
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: null
              log-level: warn
        """.trimIndent()

        return File(cacheDir, "aiunblock-tunnel.yml").apply {
            writeText(config, Charsets.UTF_8)
            setReadable(false, false)
            setReadable(true, true)
            setWritable(false, false)
            setWritable(true, true)
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun startInForeground() {
        val activityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, AiUnblockVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(activityPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Выключить", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_text)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
