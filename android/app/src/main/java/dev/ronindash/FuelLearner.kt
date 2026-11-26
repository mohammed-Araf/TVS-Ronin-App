package dev.ronindash

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A small on-device learning model for fuel, that needs nothing from the rider: one Kalman filter (Bayesian linear
 * regression) over 16 numbers.
 *
 *  - 6 fuel-flow weights (L/h), on features computed every second from RPM, throttle, speed, gear and engine temperature:
 *        flow = w0·physics + w1·idle + w2·wind + w3·accelerating + w4·low gear + w5·cold engine
 *  - 9 gauge levels: the litres left at the moment the bike's gauge drops from b bars to b−1 (b = 9…1). The gauge is
 *    uneven (a full tank and ~11 L both show 9 bars), but each drop happens at about the same fuel every time.
 *  - 1 gauge temperature term: a hot bike reads a little more fuel than a cold one, so the lower drops come later.
 *
 * Truth comes from the gauge itself. Between two bar drops (no fill in between), the fuel the model counted must equal the
 * difference of those two levels: one linear equation in the 16 numbers, one Kalman update. After a few tanks the levels
 * and the weights agree with each other, without the rider ever typing anything. A logged full tank (litres on the pump)
 * is the strongest evidence and pins the absolute scale; it's welcome but never needed.
 *
 * Priors keep everything sane (physics ±15 %, levels from the owner's manual ±1.2 L); updates that disagree wildly (a
 * missed fill, a gauge glitch) are rejected. A 16×16 covariance: ~2 KB saved, microseconds per update. Plain Kotlin.
 */
class FuelLearner(saved: String = "", val tankL: Double = 14.0) {
    companion object {
        const val N = 6
        const val BARS = FUEL_BARS
        /** Index of the gauge temperature term (after the weights and the levels). */
        const val G = N + BARS
        const val M = G + 1
        val NAMES = listOf("physics", "idle", "wind", "accelerating", "low gear", "cold engine")
        private val PRIOR_SD = doubleArrayOf(.3, .6, .6, .6, .6, .6)
        /** How exactly the gauge drops at its level, after the temperature correction: the first drop is the sharp one. */
        fun sensorSd(b: Int) = if (b == BARS) .3 else .6
        /** Engine temperature in gauge-correction units (0 at 60 °C, ±1 per 30 °C). Unknown → 0. */
        fun dT(engineC: Int?) = (((engineC ?: 60) - 60) / 30.0).coerceIn(-1.5, 1.5)
    }

    val x = DoubleArray(M)
    private val p = Array(M) { DoubleArray(M) }
    /** Feature × hours since the last full tank (fill-to-fill). */
    val acc = DoubleArray(N)
    /** Feature × hours since the last gauge drop (drop-to-drop). */
    val span = DoubleArray(N)
    var updates = 0; private set
    var gaugeUpdates = 0; private set

    init { prior(); load(saved) }

    private fun prior() {
        for (r in p) r.fill(0.0)
        x.fill(0.0); x[0] = 1.0
        for (i in 0 until N) p[i][i] = PRIOR_SD[i] * PRIOR_SD[i]
        // Anchors for the absolute scale (gaps alone can't give it): the manual's 0 bars ≈ 2 L and 1 bar ≈ 3.2 L (so the
        // 1 → 0 and 2 → 1 drops), and the owner's "a full tank and 11 L both show 9 bars" (the 9 → 8 drop). Between: uneven.
        val k = tankL / 14
        val top = 10.9 * k
        for (b in 1..BARS) {
            x[N + b - 1] = when (b) { 1 -> 2.1 * k; 2 -> 3.2 * k; else -> 3.2 * k + (b - 2) * (top - 3.2 * k) / (BARS - 2) }
            p[N + b - 1][N + b - 1] = when (b) { BARS -> .6 * .6; 1, 2 -> .5 * .5; else -> 1.0 }
        }
        p[G][G] = .4 * .4
    }

    /** The fuel-flow weights. */
    val w: DoubleArray get() = x.copyOf(N)
    fun setPhysics(v: Double) { x[0] = v.coerceIn(.6, 1.6) }

    /** The features for one moment, each already in L/h. */
    fun features(rpm: Int, throttle: Int, kmh: Double, accelKmhS: Double, gear: Int, engineC: Int?, warmC: Int, engineOn: Boolean): DoubleArray {
        val f = DoubleArray(N)
        if (!engineOn || rpm <= 0) return f
        val phys = FuelModel.lph(rpm, throttle, kmh)
        f[0] = phys
        f[1] = if (kmh < 3) .15 else 0.0                                   // idling: the idle mixture itself
        f[2] = .5 * (kmh / 100).let { it * it * it }                          // air drag grows with speed³ (≈0.5 L/h at 100)
        f[3] = if (accelKmhS > .5 && throttle > 5) .3 * accelKmhS.coerceAtMost(8.0) / 4 * (kmh / 50).coerceIn(.2, 2.0) else 0.0
        f[4] = if (gear in 1..2 && throttle > 20) .3 * phys else 0.0         // low gears under load run rich
        f[5] = if (engineC != null && engineC < warmC) .25 * phys * ((warmC - engineC).toDouble() / 30).coerceIn(0.0, 1.0) else 0.0
        return f
    }

    fun predict(f: DoubleArray): Double { var y = 0.0; for (i in 0 until N) y += x[i] * f[i]; return max(0.0, y) }

    /** dt seconds of riding: add to both running totals. */
    fun accumulate(f: DoubleArray, dt: Double) { for (i in 0 until N) { val v = f[i] * dt / 3600; acc[i] += v; span[i] += v } }

    /** Litres the model says these feature totals burned. */
    fun litres(a: DoubleArray): Double = max(0.0, (0 until N).sumOf { x[it] * a[it] })

    fun expectedSinceFull(): Double = litres(acc)

    // ----------------------------------------------------------------------------------------------- the gauge

    /** Litres left when the gauge drops from [b] to b−1 bars, at this engine temperature. */
    fun dropAt(b: Int, engineC: Int?): Double = x[N + b - 1] - x[G] * dT(engineC)

    /** How unsure [dropAt] is (variance, L²), including the gauge's own scatter. */
    fun dropVar(b: Int, engineC: Int?): Double {
        val h = DoubleArray(M); h[N + b - 1] = 1.0; h[G] = -dT(engineC)
        return quad(h) + sensorSd(b).let { it * it }
    }

    /** The fuel band that [bars] bars means right now: (lowest, highest) litres. */
    fun band(bars: Int, engineC: Int?): Pair<Double, Double> {
        val lo = if (bars >= 1) dropAt(bars.coerceAtMost(BARS), engineC) else 0.0
        val hi = if (bars < BARS) dropAt(bars + 1, engineC) else tankL
        return max(0.0, lo) to max(lo + .1, hi)
    }

    /**
     * The gauge dropped [a] → a−1 at engine temperature [ca], and later [b] → b−1 at [cb], with no fill in between; the model
     * counted the feature totals [sp] between the two. Returns false if it didn't fit (rejected).
     */
    fun learnGap(a: Int, ca: Int?, b: Int, cb: Int?, sp: DoubleArray): Boolean {
        if (a <= b) return false
        val used = litres(sp)
        if (used < .3 || used > tankL) return false
        val h = DoubleArray(M)
        for (i in 0 until N) h[i] = sp[i]
        h[N + a - 1] = -1.0; h[N + b - 1] = 1.0; h[G] = dT(ca) - dT(cb)
        val r = sensorSd(a).let { it * it } + sensorSd(b).let { it * it } + (.03 * used).let { it * it }
        return update(h, 0.0, r).also { if (it) gaugeUpdates++ }
    }

    /**
     * The tank was filled to [level] ± [levelSd] L (a logged full tank: the tank's size, ±0.4; a fill nobody logged that
     * brought the gauge back to full: most likely near the top, ±0.9), then the gauge dropped [b] → b−1 at [cb] after the
     * totals [sp]. This is what pins the absolute scale.
     */
    fun learnFillToDrop(b: Int, cb: Int?, sp: DoubleArray, level: Double = tankL, levelSd: Double = .4): Boolean {
        val used = litres(sp)
        if (used < .3) return false
        val h = DoubleArray(M)
        for (i in 0 until N) h[i] = sp[i]
        h[N + b - 1] = 1.0; h[G] = -dT(cb)
        val r = levelSd * levelSd + sensorSd(b).let { it * it } + (.03 * used).let { it * it }
        return update(h, level, r, gate = 3.0).also { if (it) gaugeUpdates++ }
    }

    // ----------------------------------------------------------------------------------------------- fills

    /**
     * Truth arrived: [litres] went in since the totals were last reset (fill-to-fill), known to ±[sdL].
     * Returns what changed, or null if it was rejected (more than 30 % off the expectation: a typo, or not filled to the top).
     */
    fun learn(litres: Double, sdL: Double): String? {
        val exp = expectedSinceFull()
        if (exp < .5 || litres <= 0) return null
        if (abs(litres / exp - 1) > .3) return null
        val h = DoubleArray(M); for (i in 0 until N) h[i] = acc[i]
        if (!update(h, litres, sdL * sdL, gate = 100.0)) return null
        updates++
        return "expected %.2f L, poured %.2f L".format(exp, litres)
    }

    fun resetTotals() { acc.fill(0.0) }

    // ----------------------------------------------------------------------------------------------- the filter

    private fun quad(h: DoubleArray): Double { var s = 0.0; for (i in 0 until M) { if (h[i] == 0.0) continue; for (j in 0 until M) s += h[i] * p[i][j] * h[j] }; return s }

    /** One Kalman update for the observation h·x = y ± √r. Rejected if more than [gate] standard deviations off. */
    private fun update(h: DoubleArray, y: Double, r: Double, gate: Double = 4.0): Boolean {
        val ph = DoubleArray(M) { i -> (0 until M).sumOf { j -> p[i][j] * h[j] } }
        val s = (0 until M).sumOf { h[it] * ph[it] } + r
        val err = y - (0 until M).sumOf { h[it] * x[it] }
        if (abs(err) > gate * sqrt(s)) return false
        for (i in 0 until M) x[i] += ph[i] / s * err
        for (i in 0 until M) for (j in 0 until M) p[i][j] -= ph[i] * ph[j] / s
        for (i in 0 until M) p[i][i] = max(p[i][i], 1e-6)
        sane()
        return true
    }

    private fun sane() {
        x[0] = x[0].coerceIn(.6, 1.6)
        for (i in 1 until N) x[i] = x[i].coerceIn(-.5, 3.0)
        x[N] = x[N].coerceIn(.3, tankL - 2)
        for (b in 2..BARS) x[N + b - 1] = x[N + b - 1].coerceIn(x[N + b - 2] + .2, tankL - .2)
        x[G] = x[G].coerceIn(-1.5, 1.5)
    }

    /** How sure the physics weight is: ± litres per 100 L of fuel. */
    fun spread(): Double = sqrt(max(0.0, p[0][0])) * 100

    fun summary(): String = NAMES.indices.joinToString(", ") { "${NAMES[it]} %.2f".format(x[it]) }

    fun gaugeSummary(): String = (BARS downTo 1).joinToString(" ") { "$it→${it - 1} %.1f".format(x[N + it - 1]) } +
        "  hot reads %+.2f L".format(x[G])

    fun save(): String = buildString {
        append(x.joinToString(",")); append(";")
        append(p.joinToString(",") { r -> r.joinToString(",") }); append(";")
        append(acc.joinToString(",")); append(";"); append(updates); append(";")
        append(span.joinToString(",")); append(";"); append(gaugeUpdates)
    }

    /** Back to a saved state (undoing a guessed fill-up). */
    fun restore(s: String) { prior(); load(s) }

    private fun load(s: String) {
        val parts = s.split(";")
        if (parts.size < 4) return
        try {
            val xx = parts[0].split(",").map { it.toDouble() }
            val pp = parts[1].split(",").map { it.toDouble() }
            val aa = parts[2].split(",").map { it.toDouble() }
            if (aa.size != N) return
            val n = xx.size                                  // 6 = the first version (weights only): keep them, gauge from the prior
            if ((n != N && n != M) || pp.size != n * n) return
            for (i in 0 until n) { x[i] = xx[i]; for (j in 0 until n) p[i][j] = pp[i * n + j] }
            for (i in 0 until N) acc[i] = aa[i]
            updates = parts[3].toInt()
            if (parts.size >= 6) {
                parts[4].split(",").map { it.toDouble() }.takeIf { it.size == N }?.forEachIndexed { i, v -> span[i] = v }
                gaugeUpdates = parts[5].toInt()
            }
        } catch (_: Exception) {}
    }
}
