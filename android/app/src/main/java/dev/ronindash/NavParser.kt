package dev.ronindash

import java.util.Locale

enum class Dir { STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE, UNKNOWN }

data class NavInfo(val nextM: Int, val minutesLeft: Int, val leftM: Int, val dir: Dir, val road: String) {
    val isLeft get() = dir == Dir.LEFT || dir == Dir.SLIGHT_LEFT || dir == Dir.SHARP_LEFT
    val isRight get() = dir == Dir.RIGHT || dir == Dir.SLIGHT_RIGHT || dir == Dir.SHARP_RIGHT
}

/**
 * Reads Google Maps' turn-by-turn notification (title / text / sub text).
 * Typical: title "250 m", text "Turn left onto MG Road", sub "12 min · 5.1 km · 10:45 ETA".
 * Raw strings are kept in Bike.lastMapsRaw so the parser can be tuned from real rides.
 */
object NavParser {
    private val dist = Regex("""(\d+(?:[.,]\d+)?)\s*(km|m|mi|ft)\b""", RegexOption.IGNORE_CASE)
    private val hrs = Regex("""(\d+)\s*(?:h|hr|hrs|hour|hours)\b""", RegexOption.IGNORE_CASE)
    private val mins = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
    private val roadAfter = Regex("""\b(?:onto|on to|toward|towards|on|into|at)\s+(.+)$""", RegexOption.IGNORE_CASE)

    fun parse(title: String, text: String, sub: String): NavInfo? {
        val main = "$title $text"
        val low = main.lowercase(Locale.ROOT)
        val dir = when {
            "u-turn" in low || "u turn" in low || "make a u" in low -> Dir.UTURN
            "roundabout" in low || "rotary" in low -> Dir.ROUNDABOUT
            "arriv" in low || "destination" in low -> Dir.ARRIVE
            "sharp left" in low -> Dir.SHARP_LEFT
            "sharp right" in low -> Dir.SHARP_RIGHT
            "slight left" in low || "keep left" in low || "bear left" in low -> Dir.SLIGHT_LEFT
            "slight right" in low || "keep right" in low || "bear right" in low -> Dir.SLIGHT_RIGHT
            "left" in low -> Dir.LEFT
            "right" in low -> Dir.RIGHT
            "straight" in low || "continue" in low || "head " in low -> Dir.STRAIGHT
            else -> Dir.UNKNOWN
        }
        val next = dist.find(main)?.let { toMeters(it) }
        if (next == null && dir == Dir.UNKNOWN) return null
        val minutes = (hrs.find(sub)?.groupValues?.get(1)?.toIntOrNull() ?: 0) * 60 +
            (mins.find(sub)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
        val left = dist.find(sub)?.let { toMeters(it) } ?: 0
        val roadSrc = text.ifBlank { title }
        val road = roadAfter.find(roadSrc)?.groupValues?.get(1) ?: roadSrc.replace(dist, "")
        return NavInfo(next ?: 0, minutes, left, dir, road.trim())
    }

    private fun toMeters(m: MatchResult): Int {
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
        return when (m.groupValues[2].lowercase(Locale.ROOT)) {
            "km" -> (v * 1000).toInt()
            "mi" -> (v * 1609).toInt()
            "ft" -> (v * 0.3048).toInt()
            else -> v.toInt()
        }
    }

    /** Short word for the cluster text line. */
    fun word(d: Dir) = when (d) {
        Dir.LEFT -> "LEFT"; Dir.RIGHT -> "RIGHT"; Dir.SLIGHT_LEFT -> "KEEP L"; Dir.SLIGHT_RIGHT -> "KEEP R"
        Dir.SHARP_LEFT -> "SHARP L"; Dir.SHARP_RIGHT -> "SHARP R"; Dir.UTURN -> "U-TURN"
        Dir.ROUNDABOUT -> "RNDABT"; Dir.ARRIVE -> "ARRIVED"; Dir.STRAIGHT -> "STRAIGHT"; Dir.UNKNOWN -> ""
    }
}
