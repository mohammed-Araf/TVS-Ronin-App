package dev.ronindash

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Fuel, mileage and range from what the bike reports (mock FIXES 15–17).
 *
 * Fuel flow (L/h) = K × RPM × engine load, load from the throttle the rider gives: idle ≈ 0.15 L/h, fuel cut with the throttle
 * shut while rolling, richer above 70 % throttle. Calibrated so steady 60 km/h in 5th ≈ 44 km/l and 120 km/h ≈ 32 km/l, then
 * corrected per bike (fuelCal) at every full-to-full fill. Distance comes from GPS; without GPS, bike speed ÷ the speedometer
 * error learned from GPS. Range = OUR petrol left × the rider's own mileage over the last ~50 km. The bike's DTE is never used;
 * its fuel gauge (in BARS, 0–9) only spots a fill-up and gives two fixed points (1 bar ≈ 3.2 L, 0 bars ≈ 2 L).
 * Plain Kotlin state + a [Store] so it runs in unit tests.
 */
class FuelModel(private val store: Store) {
    interface Store {
        var fuelCal: Double; var fulls: Int; var recKm: Double; var recL: Double; var tankLeftL: Double
        var burnRaw: Double; var poured: Double; var fullKnown: Boolean; var speedoK: Double
        val tankL: Double; val fuelPrice: Double
    }

    companion object {
        const val K = 5.27e-4
        const val L0 = .19
        const val TAU = 25.0
        const val CLAIM = 42.0
        /** Litres left at each gauge bar count (owner's manual: 1 bar ≈ 3.2 L, 0 bars ≈ 2 L, 9 bars = full). */
        fun barsToL(bars: Int, tank: Double): Double = when {
            bars <= 0 -> 2.0
            bars >= FUEL_BARS -> tank
            else -> 3.2 + (bars - 1) * (tank - 3.2) / (FUEL_BARS - 1)
        }

        /** Stock fuel flow, before the per-bike correction. */
        fun lph(rpm: Int, throttle: Int, kmh: Double): Double {
            if (rpm <= 0) return 0.0
            if (throttle < 2 && rpm > 1800 && kmh > 10) return .02
            val load = L0 + (1 - L0) * (1 - exp(-throttle / TAU))
            val rich = if (throttle > 70) 1 + .15 * (throttle - 70) / 30 else 1.0
            return K * rpm * load * rich
        }
    }

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

    val recent get() = store.recKm / max(.01, store.recL)
    val range get() = if (tank < 0) 0.0 else max(0.0, tank * recent)
    val tankKnown get() = tank >= 0

    /**
     * One second of riding (dt seconds). gpsKmh = null when there's no fresh, accurate GPS fix.
     * Returns the distance used for this step (km).
     */
    fun step(dt: Double, rpm: Int, throttle: Int, bikeKmh: Double, gpsKmh: Double?, engineOn: Boolean, recording: Boolean): Double {
        val dv = if (lastBike < 0) 0.0 else bikeKmh - lastBike
        lastBike = bikeKmh
        if (gpsKmh != null && bikeKmh > 20 && gpsKmh > 15 && abs(dv) < 3) {
            store.speedoK = (store.speedoK + (bikeKmh / gpsKmh - store.speedoK) * .05).coerceIn(.9, 1.2)
        }
        val kmh = gpsKmh ?: (bikeKmh / store.speedoK)
        val moving = bikeKmh > 3
        val distKm = if (moving) kmh * dt / 3600 else 0.0            // no GPS creep while stopped
        lphNow = if (engineOn) lph(rpm, throttle, bikeKmh) * store.fuelCal else 0.0
        val litres = lphNow * dt / 3600
        fV = fV * .6 + (if (moving) kmh else 0.0) * .4
        fF = fF * .6 + lphNow * .4
        live = if (moving && fF > 0) min(99.0, fV / fF) else 0.0
        if (tank >= 0) tank = max(0.0, tank - litres)
        store.burnRaw += litres / store.fuelCal
        val dec = exp(-distKm / 50)
        store.recKm = store.recKm * dec + distKm
        store.recL = store.recL * dec + litres
        if (recording && (moving || tripKm > 0)) { tripKm += distKm; tripL += litres; if (moving) tripMovingS += dt.toInt().coerceAtLeast(1) }
        return distKm
    }

    fun newTrip() { tripKm = 0.0; tripL = 0.0; tripMovingS = 0 }

    /** First gauge reading with no count of our own yet: start from the bars. */
    fun seedFromBars(bars: Int) { if (tank < 0 && bars in 0..FUEL_BARS) { tank = barsToL(bars, store.tankL); save() } }

    /**
     * The gauge dropped to 1 bar (or 0): a fixed point from the manual. If we know the last full tank, it nudges the model a
     * little (the gauge is coarse), and petrol left snaps toward it.
     */
    fun gaugePoint(bars: Int): String? {
        if (bars > 1 || !store.fullKnown) return null
        val left = barsToL(bars, store.tankL)
        val actual = store.tankL - left + store.poured
        val ratio = actual / max(.5, store.burnRaw)
        val prev = store.fuelCal
        if (abs(ratio / prev - 1) > .3) return null
        store.fuelCal = prev + .1 * (ratio - prev)
        tank = (tank + left) / 2
        save()
        return "gauge at $bars bar: about %.1f L used since full → fuel model ×%.3f → ×%.3f".format(actual, prev, store.fuelCal)
    }

    // ------------------------------------------------------------- fills

    /** Snapshot before a gauge guess, so a later answer can undo it. */
    private data class Snap(val tank: Double, val burnRaw: Double, val poured: Double, val fullKnown: Boolean, val fuelCal: Double)
    private var snap: Snap? = null

    /** Nobody answered: the fuel gauge decides (how far it rose; ≥ 9 bars = full tank). Returns the guess text. */
    fun gaugeFill(bars0: Int, bars1: Int): String {
        snap = Snap(tank, store.burnRaw, store.poured, store.fullKnown, store.fuelCal)
        val full = bars1 >= FUEL_BARS
        if (full) {
            if (store.fullKnown) {
                val actual = store.tankL - barsToL(bars0, store.tankL) + store.poured
                val ratio = actual / max(.5, store.burnRaw); val prev = store.fuelCal
                if (abs(ratio / prev - 1) <= .3) store.fuelCal = prev + .15 * (ratio - prev)
            }
            tank = store.tankL; store.burnRaw = 0.0; store.poured = 0.0; store.fullKnown = true
        } else {
            val added = barsToL(bars1, store.tankL) - barsToL(bars0, store.tankL)
            tank = min(store.tankL, max(0.0, tank) + added); store.poured += added
        }
        save()
        return if (full) "full tank" else "about %.1f L".format(barsToL(bars1, store.tankL) - barsToL(bars0, store.tankL))
    }

    fun undoGuess() {
        val z = snap ?: return
        tank = z.tank; store.burnRaw = z.burnRaw; store.poured = z.poured; store.fullKnown = z.fullKnown; store.fuelCal = z.fuelCal
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
            val ratio = actual / max(.5, store.burnRaw); val prev = store.fuelCal
            msg = if (abs(ratio / prev - 1) > .3) "Full tank: %.1f L poured vs %.1f L expected is too far off, not used to calibrate".format(actual, store.burnRaw * prev)
            else {
                val w = actual / (actual + 8)
                store.fuelCal = prev + w * (ratio - prev); store.fulls++
                "Full to full: %.1f L poured, model expected %.1f L → fuel model ×%.3f → ×%.3f".format(actual, store.burnRaw * prev, prev, store.fuelCal)
            }
        }
        tank = store.tankL; store.burnRaw = 0.0; store.poured = 0.0; store.fullKnown = true
        save()
        return msg
    }

    /** Answer: a partial fill, in litres (₹ ÷ price before calling). */
    fun added(litres: Double): String {
        undoGuess()
        val room = store.tankL - max(0.0, tank)
        tank = min(store.tankL, max(0.0, tank) + litres); store.poured += litres
        save()
        return if (litres > room + .7) "%.1f L is more than our estimate says fits (%.1f L): petrol left was too low, set to full".format(litres, room)
        else "%.2f L added → petrol left %.1f L".format(litres, tank)
    }

    fun save() { store.tankLeftL = tank }
}
