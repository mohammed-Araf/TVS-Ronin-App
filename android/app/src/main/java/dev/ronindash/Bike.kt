package dev.ronindash

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { OFF, WAITING, CONNECTING, LIVE, PROBLEM }

data class Conn(val phase: Phase = Phase.OFF, val detail: String = "Not running")

/** Shared app state between the background service, the notification listener and the UI. */
object Bike {
    val conn = MutableStateFlow(Conn())
    val state = MutableStateFlow(BikeState())
    val missedCalls = MutableStateFlow(0)
    val unreadMessages = MutableStateFlow(0)
    /** Who: names (or numbers) behind the missed calls and the unread messages, newest first. */
    val missedFrom = MutableStateFlow<List<String>>(emptyList())
    val unreadFrom = MutableStateFlow<List<String>>(emptyList())
    val nav = MutableStateFlow<NavInfo?>(null)
    val lastMapsRaw = MutableStateFlow("")
    val log = MutableStateFlow<List<String>>(emptyList())
    val resumeTick = MutableStateFlow(0)
    val logFile = MutableStateFlow("")
    /** Seconds left in the crash countdown, null when no alert. */
    val sos = MutableStateFlow<Int?>(null)
    val sosReason = MutableStateFlow("")
    val tripsChanged = MutableStateFlow(0)
    val liveTripKm = MutableStateFlow(0.0)
    val liveTripKmpl = MutableStateFlow(0.0)
    val tripStoppedMin = MutableStateFlow(0)
    val hasGps = MutableStateFlow(false)
    /** km until the next service (null until the odometer is known). */
    val serviceLeftKm = MutableStateFlow<Int?>(null)
    /** Appearance, applied instantly when changed in Settings. */
    val theme = MutableStateFlow("system")
    val accent = MutableStateFlow(0)

    // ---- riding (computed in BikeService from guarded bike data, shown by the screens)
    /** Which screen the bike picked: parked / riding / fast. Never chosen by a button. */
    val mode = MutableStateFlow(RideMode.PARK)
    val fuel = MutableStateFlow(FuelView())
    /** "Filled up?" card (null = nothing to ask). */
    val ask = MutableStateFlow<FuelAsk?>(null)
    /** Engine warm-up after a cold start (null = warm, or the engine is off). */
    val warmup = MutableStateFlow<WarmUp?>(null)
    val hazard = MutableStateFlow(false)
    /** Side stand down while moving (2 agreeing readings): the emergency screen. */
    val standAlarm = MutableStateFlow(false)
    /** Next turn, shown about 36 s before it (null = nothing close). */
    val turn = MutableStateFlow<Turn?>(null)
    val lean = MutableStateFlow(Lean())
    /** Calls and messages held while riding fast, released when slow. */
    val released = MutableStateFlow<List<Alert>>(emptyList())
    /** Bike display brightness 2–10 as the bike reports it; clMan = the rider (or an assistant) chose it this ride. */
    val clusterLevel = MutableStateFlow(0)
    val clusterManual = MutableStateFlow(false)
    /** Fields the data guard keeps rejecting (Test & log only, never a popup). */
    val guardFailing = MutableStateFlow<List<String>>(emptyList())
    val gpsLost = MutableStateFlow(false)
    /** The phone is on the mount (proximity clear, upright, not dark-in-a-pocket), from the service's short sensor check. */
    val mounted = MutableStateFlow(false)
    /** Phone battery % used this ride (Test & log). */
    val rideBatteryUsed = MutableStateFlow<Int?>(null)
    val odoVsGps = MutableStateFlow("")
    /** The bike switched off: a dashboard that opened itself closes again. */
    val closeDash = MutableStateFlow(false)

    @Volatile var service: BikeService? = null

    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(line: String) {
        val l = "${ts.format(Date())}  $line"
        log.value = (log.value + l).takeLast(400)
        android.util.Log.i("RoninDash", line)
        service?.csv("LOG", "", "", line)
    }
}

enum class RideMode { PARK, RIDE, FAST }

data class FuelView(
    val tankL: Double = 0.0, val tankKnown: Boolean = false, val rangeKm: Double = 0.0, val recentKmpl: Double = 41.6,
    val liveKmpl: Double = 0.0, val lph: Double = 0.0, val moving: Boolean = false, val idling: Boolean = false,
    val tripKm: Double = 0.0, val tripL: Double = 0.0, val tripMovingS: Int = 0, val low: Boolean = false,
) { val tripKmpl get() = if (tripL > 0.005) tripKm / tripL else 0.0 }

/** stage: "ask" → "litres" / "rs" / "pump". late = already guessed from the fuel gauge, waits until stopped. */
data class FuelAsk(val stage: String = "ask", val at: Long = System.currentTimeMillis(), val station: String = "",
    val late: Boolean = false, val guess: String = "")

/** startC = engine temperature when it started, nowC = now, warmC = where it counts as warm. */
data class WarmUp(val startC: Int, val nowC: Int, val warmC: Int, val sinceMs: Long) {
    val frac: Float get() = if (warmC <= startC) 1f else ((nowC - startC).toFloat() / (warmC - startC)).coerceIn(0f, 1f)
}

/** dir as NavParser's Dir; meters counting down; angle = where the turn is relative to the heading (compass). */
data class Turn(val dir: Dir, val meters: Int, val angle: Float, val now: Boolean)

data class Lean(val now: Int = 0, val maxLeft: Int = 0, val maxRight: Int = 0)

data class Alert(val call: Boolean, val who: String, val at: Long = System.currentTimeMillis())

/** Simple settings store. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("ronin", Context.MODE_PRIVATE)
    private fun b(k: String, d: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(t: Any?, pr: kotlin.reflect.KProperty<*>) = p.getBoolean(k, d)
        override fun setValue(t: Any?, pr: kotlin.reflect.KProperty<*>, value: Boolean) = p.edit().putBoolean(k, value).apply()
    }
    private fun i(k: String, d: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(t: Any?, pr: kotlin.reflect.KProperty<*>) = p.getInt(k, d)
        override fun setValue(t: Any?, pr: kotlin.reflect.KProperty<*>, value: Int) = p.edit().putInt(k, value).apply()
    }
    private fun s(k: String, d: String) = object : kotlin.properties.ReadWriteProperty<Any?, String> {
        override fun getValue(t: Any?, pr: kotlin.reflect.KProperty<*>) = p.getString(k, d) ?: d
        override fun setValue(t: Any?, pr: kotlin.reflect.KProperty<*>, value: String) = p.edit().putString(k, value).apply()
    }
    private fun d(k: String, d: Double) = object : kotlin.properties.ReadWriteProperty<Any?, Double> {
        override fun getValue(t: Any?, pr: kotlin.reflect.KProperty<*>) = java.lang.Double.longBitsToDouble(p.getLong(k, d.toRawBits()))
        override fun setValue(t: Any?, pr: kotlin.reflect.KProperty<*>, value: Double) = p.edit().putLong(k, value.toRawBits()).apply()
    }

    // ---- first-time setup + companion device
    var setupDone by b("setupDone", false)
    /** CompanionDeviceManager association id for the bike (-1 = not associated yet). */
    var associationId by i("assocId", -1)

    // ---- riding screens, screen and battery
    var fastKmh by i("fastKmh", 70)
    var fightSun by b("fightSun", false)
    var autoOpen by b("autoOpen", true)
    var clusterBright by b("clusterBright", true)
    var agentBright by b("agentBright", true)
    /** Night panel: "off", "night" (sunset to sunrise), "always". */
    var night by s("night", "night")
    /** "accent", "red", "amber", "green", "white". */
    var nightGlow by s("nightGlow", "accent")
    var nightBright by i("nightBright", 70)
    /** While riding show: "mileage", "speed", "nothing". */
    var nightShow by s("nightShow", "mileage")
    var nightTurns by b("nightTurns", true)

    // ---- helmet sounds
    var sndInd by b("sndInd", true)
    var sndTurns by b("sndTurns", true)
    var sayTurns by b("sayTurns", false)
    var sndSpeed by b("sndSpeed", true)
    var sndWarn by b("sndWarn", true)
    var sndMissed by b("sndMissed", true)
    var sndWarm by b("sndWarm", true)
    /** Engine-start sound with the opening animation (not when the bike opened the app, and never on silent). */
    var openSound by b("openSound", true)
    /** "off", "emergency", "more". */
    var voice by s("voice", "emergency")
    var voiceName by s("voiceName", "af_heart")

    // ---- calls and messages while riding
    var holdAlerts by b("holdAlerts", true)
    var slowKmh by i("slowKmh", 20)

    // ---- fuel model (learned per bike)
    /** Fuel model correction from full-to-full fills (1 = the stock model). */
    var fuelCal by d("fuelCal", 1.0)
    /** The on-device fuel model ([FuelLearner]): weights, covariance, totals since the last full tank. */
    var fuelMl by s("fuelMl", "")
    var fulls by i("fulls", 0)
    /** Recent riding, distance and fuel weighted over the last ~50 km (kept across rides). */
    var recKm by d("recKm", 50.0)
    var recL by d("recL", 50.0 / 41.6)
    /** Our petrol-left count; < 0 = unknown until the first fill or a gauge reading. */
    var tankLeftL by d("tankLeft", -1.0)
    /** Uncalibrated model litres since the last known full tank, and litres poured since then (partial fills). */
    var burnRaw by d("burnRaw", 0.0)
    var poured by d("poured", 0.0)
    var fullKnown by b("fullKnown", false)
    /** Bike speedometer ÷ GPS speed, learned on steady stretches (reads ~5 % high). */
    var speedoK by d("speedoK", 1.0)
    /** Fuel stations the rider filled at: "lat,lon;lat,lon". */
    var stations by s("stations", "")
    /** Fuel gauge bars when the bike last went off, to spot a fill while it was parked. */
    var lastBars by i("lastBars", -1)

    // ---- warm-up
    /** Engine temperature at which the engine counts as warm (°C). */
    var warmC by i("warmC", 60)

    var mac: String?
        get() = p.getString("mac", null)
        set(v) = p.edit().putString("mac", v).apply()
    var bikeName: String
        get() = p.getString("bikeName", "") ?: ""
        set(v) = p.edit().putString("bikeName", v).apply()
    var riderName: String
        get() = p.getString("rider", "") ?: ""
        set(v) = p.edit().putString("rider", v).apply()
    /** "default" = the phone's default assistant (Google Assistant / Gemini), "gemini" = Gemini app, "none". */
    var assist: String
        get() = p.getString("assist", "default") ?: "default"
        set(v) = p.edit().putString("assist", v).apply()
    var calls: Boolean
        get() = p.getBoolean("calls", true)
        set(v) = p.edit().putBoolean("calls", v).apply()
    var sms: Boolean
        get() = p.getBoolean("sms", true)
        set(v) = p.edit().putBoolean("sms", v).apply()
    var whatsapp: Boolean
        get() = p.getBoolean("whatsapp", true)
        set(v) = p.edit().putBoolean("whatsapp", v).apply()
    var navOn: Boolean
        get() = p.getBoolean("nav", true)
        set(v) = p.edit().putBoolean("nav", v).apply()
    var vaFeedback: Boolean
        get() = p.getBoolean("vaFeedback", true)
        set(v) = p.edit().putBoolean("vaFeedback", v).apply()
    var fileLog: Boolean
        get() = p.getBoolean("fileLog", true)
        set(v) = p.edit().putBoolean("fileLog", v).apply()
    var tankL: Double
        get() = p.getFloat("tankL", 14f).toDouble()
        set(v) = p.edit().putFloat("tankL", v.toFloat()).apply()
    var fuelPrice: Double
        get() = p.getFloat("fuelPrice", 103f).toDouble()
        set(v) = p.edit().putFloat("fuelPrice", v.toFloat()).apply()
    var crashDetect: Boolean
        get() = p.getBoolean("crash", true)
        set(v) = p.edit().putBoolean("crash", v).apply()
    var sosCall: Boolean
        get() = p.getBoolean("sosCall", false)
        set(v) = p.edit().putBoolean("sosCall", v).apply()
    /** Emergency phone numbers. */
    var sosContacts: List<String>
        get() = (p.getString("sosContacts", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = p.edit().putString("sosContacts", v.joinToString(",")).apply()
    var tripRecord: Boolean
        get() = p.getBoolean("trips", true)
        set(v) = p.edit().putBoolean("trips", v).apply()
    /** "system", "light" or "dark". */
    var theme: String
        get() = p.getString("theme", "system") ?: "system"
        set(v) { p.edit().putString("theme", v).apply(); Bike.theme.value = v }
    var accent: Int
        get() = p.getInt("accent", 0)
        set(v) { p.edit().putInt("accent", v).apply(); Bike.accent.value = v }
    var keepScreenOn: Boolean
        get() = p.getBoolean("screenOn", true)
        set(v) = p.edit().putBoolean("screenOn", v).apply()
    var speedAlert: Boolean
        get() = p.getBoolean("speedAlert", true)
        set(v) = p.edit().putBoolean("speedAlert", v).apply()
    var speedLimit: Int
        get() = p.getInt("speedLimit", 80)
        set(v) = p.edit().putInt("speedLimit", v).apply()
    /** Also vibrate the phone (the alert itself is two pings in the helmet). */
    var speedBeep: Boolean
        get() = p.getBoolean("speedBeep", false)
        set(v) = p.edit().putBoolean("speedBeep", v).apply()
    /** Also send the limit to the cluster's own overspeed warning (status byte 3). */
    var clusterOverspeed: Boolean
        get() = p.getBoolean("clusterOverspeed", true)
        set(v) = p.edit().putBoolean("clusterOverspeed", v).apply()
    var serviceIntervalKm: Int
        get() = p.getInt("serviceKm", 6000)
        set(v) = p.edit().putInt("serviceKm", v).apply()
    /** Odometer at the last service; -1 = follow the plain schedule (every interval from 0 km). */
    var lastServiceOdo: Double
        get() = p.getFloat("lastServiceOdo", -1f).toDouble()
        set(v) = p.edit().putFloat("lastServiceOdo", v.toFloat()).apply()
    var serviceWarnKm: Int
        get() = p.getInt("serviceWarnKm", 300)
        set(v) = p.edit().putInt("serviceWarnKm", v).apply()
    /** The due-odometer we already warned about, so each reminder is shown once. */
    var serviceWarnedFor: Int
        get() = p.getInt("serviceWarnedFor", 0)
        set(v) = p.edit().putInt("serviceWarnedFor", v).apply()
    var lastOdo: Double
        get() = p.getFloat("lastOdo", 0f).toDouble()
        set(v) = p.edit().putFloat("lastOdo", v.toFloat()).apply()

    /** Odometer reading at which the next service is due. */
    fun nextServiceAt(odo: Double): Int {
        val iv = serviceIntervalKm.coerceAtLeast(500)
        val last = lastServiceOdo
        return if (last >= 0) (last + iv).toInt() else ((odo / iv).toInt() + 1) * iv
    }
}

val ACCENTS = listOf(
    "Amber" to 0xFFE8930C, "Blue" to 0xFF3B7BF6, "Green" to 0xFF1FA463,
    "Red" to 0xFFE5484D, "Purple" to 0xFF8B5CF6, "Teal" to 0xFF0EA5B7,
)
