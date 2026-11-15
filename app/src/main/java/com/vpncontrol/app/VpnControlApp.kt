package com.vpncontrol.app

import android.app.Application
import android.content.Intent
import android.os.Build
import org.amnezia.awg.backend.GoBackend

/** Restores the saved tunnel when Android starts the Always-on VPN service after boot. */
class VpnControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try { ProfileStore.installBuiltIns(this) } catch (_: Exception) { }
        GoBackend.setAlwaysOnCallback {
            val intent = Intent(this, WireGuardControllerService::class.java)
                .setAction(WireGuardControllerService.ACTION_UP)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            } catch (_: Exception) { }
        }
    }
}
