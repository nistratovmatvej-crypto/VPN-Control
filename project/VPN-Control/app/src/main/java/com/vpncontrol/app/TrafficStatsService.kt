package com.vpncontrol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Samples native AmneziaWG peer counters while the tunnel is up, including with UI closed. */
class TrafficStatsService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val sampler = object : Runnable {
        override fun run() {
            WireGuardControllerService.tunnelCounters()?.let { (rx, tx) ->
                TrafficStatsTracker.sampleTunnel(this@TrafficStatsService, rx, tx)
            }
            handler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("vpncontrol_stats", "VPN Control · статистика", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "vpncontrol_stats") else Notification.Builder(this)
        startForeground(3103, n.setContentTitle("VPN Control")
            .setContentText("Сохраняем статистику трафика туннеля")
            .setSmallIcon(android.R.drawable.stat_sys_download_done).build())
        handler.post(sampler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() { handler.removeCallbacks(sampler); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
