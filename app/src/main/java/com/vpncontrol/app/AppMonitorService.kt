package com.vpncontrol.app

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.net.ConnectivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Foreground-app rule engine. Usage Access is explicitly granted by the device owner. */
class AppMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastPackage = ""
    private var lastDesiredState: Boolean? = null
    private var lastDnsNotice = 0L
    @Volatile private var dnsReady = false
    @Volatile private var dnsCheckRunning = false
    private var lastDnsCheck = 0L
    private val tick = object : Runnable {
        override fun run() {
            WireGuardControllerService.tunnelCounters()?.let { (rx, tx) -> TrafficStatsTracker.sampleTunnel(this@AppMonitorService, rx, tx) }
            val prefs = getSharedPreferences("vpncontrol", MODE_PRIVATE)
            if (!prefs.getBoolean("autoEnabled", false)) { stopSelf(); return }
            val pkg = foregroundPackage()
            if (pkg.isNotBlank()) {
                prefs.edit().putString("foregroundPackage", pkg).apply()
                lastPackage = pkg
                val routingMode = prefs.getString("routingMode", "all") ?: "all"
                val routingPackages = prefs.getStringSet("routingPackages", emptySet()) ?: emptySet()
                val routeSelection = when (routingMode) {
                    "include" -> pkg in routingPackages
                    "exclude" -> pkg !in routingPackages
                    else -> true
                }
                val legacyRule = when (pkg) {
                    "org.telegram.messenger" -> prefs.getBoolean("ruleTelegram", true)
                    "com.google.android.youtube" -> prefs.getBoolean("ruleYoutube", true)
                    "com.anthropic.claude" -> prefs.getBoolean("ruleClaude", false)
                    else -> routeSelection
                }
                val dnsRequired = prefs.getStringSet("dnsRequiredPackages", emptySet()) ?: emptySet()
                val gateEnabled = prefs.getBoolean("dnsGateEnabled", true)
                val needsDns = gateEnabled && pkg in dnsRequired
                if (needsDns) refreshDnsReadiness()
                val needsVpn = legacyRule && (!needsDns || dnsReady)
                if (needsVpn != lastDesiredState) {
                    lastDesiredState = needsVpn
                    setVpn(needsVpn)
                    if (needsDns && !dnsReady) {
                        val now = System.currentTimeMillis()
                        if (now - lastDnsNotice > 60_000) {
                            lastDnsNotice = now
                            notifyUser("DNS пока недоступен", "VPN не включён для этого приложения. Повторю проверку автоматически.")
                        }
                    }
                }
                if (pkg == "com.anthropic.claude" && !prefs.getBoolean("ruleClaude", false)) {
                    val now = System.currentTimeMillis()
                    if (now - lastDnsNotice > 60_000) {
                        lastDnsNotice = now
                        notifyUser("Claude открыт", "VPN-правило выключено. Private DNS меняется вручную в настройках Android.")
                    }
                }
            }
            handler.postDelayed(this, 1800)
        }
    }

    override fun onCreate() {
        super.onCreate(); createChannel()
        startForeground(3101, notification("Автоматизация VPN Control", "Отслеживаем активное приложение"))
        handler.post(tick)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** Resolve a real hostname on the active network; DNS-gated apps stay outside the VPN until it works. */
    private fun refreshDnsReadiness() {
        val now = System.currentTimeMillis()
        if (dnsCheckRunning || now - lastDnsCheck < 12_000L) return
        dnsCheckRunning = true
        Thread {
            val ready = try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                network != null && network.getAllByName("example.com").isNotEmpty()
            } catch (_: Exception) { false }
            dnsReady = ready
            lastDnsCheck = System.currentTimeMillis()
            dnsCheckRunning = false
        }.start()
    }

    private fun foregroundPackage(): String {
        val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis(); val events = manager.queryEvents(end - 15_000, end)
        val event = UsageEvents.Event(); var latestTime = 0L; var latest = ""
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val foreground = if (Build.VERSION.SDK_INT >= 29) event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                else event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            if (foreground && event.timeStamp >= latestTime) { latestTime = event.timeStamp; latest = event.packageName }
        }
        return latest
    }

    private fun setVpn(enabled: Boolean) {
        val prefs = getSharedPreferences("vpncontrol", MODE_PRIVATE)
        val lockdown = try { android.provider.Settings.Secure.getInt(contentResolver, "always_on_vpn_lockdown", 0) == 1 } catch (_: Exception) { false }
        // System lockdown takes precedence over automation's "VPN off" rule.
        // Android will block excluded apps while lockdown is active; warn instead of stopping the tunnel.
        val effectiveEnabled = enabled || lockdown
        if (!enabled && lockdown) notifyUser("Kill Switch активен", "Не отключаю туннель: системная блокировка запрещает интернет вне VPN. Приложения вне списка VPN могут быть недоступны.")
        if (effectiveEnabled && !java.io.File(filesDir, "active.enc").exists()) {
            notifyUser("VPN не настроен", "Импортируй конфигурацию .conf в VPN Control")
            return
        }
        val action = if (effectiveEnabled) WireGuardControllerService.ACTION_UP else WireGuardControllerService.ACTION_DOWN
        val i = Intent(this, WireGuardControllerService::class.java).setAction(action)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            // state is committed by WireGuardControllerService
        } catch (_: Exception) { notifyUser("Не удалось применить правило", "Проверь разрешения и состояние VPN") }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("vpncontrol", "VPN Control", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    @Suppress("DEPRECATION") private fun notification(title: String, body: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "vpncontrol") else Notification.Builder(this)
        return b.setContentTitle(title).setContentText(body).setSmallIcon(android.R.drawable.stat_sys_warning).build()
    }
    private fun notifyUser(title: String, body: String) { getSystemService(NotificationManager::class.java).notify((title + body).hashCode(), notification(title, body)) }
    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
