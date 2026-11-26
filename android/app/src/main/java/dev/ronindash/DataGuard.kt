package dev.ronindash

import kotlin.math.abs
import kotlin.math.max

/**
 * Bike-data guard: a 1:1 port of tools/data_guard.py (the reference, with its replay + glitch-injection test).
 *
 * A frame can pass the checksum and still carry a wrong value. Every field goes through:
 *  1. Range   — physically possible for this bike? (never accepted otherwise)
 *  2. Rate    — could it have changed this much since the last good value?
 *  3. Confirm — a jump is held as suspect; if the next 2 readings agree, it was real.
 *  4. Hold    — while rejected the last good value stays; after 3 s with nothing good the field is unknown (null).
 * Plain Kotlin (no Android), so it runs as a JVM unit test against the real captures.
 */
class DataGuard {
    data class Limit(val lo: Double, val hi: Double, val rate: Double?, val tol: Double)

    companion object {
        val LIMITS = mapOf(
            "speed" to Limit(0.0, 180.0, 40.0, 6.0),
            "rpm" to Limit(0.0, 11000.0, 8000.0, 1500.0),
            "fuel" to Limit(0.0, 100.0, 1.0, 3.0),
            "temp" to Limit(-30.0, 160.0, 3.0, 4.0),
            "intake" to Limit(-30.0, 90.0, 3.0, 4.0),
            "batt" to Limit(6.0, 15.8, 1.0, 0.8),
            "gear" to Limit(0.0, 5.0, null, 0.0),
            "odo" to Limit(0.0, 999999.0, null, 2.0),
            "range" to Limit(0.0, 999.0, 1.0, 15.0),
        )
        /** rpm per km/h in each gear (real min/max seen + margin). Ronin gear top speeds 42 / 76 / 92 / 114 km/h. */
        val RATIO = mapOf(1 to (112.0 to 400.0), 2 to (100.0 to 200.0), 3 to (70.0 to 150.0), 4 to (62.0 to 115.0), 5 to (55.0 to 76.0))
        val GEAR_TOP = mapOf(1 to 42, 2 to 76, 3 to 92, 4 to 114)
        const val CONFIRM = 2
        const val STALE_S = 3.0
        const val GAP_RESET_S = 10.0

        fun ratioFits(gear: Int, r: Double) =
            listOf(gear - 1, gear, gear + 1).any { g -> RATIO[g]?.let { r in it.first..it.second } == true }
    }

    /** What the other fields said at this moment (for cross-checks). */
    data class Ctx(
        val speed: Double? = null, val gear: Double? = null, val rpm: Double? = null, val clutch: Boolean = false,
        val throttle: Double = 0.0, val cranking: Boolean = false, val neutralLamp: Boolean? = null,
    )

    class Field(val name: String) {
        private val lim = LIMITS.getValue(name)
        var good: Double? = null; private set
        private var tGood = 0.0
        private val hist = ArrayList<Pair<Double, Double>>()
        private var suspect = ArrayList<Pair<Double, Double>>()
        private var provisional = false
        var rejected = 0; private set
        var accepted = 0; private set
        /** Rejections in a row: a field that keeps failing shows up in Test & log. */
        var failRun = 0; private set

        fun feed(v: Double?, t: Double, c: Ctx): Pair<Double?, String> {
            val r = feedInner(v, t, c)
            failRun = if (r.second == "ok" || r.second == "confirmed") 0 else failRun + 1
            return r
        }

        private fun feedInner(v: Double?, t: Double, c: Ctx): Pair<Double?, String> {
            val (lo, hi, rate, tol) = lim
            if (v == null || v < lo || v > hi) { rejected++; return value(t) to "out of range" }
            val g = good
            if (g == null || t - tGood > GAP_RESET_S) {
                hist.clear()
                val r = accept(v, t); provisional = true; return r
            }
            val dt = max(t - tGood, .05)
            if (provisional) {
                val agrees = abs(v - g) <= (rate ?: 1.0) * dt + max(tol, 1.0)
                val r = accept(v, t); provisional = !agrees; return r
            }
            var ok: Boolean
            when (name) {
                "odo" -> ok = v >= g && v <= g + 1 + 180 * dt / 3600 + 1
                "fuel" -> ok = v >= g - (rate!! * dt + tol) && v <= g + tol
                "gear" -> {
                    ok = abs(v - g) <= 2 && (v == 0.0) == (c.neutralLamp ?: (v == 0.0))
                    val sp = c.speed ?: 0.0; val rpm = c.rpm
                    val gi = v.toInt()
                    if (ok && gi in GEAR_TOP && sp > GEAR_TOP.getValue(gi) + 8 && !c.clutch) ok = false
                    if (ok && sp >= 20 && rpm != null && rpm > 0 && !c.clutch && v > 0) ok = ratioFits(gi, rpm / sp)
                }
                "batt" -> ok = if (c.cranking) v >= 7.0 else if (v >= g) v - g <= 4.5 else g - v <= rate!! * dt + tol
                else -> ok = abs(v - g) <= rate!! * dt + tol
            }
            if (ok && name == "rpm" && v - g > 2500 && c.throttle < 3) ok = false
            if (ok && name == "rpm") {
                val gr = c.gear?.toInt(); val sp = c.speed ?: 0.0
                if (gr != null && gr in RATIO && sp >= 20 && !c.clutch) ok = ratioFits(gr, v / sp)
            }
            if (ok) return accept(v, t)
            // agrees with a value accepted in the last few seconds → the recent change was an outlier: go straight back
            for ((pv, pt) in hist) {
                val near = if (name == "gear") abs(v - pv) <= 2 else abs(v - pv) <= (rate ?: (180.0 / 3600)) * (t - pt) + tol
                if (t - pt <= 15 && near) return accept(v, t, confirmed = true)
            }
            suspect = ArrayList(suspect.filter { abs(it.first - v) <= tol && t - it.second <= 5 } + (v to t))
            if (suspect.size > CONFIRM) return accept(v, t, confirmed = true)
            rejected++
            return value(t) to "jump"
        }

        private fun accept(v: Double, t: Double, confirmed: Boolean = false): Pair<Double?, String> {
            val g = good
            if (g != null && v != g) { hist.add(g to tGood); while (hist.size > 4) hist.removeAt(0) }
            good = v; tGood = t; suspect = ArrayList(); accepted++
            return v to if (confirmed) "confirmed" else "ok"
        }

        fun value(t: Double): Double? = good?.takeIf { t - tGood <= STALE_S }
    }

    val f = LIMITS.keys.associateWith { Field(it) }
    private var thr = DoubleArray(3)
    private var clutch = false
    private var neutral = false
    private var starterT = -99.0

    /**
     * Feed one decoded frame (plain 20 bytes as ints). t = seconds. Returns the guarded values that changed:
     * field → value or null (unknown). Fields this frame doesn't carry aren't in the result.
     */
    fun frame(u: IntArray, t: Double): Map<String, Double?> {
        if (u[1] == 0x10) thr = doubleArrayOf(thr[1], thr[2], (u[10] / 2).toDouble())
        if (u[1] == 0x11) {
            clutch = (u[3] and 1) != 0 || (u[13] and 1) != 0
            neutral = (u[13] and 1) != 0
            if ((u[3] and 0x10) != 0 || (u[6] and 0x20) != 0) starterT = t
        }
        val real = decode(u)
        val out = LinkedHashMap<String, Double?>()
        for (k in real.keys.sortedBy { it != "speed" }) {
            val c = Ctx(f.getValue("speed").value(t), f.getValue("gear").value(t), f.getValue("rpm").value(t), clutch,
                thr.max(), t - starterT < 6, neutral)
            out[k] = f.getValue(k).feed(real[k], t, c).first
        }
        return out
    }

    /** Fields that keep failing (5+ rejections in a row), for Test & log. */
    fun failing(): List<String> = f.values.filter { it.failRun >= 5 }.map { it.name }

    private fun decode(d: IntArray): Map<String, Double> = when (d[1]) {
        0x10 -> mapOf("speed" to d[2].toDouble(), "odo" to ((d[3] shl 16) or (d[4] shl 8) or d[5]) / 10.0,
            "fuel" to d[6].toDouble(), "rpm" to ((d[16] shl 8) or d[17]).toDouble())
        0x11 -> mapOf("gear" to (d[5] and 0x0F).toDouble())
        0x18 -> mapOf("intake" to (d[7] - 40).toDouble(), "temp" to (d[8] - 40).toDouble(), "batt" to d[11] / 10.0)
        0x12 -> mapOf("range" to Protocol.s16(d[3], d[4]).toDouble())
        else -> emptyMap()
    }
}
