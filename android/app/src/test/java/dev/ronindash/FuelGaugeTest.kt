package dev.ronindash

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A month of riding a simulated Ronin where nobody ever types a fill-up. The bike's real fuel use differs from the physics
 * model, its gauge is uneven (full and 11 L both show 9 bars) and reads higher when hot, the fuel sloshes. Fills happen at
 * random levels, half with the "Filled up?" card ignored, half without the app noticing at all. Then a friend takes the
 * bike for 180 km hard riding (and fills up) while the app is off, and the owner comes back a month later.
 * Range must stay within ±20 km of the truth.
 */
class FuelGaugeTest {
    class Mem(override val tankL: Double = 14.0) : FuelModel.Store {
        override var fuelCal = 1.0; override var fulls = 0; override var recKm = 41.6; override var recL = 1.0
        override var tankLeftL = -1.0; override var burnRaw = 0.0; override var poured = 0.0; override var fullKnown = false
        override var speedoK = 1.0; override val fuelPrice = 105.0; override var ml = ""; override val warmC = 60
        override var gauge = ""; override var odo = 0.0
    }

    /** The real bike. */
    class Bike(val rnd: Random) {
        val drop = doubleArrayOf(0.0, 2.3, 3.1, 4.6, 5.5, 6.9, 7.6, 8.8, 9.7, 11.2)   // litres at the b → b−1 drop
        val hot = .35                                                               // L the gauge over-reads per 30 °C over 60
        var litres = 9.0; var odo = 8000.0; var temp = 30.0; var slosh = 0.0
        val fx = FuelLearner()
        fun flow(f: DoubleArray) = 1.08 * f[0] + 0.05 * f[1] / .15 + 0.9 * f[2] + 0.5 * f[3] + 0.6 * f[4] + 1.2 * f[5]
        fun bars(): Int {
            slosh = slosh * .95 + rnd.nextDouble(-.04, .04)
            val dT = ((temp - 60) / 30).coerceIn(-1.5, 1.5)
            return (1..9).count { b -> litres + slosh > drop[b] - hot * dT }
        }
    }

    data class Style(val top: List<Double>, val punch: Double, val every: Int = 45)
    private val owner = Style(listOf(0.0, 25.0, 40.0, 55.0, 65.0, 80.0), 1.0)
    private val friend = Style(listOf(0.0, 15.0, 30.0, 50.0), 3.5, 8)

    /** One ride of [seconds]; fuel model may be null (app off). Returns (km, true litres). */
    private fun ride(b: Bike, m: FuelModel?, rnd: Random, seconds: Int, st: Style, check: ((Int) -> Unit)? = null): Pair<Double, Double> {
        var v = 0.0; var target = 0.0; var km = 0.0; var lt = 0.0
        m?.newTrip()
        for (t in 0 until seconds) {
            if (t % st.every == 0) target = st.top.random(rnd)
            val a = ((target - v) * .12 * st.punch).coerceIn(-6.0, 4.0 * st.punch); v = (v + a).coerceAtLeast(0.0)
            val gear = when { v < 15 -> 1; v < 30 -> 2; v < 45 -> 3; v < 60 -> 4; else -> 5 }
            val rpm = maxOf(1400, (v * listOf(0.0, 150.0, 118.0, 101.0, 79.0, 65.0)[gear]).toInt())
            val thr = if (a > 0) (20 + a * 12 + v / 3).toInt().coerceIn(0, 100) else if (v > 5) 8 else 0
            b.temp = minOf(90.0, b.temp + .25)
            val f = b.fx.features(rpm, thr, v, a, gear, b.temp.toInt(), 60, true)
            val burn = b.flow(f) / 3600
            b.litres = maxOf(0.0, b.litres - burn); lt += burn
            b.odo += v / 3600; km += v / 3600
            m?.step(1.0, rpm, thr, v, v, true, false, gear, b.temp.toInt(), b.bars(), (b.odo * 10).toInt() / 10.0)
            check?.invoke(t)
        }
        return km to lt
    }

    private fun park(b: Bike, minutes: Double) { b.temp = 30 + (b.temp - 30) * exp(-minutes / 40) }

    @Test fun rangeWithoutAnyLoggedFill() {
        val rnd = Random(11)
        val bike = Bike(rnd)
        val store = Mem()
        var m = FuelModel(store)
        // the owner's true mileage, measured on the side
        var ownKm = 0.0; var ownL = 0.0
        val errs = ArrayList<Double>(); val tErr = ArrayList<Double>(); val kErr = ArrayList<Double>(); val byBar = sortedMapOf<Int, ArrayList<Double>>()
        fun trueRange() = bike.litres * ownKm / ownL
        var fills = 0
        for (day in 1..30) repeat(2) {
            val sec = rnd.nextInt(20, 45) * 60
            val (km, lt) = ride(bike, m, rnd, sec, owner) { t -> if (day > 20 && t % 300 == 60 && ownL > 1) { errs += m.range - trueRange(); tErr += m.tank - bike.litres; byBar.getOrPut(bike.bars()) { ArrayList() } += m.tank - bike.litres; kErr += m.kmpl / (ownKm / ownL) - 1 } }
            ownKm += km; ownL += lt
            park(bike, if (it == 0) 480.0 else 900.0)
            if (bike.bars() <= 2) {                                     // fill up, nobody types anything
                fills++
                val b0 = bike.bars()
                bike.litres = if (rnd.nextDouble() < .7) rnd.nextDouble(13.2, 14.0) else bike.litres + 190.0 / 105
                if (rnd.nextBoolean()) m.gaugeFill(b0, bike.bars())   // "Filled up?" card ignored: the gauge decides
            }
        }
        val rms = sqrt(errs.sumOf { it * it } / errs.size)
        println("month 1: $fills unlogged fills, ${m.learner.gaugeUpdates} gauge lessons, range error over the last 10 days: rms %.1f km, worst %.1f km"
            .format(rms, errs.maxOf { abs(it) }))
        println("  petrol left off by rms %.2f L, km/l off by rms %.1f %% (mean %+.1f %%)".format(sqrt(tErr.sumOf { it * it } / tErr.size),
            sqrt(kErr.sumOf { it * it } / kErr.size) * 100, kErr.average() * 100))
        println("  by bars: " + byBar.entries.joinToString("  ") { (b, e) -> "$b: %+.2f±%.2f".format(e.average(), sqrt(e.sumOf { it * it } / e.size)) })
        println("  learned: ${m.learner.summary()}")
        println("  levels:  ${m.learner.gaugeSummary()}   (true: ${(9 downTo 1).joinToString(" ") { "$it→${it - 1} ${bike.drop[it]}" }}  hot reads +0.35)")
        assertTrue("range within ±20 km (rms) after a month with no logged fills", rms < 20)

        // the friend takes it: 180 km hard riding, fills up, app off the whole time
        m.save()
        repeat(6) { ride(bike, null, rnd, 30 * 60, friend); park(bike, 30.0); if (bike.bars() <= 3) bike.litres = rnd.nextDouble(12.0, 14.0) }
        park(bike, 60.0 * 24 * 30)                                     // then a month standing
        // the owner opens the app again (fresh service, saved state)
        m = FuelModel(store)
        var first = Double.NaN; var afterDrop = Double.NaN; var b0 = -1; var dropT = -1
        ride(bike, m, rnd, 60 * 60, owner) { t ->
            if (t == 60) { first = m.range - trueRange(); b0 = bike.bars() }
            if (t > 60 && dropT < 0 && bike.bars() < b0) dropT = t
            if (dropT > 0 && t == dropT + 120) afterDrop = m.range - trueRange()
        }
        println("after the friend + a month off: range off by %.1f km at start (gauge only), %s after the first bar drop"
            .format(first, if (afterDrop.isNaN()) "no drop yet" else "%.1f km".format(afterDrop)))
        assertTrue("back from a month away: range within ±30 km from the gauge alone", abs(first) < 30)
        if (!afterDrop.isNaN()) assertTrue("after the first bar drop: within ±20 km", abs(afterDrop) < 20)
    }

    @Test fun rangeFollowsANewRider() {
        val rnd = Random(5)
        val bike = Bike(rnd); bike.litres = 12.0
        val m = FuelModel(Mem())
        var oK = 0.0; var oL = 0.0; var fK = 0.0; var fL = 0.0
        for (day in 1..30) repeat(2) {                                  // the friend borrows it every 4th day, app on
            val fr = it == 1 && day % 4 == 0
            val (k, l) = ride(bike, m, rnd, 30 * 60, if (fr) friend else owner)
            if (fr) { fK += k; fL += l } else { oK += k; oL += l }
            park(bike, 600.0); if (bike.bars() <= 2) bike.litres = 13.8
        }
        ride(bike, m, rnd, 30 * 60, owner); park(bike, 600.0)
        val ownerKmpl = m.kmpl
        ride(bike, m, rnd, 20 * 60, friend)
        val want = (fK / fL) / (oK / oL); val got = m.kmpl / ownerKmpl
        println("km/l used for range: owner %.1f, after 20 min of the friend %.1f  (true: owner %.1f, friend %.1f)".format(ownerKmpl, m.kmpl, oK / oL, fK / fL))
        println("  true change ×%.2f, range follows ×%.2f".format(want, got))
        assertTrue("range follows the new rider the right way, at least half as much", got < 1 && (1 - got) > (1 - want) / 2)
    }
}
