package dev.ronindash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Bench testing from a computer over USB. Only adb (the shell user holds android.permission.DUMP) can send it, and it
 * does nothing in a release build.
 *   adb shell am broadcast -n dev.ronindash/.DebugReceiver --ei bright 10
 *   adb shell am broadcast -n dev.ronindash/.DebugReceiver --es hex "5A F1 0A 00 …"   (plain 20-byte frame)
 *   adb shell am broadcast -n dev.ronindash/.DebugReceiver --ez status true
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val s = Bike.service ?: run { Bike.log("[adb] not connected"); return }
        when {
            i.hasExtra("bright") -> i.getIntExtra("bright", -1).let { s.send("brightness $it", Protocol.brightness(it)) }
            i.hasExtra("hex") -> Protocol.fromHex(i.getStringExtra("hex") ?: "")?.let { s.send("adb", it) } ?: Bike.log("[adb] bad hex")
            i.getBooleanExtra("status", false) -> s.send("status", Protocol.status(Bike.missedCalls.value, Bike.unreadMessages.value))
            i.hasExtra("mark") -> Bike.log("[mark] ${i.getStringExtra("mark")}")
        }
    }
}
