package dev.ronindash

import java.text.Normalizer
import java.util.Calendar
import java.util.UUID

/** Bars on the Ronin's fuel gauge when full (9; byte 6 reads 9 with a full tank). */
const val FUEL_BARS = 9

/** TVS Ronin (U368) cluster protocol. Full notes: PROTOCOL.md in the project root. */
object Protocol {
    val SERVICE: UUID = UUID.fromString("5456534d-5647-5341-5342-454e544f5251")
    val RX: UUID = UUID.fromString("00005354-0000-1000-8000-00805f9b34fb") // bike -> phone (notify)
    val TX: UUID = UUID.fromString("00005352-0000-1000-8000-00805f9b34fb") // phone -> bike (write)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val XOR_KEY = 0xEB

    /** Encode/decode (same operation): XOR every byte except the first two and the last. */
    fun xor(frame: ByteArray): ByteArray {
        val b = frame.copyOf()
        for (i in 2 until b.size - 1) b[i] = (b[i].toInt() xor XOR_KEY).toByte()
        return b
    }

    fun checksumOk(f: ByteArray): Boolean {
        if (f.size < 4) return false
        var s = 0
        for (i in 0 until f.size - 2) s += f[i].toInt() and 0xFF
        return (s % 256) + (f[f.size - 2].toInt() and 0xFF) == 255
    }

    fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /** Cluster text: plain printable ASCII, accents removed, upper case. */
    fun clean(s: String, max: Int = 17): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return n.filter { it.code in 0x20..0x7E }.trim().uppercase().take(max)
    }

    // ---------------------------------------------------------------- phone -> bike (plain; xor() before writing)

    fun textFrame(header: Int, text: String, maxLen: Int = 17): ByteArray {
        val body = text.toByteArray(Charsets.US_ASCII).take(maxLen).toByteArray()
        val out = ByteArray(20)
        out[0] = 0x5B; out[1] = header.toByte()
        body.copyInto(out, 2)
        out[19] = 0xFF.toByte()
        return out
    }

    fun caller(t: String) = textFrame(0x43, t)
    fun sms(t: String) = textFrame(0x53, t, 16)
    fun text(t: String) = textFrame(0x4C, t)
    fun riderName(t: String) = textFrame(0x52, t)
    fun navText(t: String) = textFrame(0x4F, t)

    /** `5B 4A` mobile status. va: 0 idle, 32 listening. overspeed = the cluster's warning speed (steady value, no 0x64/0x00 race). */
    fun status(calls: Int, sms: Int, va: Int = 0, signal: Int = 4, batt: Int = 4, overspeed: Int = 120): ByteArray {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val f = intArrayOf(
            0x5B, 0x4A, (signal.coerceIn(0, 4) shl 4) or batt.coerceIn(0, 4), overspeed.coerceIn(30, 199), 0, 0,
            h, c.get(Calendar.MINUTE), c.get(Calendar.SECOND), if (h < 12) 16 else 17,
            calls.coerceIn(0, 99), 0,
            c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR) % 100,
            sms.coerceIn(0, 99), va, 0, 0, 0xFF,
        )
        return ByteArray(20) { f[it].toByte() }
    }

    /**
     * `5A F1` settings, dated form (the one the bike obeys; verified on the bike). level = display brightness
     * 2–10 = 20–100 %. The bike accepts 1 but its menu has no 10 % and the cluster misbehaves, so never send it (0 and 11+ are ignored). The bike reports it back in type 0x11 byte 17, high nibble.
     * Byte 3 (speedo page) and byte 12 (trip reset) stay 0 so nothing else changes.
     */
    fun brightness(level: Int): ByteArray {
        val c = Calendar.getInstance()
        val f = intArrayOf(0x5A, 0xF1, level.coerceIn(2, 10), 0, 0, 0, 0, 0, 0,
            c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR) % 100, 0, 0, 1, 0, 0, 0, 0, 0xFF)
        return ByteArray(20) { f[it].toByte() }
    }

    fun fromHex(s: String): ByteArray? {
        val h = s.filter { it.isLetterOrDigit() }
        if (h.length != 40 || h.any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(20) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * `5A 4E` navigation. state: 1 turn coming, 3 left now, 4 right now, 5 straight (>=1 km), 6 no GPS,
     * 7 no data, 8 arrived, 0 U-turn/reroute. pictogram: 7 straight, 41 U-turn, 72 rerouting.
     */
    fun nav(nextM: Int, minutes: Int, leftM: Int, picto: Int, state: Int, lines: Int = 1, alert: Boolean = false): ByteArray {
        val n = nextM.coerceIn(0, 0xFFFF); val m = minutes.coerceIn(0, 0xFFFF); val l = leftM.coerceIn(0, 0xFFFFFF)
        val f = intArrayOf(
            0x5A, 0x4E, n shr 8, n and 255, m shr 8, m and 255, l shr 16, (l shr 8) and 255, l and 255,
            picto, lines, 1, 0, if (alert) 1 else 0, state, 0, 0, 0, 0, 0xFF,
        )
        return ByteArray(20) { f[it].toByte() }
    }

    // ---------------------------------------------------------------- debug view of bike frames

    private fun x(v: Int) = "0x" + v.toString(16).uppercase().padStart(2, '0')

    // Plain templates only: a '%' next to a letter in a format string crashes String.format ("fuel=..% thr" did).
    fun describe(u: IntArray): String = when (u[1]) {
        0x10 -> "live spd=${u[2]} fuelBars=${u[6]} thr=${u[10] / 2}pc btn=${x(u[11])} rpm=${(u[16] shl 8) or u[17]}"
        0x11 -> "status gear=${u[5] and 0x0F} b3=${x(u[3])} b13=${x(u[13])} b15=${x(u[15])} b17=${x(u[17])}"
        0x12 -> "trip range=${s16(u[3], u[4])} trip=${s16(u[8], u[9]) / 10.0}"
        0x18 -> "engine iat=${u[7] - 40}C temp=${u[8] - 40}C batt=${u[11] / 10.0}V"
        0x19 -> "trips A=${s16(u[5], u[6]) / 10.0} B=${s16(u[8], u[9]) / 10.0} dte=${s16(u[11], u[12])}"
        else -> "type ${x(u[1])}"
    }

    fun s16(hi: Int, lo: Int) = ((hi shl 8) or lo).toShort().toInt()
}

/** Everything the bike tells us, merged from all frame types. */
data class BikeState(
    val speed: Int = 0, val odoKm: Double = 0.0, val fuelBars: Int = 0, val throttlePct: Int = 0, val rpm: Int = 0,
    val topSpeed: Int = 0, val avgSpeed: Int = 0,
    val gear: Int = -1, val neutral: Boolean = false, val leftInd: Boolean = false, val rightInd: Boolean = false,
    val highBeam: Boolean = false, val milLamp: Boolean = false, val absLamp: Boolean = false,
    val clutch: Boolean = false, val sideStand: Boolean = false, val engineOn: Boolean = false,
    val rideMode: Int = 0, val button: Int = 0, val rangeKm: Int = 0, val tripKm: Double = 0.0,
    val engineTempC: Int? = null, val intakeC: Int? = null, val batteryV: Double? = null,
    val tripAKm: Double = 0.0, val tripBKm: Double = 0.0, val dteKm: Int = 0,
    val frames: Int = 0, val zeroFrames: Int = 0, val lastFrameMs: Long = 0,
) {
    /** Live byte 6 is the fuel gauge in BARS, not a percent (it read 9 with a full tank all day; the official app shows that as
     *  "9 %, low fuel", a bug). Manual: 1 bar ≈ 3.2 L left, 0 bars ≈ 2 L. FUEL_BARS = bars on a full gauge (9). */
    val fuelPct: Int get() = (fuelBars * 100 / FUEL_BARS).coerceIn(0, 100)

    fun update(u: IntArray): BikeState {
        val bit = { v: Int, mask: Int -> (v and mask) != 0 }
        return when (u[1]) {
            0x10 -> copy(
                speed = u[2], odoKm = ((u[3] shl 16) or (u[4] shl 8) or u[5]) / 10.0, fuelBars = u[6],
                avgSpeed = u[7], topSpeed = u[9], throttlePct = u[10] / 2, rpm = (u[16] shl 8) or u[17], button = u[11] and 0x07,
            )
            0x11 -> copy(
                gear = u[5] and 0x0F, clutch = bit(u[3], 0x01), sideStand = bit(u[3], 0x40),
                neutral = bit(u[13], 0x01), leftInd = bit(u[13], 0x02), rightInd = bit(u[13], 0x04),
                highBeam = bit(u[13], 0x08), milLamp = bit(u[13], 0x10), absLamp = bit(u[13], 0x20),
                engineOn = bit(u[15], 0x40), rideMode = u[17] and 0x0F,
            )
            0x12 -> copy(rangeKm = Protocol.s16(u[3], u[4]), tripKm = Protocol.s16(u[8], u[9]) / 10.0)
            0x18 -> copy(intakeC = u[7] - 40, engineTempC = u[8] - 40, batteryV = u[11] / 10.0)
            0x19 -> copy(
                tripAKm = Protocol.s16(u[5], u[6]) / 10.0, tripBKm = Protocol.s16(u[8], u[9]) / 10.0,
                dteKm = Protocol.s16(u[11], u[12]),
            )
            else -> this
        }
    }

    val gearLabel: String get() = when { neutral || gear == 0 -> "N"; gear in 1..6 -> "$gear"; else -> "–" }
}
