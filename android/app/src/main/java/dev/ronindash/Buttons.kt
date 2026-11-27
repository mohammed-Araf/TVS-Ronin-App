package dev.ronindash

/**
 * Extra uses for the handlebar buttons while riding, without taking away what they normally do.
 *
 *  - Starter (0x11 byte 3 bit 0x10 / byte 6 bit 0x20): short hold and long hold. Only counts once the engine has been running
 *    for a few seconds, so starting the bike is never read as a gesture. The bike only reports the starter after it has been
 *    held ~2.5–3 s (ride 2026-11-27: a quick press never shows up), so a "short" press is really a ~3 s hold, and a long one
 *    is kept held ~3 s more (~6 s in all). No double press: two 3 s holds aren't a gesture anyone wants.
 *  - Mode: a single press is the bike's own ride-mode change, so we only use double (Urban → Rain → Urban, the mode ends
 *    where it was) and long (held, the mode didn't change).
 *  - I: hold 2–3 s and let go (the only press the bike reports with no call on screen). Calls keep their own I-button rules.
 *
 * The bike sends each status frame only every ~0.4–1.3 s, so short taps can fall between frames. The timings below are a first
 * guess; every edge is logged as `[btn]` with milliseconds so they can be tuned from a ride log.
 */
enum class Gesture { SINGLE, DOUBLE, LONG }

/** What a gesture does. Every press can be set to any of these in Settings → Handlebar buttons. */
enum class BtnAction(val key: String, val label: String) {
    NONE("none", "Nothing"),
    NEXT("next", "Next song"),
    PREV("prev", "Previous song"),
    PLAY("play", "Play / pause"),
    VOL_UP("volup", "Volume up"),
    VOL_DOWN("voldown", "Volume down"),
    MUTE("mute", "Mute / unmute music"),
    MAP("map", "Show the map"),
    DASH("dash", "Show the dashboard"),
    ASSIST("assist", "Assistant (Gemini / Google)");
    companion object {
        fun of(k: String) = when (k) { "up" -> NEXT; "down" -> PREV; else -> entries.firstOrNull { it.key == k } ?: NONE }
    }
}

/** Press-and-release detector for a button we can only sample (the starter). Feed every status frame; call [poll] at [deadline]. */
class TapDetector(
    private val longMs: Long = 1500,
    private val doubleGapMs: Long = 1600,
    /** false = no double press: a release is a SINGLE straight away. */
    private val doubles: Boolean = true,
) {
    private var down = false
    private var downAt = 0L
    private var armed = false
    private var longFired = false
    private var taps = 0
    private var lastUpAt = 0L
    /** When a lone tap becomes a SINGLE if no second tap comes (0 = nothing pending). */
    var deadline = 0L; private set
    /** Last edge, for the log. */
    var note = ""; private set

    /** pressed = the bit in this frame; allowed = conditions hold (engine running long enough). */
    fun frame(pressed: Boolean, allowed: Boolean, now: Long): Gesture? {
        note = ""
        if (pressed && !down) {
            down = true; downAt = now; armed = allowed; longFired = false
            note = "down" + if (!allowed) " (ignored: engine not running long enough)" else ""
            return null
        }
        if (pressed && down) {
            if (armed && !longFired && now - downAt >= longMs) {
                longFired = true; taps = 0; deadline = 0; note = "held ${now - downAt} ms"
                return Gesture.LONG
            }
            return null
        }
        if (!pressed && down) {
            down = false
            val held = now - downAt
            note = "up after $held ms"
            if (!armed || longFired) return null
            if (!doubles) return Gesture.SINGLE
            taps++
            if (taps >= 2) { taps = 0; deadline = 0; return Gesture.DOUBLE }
            taps = 1; lastUpAt = now; deadline = now + doubleGapMs
        }
        return null
    }

    fun poll(now: Long): Gesture? {
        if (deadline == 0L || now < deadline || down) return null
        deadline = 0; taps = 0
        return Gesture.SINGLE
    }

    fun reset() { down = false; taps = 0; deadline = 0; longFired = false }
}

/**
 * Mode button. A press shows up as the ride mode changing (0x11 byte 17 low nibble) and/or byte 11 = 3 appearing in 0x10;
 * either can be missed, so both count, with the same press seen twice merged. Double = two presses within [gapMs].
 * Long = byte 11 stayed 3 for [longMs] while the mode didn't change.
 */
class ModeDetector(private val gapMs: Long = 2500, private val longMs: Long = 2500, private val mergeMs: Long = 900) {
    private var lastMode = -1
    private var lastPressAt = -1_000_000L
    private var lastSource = ""
    private var presses = 0
    private var codeSince = 0L
    private var modeAtCode = -1
    private var longFired = false
    var note = ""; private set

    private fun press(source: String, detail: String, now: Long, allowed: Boolean): Gesture? {
        if (source != lastSource && now - lastPressAt < mergeMs) { note = "$detail (same press)"; lastSource = source; return null }
        presses = if (now - lastPressAt <= gapMs) presses + 1 else 1
        lastPressAt = now; lastSource = source
        note = "$detail press #$presses"
        if (presses >= 2) { presses = 0; return if (allowed) Gesture.DOUBLE else null }
        return null
    }

    /** From 0x11: the ride mode nibble. */
    fun mode(m: Int, allowed: Boolean, now: Long): Gesture? {
        note = ""
        if (m <= 0) return null
        val prev = lastMode; lastMode = m
        if (prev <= 0 || prev == m) return null
        return press("mode", "mode $prev→$m", now, allowed)
    }

    /** From 0x10: byte 11 low bits (3 = mode button). */
    fun code(c: Int, allowed: Boolean, now: Long): Gesture? {
        note = ""
        if (c != 3) { codeSince = 0; return null }
        if (codeSince == 0L) { codeSince = now; modeAtCode = lastMode; longFired = false; return press("code3", "code3", now, allowed) }
        if (!longFired && allowed && now - codeSince >= longMs && lastMode == modeAtCode) {
            longFired = true; presses = 0; note = "code3 held ${now - codeSince} ms, mode unchanged"
            return Gesture.LONG
        }
        return null
    }
}
