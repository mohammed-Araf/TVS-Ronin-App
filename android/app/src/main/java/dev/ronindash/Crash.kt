package dev.ronindash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.VibrationEffect
import android.os.VibratorManager
import android.telephony.SmsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Crash detection: a hard impact on the phone (> 4 g) while the bike was moving fast, followed by the bike
 * stopping or the bike link dropping; or the engine cutting out at speed (tip-over sensor).
 * A 30 s countdown with alarm lets the rider cancel (phone button or any bike button) before SMS go out.
 */
@SuppressLint("MissingPermission")
class CrashGuard(
    private val ctx: Context, private val h: Handler, private val send: (String, ByteArray) -> Unit,
    /** Helmet: an earcon and (Voice setting allowing) a fixed phrase. */
    private val cue: (String?, String) -> Unit = { _, _ -> },
) : SensorEventListener {
    private val prefs = Prefs(ctx)
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private var listening = false
    private var lastFastMs = 0L
    private var lastFastKmh = 0.0
    private var impactMs = 0L
    private var impactG = 0.0
    private var lastEngineOn = false
    private var cooldownUntil = 0L
    private var ringtone: Ringtone? = null
    var lastLoc: Location? = null

    companion object { const val CH = "sos"; const val NOTIF = 7; const val COUNTDOWN = 30 }

    fun setRiding(riding: Boolean) {
        val want = riding && prefs.crashDetect
        if (want == listening) return
        listening = want
        if (want) sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        else sm.unregisterListener(this)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81
        if (g > 4.0) {
            val now = System.currentTimeMillis()
            if (now - lastFastMs < 3000 && now - impactMs > 3000) {
                impactMs = now; impactG = g.toDouble()
                Bike.log("[crash] impact %.1f g at ~%.0f km/h, watching…".format(g, lastFastKmh))
                h.postDelayed({ checkAfterImpact() }, 4000)
            }
        }
    }

    /** Called with every bike status update. */
    fun onBike(s: BikeState, linkUp: Boolean) {
        val now = System.currentTimeMillis()
        if (s.speed > 20) { lastFastMs = now; lastFastKmh = s.speed.toDouble() }
        if (prefs.crashDetect && lastEngineOn && !s.engineOn && now - lastFastMs < 2000 && lastFastKmh > 25) {
            trigger("engine cut out at ${lastFastKmh.toInt()} km/h (tip-over?)")
        }
        lastEngineOn = s.engineOn
        if (!linkUp) lastEngineOn = false
    }

    private fun checkAfterImpact() {
        val s = Bike.state.value
        val stopped = s.speed < 5 || System.currentTimeMillis() - s.lastFrameMs > 3000
        if (stopped) trigger("impact %.1f g at %.0f km/h, then stopped".format(impactG, lastFastKmh))
        else Bike.log("[crash] impact but still riding, ignored")
    }

    // ------------------------------------------------------------ countdown

    fun trigger(reason: String, words: String = "Crash detected. Hold the I button if you're ok") {
        val now = System.currentTimeMillis()
        if (Bike.sos.value != null || now < cooldownUntil) return
        cooldownUntil = now + 120_000
        Bike.sosReason.value = reason
        Bike.sos.value = COUNTDOWN
        Bike.log("[sos] countdown: $reason")
        alarm(true)
        cue("urgent", words)
        send("sos-ask", Protocol.text("CRASH? HOLD I"))
        showAlert()
        h.postDelayed(tick, 1000)
    }

    private val tick = object : Runnable {
        override fun run() {
            val left = Bike.sos.value ?: return
            if (left <= 1) { sendNow(); return }
            if (left - 1 == 10) cue(null, "Sending SOS in ten seconds")
            Bike.sos.value = left - 1
            h.postDelayed(this, 1000)
        }
    }

    fun cancel(by: String) {
        if (Bike.sos.value == null) return
        h.removeCallbacks(tick)
        Bike.sos.value = null
        alarm(false)
        send("clear", Protocol.text(""))
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF)
        Bike.log("[sos] cancelled by $by")
    }

    fun sendNow() {
        h.removeCallbacks(tick)
        Bike.sos.value = null
        alarm(false)
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF)
        val contacts = prefs.sosContacts
        if (contacts.isEmpty()) { Bike.log("[sos] no emergency contacts set!"); return }
        val loc = lastLoc
        val where = if (loc != null) "https://maps.google.com/?q=%.6f,%.6f (±%.0f m, %s)".format(Locale.US,
            loc.latitude, loc.longitude, loc.accuracy, SimpleDateFormat("HH:mm", Locale.US).format(Date(loc.time)))
        else "location unknown"
        val name = prefs.riderName.ifBlank { "the rider" }
        val msg = "SOS from Ronin Dash: possible crash for $name. ${Bike.sosReason.value}. Location: $where. Please call or check on them."
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            val sms = ctx.getSystemService(SmsManager::class.java)
            contacts.forEach { n ->
                try { sms.sendMultipartTextMessage(n, null, sms.divideMessage(msg), null, null); Bike.log("[sos] SMS sent to $n") }
                catch (e: Exception) { Bike.log("[sos] SMS to $n failed: ${e.message}") }
            }
        } else Bike.log("[sos] SMS permission missing!")
        send("sos-sent", Protocol.text("SOS SENT"))
        cue(null, "SOS sent")
        if (prefs.sosCall && ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            h.postDelayed({
                try {
                    ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${contacts.first()}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Bike.log("[sos] calling ${contacts.first()}")
                } catch (e: Exception) { Bike.log("[sos] call failed: ${e.message}") }
            }, 3000)
        }
    }

    private fun alarm(on: Boolean) {
        if (on) {
            ringtone = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                isLooping = true; play()
            }
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        } else {
            ringtone?.stop(); ringtone = null
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        }
    }

    private fun showAlert() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Crash alert", NotificationManager.IMPORTANCE_HIGH))
        val i = Intent(ctx, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Crash detected: are you OK?")
            .setContentText("SOS goes to your contacts in $COUNTDOWN s. Tap to cancel.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIF, n)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }

    fun stop() { setRiding(false); cancel("service stop") }
}
