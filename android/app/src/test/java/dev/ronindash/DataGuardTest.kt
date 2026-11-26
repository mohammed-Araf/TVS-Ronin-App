package dev.ronindash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Same test as tools/data_guard.py: replay every real capture (no real value may be held back for 2+ readings), then inject
 * wrong-but-checksum-valid values (none impossible may ever be shown, none wrong may be kept once the real value is back).
 */
class DataGuardTest {
    private val captures = File("../../captures").takeIf { it.isDirectory } ?: File("../captures")

    private fun load(f: File): List<Pair<Double, IntArray>> {
        val lines = f.readLines()
        val head = lines.first().split(",")
        val iTime = head.indexOf("time"); val iDir = head.indexOf("dir"); val iDec = head.indexOf("decoded"); val iOk = head.indexOf("checksum_ok")
        return lines.drop(1).mapNotNull { l ->
            val c = l.split(",")
            if (c.size <= iOk || c[iDir] != "RX" || c[iOk] != "True") return@mapNotNull null
            val hex = c[iDec].replace(" ", "")
            if (hex.length != 40) return@mapNotNull null
            val d = IntArray(20) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
            if (d[0] != 0x5A) return@mapNotNull null
            val (h, m, s) = c[iTime].split(":")
            (h.toInt() * 3600 + m.toInt() * 60 + s.toDouble()) to d
        }
    }

    private class Stats { var injected = 0; var impossible = 0; var sustainedWrong = 0; var sustainedReal = 0 }

    private fun run(frames: List<Pair<Double, IntArray>>, glitch: Double, seed: Long): Stats {
        val rnd = Random(seed); val g = DataGuard(); val st = Stats()
        val runRej = HashMap<String, Int>(); val runWrong = HashMap<String, Int>()
        for ((t, d0) in frames) {
            val real = DataGuardProbe.decode(d0)
            if (real.isEmpty()) { g.frame(d0, t); continue }
            var d = d0; var hit: String? = null
            if (glitch > 0 && rnd.nextDouble() < glitch) {
                hit = real.keys.toList()[rnd.nextInt(real.size)]
                d = DataGuardProbe.inject(d0, hit, rnd)
                st.injected++
            }
            val shown = g.frame(d, t)
            for ((k, v) in shown) {
                val lim = DataGuard.LIMITS.getValue(k)
                if (v != null && (v < lim.lo || v > lim.hi)) st.impossible++
                val r = real.getValue(k)
                val rej = k != hit && g.f.getValue(k).let { it.failRun > 0 }
                runRej[k] = if (rej) (runRej[k] ?: 0) + 1 else 0
                if (runRej[k] == 2) st.sustainedReal++
                val wrong = v != null && abs(v - r) > max(lim.tol * 3, (lim.hi - lim.lo) * .1)
                runWrong[k] = if (wrong && k != hit) (runWrong[k] ?: 0) + 1 else 0
                if (runWrong[k] == 2) st.sustainedWrong++
            }
        }
        return st
    }

    @Test fun replayAndGlitches() {
        val files = captures.listFiles { f -> f.name.startsWith("frames_") && f.name.endsWith(".csv") }.orEmpty()
        assumeTrue("captures/ not found (private ride logs)", files.isNotEmpty())
        var injected = 0
        for (f in files.sorted()) {
            val frames = load(f)
            assertEquals("${f.name}: real values held back", 0, run(frames, 0.0, 0).sustainedReal)
            for (seed in 1L..20L) {
                val s = run(frames, .05, seed); injected += s.injected
                assertEquals("${f.name} seed $seed: impossible value shown", 0, s.impossible)
                assertEquals("${f.name} seed $seed: wrong value kept after the real one came back", 0, s.sustainedWrong)
            }
        }
        assertTrue(injected > 1000)
    }

    @Test fun gearAboveItsTopSpeedIsRejected() {
        val g = DataGuard()
        fun live(spd: Int, rpm: Int) = IntArray(20).also { it[0] = 0x5A; it[1] = 0x10; it[2] = spd; it[16] = rpm shr 8; it[17] = rpm and 255; it[10] = 40 }
        fun status(gear: Int) = IntArray(20).also { it[0] = 0x5A; it[1] = 0x11; it[5] = gear }
        var t = 0.0
        repeat(5) { g.frame(live(70, 4550), t); g.frame(status(5), t); t += 1 }   // 5th at 70 km/h ≈ 65 rpm per km/h
        g.frame(live(70, 4550), t)
        assertEquals(5.0, g.frame(status(1), t)["gear"])     // "1st gear at 70 km/h" can't be right: keeps 5th
    }
}

/** Test helpers: same decode as the guard, and a glitch that still passes the checksum. */
object DataGuardProbe {
    fun decode(d: IntArray): Map<String, Double> = when (d[1]) {
        0x10 -> mapOf("speed" to d[2].toDouble(), "odo" to ((d[3] shl 16) or (d[4] shl 8) or d[5]) / 10.0, "fuel" to d[6].toDouble(), "rpm" to ((d[16] shl 8) or d[17]).toDouble())
        0x11 -> mapOf("gear" to (d[5] and 0x0F).toDouble())
        0x18 -> mapOf("intake" to (d[7] - 40).toDouble(), "temp" to (d[8] - 40).toDouble(), "batt" to d[11] / 10.0)
        0x12 -> mapOf("range" to Protocol.s16(d[3], d[4]).toDouble())
        else -> emptyMap()
    }

    /** Writes a wrong value for [k] into a copy of the frame (nonsense, a big plausible jump, or a snap to a limit). */
    fun inject(d0: IntArray, k: String, rnd: Random): IntArray {
        val d = d0.copyOf(); val lim = DataGuard.LIMITS.getValue(k); val real = decode(d0).getValue(k)
        val kind = rnd.nextDouble()
        val v = when {
            kind < .4 -> listOf(65535.0, 255.0, 900000.0, 0xFFFFFF / 10.0)[rnd.nextInt(4)]
            kind < .8 -> real + (if (rnd.nextBoolean()) 1 else -1) * (lim.hi - lim.lo) * (.3 + rnd.nextDouble() * .6)
            else -> if (real != lim.lo) lim.lo else lim.hi
        }
        fun b(x: Double) = x.roundToInt().coerceIn(0, 255)
        when (k) {
            "speed" -> d[2] = b(v); "fuel" -> d[6] = b(v)
            "rpm" -> { val r = v.roundToInt().coerceIn(0, 65535); d[16] = r shr 8; d[17] = r and 255 }
            "odo" -> { val o = (v * 10).roundToInt().coerceIn(0, 0xFFFFFF); d[3] = o shr 16; d[4] = (o shr 8) and 255; d[5] = o and 255 }
            "gear" -> d[5] = (d[5] and 0xF0) or (v.roundToInt().coerceIn(0, 15))
            "intake" -> d[7] = b(v + 40); "temp" -> d[8] = b(v + 40); "batt" -> d[11] = b(v * 10)
            "range" -> { val r = v.roundToInt().coerceIn(-32768, 32767) and 0xFFFF; d[3] = r shr 8; d[4] = r and 255 }
        }
        return d
    }
}
