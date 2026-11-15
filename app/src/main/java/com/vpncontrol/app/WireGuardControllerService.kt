package com.vpncontrol.app

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.NoopTunnelActionHandler
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Owns one AmneziaWG backend instance and performs real tunnel/handshake operations. */
class WireGuardControllerService : Service() {
    companion object {
        const val ACTION_UP = "com.vpncontrol.app.AWG_UP"
        const val ACTION_DOWN = "com.vpncontrol.app.AWG_DOWN"
        const val ACTION_RECONNECT = "com.vpncontrol.app.AWG_RECONNECT"
        const val ACTION_AUTO_SELECT = "com.vpncontrol.app.AWG_AUTO_SELECT"
        @Volatile private var sharedBackend: GoBackend? = null
        private val tunnel = object : Tunnel {
            override fun getName(): String = "VPNControl"
            override fun onStateChange(newState: Tunnel.State) { }
            override fun isIpv4ResolutionPreferred(): Boolean = true
            override fun isMetered(): Boolean = false
        }

        private fun contextBackend(context: android.content.Context): GoBackend = synchronized(Companion) {
            sharedBackend ?: GoBackend(context.applicationContext, NoopTunnelActionHandler()).also { sharedBackend = it }
        }

        /** Actual peer counters supplied by the native AmneziaWG backend. */
        fun isTunnelUp(context: android.content.Context): Boolean = try { contextBackend(context.applicationContext).getState(tunnel) == Tunnel.State.UP } catch (_: Exception) { false }

        fun tunnelCounters(): Pair<Long, Long>? {
            return try {
                val b = sharedBackend ?: return null
                if (b.getState(tunnel) != Tunnel.State.UP) return null
                val s = b.getStatistics(tunnel)
                Pair(s.totalRx(), s.totalTx())
            } catch (_: Exception) { null }
        }
    }

    private fun lastHandshakeEpoch(engine: GoBackend): Long = try {
        val method = engine.javaClass.methods.firstOrNull { it.name == "getLastHandshake" && it.parameterCount == 1 } ?: return 0L
        (method.invoke(engine, tunnel) as? Number)?.toLong() ?: 0L
    } catch (_: Exception) { 0L }

    private fun backend(): GoBackend = contextBackend(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(3102, notification("VPNcontrol", "Применяем настройки туннеля…"))
        if (intent?.action == ACTION_UP || intent?.action == ACTION_RECONNECT) getSharedPreferences("vpncontrol", MODE_PRIVATE).edit().putBoolean("vpnConnecting", true).apply()
        if (intent?.action == ACTION_DOWN) getSharedPreferences("vpncontrol", MODE_PRIVATE).edit().putBoolean("vpnConnecting", false).apply()
        Thread {
            try {
                val engine = backend()
                when (intent?.action) {
                    ACTION_UP -> connectActiveProfile(engine)
                    ACTION_RECONNECT -> reconnectActiveProfile(engine)
                    ACTION_AUTO_SELECT -> autoSelectByHandshake(engine)
                    else -> {
                        tunnelCounters()?.let { (rx, tx) -> TrafficStatsTracker.sampleTunnel(this, rx, tx) }
                        engine.setState(tunnel, Tunnel.State.DOWN, null)
                        getSharedPreferences("vpncontrol", MODE_PRIVATE).edit()
                            .putBoolean("vpnWasStarted", false).putBoolean("vpnConnecting", false).remove("connectedAt").apply()
                        stopService(Intent(this, TrafficStatsService::class.java))
                        notifyResult("VPN отключён", "Туннель остановлен")
                    }
                }
            } catch (e: Exception) {
                getSharedPreferences("vpncontrol", MODE_PRIVATE).edit()
                    .putBoolean("vpnWasStarted", false).remove("connectedAt").apply()
                stopService(Intent(this, TrafficStatsService::class.java))
                notifyResult("Ошибка VPN", e.message ?: "Не удалось применить конфигурацию")
            } finally { stopSelf(startId) }
        }.start()
        return START_NOT_STICKY
    }

    private fun loadConfig(file: File): Config {
        require(file.exists()) { "Профиль AmneziaWG не найден" }
        val clear = ConfigStore.decrypt(file.readBytes())
        return ByteArrayInputStream(clear).use { Config.parse(it) }
    }

    /** Applies the user's per-app VPN rules for the live tunnel. Probe attempts intentionally use
     * the raw full-tunnel profile so this app can generate traffic and trigger a peer handshake. */
    private fun configForActiveProfile(): Config {
        val prefs = getSharedPreferences("vpncontrol", MODE_PRIVATE)
        var raw = ConfigStore.decrypt(File(filesDir, "active.enc").readBytes()).toString(Charsets.UTF_8)
        // Strip stale split-tunnel directives before applying the current user selection.
        raw = raw.replace(Regex("(?im)^(IncludedApplications|ExcludedApplications)\\s*=.*(?:\\r?\\n)?"), "")
        val mode = prefs.getString("routingMode", "all") ?: "all"
        val selected = prefs.getStringSet("routingPackages", emptySet())?.sorted().orEmpty()
        require(mode != "include" || selected.isNotEmpty()) { "Выбери приложения для режима «Только выбранные через VPN»" }
        val directive = when (mode) {
            "include" -> "IncludedApplications = ${selected.joinToString(", ")}"
            "exclude" -> if (selected.isNotEmpty()) "ExcludedApplications = ${selected.joinToString(", ")}" else ""
            else -> ""
        }
        val dns = when (prefs.getString("dnsMode", "profile")) {
            "cloudflare" -> "1.1.1.1, 1.0.0.1"
            "quad9" -> "9.9.9.9, 149.112.112.112"
            "google" -> "8.8.8.8, 8.8.4.4"
            else -> ""
        }
        if (dns.isNotBlank()) {
            if (Regex("(?im)^DNS\\s*=").containsMatchIn(raw)) {
                raw = raw.replace(Regex("(?im)^DNS\\s*=.*$"), "DNS = $dns")
            } else {
                val peer = Regex("(?im)^\\[Peer\\]").find(raw)?.range?.first ?: raw.length
                raw = raw.substring(0, peer) + "DNS = $dns\n" + raw.substring(peer)
            }
        }
        if (directive.isNotBlank()) {
            val peer = Regex("(?im)^\\[Peer\\]").find(raw)?.range?.first ?: raw.length
            raw = raw.substring(0, peer) + directive + "\n" + raw.substring(peer)
        }
        return ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)).use { Config.parse(it) }
    }

    private fun connectActiveProfile(engine: GoBackend) {
        val prefs = getSharedPreferences("vpncontrol", MODE_PRIVATE)
        engine.setState(tunnel, Tunnel.State.UP, configForActiveProfile())
        prefs.edit().putBoolean("vpnConnecting", false).putBoolean("vpnWasStarted", true)
            .putLong("connectedAt", System.currentTimeMillis()).apply()
        startTrafficSampler()
        notifyResult("VPN подключён", "Туннель AmneziaWG активен")
    }

    private fun reconnectActiveProfile(engine: GoBackend) {
        require(File(filesDir, "active.enc").exists()) { "Сначала импортируй VPN-конфигурацию" }
        val prefs = getSharedPreferences("vpncontrol", MODE_PRIVATE)
        val wasConnected = prefs.getBoolean("vpnWasStarted", false) || engine.getState(tunnel) == Tunnel.State.UP
        if (!wasConnected) {
            connectActiveProfile(engine)
            return
        }
        engine.setState(tunnel, Tunnel.State.DOWN, null)
        Thread.sleep(350L)
        engine.setState(tunnel, Tunnel.State.UP, configForActiveProfile())
        prefs.edit().putBoolean("vpnConnecting", false).putBoolean("vpnWasStarted", true)
            .putLong("connectedAt", System.currentTimeMillis()).apply()
        startTrafficSampler()
        notifyResult("Настройки применены", "Маршрутизация приложений и DNS обновлены")
    }

    /** Tests each saved profile by establishing the real AWG tunnel and waiting for a fresh handshake. */
    private fun autoSelectByHandshake(engine: GoBackend) {
        val profiles = ProfileStore.all(this).sortedByDescending { it.favorite }
        require(profiles.isNotEmpty()) { "Сначала импортируй несколько VPN-профилей" }
        val prefs = getSharedPreferences("vpncontrol", MODE_PRIVATE)
        require(!prefs.getBoolean("vpnWasStarted", false)) { "Перед тестированием отключи текущий VPN" }
        engine.setState(tunnel, Tunnel.State.DOWN, null)
        prefs.edit().putBoolean("vpnWasStarted", false).remove("connectedAt").apply()
        notifyResult("Автовыбор", "Проверяю реальные AmneziaWG handshake…")

        data class Candidate(val id: String, val name: String, val handshakeMs: Long)
        val candidates = mutableListOf<Candidate>()
        for (profile in profiles) {
            try {
                val file = File(File(filesDir, "profiles"), profile.id + ".enc")
                val cfg = loadConfig(file)
                engine.setState(tunnel, Tunnel.State.UP, cfg)
                val started = System.nanoTime()
                // Generate outbound traffic so a peer without PersistentKeepalive attempts a handshake.
                pokeTunnel()
                var connected = false
                // Accept only a recent handshake from this freshly started tunnel.
                while ((System.nanoTime() - started) < 8_000_000_000L) {
                    val handshakeEpoch = lastHandshakeEpoch(engine)
                    val nowEpoch = System.currentTimeMillis() / 1000L
                    if (handshakeEpoch > 0L && nowEpoch - handshakeEpoch <= 30L) { connected = true; break }
                    Thread.sleep(350L)
                }
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                if (connected) candidates.add(Candidate(profile.id, profile.name, elapsed))
                engine.setState(tunnel, Tunnel.State.DOWN, null)
                Thread.sleep(500L)
            } catch (_: Exception) {
                try { engine.setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) { }
            }
        }

        val best = candidates.minByOrNull { it.handshakeMs }
            ?: throw IllegalStateException("Ни один сервер не ответил на реальный AmneziaWG handshake. Активный профиль не менял.")
        ProfileStore.activate(this, best.id)
        // Verify the winner once more in full-tunnel probe mode so the app's own socket
        // can trigger the handshake. Apply split-tunnel rules only after validation.
        val winnerFile = File(File(filesDir, "profiles"), best.id + ".enc")
        engine.setState(tunnel, Tunnel.State.UP, loadConfig(winnerFile))
        val finalStarted = System.nanoTime()
        var finalHandshake = false
        pokeTunnel()
        while ((System.nanoTime() - finalStarted) < 8_000_000_000L) {
            val handshakeEpoch = lastHandshakeEpoch(engine)
            val nowEpoch = System.currentTimeMillis() / 1000L
            if (handshakeEpoch > 0L && nowEpoch - handshakeEpoch <= 30L) { finalHandshake = true; break }
            Thread.sleep(350L)
        }
        if (!finalHandshake) {
            engine.setState(tunnel, Tunnel.State.DOWN, null)
            throw IllegalStateException("Лучший сервер перестал отвечать при повторном подключении")
        }
        engine.setState(tunnel, Tunnel.State.UP, configForActiveProfile())
        prefs.edit().putBoolean("vpnWasStarted", true)
            .putLong("connectedAt", System.currentTimeMillis())
            .putString("lastAutoSelected", best.id)
            .putLong("lastAutoHandshakeMs", best.handshakeMs).apply()
        startTrafficSampler()
        notifyResult("Выбран лучший сервер", "${best.name} · handshake ${best.handshakeMs} мс · VPN подключён")
    }

    private fun pokeTunnel() {
        try { Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 2200) } }
        catch (_: Exception) { /* A failed TCP connect still triggers a WireGuard handshake attempt. */ }
    }

    private fun startTrafficSampler() {
        val i = Intent(this, TrafficStatsService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
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
    private fun notifyResult(title: String, body: String) {
        getSystemService(NotificationManager::class.java).notify(title.hashCode(), notification(title, body))
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
