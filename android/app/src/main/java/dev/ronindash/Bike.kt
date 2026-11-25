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

/** Simple settings store. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("ronin", Context.MODE_PRIVATE)

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
    var autoStart: Boolean
        get() = p.getBoolean("autoStart", true)
        set(v) = p.edit().putBoolean("autoStart", v).apply()
    var fileLog: Boolean
        get() = p.getBoolean("fileLog", true)
        set(v) = p.edit().putBoolean("fileLog", v).apply()
    var rpmPerKmhTop: Double
        get() = p.getFloat("rpmTop", Mileage.DEFAULT_RPM_PER_KMH.toFloat()).toDouble()
        set(v) = p.edit().putFloat("rpmTop", v.toFloat()).apply()
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
        get() = p.getBoolean("speedAlert", false)
        set(v) = p.edit().putBoolean("speedAlert", v).apply()
    var speedLimit: Int
        get() = p.getInt("speedLimit", 80)
        set(v) = p.edit().putInt("speedLimit", v).apply()
    var speedBeep: Boolean
        get() = p.getBoolean("speedBeep", true)
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
