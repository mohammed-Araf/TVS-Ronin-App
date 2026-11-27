package dev.ronindash

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fuel, mileage and range from what the bike reports. Works without the rider ever logging a fill-up.
 *
 * Petrol left is one number with an uncertainty, kept honest from three directions:
 *  - counted down every second by the learning model's fuel flow ([FuelLearner]: RPM, throttle, speed, gear, temperature);
 *  - snapped to the learned level each time the gauge drops a bar (the first drop below full is the sharpest);
 *  - kept inside the band the current bar count means, so an unlogged fill-up, or a friend's ride while the app was off
 *    (seen from the odometer), is caught the next time the bike connects.
 * The gauge drops also teach the model (drop-to-drop fuel), so it gets better every tank even if nobody types anything.
 * After a fill nobody logged, the level starts from what this rider usually fills to (learned at the first drop after).
 *
 * Range = petrol left × the km/l of the riding happening now: this ride's own mileage, blended in as it gets longer,
 * on top of the last ~50 km. A friend riding hard pulls it down within a few km, and the owner gets theirs back.
 * Distance comes from GPS; without GPS, bike speed ÷ the speedometer error learned from GPS. The bike's DTE is never used.
 * Plain Kotlin state + a [Store] so it runs in unit tests.
 */
class FuelModel(private val store: Store) {
    interface Store {
        var fuelCal: Double; var fulls: Int; var recKm: Double; var recL: Double; var tankLeftL: Double
        var burnRaw: Double; var poured: Double; var fullKnown: Boolean; var speedoK: Double
        val tankL: Double; val fuelPrice: Double
        /** The learning model's state ([FuelLearner.save]). */
        var ml: String
        val warmC: Int
        /** The gauge tracker's state ([FuelModel.save]). */
        var gauge: String
        /** Odometer when we last saw the bike (km), to notice riding we didn't see. */
        var odo: Double
    }

    /** Small on-device model: learns this bike's fuel use and fuel gauge (see [FuelLearner]). */
    val learner = FuelLearner(store.ml, store.tankL).also { if (store.ml.isBlank()) it.setPhysics(store.fuelCal) }   // keep what the old model learned

    companion object {
        /** Stock flow scale. Was 5.27e-4; the owner's rides read ~8–10 km/l high (shown ~51–53 vs ~43 real, 2026-11-27), so
         *  ×1.19 before any fill or gauge drop has taught the model. The learner still corrects it per bike from there. */
        const val K = 6.27e-4
        const val L0 = .19
        const val TAU = 25.0
        const val CLAIM = 42.0
        /** The gauge must show a new bar count this long (s) before we believe it (fuel sloshing, slopes). */
        const val SETTLE_S = 30
        /** lastDrop markers: nothing to measure from / a logged full tank / a fill nobody logged. */
        private const val NONE = -1
        private const val FULL = 100
        private const val REFUEL = 101

        /** Engine load with the throttle shut but in gear and rolling: the ECU opens the idle air itself (Glide Through
         *  Technology) to keep the bike pulling, so it burns fuel even though the throttle reads 0. */
        const val GTT_LOAD = .40

        /**
         * Stock fuel flow, before the per-bike correction. accel = km/h per second, clutchIn = clutch lever pulled.
         * Throttle shut is NOT "no fuel": with the clutch pulled (or stopped) the engine idles; in gear and rolling the ECU feeds
         * it (GTT, and gentle lift-offs). Only real engine braking (high revs, slowing down clearly) counts as the fuel cut.
         */
        fun lph(rpm: Int, throttle: Int, kmh: Double, accel: Double = 0.0, clutchIn: Boolean = false, gear: Int = -1): Double {
            if (rpm <= 0) return 0.0
            if (throttle < 2) {
                if (clutchIn || kmh < 3) return K * rpm * L0                               // idling
                if (gear in 1..5 && kmh <= GTT_MAX_KMH[gear] + 1) return K * rpm * GTT_LOAD  // GTT pulling the bike
                if (gear < 1 && kmh <= 25) return K * rpm * GTT_LOAD                       // gear unknown, GTT speeds
                if (rpm > 2500 && accel < -1.5) return .02                                  // engine braking: fuel cut
                return K * rpm * L0                                                         // rolling off: idle air only
            }
            val load = L0 + (1 - L0) * (1 - exp(-throttle / TAU))
            val rich = if (throttle > 70) 1 + .15 * (throttle - 70) / 30 else 1.0
            return K * rpm * load * rich
        }
    }

    /** What happened, for the log. */
    var onLog: (String) -> Unit = {}

    // live smoothing (ratio of averages, so one blip can't swing it)
    private var fV = 0.0
    private var fF = 0.0
    var live = 0.0; private set
    var lphNow = 0.0; private set
    var tripKm = 0.0; private set
    var tripL = 0.0; private set
    var tripMovingS = 0; private set
    private var lastBike = -1.0
    var tank: Double = store.tankLeftL; private set
    /** Variance of [tank] (L²). */
    var tankVar = 1.0; private set

    // this ride (engine on, after ≥ 10 min off), for the rider-style part of range
    var rideKm = 0.0; private set
    var rideL = 0.0; private set
    private var offS = 0

    // the gauge
    private var shown = -1
    private var cand = -1
    private var candS = 0
    private var candSnap = DoubleArray(FuelLearner.N)
    private var candC: Int? = null
    private var lastDrop = NONE
    private var lastDropC: Int? = null
    /** What this rider fills up to when they fill to a full gauge (learned, L): count, mean, variance. */
    private var habitN = 0
    private var habitMean = store.tankL - .6                          // until learned: most fills are to the top
    private var habitVar = .8 * .8
    private var odoNow = store.odo
    /** The bar count when we last looked (kept over restarts, to spot a fill while we were away). */
    private var lastShown = -1
    /** Litres the gauge says went between drops vs litres the model counted, recent gaps weighted most (+5 L of "agrees"). */
    private var calLevel = 0.0
    private var calModel = 0.0
    /** The gauge's verdict on the model: every counted litre is × this. */
    val cal: Double get() = ((calLevel + 5) / (calModel + 5)).coerceIn(.7, 1.4)
    private var lastEngineC: Int? = null

    init { loadGauge(store.gauge) }

    val recent get() = store.recKm / max(.01, store.recL)
    /** Mileage over the last ~500 km: the bike's usual, steady through one odd ride. */
    private var longKm = 0.0
    private var longL = 0.0
    val usual: Double get() = if (longL < 2) recent else longKm / longL
    /** km/l for range: the usual and the last ~50 km, then this ride's own mileage from 2 km on (half by 7 km). */
    val kmpl: Double get() {
        val base = (usual + recent) / 2
        if (rideKm < 2 || rideL < .02) return base
        val k = (rideKm - 2).coerceAtLeast(0.0) / (rideKm + 3)            // the first 2 km (cold start, leaving home) are noise
        return k * (rideKm / rideL) + (1 - k) * base
    }
    val range get() = if (tank < 0) 0.0 else max(0.0, tank * kmpl)
    /** ± km (1 sd) on [range]: petrol-left uncertainty plus ~8 % for how the rest of the tank gets ridden. */
    val rangeSd get() = if (tank < 0) 0.0 else sqrt(tankVar * kmpl * kmpl + (range * .08).let { it * it })
    val tankKnown get() = tank >= 0

    /**
     * One second of riding (dt seconds). gpsKmh = null when there's no fresh, accurate GPS fix. [bars] = the gauge
     * (−1 = not connected), [odoKm] = the bike's odometer (0 = unknown). Returns the distance used for this step (km).
     */
    fun step(dt: Double, rpm: Int, throttle: Int, bikeKmh: Double, gpsKmh: Double?, engineOn: Boolean, recording: Boolean,
             gear: Int = -1, engineC: Int? = null, bars: Int = -1, odoKm: Double = 0.0, clutchIn: Boolean = false): Double {
        if (odoKm > 0) odometer(odoKm)
        if (engineC != null) lastEngineC = engineC
        val dv = if (lastBike < 0) 0.0 else bikeKmh - lastBike
        lastBike = bikeKmh
        if (gpsKmh != null && bikeKmh > 20 && gpsKmh > 15 && abs(dv) < 3) {
            store.speedoK = (store.speedoK + (bikeKmh / gpsKmh - store.speedoK) * .05).coerceIn(.9, 1.2)
        }
        val kmh = gpsKmh ?: (bikeKmh / store.speedoK)
        val moving = bikeKmh > 3
        val distKm = if (moving) kmh * dt / 3600 else 0.0            // no GPS creep while stopped
        val feat = learner.features(rpm, throttle, bikeKmh, dv / dt, gear, engineC, store.warmC, engineOn, clutchIn)
        lphNow = learner.predict(feat) * cal
        learner.accumulate(feat, dt)
        val litres = lphNow * dt / 3600
        // ~6 s window: letting go of the throttle for a moment mustn't throw the number to 99 and back
        fV = fV * .85 + (if (moving) kmh else 0.0) * .15
        fF = fF * .85 + lphNow * .15
        live = if (moving && fF > 0) min(99.0, fV / fF) else 0.0
        if (tank >= 0) { tank = max(0.0, tank - litres); tankVar += (.05 * litres).let { it * it } }
        store.burnRaw += litres
        val dec = exp(-distKm / 50)
        store.recKm = store.recKm * dec + distKm
        store.recL = store.recL * dec + litres
        val dl = exp(-distKm / 500); longKm = longKm * dl + distKm; longL = longL * dl + litres
        if (engineOn) { if (offS > 600) { rideKm = 0.0; rideL = 0.0 }; offS = 0; rideKm += distKm; rideL += litres }
        else offS += dt.toInt().coerceAtLeast(1)
        if (recording && (moving || tripKm > 0)) { tripKm += distKm; tripL += litres; if (moving) tripMovingS += dt.toInt().coerceAtLeast(1) }
        if (bars >= 0) gauge(bars, engineC)
        return distKm
    }

    /** A new trip (after a long stop): also starts this ride's own mileage for range. */
    fun newTrip() { tripKm = 0.0; tripL = 0.0; tripMovingS = 0; rideKm = 0.0; rideL = 0.0 }

    // ------------------------------------------------------------------------------------------------ odometer

    /** Every odometer reading: a jump since the last one we saw means the bike was ridden while we weren't watching. */
    private fun odometer(km: Double) {
        val last = odoNow
        odoNow = km
        if (last <= 0) return
        val d = km - last
        if (d < .5) return                                           // normal riding, or nobody rode it: petrol left still holds
        // ridden d km without us (app closed, phone elsewhere, a friend): count it at the usual mileage, less sure
        lastDrop = NONE; store.fullKnown = false; learner.resetTotals(); learner.span.fill(0.0)
        if (tank >= 0) {
            val used = d / recent.coerceIn(15.0, 70.0)
            tank = max(0.0, tank - used); tankVar += (.25 * used).let { it * it } + .1
        }
        shown = -1                                                   // re-read the gauge: it decides from here
        onLog("[fuel] odometer moved %.0f km while we were away: petrol left now %.1f ± %.1f L, checking against the gauge".format(d, max(0.0, tank), sqrt(tankVar)))
        save()
    }

    // ------------------------------------------------------------------------------------------------ the gauge

    private fun gauge(bars: Int, engineC: Int?) {
        if (shown < 0) {
            val before = lastShown
            shown = bars; cand = -1
            when {
                tank < 0 -> reseed(bars, engineC, "first gauge reading")
                before >= 0 && bars >= before + 2 -> {               // filled while we weren't watching
                    lastDrop = REFUEL; learner.span.fill(0.0); store.fullKnown = false
                    reseed(bars, engineC, "gauge went $before → $bars bars while we were away: a fill")
                }
                else -> constrain(engineC, reseedIfOut = true)
            }
            lastShown = bars
            return
        }
        if (bars == shown) { cand = -1; candS = 0; constrain(engineC, reseedIfOut = false); return }
        if (bars != cand) { cand = bars; candS = 0; candSnap = learner.span.copyOf(); candC = engineC }
        // fuel can't slosh two bars up: a fill shows within seconds. Anything else must hold for SETTLE_S.
        if (++candS < if (bars >= shown + 2) 3 else SETTLE_S) return
        val prev = shown
        shown = bars; cand = -1; candS = 0; lastShown = bars
        when {
            bars == prev - 1 -> drop(prev)
            bars < prev -> {                                         // jumped more than one bar: can't tell where, just stay in band
                lastDrop = NONE; for (i in learner.span.indices) learner.span[i] -= candSnap[i]
                constrain(engineC, reseedIfOut = true)
            }
            bars == prev + 1 -> constrain(engineC, reseedIfOut = false)   // one bar back up: a warm gauge or a slope, not a fill
            else -> {                                                // rose ≥ 2 bars: a fill. The "Filled up?" answer (if any) has the details.
                if (lastDrop != FULL && lastDrop != REFUEL) { lastDrop = REFUEL; learner.span.fill(0.0); store.fullKnown = false }
                val (lo, _) = learner.band(bars, engineC)
                if (tank < lo - .3) reseed(bars, engineC, "gauge rose $prev → $bars, nobody logged a fill")
            }
        }
        save()
    }

    /** The gauge settled one bar lower: [a] → a−1. A measurement of petrol left, and of the model. */
    private fun drop(a: Int) {
        val c = candC
        val sp = candSnap
        val after = DoubleArray(FuelLearner.N) { learner.span[it] - sp[it] }
        var learned = ""
        when (lastDrop) {
            FULL -> if (learner.learnFillToDrop(a, c, sp)) learned = "learned from full → $a bars"
            REFUEL -> if (a == FuelLearner.BARS) {
                // a fill back to a full gauge is most likely to the top: weak evidence for the scale
                if (learner.learnFillToDrop(a, c, sp, store.tankL - .5, .9)) learned = "learned from a fill → $a bars. "
                // and what did they fill to? (where the next fill nobody logs starts)
                val lvl = learner.dropAt(a, c) + learner.litres(sp) * cal
                if (lvl in 6.0..store.tankL + .5) {
                    habitN++; val k = 1.0 / min(habitN + 1, 8)
                    val d = lvl - habitMean; habitMean += k * d; habitVar = (1 - k) * (habitVar + k * d * d)
                    learned += "filled to ~%.1f L".format(lvl)
                }
            }
            in 1..FuelLearner.BARS -> if (lastDrop > a) {
                val gauge = learner.dropAt(lastDrop, lastDropC) - learner.dropAt(a, c); val counted = learner.litres(sp)
                learned = if (learner.learnGap(lastDrop, lastDropC, a, c, sp)) {
                    if (counted > .3) { calLevel = calLevel * .9 + gauge; calModel = calModel * .9 + counted }
                    "learned from $lastDrop → $a bars (%.2f L by the gauge, %.2f counted, ×%.2f)".format(gauge, counted, cal)
                } else "gauge gap didn't fit, not used"
            }
        }
        val again = lastDrop == a
        if (!again) { for (i in after.indices) learner.span[i] = after[i]; lastDrop = a; lastDropC = c }   // again: keep counting from the first time
        // petrol left: the level at the drop, minus what burned while the gauge was settling
        val m = learner.dropAt(a, c) - learner.litres(after) * cal
        val r = learner.dropVar(a, c) * if (again) 4 else 1
        if (tank < 0) { tank = m; tankVar = r }
        else { val k = tankVar / (tankVar + r); tank += k * (m - tank); tankVar *= (1 - k) }
        tank = tank.coerceIn(0.0, store.tankL)
        onLog("[fuel] gauge $a → ${a - 1}: petrol left %.1f ± %.1f L. %s".format(tank, sqrt(tankVar), learned).trim() +
            "  levels ${learner.gaugeSummary()}")
    }

    /** Keep petrol left inside what the gauge shows (with the levels' own uncertainty as slack). */
    private fun constrain(engineC: Int?, reseedIfOut: Boolean) {
        if (shown < 0) return
        val (lo, hi) = learner.band(shown, engineC)
        val tolLo = if (shown >= 1) sqrt(learner.dropVar(shown, engineC)) else .3
        val tolHi = if (shown < FuelLearner.BARS) sqrt(learner.dropVar(shown + 1, engineC)) else .4
        when {
            tank < 0 -> reseed(shown, engineC, "no estimate yet")
            reseedIfOut -> {                                         // what we had, cut to what the gauge allows
                val a = lo - tolLo / 2; val b = hi + tolHi / 2
                if (tank < a || tank > b || tankVar > (b - a).let { it * it } / 12) {
                    val was = tank
                    val (mu, v) = truncated(tank, sqrt(tankVar), a, b)
                    tank = mu; tankVar = v + .05
                    onLog("[fuel] %.1f L checked against $shown bars → %.1f ± %.1f L".format(was, tank, sqrt(tankVar)))
                }
            }
            tank < lo - tolLo || tank > hi + tolHi -> { tank = tank.coerceIn(lo - tolLo, hi + tolHi); tankVar = max(tankVar, min(tolLo, tolHi).let { it * it }) }
        }
    }

    /** Start petrol left from the bar count alone: mid-band, or this rider's usual fill when the gauge is full. */
    private fun reseed(bars: Int, engineC: Int?, why: String) {
        val (lo, hi) = learner.band(bars, engineC)
        if (bars >= FuelLearner.BARS) {
            tank = habitMean.coerceIn(lo, hi); tankVar = habitVar + .1
        } else { tank = (lo + hi) / 2; tankVar = (hi - lo).let { it * it } / 12 + .1 }
        onLog("[fuel] $why → petrol left from the gauge: %.1f ± %.1f L".format(tank, sqrt(tankVar)))
    }

    /** Mean and variance of a normal (μ, σ) cut to [a, b]. Far outside: the nearer end. */
    private fun truncated(mu: Double, sd: Double, a: Double, b: Double): Pair<Double, Double> {
        val sg = max(sd, .05)
        val al = (a - mu) / sg; val be = (b - mu) / sg
        val z = cdf(be) - cdf(al)
        if (z < 1e-6) return (if (mu < a) a else b) to ((b - a) / 6).let { it * it }
        val m = mu + sg * (pdf(al) - pdf(be)) / z
        val v = sg * sg * (1 + (al * pdf(al) - be * pdf(be)) / z - ((pdf(al) - pdf(be)) / z).let { it * it })
        return m.coerceIn(a, b) to max(v, .01)
    }
    private fun pdf(x: Double) = exp(-x * x / 2) / 2.5066282746
    private fun cdf(x: Double): Double {                             // Abramowitz–Stegun 7.1.26
        val t = 1 / (1 + .3275911 * abs(x) / 1.41421356)
        val e = 1 - t * (.254829592 + t * (-.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429)))) * exp(-x * x / 2)
        return if (x >= 0) (1 + e) / 2 else (1 - e) / 2
    }

    /** First gauge reading with no count of our own yet (kept for callers; the gauge tracker does it every second). */
    fun seedFromBars(bars: Int) { if (tank < 0 && bars in 0..FUEL_BARS) { reseed(bars, lastEngineC, "first gauge reading"); save() } }

    // ------------------------------------------------------------------------------------------------ fills

    /** Snapshot before a gauge guess, so a later answer can undo it. */
    private data class Snap(val tank: Double, val tankVar: Double, val burnRaw: Double, val poured: Double, val fullKnown: Boolean,
                            val ml: String, val lastDrop: Int)
    private var snap: Snap? = null

    /** Nobody answered "Filled up?": the gauge decides. Returns the guess text. */
    fun gaugeFill(bars0: Int, bars1: Int): String {
        snap = Snap(tank, tankVar, store.burnRaw, store.poured, store.fullKnown, learner.save(), lastDrop)
        val before = max(0.0, tank)
        reseed(bars1, lastEngineC, "fill nobody logged ($bars0 → $bars1 bars)")
        lastDrop = REFUEL; learner.span.fill(0.0); store.fullKnown = false; learner.resetTotals()
        save()
        return if (bars1 >= FUEL_BARS) "full tank" else "about %.1f L".format(max(0.0, tank - before))
    }

    fun undoGuess() {
        val z = snap ?: return
        tank = z.tank; tankVar = z.tankVar; store.burnRaw = z.burnRaw; store.poured = z.poured; store.fullKnown = z.fullKnown
        learner.restore(z.ml); lastDrop = z.lastDrop
        snap = null; save()
    }

    fun keepGuess() { snap = null }

    /**
     * Answer: full tank (pumpL = litres on the pump, optional) — a full-to-full with litres calibrates the model,
     * weighted by litres; answers more than 30 % off are ignored (typo, or not filled to the top).
     */
    fun fullTank(pumpL: Double?): String {
        undoGuess()
        var msg = "Full tank"
        if (pumpL != null && pumpL > 0 && store.fullKnown) {
            val actual = pumpL + store.poured
            val r = learner.learn(actual, .3 + .03 * actual)              // the pump is exact; "full" varies a little
            msg = if (r == null) "Full tank: %.1f L poured vs %.1f L expected is too far off, not used to learn".format(actual, learner.expectedSinceFull())
            else { store.fulls++; "Full to full: $r → ${learner.summary()}" }
        }
        tank = store.tankL; tankVar = .15; store.burnRaw = 0.0; store.poured = 0.0; store.fullKnown = true; learner.resetTotals()
        lastDrop = FULL; learner.span.fill(0.0)
        save()
        return msg
    }

    /** Answer: a partial fill, in litres (₹ ÷ price before calling). */
    fun added(litres: Double): String {
        undoGuess()
        val room = store.tankL - max(0.0, tank)
        tank = min(store.tankL, max(0.0, tank) + litres); tankVar += (.1 * litres).let { it * it }; store.poured += litres
        lastDrop = NONE; learner.span.fill(0.0)                        // fuel added mid-gap: the next gap starts from here
        save()
        return if (litres > room + .7) "%.1f L is more than our estimate says fits (%.1f L): petrol left was too low, set to full".format(litres, room)
        else "%.2f L added → petrol left %.1f L".format(litres, tank)
    }

    // ------------------------------------------------------------------------------------------------ saving

    fun save() {
        store.tankLeftL = tank; store.ml = learner.save(); store.fuelCal = learner.x[0]
        if (odoNow > 0) store.odo = odoNow
        store.gauge = listOf(tankVar, lastDrop, lastDropC ?: -999, habitN, habitMean, habitVar, calLevel, calModel, longKm, longL, lastShown).joinToString(",")
    }

    private fun loadGauge(s: String) {
        val v = s.split(",").mapNotNull { it.toDoubleOrNull() }
        if (v.size < 6) { tankVar = if (tank >= 0) 1.0 else 4.0; return }
        tankVar = v[0]; lastDrop = v[1].toInt(); lastDropC = v[2].toInt().takeIf { it != -999 }
        habitN = v[3].toInt(); habitMean = v[4]; habitVar = v[5]
        if (v.size >= 8) { calLevel = v[6]; calModel = v[7] }
        if (v.size >= 10) { longKm = v[8]; longL = v[9] }
        if (v.size >= 11) lastShown = v[10].toInt()
    }
}
