package dev.ronindash

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Simulated bike whose real fuel use differs from the physics model (8 % thirstier, more air drag, richer in low gears,
 * thirsty when cold). Ride it with fill-ups every ~150 km and check the learner's estimate of a new, unseen ride gets
 * much closer to the truth than plain physics, and never does anything silly.
 */
class FuelLearnerTest {
    private fun truth(l: FuelLearner, f: DoubleArray) = 1.08 * f[0] + 0.05 * f[1] / .15 + 0.9 * f[2] + 0.5 * f[3] + 0.6 * f[4] + 1.2 * f[5]

    /** One ride: (features per second, true litres). Mixed city / highway, starts cold sometimes. */
    private fun ride(l: FuelLearner, rnd: Random, seconds: Int): Pair<List<DoubleArray>, Double> {
        val out = ArrayList<DoubleArray>(seconds)
        var v = 0.0; var temp = if (rnd.nextBoolean()) 30 else 75; var target = 0.0; var litres = 0.0
        for (t in 0 until seconds) {
            if (t % 40 == 0) target = listOf(0.0, 25.0, 45.0, 60.0, 80.0, 100.0).random(rnd)
            val a = ((target - v) * .15).coerceIn(-6.0, 4.0); v = (v + a).coerceAtLeast(0.0)
            val gear = when { v < 15 -> 1; v < 30 -> 2; v < 45 -> 3; v < 60 -> 4; else -> 5 }
            val ratio = listOf(0.0, 150.0, 118.0, 101.0, 79.0, 65.0)[gear]
            val rpm = maxOf(1400, (v * ratio).toInt())
            val thr = if (a > 0) (20 + a * 15 + v / 3).toInt().coerceIn(0, 100) else if (v > 5) 8 else 0
            if (t % 20 == 0 && temp < 85) temp++
            val f = l.features(rpm, thr, v, a, gear, temp, 60, true)
            out += f; litres += truth(l, f) / 3600
        }
        return out to litres
    }

    @Test fun learnsFromFillUps() {
        val rnd = Random(7)
        val l = FuelLearner()
        val (test, testTrue) = ride(l, rnd, 4 * 3600)
        val physErr = abs(test.sumOf { it[0] } / 3600 - testTrue) / testTrue

        repeat(12) {                                     // 12 tanks
            var poured = 0.0
            repeat(5) { val (fs, lt) = ride(l, rnd, 3600); fs.forEach { l.accumulate(it, 1.0) }; poured += lt }
            l.learn(poured + rnd.nextDouble(-.2, .2), .3 + .03 * poured)
            l.resetTotals()
            println("after fill %d: off by %.1f %%".format(l.updates, abs(test.sumOf { l.predict(it) } / 3600 - testTrue) / testTrue * 100))
        }
        val mlErr = abs(test.sumOf { l.predict(it) } / 3600 - testTrue) / testTrue
        println("physics off by %.1f %%, learned model off by %.1f %% after %d fills (%s)".format(physErr * 100, mlErr * 100, l.updates, l.summary()))
        assertTrue("learned model should beat physics", mlErr < physErr / 2)
        assertTrue("within 4 %", mlErr < .04)
    }

    @Test fun ignoresNonsenseAndSurvivesSaveLoad() {
        val l = FuelLearner()
        val f = l.features(4000, 40, 60.0, 0.0, 4, 80, 60, true)
        repeat(3600 * 4) { l.accumulate(f, 1.0) }
        val before = l.save()
        assertTrue("a typo (3× the litres) is rejected", l.learn(l.expectedSinceFull() * 3, .5) == null)
        val copy = FuelLearner(l.save())
        assertTrue("save/load keeps the model", copy.save() == before)
        assertTrue("never negative", l.predict(DoubleArray(FuelLearner.N) { -5.0 }) == 0.0)
    }
}
