package dev.ronindash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Assistants get exactly ONE thing: the bike display brightness (mock FIX 31). One entry point, reached three ways, all calling
 * BikeService.setBrightness(low | high | up | down | 2–10):
 *  - static launcher shortcuts "Bike display brighter / dimmer" (long-press, Google Assistant, routines)
 *  - the intent dev.ronindash.action.SET_BIKE_BRIGHTNESS with extra "level" (Tasker, MacroDroid, other agents)
 *  - the deep link ronindash://brightness?level=low
 * Nothing else is exported: no data, messages, calls, trips or settings. No screen opens; the reply goes back as the result
 * (extra "message") and a short toast.
 */
class BrightnessActivity : Activity() {
    companion object { const val ACTION = "dev.ronindash.action.SET_BIKE_BRIGHTNESS" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val level = intent?.getStringExtra("level") ?: intent?.data?.getQueryParameter("level")
            ?: intent?.getIntExtra("level", -1)?.takeIf { it >= 0 }?.toString() ?: ""
        val (ok, say) = Bike.service?.setBrightness(level) ?: (false to "The bike isn't connected. Turn the ignition on and try again.")
        Bike.log("[assistant] brightness \"$level\" → ${if (ok) "done" else "refused"}: $say")
        setResult(if (ok) RESULT_OK else RESULT_CANCELED, Intent().putExtra("message", say))
        Toast.makeText(this, say, Toast.LENGTH_SHORT).show()
        finish()
    }
}
