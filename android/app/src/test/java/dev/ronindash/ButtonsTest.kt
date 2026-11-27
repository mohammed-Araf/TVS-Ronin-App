package dev.ronindash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ButtonsTest {
    /** Feed (time ms, pressed) samples the way status frames arrive; return every gesture with its time. */
    private fun run(d: TapDetector, samples: List<Pair<Long, Boolean>>, allowed: Boolean = true): List<Pair<Long, Gesture>> {
        val out = ArrayList<Pair<Long, Gesture>>()
        var t = 0L
        for ((at, p) in samples) {
            while (t < at) { d.poll(t)?.let { out += t to it }; t += 50 }
            d.frame(p, allowed, at)?.let { out += at to it }
        }
        while (t < samples.last().first + 5000) { d.poll(t)?.let { out += t to it }; t += 50 }
        return out
    }

    @Test fun singleTap() {
        val g = run(TapDetector(), listOf(0L to false, 500L to true, 1000L to false, 1500L to false))
        assertEquals(listOf(Gesture.SINGLE), g.map { it.second })
        assertEquals(2600L, g[0].first) // waits for a possible second tap
    }

    @Test fun doubleTap() {
        val g = run(TapDetector(), listOf(0L to false, 500L to true, 900L to false, 1300L to true, 1700L to false))
        assertEquals(listOf(Gesture.DOUBLE), g.map { it.second })
    }

    @Test fun holdFiresWhileHeldAndNotAgainOnRelease() {
        val g = run(TapDetector(), listOf(0L to false, 500L to true, 1200L to true, 2100L to true, 2800L to true, 3200L to false))
        assertEquals(listOf(2100L to Gesture.LONG), g)
    }

    @Test fun starterShortHoldFiresOnRelease() {
        // the bike first reports it ~2.5 s into the hold; let go at ~4 s
        val g = run(TapDetector(longMs = 3000, doubles = false), listOf(0L to false, 2500L to true, 3300L to true, 4000L to false))
        assertEquals(listOf(4000L to Gesture.SINGLE), g)
    }

    @Test fun starterLongHold() {
        val g = run(TapDetector(longMs = 3000, doubles = false),
            listOf(0L to false, 2500L to true, 4000L to true, 5600L to true, 6500L to true, 7000L to false))
        assertEquals(listOf(5600L to Gesture.LONG), g)
    }

    @Test fun pressThatStartedBeforeEngineSettledIsIgnored() {
        val g = run(TapDetector(), listOf(0L to false, 500L to true, 1000L to false), allowed = false)
        assertEquals(emptyList<Pair<Long, Gesture>>(), g)
    }

    @Test fun modeDoublePressFromModeNibble() {
        val m = ModeDetector()
        assertNull(m.mode(1, true, 0))
        assertNull(m.code(3, true, 100))   // first press seen as byte 11 = 3
        assertNull(m.mode(3, true, 400))   // the same press seen as Urban → Rain: merged
        assertEquals(Gesture.DOUBLE, m.mode(1, true, 1800)) // second press: Rain → Urban
    }

    @Test fun modeSinglePressDoesNothing() {
        val m = ModeDetector()
        m.mode(1, true, 0); m.code(3, true, 100); m.mode(3, true, 400)
        assertNull(m.code(0, true, 4000)); assertNull(m.mode(3, true, 5000))
    }

    @Test fun modeHoldWithoutModeChange() {
        val m = ModeDetector()
        m.mode(1, true, 0)
        assertNull(m.code(3, true, 100))
        assertNull(m.code(3, true, 1400))
        assertEquals(Gesture.LONG, m.code(3, true, 2700))
        assertNull(m.code(3, true, 4000))
    }
}
