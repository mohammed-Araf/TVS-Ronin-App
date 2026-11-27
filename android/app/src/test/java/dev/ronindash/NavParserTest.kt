package dev.ronindash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Real Google Maps notification texts from the ride on 2026-11-27. */
class NavParserTest {
    @Test fun distanceAndRoadOnlyTakesTheArrowPicture() {
        val n = NavParser.parse("90 m", "Sarakki Main Rd", "26 min · 7.9 km · 10:43 am ETA", Dir.LEFT)!!
        assertEquals(90, n.nextM); assertEquals(Dir.LEFT, n.dir); assertEquals("Sarakki Main Rd", n.road)
        assertEquals(26, n.minutesLeft); assertEquals(7900, n.leftM)
    }

    @Test fun wordsWinOverThePicture() {
        assertEquals(Dir.SLIGHT_LEFT, NavParser.parse("70 m", "Keep left", "20 min · 6.8 km · 10:45 am ETA", Dir.RIGHT)!!.dir)
    }

    @Test fun updateWithoutDistanceIsSkippedNotZero() {
        assertNull(NavParser.parse("", "5th Main Rd", "30 min · 11 km · 10:37 am ETA", Dir.LEFT))
    }

    @Test fun kilometres() {
        assertEquals(1300, NavParser.parse("1.3 km", "1st Main Rd / MLA Layout Main Rd", "4 min · 1.7 km · 10:45 am ETA", Dir.RIGHT)!!.nextM)
    }
}
