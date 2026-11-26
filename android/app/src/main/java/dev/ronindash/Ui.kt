package dev.ronindash

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Line icons (24×24, stroke only): the shape is the label, so riders don't read words. Same paths as the browser mock. */
object Icons24 {
    val P = mapOf(
        "road" to "M8 3 4.5 21M16 3l3.5 18M12 4v2.5M12 10v3M12 16.5V20",
        "clock" to "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0M12 7.5V12l3 2",
        "speedo" to "M4.2 17.5a8.5 8.5 0 1 1 15.6 0M12 13.5l4.2-4.2",
        "leaf" to "M5 19.5c0-8.5 5.5-14 14.5-14.5C19 14 13.5 19.5 5 19.5zM5 19.5 13.5 11",
        "drop" to "M12 3.5c3.6 4.4 6 7.7 6 10.8a6 6 0 0 1-12 0c0-3.1 2.4-6.4 6-10.8z",
        "idle" to "M4.5 13a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0 -15 0M12 13V9M9.5 3.5h5M12 3.5v2",
        "pump" to "M5 20.5V5.5a2 2 0 0 1 2-2h5.5a2 2 0 0 1 2 2v15M3.5 20.5h12.5M14.5 9.5h1.8a1.7 1.7 0 0 1 1.7 1.7v4.6a1.4 1.4 0 0 0 2.8 0V8.2l-3-3M7.5 8h4.5",
        "thermo" to "M10 14V5.5a2 2 0 0 1 4 0V14a4 4 0 1 1-4 0zM12 9.5v6.5",
        "phone" to "M6 3.5h3.2l1.8 4.6-2.3 1.4a11 11 0 0 0 5.8 5.8l1.4-2.3 4.6 1.8V18a2.5 2.5 0 0 1-2.5 2.5A15 15 0 0 1 3.5 6 2.5 2.5 0 0 1 6 3.5z",
        "chat" to "M20.5 11.5a8 8 0 0 1-11.6 7.1L4 19.8l1.3-4.4A8 8 0 1 1 20.5 11.5z",
        "wrench" to "M14.5 3.8a5 5 0 0 0-4.3 6.8l-6.4 6.4a1.9 1.9 0 0 0 2.7 2.7l6.4-6.4a5 5 0 0 0 6.8-4.3l-2.9 2.1-2.6-.6-.6-2.6z",
        "engine" to "M4 9.5h2.5V7.5h4.5V6h3v1.5h3l2 2.5H21v5.5h-2l-2 2.5H8l-1.5-2.5H4zM12 10.5v3M12 15.5v.3",
        "abs" to "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0M7.5 14.5 9 9.5l1.5 5M8 13h2M12.5 9.5v5h1.3a1.2 1.2 0 0 0 0-2.5h-1.3 1.1a1.2 1.2 0 0 0 0-2.5zM18 10a1.4 1.4 0 0 0-2.6.6c0 1.6 2.8.9 2.8 2.6a1.4 1.4 0 0 1-2.8.4",
        "btoff" to "M7 7l10 10-5 4V12M12 8V3l5 4-2.2 1.8M3.5 3.5l17 17",
        "stand" to "M3.5 15.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0M15 15.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0M6.5 15.5 10 9h5.5l2.5 6.5M9 9h-2M12 9l-2.5 12",
        "left" to "M16 21v-8.5a3.5 3.5 0 0 0-3.5-3.5H5M9 5 5 9l4 4",
        "right" to "M8 21v-8.5A3.5 3.5 0 0 1 11.5 9H19M15 5l4 4-4 4",
        "straight" to "M12 21V4M6.5 9.5l5.5-5.5 5.5 5.5",
        "round" to "M8 10a4 4 0 1 0 8 0a4 4 0 1 0 -8 0M12 21v-7M14.8 7.2 19 3M19 7V3h-4",
        "uturn" to "M8 21V9a4 4 0 0 1 8 0v6M12.5 11.5 16 15l3.5-3.5",
        "flag" to "M5 21V4M5 4.5h11l-2 4 2 4H5",
        "battery" to "M4.5 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2zM22 10.5v3",
        "sun" to "M12 8a4 4 0 1 1 0 8a4 4 0 0 1 0-8zM12 2.5v2M12 19.5v2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M2.5 12h2M19.5 12h2M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4",
        "snow" to "M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9M9.5 4.5 12 6.5l2.5-2M9.5 19.5 12 17.5l2.5 2",
    )
    private val cache = HashMap<String, ImageVector>()

    fun get(name: String): ImageVector = cache.getOrPut(name) {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            PathParser().parsePathString(P[name] ?: P.getValue("road")).toNodes(),
            fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ).build()
    }

    fun forDir(d: Dir) = when (d) {
        Dir.LEFT, Dir.SHARP_LEFT, Dir.SLIGHT_LEFT -> "left"; Dir.RIGHT, Dir.SHARP_RIGHT, Dir.SLIGHT_RIGHT -> "right"
        Dir.ROUNDABOUT -> "round"; Dir.UTURN -> "uturn"; Dir.ARRIVE -> "flag"; else -> "straight"
    }
}

@Composable
fun Ic(name: String, color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val c by animateColorAsState(color, tween(900), label = "ic")
    Image(Icons24.get(name), null, modifier.size(size), colorFilter = ColorFilter.tint(c))
}

/**
 * Motion everywhere, nothing jumps: a number counts to its new value (~0.65 s, ease-out). A unit change (m ↔ km, km/l ↔ L/h)
 * fades instead of counting — give each unit its own [key]. null shows "–".
 */
@Composable
fun CountText(value: Double?, format: (Double) -> String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current,
              color: Color = Color.Unspecified, key: String = "") {
    androidx.compose.animation.AnimatedContent(
        targetState = key to (value == null), label = "unit",
        transitionSpec = { androidx.compose.animation.fadeIn(tween(300)) togetherWith androidx.compose.animation.fadeOut(tween(200)) },
    ) { (_, isNull) ->
        if (isNull) Text("–", modifier, style = style, color = color)
        else {
            val v by animateFloatAsState((value ?: 0.0).toFloat(), tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "n")
            Text(format(v.toDouble()), modifier, style = style, color = color)
        }
    }
}

/** Mileage colour: red (≤ 28 km/l, revving hard) → orange (32) → yellow-green (42, TVS's claim) → green (≥ 46, easy riding). */
fun mileColor(kmpl: Double, dark: Boolean, night: Boolean = false): Color {
    val hue = ((kmpl - 28) / (46 - 28)).coerceIn(0.0, 1.0).toFloat() * 125f
    return when {
        night -> Color.hsl(hue, .85f, .52f)
        dark -> Color.hsl(hue, .70f, .58f)
        else -> Color.hsl(hue, .75f, .38f)
    }
}

/** Sunrise and sunset (NOAA simplified) for "Night panel: At night". Without a location, 6:30 to 18:30. */
object Sun {
    fun isNight(lat: Double?, lon: Double?, now: Calendar = Calendar.getInstance()): Boolean {
        val mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (lat == null || lon == null) return mins < 390 || mins >= 1110
        val (rise, set) = times(lat, lon, now) ?: return false
        return mins < rise || mins >= set
    }

    /** Local minutes after midnight of sunrise and sunset; null in polar day/night. */
    fun times(lat: Double, lon: Double, c: Calendar): Pair<Int, Int>? {
        val n = c.get(Calendar.DAY_OF_YEAR)
        val tz = (c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / 60000.0
        val g = 2 * Math.PI / 365 * (n - 1)
        val eqt = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g)
        val la = Math.toRadians(lat)
        val cosH = (cos(Math.toRadians(90.833)) / (cos(la) * cos(decl))) - tan(la) * tan(decl)
        if (cosH < -1 || cosH > 1) return null
        val ha = Math.toDegrees(acos(cosH))
        val noon = 720 - 4 * lon - eqt + tz
        return (noon - 4 * ha).toInt() to (noon + 4 * ha).toInt()
    }
}
