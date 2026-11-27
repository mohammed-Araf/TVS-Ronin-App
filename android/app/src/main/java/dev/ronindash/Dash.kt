package dev.ronindash

import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Colours of a focus screen: the app's own by day, OLED black + one glow colour at night. */
data class DashColors(val night: Boolean, val bg: Color, val fg: Color, val dim: Color, val warn: Color, val emerg: Color, val dark: Boolean)

@Composable
fun dashColors(night: Boolean, prefs: Prefs): DashColors {
    val cs = MaterialTheme.colorScheme
    val accent = cs.primary
    if (!night) {
        val dark = cs.background.luminanceApprox() < .3f
        return DashColors(false, cs.background, cs.onBackground, cs.onSurfaceVariant, Color(0xFFE8930C), Color(0xFFE5484D), dark)
    }
    val base = when (prefs.nightGlow) {
        "red" -> Color(0xFFFF453A); "amber" -> Color(0xFFFFB020); "green" -> Color(0xFF3DDC84); "white" -> Color.White; else -> accent
    }
    val k = prefs.nightBright / 100f
    fun dim(c: Color) = Color(c.red * k, c.green * k, c.blue * k, 1f)
    return DashColors(true, Color.Black, dim(base), dim(base).copy(alpha = .45f), dim(Color(0xFFFFB020)), dim(Color(0xFFFF453A)), true)
}

private fun Color.luminanceApprox() = .3f * red + .59f * green + .11f * blue

/**
 * The focus screens (mock FIXES 2, 20–21). Which one shows is decided by the bike, never by a button:
 * RIDING = engine on, stand up, moving or clutch held (stopped in neutral for 2 s → the normal dashboard) → live mileage gauge, next turn, range, trip, time.
 * FAST = ≥ the fast speed for 3 s → nothing to read: a compass at the next turn with the metres counting down, the bottom glowing
 *        in the mileage colour. PARKED at night → everything as icons and numbers. Swipe up = full dashboard for 10 s.
 */
@Composable
fun FocusScreen(mode: RideMode, night: Boolean, onReveal: (Long) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val c = dashColors(night, prefs)
    val stand by Bike.standAlarm.collectAsState()
    val conn by Bike.conn.collectAsState()
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(5000); hint = false }
    Box(
        Modifier.fillMaxSize().background(c.bg).pointerInput(Unit) {
            var dy = 0f
            detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy < -60) onReveal(10_000) }) { _, d -> dy += d }
        },
    ) {
        Crossfade(mode, animationSpec = tween(600), label = "screen") { m ->
            when (m) {
                RideMode.RIDE -> RidingView(c, prefs)
                RideMode.FAST -> FastView(c)
                RideMode.PARK -> ParkedView(c, prefs, onReveal)
            }
        }
        // indicator: a blinking arrow at that edge of the screen (both edges = hazard)
        val ind by Bike.indicator.collectAsState()
        ind?.let { IndicatorArrows(it, c) }
        // GTT: the bike is pulling itself on idle (clutch out, throttle shut, slow)
        val gtt by Bike.gtt.collectAsState()
        AnimatedVisibility(gtt && mode != RideMode.PARK, Modifier.align(Alignment.TopStart).systemBarsPadding().padding(16.dp),
            enter = fadeIn(), exit = fadeOut()) { GttBadge(c) }
        // not connected: one icon, no words
        AnimatedVisibility(conn.phase != Phase.LIVE && mode != RideMode.PARK, Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(16.dp),
            enter = fadeIn(), exit = fadeOut()) { Ic("btoff", c.warn, 34.dp) }
        // emergency: side stand down while moving
        AnimatedVisibility(stand, enter = fadeIn(tween(150)), exit = fadeOut(tween(400))) { StandAlarm(c) }
        AnimatedVisibility(hint, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp), enter = fadeIn(), exit = fadeOut(tween(1200))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(.35f)) {
                Text("︿", color = c.fg, fontSize = 18.sp); Text("Swipe up to see everything", color = c.fg, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun GttBadge(c: DashColors) {
    Text("GTT", color = c.bg, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.fg).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun IndicatorArrows(side: String, c: DashColors) {
    val t = rememberInfiniteTransition(label = "ind")
    val a by t.animateFloat(1f, .12f, infiniteRepeatable(tween(360), RepeatMode.Reverse), label = "ia")
    val green = if (c.night) Color(0xFF3DDC84).copy(alpha = c.fg.alpha) else Color(0xFF1FA463)
    Box(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 6.dp)) {
        if (side == "left" || side == "both")
            Text("◀", color = green.copy(alpha = a), fontSize = 64.sp, modifier = Modifier.align(Alignment.CenterStart))
        if (side == "right" || side == "both")
            Text("▶", color = green.copy(alpha = a), fontSize = 64.sp, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun StandAlarm(c: DashColors) {
    val t = rememberInfiniteTransition(label = "stand")
    val a by t.animateFloat(1f, .25f, infiniteRepeatable(tween(360), RepeatMode.Reverse), label = "a")
    Box(Modifier.fillMaxSize().background(c.emerg.copy(alpha = .85f * a)), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Ic("stand", Color.White, 40.dp); Text("STAND", color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------- riding

@Composable
private fun RidingView(c: DashColors, prefs: Prefs) {
    val f by Bike.fuel.collectAsState()
    val s by Bike.state.collectAsState()
    val turn by Bike.turn.collectAsState()
    val warm by Bike.warmup.collectAsState()
    val live = Bike.conn.collectAsState().value.phase == Phase.LIVE
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(turn != null && prefs.nightTurns, enter = fadeIn(), exit = fadeOut()) {
                turn?.let { TurnBadge(it, c) }
            }
        }
        Spacer(Modifier.weight(1f))
        when (prefs.nightShow) {
            "mileage" -> MileageGauge(f, c, Modifier.fillMaxWidth().height(190.dp))
            "speed" -> {
                CountText(if (live && "speed" !in s.unknown) s.speed.toDouble() else null, { "${it.roundToInt()}" },
                    style = TextStyle(fontSize = 120.sp, fontWeight = FontWeight.Light), color = c.fg)
                Text("KM/H", color = c.dim, fontSize = 14.sp, letterSpacing = 3.sp)
            }
            else -> {}
        }
        Spacer(Modifier.weight(1f))
        warm?.let { WarmBar(it, c, Modifier.fillMaxWidth(.7f)) ; Spacer(Modifier.height(18.dp)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            InfoChip("pump", if (f.tankKnown) f.rangeKm else null, { "${it.roundToInt()}" }, "km", c, if (f.low) c.warn else c.fg)
            InfoChip("road", f.tripKm, { "%.1f".format(it) }, "km", c)
            Row(verticalAlignment = Alignment.CenterVertically) { Ic("clock", c.dim, 22.dp); Spacer(Modifier.width(6.dp)); Clock(c.fg, 22) }
        }
    }
}

@Composable
private fun InfoChip(icon: String, v: Double?, fmt: (Double) -> String, unit: String, c: DashColors, color: Color = c.fg) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Ic(icon, c.dim, 22.dp); Spacer(Modifier.width(6.dp))
        CountText(v, fmt, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold), color = color)
        Text(" $unit", color = c.dim, fontSize = 13.sp)
    }
}

@Composable
private fun Clock(color: Color, size: Int) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = System.currentTimeMillis() } }
    Text(SimpleDateFormat("h:mm", Locale.US).format(Date(now)), color = color, fontSize = size.sp, fontWeight = FontWeight.SemiBold)
}

/**
 * Riding view: one shape to glance at. Fixed scale 15 → 65 km/l, so the arc's length and colour always mean the same.
 * Tick = your average (moves slowly), dot outside the arc = TVS's 42 km/l claim. Colour: green when easy on it, red when hard.
 */
@Composable
fun MileageGauge(f: FuelView, c: DashColors, modifier: Modifier) {
    fun pos(k: Double) = ((k - 15) / 50).coerceIn(0.0, 1.0).toFloat()
    val frac by animateFloatAsState(if (f.moving) pos(f.liveKmpl).coerceAtLeast(.02f) else 0f, tween(900), label = "arc")
    val avgF by animateFloatAsState(pos(f.recentKmpl), tween(1600), label = "avg")
    val col by animateColorAsState(mileColor(if (f.moving) f.liveKmpl else f.recentKmpl, c.dark, c.night).let { if (c.night) it.copy(alpha = 1f) else it }, tween(1600), label = "mc")
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width.coerceAtMost(size.height * 1.7f)
            val r = w / 2 * .82f
            val cx = size.width / 2; val cy = size.height * .92f
            val tl = Offset(cx - r, cy - r); val sz = Size(r * 2, r * 2)
            val sw = 14.dp.toPx()
            drawArc(c.dim.copy(alpha = .25f), 180f, 180f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            if (frac > 0) drawArc(col, 180f, 180f * frac, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            fun at(fr: Float, rr: Float) = (Math.PI * (1 - fr)).let { a -> Offset(cx + rr * cos(a).toFloat(), cy - rr * sin(a).toFloat()) }
            drawLine(c.fg, at(avgF, r - sw * 1.6f), at(avgF, r + sw * .6f), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(c.dim, 3.dp.toPx(), at(pos(FuelModel.CLAIM), r + sw * 1.3f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Ic("leaf", col.copy(alpha = .6f), 22.dp)
            CountText(if (f.moving) f.liveKmpl else null, { "${it.roundToInt()}" }, style = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.SemiBold), color = col)
        }
    }
}

/** Next turn: arrow + metres counting down (never a draining bar: 4 digits = a km, 3 = hundreds, 2 = now). Amber in the last 120 m. */
@Composable
private fun TurnBadge(t: Turn, c: DashColors) {
    val col = if (t.now) c.warn else c.fg
    Row(verticalAlignment = Alignment.CenterVertically) {
        Ic(Icons24.forDir(t.dir), col, 64.dp)
        Spacer(Modifier.width(10.dp))
        CountText(t.meters.toDouble(), { "${it.roundToInt()}" }, style = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.SemiBold), color = col)
        Text(" m", color = c.dim, fontSize = 20.sp)
    }
}

/** Engine warm-up after a cold start: a thermometer and a bar that fills to "warm". */
@Composable
fun WarmBar(w: WarmUp, c: DashColors, modifier: Modifier = Modifier) {
    val frac by animateFloatAsState(w.frac, tween(900), label = "warm")
    val cold = Color(0xFF5AA9FF).let { if (c.night) Color(it.red * .8f, it.green * .8f, it.blue * .8f) else it }
    val col = androidx.compose.ui.graphics.lerp(cold, c.fg, frac)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Ic("snow", cold, 22.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.dim.copy(alpha = .25f))) {
            Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(col))
        }
        Spacer(Modifier.width(8.dp))
        Ic("thermo", col, 22.dp)
        CountText(w.nowC.toDouble(), { "${it.roundToInt()}°" }, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), color = col)
    }
}

// ---------------------------------------------------------------- fast

@Composable
private fun FastView(c: DashColors) {
    val f by Bike.fuel.collectAsState()
    val turn by Bike.turn.collectAsState()
    val glow by animateColorAsState(mileColor(if (f.moving) f.liveKmpl else f.recentKmpl, c.dark, c.night), tween(1600), label = "glow")
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(160.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, glow.copy(alpha = if (c.night) .55f else .35f)))))
        AnimatedVisibility(turn != null, Modifier.align(Alignment.Center), enter = fadeIn(tween(600)), exit = fadeOut(tween(600))) {
            turn?.let { Compass(it, c) }
        }
    }
}

/** A compass pointing at the next turn, metres under it. Swings to the exit direction in the last ~120 m and turns amber. */
@Composable
private fun Compass(t: Turn, c: DashColors) {
    val ang by animateFloatAsState(t.angle, tween(700, easing = FastOutSlowInEasing), label = "cmp")
    val col by animateColorAsState(if (t.now) c.warn else c.fg, tween(500), label = "cmpC")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(240.dp)) {
            val r = size.minDimension / 2 * .88f; val ctr = center
            drawCircle(c.dim.copy(alpha = .4f), r, ctr, style = Stroke(2.dp.toPx()))
            for (i in 0 until 24) {
                val a = Math.toRadians(i * 15.0 - 90); val big = i % 6 == 0
                val r0 = r - (if (big) 14 else 8).dp.toPx()
                drawLine(c.dim, Offset(ctr.x + r0 * cos(a).toFloat(), ctr.y + r0 * sin(a).toFloat()),
                    Offset(ctr.x + r * cos(a).toFloat(), ctr.y + r * sin(a).toFloat()), (if (big) 2.5f else 1.5f).dp.toPx())
            }
            rotate(ang, ctr) {
                val s = r / 96f
                val p = Path().apply {
                    moveTo(ctr.x, ctr.y - 70 * s); lineTo(ctr.x + 36 * s, ctr.y + 40 * s); lineTo(ctr.x, ctr.y + 18 * s)
                    lineTo(ctr.x - 36 * s, ctr.y + 40 * s); close()
                }
                drawPath(p, col)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            CountText(t.meters.toDouble(), { "${it.roundToInt()}" }, style = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.SemiBold), color = col)
            Text(" m", color = c.dim, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

// ---------------------------------------------------------------- parked (night): everything, as shapes and numbers

@Composable
private fun ParkedView(c: DashColors, prefs: Prefs, onReveal: (Long) -> Unit) {
    val f by Bike.fuel.collectAsState()
    val s by Bike.state.collectAsState()
    val ask by Bike.ask.collectAsState()
    val turn by Bike.turn.collectAsState()
    val svc by Bike.serviceLeftKm.collectAsState()
    val warm by Bike.warmup.collectAsState()
    val conn by Bike.conn.collectAsState()
    val live = conn.phase == Phase.LIVE
    val big = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Clock(c.fg, 64)
            Spacer(Modifier.weight(1f))
            val chips = listOfNotNull("pump".takeIf { f.low }, "engine".takeIf { s.milLamp && live }, "abs".takeIf { s.absLamp && live }, "btoff".takeIf { !live })
            chips.forEach { Ic(it, c.warn, 28.dp, Modifier.padding(start = 8.dp)) }
        }
        // Missed calls and unread messages first: they're why most riders keep the app open
        MissedStrip(c.fg, c.dim, c.fg.copy(alpha = .1f), c.warn, hideWhenNone = true)
        if (ask != null) Row(
            Modifier.clip(RoundedCornerShape(14.dp)).background(c.fg.copy(alpha = .1f)).clickable { onReveal(30_000) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Ic("pump", c.fg, 28.dp); Text(" ?", color = c.fg, fontSize = 26.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(10.dp)); Text("L · ₹ · full", color = c.dim) }
        warm?.let { WarmBar(it, c, Modifier.fillMaxWidth()) }
        if (f.tripKm > 0) {
            val mins = f.tripMovingS / 60
            val avg = if (f.tripMovingS > 0) f.tripKm / (f.tripMovingS / 3600.0) else 0.0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Tile("road", f.tripKm, { "%.1f".format(it) }, "km", c)
                Tile("clock", mins.toDouble(), { if (mins >= 60) "${mins / 60}:%02d".format(mins % 60) else "${it.roundToInt()}" }, if (mins >= 60) "h" else "min", c)
                Tile("speedo", avg, { "${it.roundToInt()}" }, "km/h", c)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Tile("leaf", f.tripKmpl.takeIf { it > 0 }, { "%.1f".format(it) }, "km/l", c, mileColor(f.tripKmpl, c.dark, c.night))
                Tile("drop", f.tripL * prefs.fuelPrice, { "₹${it.roundToInt()}" }, "%.2f L".format(f.tripL), c)
                Tile("idle", if (f.idling) f.lph else null, { "%.2f".format(it) }, if (f.idling) "L/h" else "off", c)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.dim.copy(alpha = .3f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ic("pump", c.dim, 24.dp); Spacer(Modifier.width(10.dp))
            val segs = if (f.tankKnown) (f.tankL / prefs.tankL * 10).roundToInt() else 0
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(10) { i -> val on = i < segs
                    val sc by animateColorAsState(if (on) (if (f.low) c.warn else c.fg) else c.dim.copy(alpha = .25f), tween(900), label = "seg")
                    Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(2.dp)).background(sc)) }
            }
            Spacer(Modifier.width(10.dp))
            CountText(if (f.tankKnown) f.rangeKm else null, { "${it.roundToInt()}" }, style = big, color = c.fg); Text(" km", color = c.dim)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ic("thermo", c.dim, 24.dp); Spacer(Modifier.width(8.dp))
            Bar((((s.engineTempC ?: 40) - 40) / 80f).coerceIn(0f, 1f), c, Modifier.weight(1f), mark = (105 - 40) / 80f)
            Spacer(Modifier.width(8.dp))
            CountText(if (live) s.engineTempC?.toDouble() else null, { "${it.roundToInt()}°" }, style = big, color = c.fg)
            Spacer(Modifier.width(18.dp))
            Ic("battery", c.dim, 24.dp); Spacer(Modifier.width(6.dp))
            CountText(if (live) s.batteryV else null, { "%.1f".format(it) }, style = big, color = c.fg); Text(" V", color = c.dim)
        }
        turn?.let { t -> Row(verticalAlignment = Alignment.CenterVertically) {
            Ic(Icons24.forDir(t.dir), c.fg, 26.dp); Spacer(Modifier.width(10.dp))
            CountText(t.meters.toDouble(), { "${it.roundToInt()}" }, style = big, color = c.fg); Text(" m", color = c.dim)
        } }
        svc?.takeIf { it <= prefs.serviceWarnKm }?.let { left -> Row(verticalAlignment = Alignment.CenterVertically) {
            val col = if (left <= 0) c.warn else c.fg
            Ic("wrench", col, 24.dp); Spacer(Modifier.width(8.dp))
            Bar(if (left <= 0) 1f else 1 - left.toFloat() / prefs.serviceIntervalKm, c, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            CountText(kotlin.math.abs(left).toDouble(), { "${it.roundToInt()}" }, style = big, color = col)
            Text(if (left <= 0) " km over" else " km", color = c.dim)
        } }
        Spacer(Modifier.weight(1f))
        BikeBrightness(c.fg, c.dim, c.fg.copy(alpha = .08f), if (c.night) c.fg else MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Tile(icon: String, v: Double?, fmt: (Double) -> String, unit: String, c: DashColors, color: Color = c.fg) {
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Ic(icon, c.dim, 22.dp)
        CountText(v, fmt, style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold), color = color)
        Text(unit, color = c.dim, fontSize = 12.sp)
    }
}

@Composable
private fun Bar(frac: Float, c: DashColors, modifier: Modifier, mark: Float? = null) {
    val fr by animateFloatAsState(frac, tween(900), label = "bar")
    Box(modifier.height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.dim.copy(alpha = .25f))) {
        Box(Modifier.fillMaxWidth(fr).height(8.dp).background(c.fg))
        if (mark != null) Box(Modifier.fillMaxWidth(mark).height(8.dp)) { Box(Modifier.align(Alignment.CenterEnd).width(2.dp).height(8.dp).background(c.warn)) }
    }
}



/**
 * Missed calls and unread messages: an icon, the count, and who. Shared by the dashboard and the Ride tab, always at the top.
 * Counts glide; names are newest first ("Amma, Ravi +2").
 */
@Composable
fun MissedStrip(fg: Color, dim: Color, bg: Color, hot: Color, hideWhenNone: Boolean = false, modifier: Modifier = Modifier) {
    val calls by Bike.missedCalls.collectAsState()
    val msgs by Bike.unreadMessages.collectAsState()
    val callers by Bike.missedFrom.collectAsState()
    val senders by Bike.unreadFrom.collectAsState()
    androidx.compose.animation.AnimatedVisibility(!hideWhenNone || calls + msgs > 0, modifier,
        enter = fadeIn() + androidx.compose.animation.expandVertically(), exit = fadeOut() + androidx.compose.animation.shrinkVertically()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MissedHalf("phone", calls, callers, fg, dim, bg, hot, Modifier.weight(1f))
            MissedHalf("chat", msgs, senders, fg, dim, bg, hot, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MissedHalf(icon: String, n: Int, who: List<String>, fg: Color, dim: Color, bg: Color, hot: Color, modifier: Modifier) {
    val on = n > 0
    val col by animateColorAsState(if (on) fg else dim, tween(500), label = "mh")
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(if (on) bg else bg.copy(alpha = bg.alpha * .4f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box {
            Ic(icon, col, 26.dp)
            if (on) Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(hot))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            CountText(n.toDouble(), { "${it.roundToInt()}" }, style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold), color = col, key = icon)
            val names = if (who.size > 2) who.take(2).joinToString(", ") + " +${who.size - 2}" else who.joinToString(", ")
            Text(if (on) names.ifBlank { if (icon == "phone") "missed" else "unread" } else "none", color = dim, fontSize = 13.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/**
 * Bike display brightness, right on the dashboard: [Auto] [—— slider ——] 42 %.
 * Tap Auto and the slider folds into the button, which grows to fill the row (no empty gap); tap it again and the slider slides back out.
 * Auto = the phone's light sensor sets it. Moving the slider = your level, Auto off (kept until you tap Auto again).
 * Changing it on the bike itself also switches Auto off for that ride.
 */
@Composable
fun BikeBrightness(fg: Color, dim: Color, bg: Color, accent: Color, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val manualRide by Bike.clusterManual.collectAsState()
    val level by Bike.clusterLevel.collectAsState()
    val conn by Bike.conn.collectAsState()
    val live = conn.phase == Phase.LIVE
    var autoPref by remember { mutableStateOf(prefs.clusterBright) }
    val auto = autoPref && !manualRide
    var pending by remember { mutableStateOf<Float?>(null) }       // what you set, until the bike reports it back
    LaunchedEffect(level, pending) {
        val p = pending ?: return@LaunchedEffect
        if (level == p.roundToInt()) pending = null else { kotlinx.coroutines.delay(4000); pending = null }
    }
    val shown = pending ?: (level.takeIf { it in 2..10 } ?: 6).toFloat()
    val p by animateFloatAsState(if (auto) 0f else 1f, androidx.compose.animation.core.spring(dampingRatio = .82f, stiffness = 320f), label = "fold")
    val view = androidx.compose.ui.platform.LocalView.current
    fun setAuto(on: Boolean) {
        autoPref = on
        Bike.service?.riderAuto(on) ?: run { prefs.clusterBright = on; Bike.clusterManual.value = !on }
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg).padding(6.dp).alpha(if (live) 1f else .55f),
    ) {
        val full = maxWidth
        val btn = 96.dp
        val gap = 10.dp * p
        val bw = full * (1 - p) + btn * p
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.width(bw).height(48.dp).clip(RoundedCornerShape(13.dp))
                    .background(accent.copy(alpha = .16f * (1 - p)))
                    .border(1.dp, if (auto) accent.copy(alpha = .6f) else dim.copy(alpha = .35f), RoundedCornerShape(13.dp))
                    .clickable { setAuto(!auto) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Ic("sun", if (auto) accent else dim, 20.dp)
                Text(" Auto", color = if (auto) accent else fg, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (p < .98f) Text(
                    if (level in 2..10) "  ·  bike display ${level * 10} %" else "  ·  bike display",
                    color = dim, maxLines = 1, softWrap = false, modifier = Modifier.alpha(1 - p),
                )
            }
            if (p > .02f) {
                Spacer(Modifier.width(gap))
                Row(
                    Modifier.width((full - bw - gap).coerceAtLeast(0.dp)).graphicsLayer {
                        alpha = p; scaleX = .55f + .45f * p
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f)   // grows out of the Auto button
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Slider(
                        value = shown, onValueChange = { pending = it.roundToInt().toFloat() }, valueRange = 2f..10f, steps = 7,
                        onValueChangeFinished = { pending?.let { Bike.service?.riderLevel(it.roundToInt()) ?: run { prefs.clusterBright = false } }; autoPref = false },
                        colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent,
                            inactiveTrackColor = dim.copy(alpha = .3f), activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(shown * 10).roundToInt()}%", color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
}
