package dev.ronindash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    /** Frames the official app sent (capture ride2b, plain): the checksum must come out the same. */
    @Test fun navChecksumMatchesOfficialApp() {
        val official = hex("5A 4E 00 64 00 09 00 08 28 00 01 01 00 00 01 00 00 00 B7 FF")
        assertEquals(Protocol.hex(official), Protocol.hex(Protocol.nav(100, 9, 2088, 0, 1)))
        assertTrue(Protocol.checksumOk(Protocol.nav(350, 7, 1540, 16, 1)))
    }

    @Test fun roadLineMatchesOfficialApp() {
        assertEquals("5B 4F 54 55 52 4E 20 52 49 47 48 54 00 00 00 00 00 00 6E FF", Protocol.hex(Protocol.navText("TURN RIGHT")))
    }

    @Test fun roadLineHasRoomForChecksum() {
        val f = Protocol.navText("1ST MAIN RD MLA LAYOUT")
        assertTrue(Protocol.checksumOk(f))
        assertEquals("1ST MAIN RD MLA ", String(f.copyOfRange(2, 18)))   // 16 characters in bytes 2–17, checksum in 18
    }
}
