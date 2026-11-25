package dev.ronindash

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Fuel model for the Ronin (225 cc), from the owner's real figures:
 *   steady 60 km/h → 44 km/l, steady 120 km/h → 32 km/l.
 * Then corrected by RPM: revving higher than the top-gear RPM for that speed (lower gear, hard acceleration)
 * burns more. Idle burns ~0.15 L/h. It's an estimate; fill-up calibration can refine it later.
 */
object Mileage {
    const val IDLE_LPH = 0.15
    const val DEFAULT_RPM_PER_KMH = 55.0 // top-gear guess until learned from the rider's own data

    fun kmplAt(v: Double): Double = when {
        v >= 60 -> 44 - (v - 60) * 0.2        // 120 → 32
        v >= 30 -> 44 - (60 - v) * 0.1        // 30 → 41 (light stop-go)
        else -> 41 - (30 - v) * 0.3           // 5 → 33.5 (crawling in traffic)
    }.coerceAtLeast(15.0)

    /** Litres used over dtS seconds. rpmPerKmhTop = RPM ÷ speed in top gear. accel in m/s². */
    fun litres(vKmh: Double, rpm: Int, accel: Double, rpmPerKmhTop: Double, dtS: Double): Double {
        if (vKmh < 3) return if (rpm > 500) IDLE_LPH * dtS / 3600 * (rpm / 1500.0).coerceIn(0.8, 3.0) else 0.0
        var l = (vKmh * dtS / 3600) / kmplAt(vKmh)
        if (rpm > 500) l *= sqrt((rpm / vKmh) / rpmPerKmhTop).coerceIn(0.85, 1.8)
        if (accel > 0.3) l *= 1 + 0.25 * accel.coerceAtMost(3.0)
        return l
    }
}

data class TripPoint(
    val t: Long, val lat: Double, val lon: Double, val alt: Double,
    val kmh: Double, val rpm: Int, val gear: Int, val fuelL: Double,
)

/** Records one ride: GPS + bike data once a second, fuel estimate integrated as we go. */
class TripRecorder(private val ctx: Context) {
    private val prefs = Prefs(ctx)
    var active = false; private set
    private var startMs = 0L
    private var lastMoveMs = 0L
    private var lastT = 0L
    private var lastKmh = 0.0
    private var fuelL = 0.0
    private var distM = 0.0
    private var maxKmh = 0.0
    private var maxRpm = 0
    private var movingS = 0.0
    private var startOdo = 0.0
    private var startFuelPct = -1
    private var lastLoc: Location? = null
    private var breaks = 0
    private var breakS = 0.0
    private var lastEngineOnMs = 0L
    private val ratios = ArrayList<Double>()
    private val pts = ArrayList<TripPoint>()
    private var file: File? = null

    private fun dir() = File(ctx.getExternalFilesDir(null), "trips").apply { mkdirs() }

    /** Called about once a second by the service. */
    fun sample(s: BikeState, loc: Location?, live: Boolean) {
        val now = System.currentTimeMillis()
        val freshLoc = loc != null && now - loc.time < 5000
        val kmh = if (live && s.lastFrameMs > now - 5000) s.speed.toDouble() else if (freshLoc) loc!!.speed * 3.6 else 0.0
        if (live && s.engineOn) lastEngineOnMs = now
        val moving = kmh > 3
        if (!active) {
            if (!moving) return
            begin(s, now)
        }
        if (moving) {
            // A stop longer than 3 min that we rode on from counts as a break (fuel, tea, photos).
            if (now - lastMoveMs > 3 * 60_000) { breaks++; breakS += (now - lastMoveMs) / 1000.0; Bike.log("[trip] break over, continuing") }
            lastMoveMs = now
        }
        val dt = if (lastT == 0L) 1.0 else ((now - lastT) / 1000.0).coerceIn(0.2, 5.0)
        val accel = (kmh - lastKmh) / 3.6 / dt
        if (kmh > 35 && s.rpm > 1000) ratios.add(s.rpm / kmh)
        fuelL += Mileage.litres(kmh, if (live) s.rpm else 0, accel, rpmTop(), dt)
        distM += kmh / 3.6 * dt
        if (moving) movingS += dt
        maxKmh = max(maxKmh, kmh); maxRpm = max(maxRpm, s.rpm)
        lastT = now; lastKmh = kmh
        if (loc != null && (lastLoc == null || loc.time != lastLoc!!.time)) {
            lastLoc = loc
            val p = TripPoint(now, loc.latitude, loc.longitude, loc.altitude, kmh, s.rpm, s.gear, fuelL)
            pts.add(p)
            file?.appendText("${p.t},%.6f,%.6f,%.0f,%.1f,${p.rpm},${p.gear},%.4f\n".format(Locale.US, p.lat, p.lon, p.alt, p.kmh, p.fuelL))
        }
        // Ride over or just a break? Engine still running at a stop = traffic/waiting (end after 20 min).
        // Ignition off or bike gone = a break; if we don't ride again within 45 min, the ride ended when we stopped.
        val stillMs = now - lastMoveMs
        val engineRunning = live && s.engineOn
        val limit = if (engineRunning) 20 * 60_000L else 45 * 60_000L
        if (stillMs > limit) finish(s)
    }

    private fun begin(s: BikeState, now: Long) {
        active = true; startMs = now; lastMoveMs = now; lastT = 0; fuelL = 0.0; distM = 0.0; maxKmh = 0.0; maxRpm = 0
        movingS = 0.0; ratios.clear(); pts.clear(); lastLoc = null; breaks = 0; breakS = 0.0
        startOdo = s.odoKm; startFuelPct = if (s.lastFrameMs > 0) s.fuelPct else -1
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        file = File(dir(), "trip_$id.csv").apply { writeText("t,lat,lon,alt,kmh,rpm,gear,fuelL\n") }
        Bike.log("[trip] started")
    }

    private fun rpmTop(): Double {
        if (ratios.size < 60) return prefs.rpmPerKmhTop
        val sorted = ratios.sorted()
        return sorted[sorted.size / 10]
    }

    /** Ends the trip and writes its summary next to the points file. */
    fun finish(s: BikeState) {
        if (!active) return
        active = false
        val f = file ?: return
        val odoKm = if (startOdo > 0 && s.odoKm > startOdo) s.odoKm - startOdo else 0.0
        val km = if (odoKm > 0.1) odoKm else distM / 1000
        if (km < 0.3) { f.delete(); Bike.log("[trip] too short, discarded"); return }
        if (ratios.size >= 60) prefs.rpmPerKmhTop = rpmTop()
        val gaugeL = if (startFuelPct > 0 && s.fuelPct in 1 until startFuelPct) (startFuelPct - s.fuelPct) / 100.0 * prefs.tankL else 0.0
        val sum = JSONObject()
            .put("start", startMs).put("end", lastMoveMs) // the ride ended when we last moved, not when we noticed
            .put("breaks", breaks).put("breakS", breakS)
            .put("km", km).put("movingS", movingS).put("maxKmh", maxKmh).put("maxRpm", maxRpm)
            .put("fuelL", fuelL).put("kmpl", if (fuelL > 0) km / fuelL else 0.0)
            .put("gaugeL", gaugeL).put("points", pts.size)
            .put("climbM", climb())
        File(f.path.replace(".csv", ".json")).writeText(sum.toString())
        Bike.log("[trip] saved %.1f km, est %.1f km/l".format(km, if (fuelL > 0) km / fuelL else 0.0))
        Bike.tripsChanged.value++
    }

    private fun climb(): Double {
        var up = 0.0
        for (i in 1 until pts.size) { val d = pts[i].alt - pts[i - 1].alt; if (d in 1.0..30.0) up += d }
        return up
    }

    /** True while we're inside a ride and moved in the last 2 minutes (keeps GPS on only when useful). */
    val recentlyMoving get() = active && System.currentTimeMillis() - lastMoveMs < 120_000
    val stoppedForMin get() = if (active) ((System.currentTimeMillis() - lastMoveMs) / 60_000).toInt() else 0
    val liveKm get() = distM / 1000
    val liveKmpl get() = if (fuelL > 0.001) distM / 1000 / fuelL else 0.0
}

data class TripSummary(
    val id: String, val start: Long, val end: Long, val km: Double, val movingS: Double, val maxKmh: Double,
    val maxRpm: Int, val fuelL: Double, val kmpl: Double, val gaugeL: Double, val climbM: Double,
    val breaks: Int = 0, val breakS: Double = 0.0,
)

object TripStore {
    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null), "trips").apply { mkdirs() }

    fun list(ctx: Context): List<TripSummary> = dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f ->
            try {
                val j = JSONObject(f.readText())
                TripSummary(f.nameWithoutExtension, j.getLong("start"), j.getLong("end"), j.getDouble("km"), j.getDouble("movingS"),
                    j.getDouble("maxKmh"), j.getInt("maxRpm"), j.getDouble("fuelL"), j.getDouble("kmpl"), j.optDouble("gaugeL", 0.0),
                    j.optDouble("climbM", 0.0), j.optInt("breaks", 0), j.optDouble("breakS", 0.0))
            } catch (_: Exception) { null }
        }.sortedByDescending { it.start }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.csv").delete(); File(dir(ctx), "$id.json").delete()
    }

    /** Points + summary as JSON for the map page. Thinned to at most ~4000 points. */
    fun mapJson(ctx: Context, id: String): String {
        val csv = File(dir(ctx), "$id.csv")
        val lines = csv.readLines().drop(1).filter { it.isNotBlank() }
        val step = max(1, lines.size / 4000)
        val arr = JSONArray()
        var prevFuel = 0.0; var prevKm = 0.0; var prevLat = 0.0; var prevLon = 0.0
        lines.forEachIndexed { i, l ->
            if (i % step != 0 && i != lines.lastIndex) return@forEachIndexed
            val c = l.split(",")
            if (c.size < 8) return@forEachIndexed
            val lat = c[1].toDouble(); val lon = c[2].toDouble(); val fuel = c[7].toDouble()
            val dKm = if (prevLat == 0.0) 0.0 else haversineKm(prevLat, prevLon, lat, lon)
            val dL = fuel - prevFuel
            val kmpl = if (dL > 0.00001) (dKm / dL).coerceIn(5.0, 80.0) else 0.0
            prevKm += dKm; prevFuel = fuel; prevLat = lat; prevLon = lon
            // [lon, lat, alt, kmh, rpm, gear, kmpl here, time, km from start, litres used so far]
            arr.put(JSONArray().put(lon).put(lat).put(c[3].toDouble()).put(c[4].toDouble()).put(c[5].toInt())
                .put(c[6].toInt()).put(kmpl).put(c[0].toLong()).put(prevKm).put(fuel))
        }
        val sum = File(dir(ctx), "$id.json").takeIf { it.exists() }?.readText() ?: "{}"
        return JSONObject().put("points", arr).put("summary", JSONObject(sum))
            .put("fuelPrice", Prefs(ctx).fuelPrice).toString()
    }

    private fun haversineKm(a: Double, b: Double, c: Double, d: Double): Double {
        val r = FloatArray(1); Location.distanceBetween(a, b, c, d, r); return r[0] / 1000.0
    }
}
