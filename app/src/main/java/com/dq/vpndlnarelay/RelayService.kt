package com.dq.vpndlnarelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/** Foreground service that keeps the relay alive with the screen off. */
class RelayService : Service() {
    private var worker: Thread? = null
    @Volatile private var http: HttpRelay? = null
    @Volatile private var ssdp: SsdpResponder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        acquireLocks()
        stopWorker()
        val host = RelayPrefs.host(this)
        val port = RelayPrefs.port(this)
        RelayState.setRunning(true)
        RelayState.log("VPN DLNA Relay ${appVersion()} started")
        worker = thread(name = "relay-main") { relayLoop(host, port) }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun relayLoop(host: String, savedPort: Int) {
        var port = savedPort
        try {
            while (true) {
                report("Connecting to $host…")
                var server = if (port > 0) Upstream.probe(host, port) else null
                if (server == null) {
                    report("Scanning $host for its DLNA port…")
                    val result = PortScanner.scan(host) { RelayState.log(it) }
                    server = result.server
                    if (server == null) {
                        report(
                            if (result.hostResponded) "No DLNA server found on $host. Retrying…"
                            else "$host is unreachable. Is the VPN connected? Retrying…"
                        )
                        Thread.sleep(RETRY_MS)
                        continue
                    }
                    port = server.port
                    RelayPrefs.savePort(this, port)
                    RelayState.setDiscoveredPort(port)
                    RelayState.log("Found DLNA server on port $port")
                }
                serve(host, server)
            }
        } catch (e: InterruptedException) {
            // Stopped.
        } finally {
            stopServers()
        }
    }

    /** Runs the relay until the upstream server stops answering. */
    private fun serve(host: String, server: ServerInfo) {
        val relayUuid = UUID.nameUUIDFromBytes("vpn-dlna-relay:${server.uuid}".toByteArray()).toString()
        val sharing = "Sharing “${server.name}” ($host:${server.port})"
        try {
            val relay = HttpRelay(host, server.port, server.uuid, relayUuid, RelayState::log).also { it.start() }
            http = relay
            ssdp = SsdpResponder(relayUuid, relay.port, RelayState::log) { addresses ->
                report(
                    if (addresses.isEmpty()) "$sharing — waiting for Wi-Fi"
                    else "$sharing at ${addresses.joinToString { "$it:${relay.port}" }}"
                )
            }.also { it.start() }
        } catch (e: IOException) {
            report("Could not start relay: ${e.message}")
            stopServers()
            Thread.sleep(RETRY_MS)
            return
        }
        var misses = 0
        while (misses < MAX_MISSES) {
            Thread.sleep(HEALTH_CHECK_MS)
            if (Upstream.probe(host, server.port) == null) misses++ else misses = 0
        }
        RelayState.log("Lost the DLNA server at $host:${server.port}")
        stopServers()
    }

    private fun appVersion(): String =
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: "?"

    private fun report(status: String) {
        RelayState.setStatus(status)
        RelayState.log(status)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
    }

    private fun stopServers() {
        ssdp?.stop()
        ssdp = null
        http?.stop()
        http = null
    }

    private fun stopWorker() {
        worker?.let {
            it.interrupt()
            it.join(5000)
        }
        worker = null
    }

    private fun shutdown() {
        stopWorker()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        RelayState.setRunning(false)
        RelayState.setStatus("Stopped")
    }

    private fun acquireLocks() {
        if (wakeLock != null) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:relay").apply { acquire() }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi.createMulticastLock("$packageName:relay").apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.release()
        wakeLock = null
        multicastLock?.release()
        multicastLock = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Relay status", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle("DLNA relay is on")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.dq.vpndlnarelay.STOP"
        private const val CHANNEL_ID = "relay"
        private const val NOTIFICATION_ID = 1
        private const val RETRY_MS = 15_000L
        private const val HEALTH_CHECK_MS = 30_000L
        private const val MAX_MISSES = 3

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RelayService::class.java))
        }

        fun stop(context: Context) {
            if (RelayState.running) {
                context.startService(Intent(context, RelayService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
