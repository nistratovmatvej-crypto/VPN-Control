package com.vpncontrol.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persists actual AmneziaWG peer byte counters (not whole-device TrafficStats). */
object TrafficStatsTracker {
    @Synchronized
    fun sampleTunnel(context: Context, rx: Long, tx: Long) {
        if (rx < 0L || tx < 0L) return
        val p = context.getSharedPreferences("vpncontrol_stats", Context.MODE_PRIVATE)
        val oldRx = p.getLong("last_tunnel_rx", -1L)
        val oldTx = p.getLong("last_tunnel_tx", -1L)
        // If the native tunnel restarted, its counters may start from zero. Count the new
        // session's current values rather than producing a negative delta.
        val dr = if (oldRx >= 0L && rx >= oldRx) rx - oldRx else rx
        val dt = if (oldTx >= 0L && tx >= oldTx) tx - oldTx else tx
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val month = day.substring(0, 7)
        val newDay = p.getString("day", "") != day
        val newMonth = p.getString("month", "") != month
        val e = p.edit()
            .putString("day", day)
            .putString("month", month)
            .putLong("last_tunnel_rx", rx)
            .putLong("last_tunnel_tx", tx)
            .putLong("total_rx", p.getLong("total_rx", 0L) + dr)
            .putLong("total_tx", p.getLong("total_tx", 0L) + dt)
            .putLong("day_rx", (if (newDay) 0L else p.getLong("day_rx", 0L)) + dr)
            .putLong("day_tx", (if (newDay) 0L else p.getLong("day_tx", 0L)) + dt)
            .putLong("month_rx", (if (newMonth) 0L else p.getLong("month_rx", 0L)) + dr)
            .putLong("month_tx", (if (newMonth) 0L else p.getLong("month_tx", 0L)) + dt)
            .putLong("last_sample_at", System.currentTimeMillis())
        e.apply()
    }

    fun value(context: Context, key: String): Long =
        context.getSharedPreferences("vpncontrol_stats", Context.MODE_PRIVATE).getLong(key, 0L)
}
