package dev.ronindash

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A small on-device learning model for fuel flow (L/h): Bayesian linear regression over six riding features, each in L/h,
 * computed every second from what the bike reports (RPM, throttle, speed, gear, engine temperature).
 *
 *   flow = w0·physics + w1·idle + w2·wind + w3·accelerating + w4·low gear + w5·cold engine
 *
 * It starts as the plain physics model (w = 1, 0, 0, 0, 0, 0). The only real truth about fuel is how much went into the
 * tank, so it learns at fill-ups: between two fills it adds up each feature × time, and the litres poured are a sum of
 * those totals × the weights. Each fill is one Kalman / recursive-least-squares update, weighted by how much we trust it
 * (litres on the pump ±0.3 L; the coarse fuel gauge ±1–1.5 L). The prior keeps every weight near physics until fills
 * disagree, so a handful of fills can't make it do anything silly, and it never goes below zero.
 *
 * 6 weights + a 6×6 covariance + 6 running totals: a few hundred bytes, microseconds per update. Plain Kotlin, unit-tested.
 */
class FuelLearner(saved: String = "") {
    companion object {
        const val N = 6
        val NAMES = listOf("physics", "idle", "wind", "accelerating", "low gear", "cold engine")
        /** How far each weight may move from its start (1 sd): physics ±15 %, the corrections ±0.6 of their scale. */
        private val PRIOR_SD = doubleArrayOf(.15, .6, .6, .6, .6, .6)
        private val START = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }

    val w = START.copyOf()
    private val p = Array(N) { i -> DoubleArray(N) { j -> if (i == j) PRIOR_SD[i] * PRIOR_SD[i] else 0.0 } }
    /** Feature × hours since the last full tank (what the next fill-up is compared with). */
    val acc = DoubleArray(N)
    var updates = 0; private set

    init { load(saved) }

    /** The features for one moment, each already in L/h. */
    fun features(rpm: Int, throttle: Int, kmh: Double, accelKmhS: Double, gear: Int, engineC: Int?, warmC: Int, engineOn: Boolean): DoubleArray {
        val f = DoubleArray(N)
        if (!engineOn || rpm <= 0) return f
        val phys = FuelModel.lph(rpm, throttle, kmh)
        f[0] = phys
        f[1] = if (kmh < 3) .15 else 0.0                                   // idling: the idle mixture itself
        f[2] = .5 * (kmh / 100).let { it * it * it }                          // air drag grows with speed³ (≈0.5 L/h at 100)
        f[3] = if (accelKmhS > .5 && throttle > 5) .3 * min(accelKmhS, 8.0) / 4 * (kmh / 50).coerceIn(.2, 2.0) else 0.0
        f[4] = if (gear in 1..2 && throttle > 20) .3 * phys else 0.0         // low gears under load run rich
        f[5] = if (engineC != null && engineC < warmC) .25 * phys * ((warmC - engineC).toDouble() / 30).coerceIn(0.0, 1.0) else 0.0
        return f
    }

    fun predict(f: DoubleArray): Double { var y = 0.0; for (i in 0 until N) y += w[i] * f[i]; return max(0.0, y) }

    /** One second (dt s) of riding: add to the running totals. */
    fun accumulate(f: DoubleArray, dt: Double) { for (i in 0 until N) acc[i] += f[i] * dt / 3600 }

    fun expectedSinceFull(): Double = max(0.0, (0 until N).sumOf { w[it] * acc[it] })

    /**
     * Truth arrived: [litres] went in since the totals were last reset (fill-to-fill), known to ±[sdL].
     * Returns what changed, or null if it was rejected (more than 30 % off the expectation: a typo, or not filled to the top).
     */
    fun learn(litres: Double, sdL: Double): String? {
        val exp = expectedSinceFull()
        if (exp < .5 || litres <= 0) return null
        if (abs(litres / exp - 1) > .3) return null
        val pa = DoubleArray(N) { i -> (0 until N).sumOf { j -> p[i][j] * acc[j] } }
        val s = (0 until N).sumOf { acc[it] * pa[it] } + sdL * sdL
        val err = litres - (0 until N).sumOf { w[it] * acc[it] }
        val k = DoubleArray(N) { pa[it] / s }
        for (i in 0 until N) w[i] += k[i] * err
        for (i in 0 until N) for (j in 0 until N) p[i][j] -= k[i] * pa[j]
        // keep it sane: physics stays 0.6–1.6×, corrections can't flip a feature into "saves fuel" beyond −0.5
        w[0] = w[0].coerceIn(.6, 1.6)
        for (i in 1 until N) w[i] = w[i].coerceIn(-.5, 3.0)
        updates++
        return "expected %.2f L, poured %.2f L".format(exp, litres)
    }

    fun resetTotals() { acc.fill(0.0) }

    /** How sure the model is now: ± litres per 100 km of typical riding (shrinks with each fill). */
    fun spread(): Double = sqrt(max(0.0, p[0][0])) * 100

    fun summary(): String = NAMES.indices.joinToString(", ") { "${NAMES[it]} %.2f".format(w[it]) }

    fun save(): String = buildString {
        append(w.joinToString(",")); append(";")
        append(p.joinToString(",") { r -> r.joinToString(",") }); append(";")
        append(acc.joinToString(",")); append(";"); append(updates)
    }

    /** Back to a saved state (undoing a guessed fill-up). */
    fun restore(s: String) { load(s) }

    private fun load(s: String) {
        val parts = s.split(";")
        if (parts.size < 4) return
        try {
            val ww = parts[0].split(",").map { it.toDouble() }
            val pp = parts[1].split(",").map { it.toDouble() }
            val aa = parts[2].split(",").map { it.toDouble() }
            if (ww.size != N || pp.size != N * N || aa.size != N) return
            for (i in 0 until N) { w[i] = ww[i]; acc[i] = aa[i]; for (j in 0 until N) p[i][j] = pp[i * N + j] }
            updates = parts[3].toInt()
        } catch (_: Exception) {}
    }
}
