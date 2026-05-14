package net.wanners.groceries

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.net.NetworkInterface
import java.net.Inet4Address

class ServerService : LifecycleService() {

    private var server: GroceriesServer? = null
    private var store: Store? = null
    private var discovery: Discovery? = null
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            else -> startServerOnce()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startServerOnce() {
        if (server != null) return

        val itemsFile = File(filesDir, "items.json")
        val store = Store(itemsFile).also { this.store = it }

        val ips = lanIPv4Addresses()
        val port = PORT
        val notification = buildNotification(ips, port)
        startForegroundCompat(notification)

        server = GroceriesServer(store).also { it.start(port) }

        discovery = Discovery(this).also { it.register(port) }

        ServerHolder.mutate {
            it.copy(
                running = true,
                port = port,
                ips = ips,
                mdnsHost = "groceries.local",
                itemCount = store.snapshot().size,
                startedAt = System.currentTimeMillis(),
            )
        }

        stateJob = lifecycleScope.launch(Dispatchers.Default) {
            store.changes.collectLatest {
                val count = store.snapshot().size
                ServerHolder.mutate { st -> st.copy(itemCount = count) }
                updateNotification(buildNotification(ips, port))
            }
        }
    }

    private fun stopSelfCleanly() {
        stateJob?.cancel(); stateJob = null
        discovery?.unregister(); discovery = null
        server?.stop(); server = null
        store = null
        ServerHolder.mutate { ServerState() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopSelfCleanly()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(notification: Notification) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification)
    }

    private fun buildNotification(ips: List<String>, port: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val urlLine = ips.firstOrNull()?.let { "http://$it:$port" } ?: "http://groceries.local:$port"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Grocery list running")
            .setContentText(urlLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                buildString {
                    append("http://groceries.local:$port\n")
                    ips.forEach { append("http://$it:$port\n") }
                }.trim()
            ))
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Grocery list HTTP server" }
        nm.createNotificationChannel(channel)
    }

    private fun lanIPv4Addresses(): List<String> = buildList {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@buildList
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        add(addr.hostAddress!!)
                    }
                }
            }
        } catch (_: Throwable) {
            // best effort
        }
    }

    companion object {
        const val PORT = 8080
        private const val NOTIF_ID = 0xC0FFEE
        private const val CHANNEL_ID = "groceries-server"
        const val ACTION_STOP = "net.wanners.groceries.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ServerService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
