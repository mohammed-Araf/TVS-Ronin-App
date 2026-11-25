package dev.ronindash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the bike service after the phone restarts (if the rider enabled it). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        val p = Prefs(c)
        if (p.autoStart && p.mac != null) {
            try { BikeService.start(c) } catch (_: Exception) {}
        }
    }
}
