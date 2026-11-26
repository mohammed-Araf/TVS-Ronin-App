package dev.ronindash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RideScreen(modifier: Modifier) {
    val conn by Bike.conn.collectAsState()
    val s by Bike.state.collectAsState()
    val nav by Bike.nav.collectAsState()
    val live = conn.phase == Phase.LIVE
    val f by Bike.fuel.collectAsState()
    val warm by Bike.warmup.collectAsState()
    val ask by Bike.ask.collectAsState()
    val lean by Bike.lean.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val dark = MaterialTheme.colorScheme.background.red < .3f

    // Smoothly animated values (the bike only sends each frame type about once a second).
    val rpmFrac by animateFloatAsState(if (live) (s.rpm / 9000f).coerceIn(0f, 1f) else 0f, spring(stiffness = Spring.StiffnessLow), label = "rpm")
    val rpm by animateIntAsState(if (live) s.rpm else 0, tween(700), label = "rpmN")
    val fuelFrac by animateFloatAsState(if (f.tankKnown) (f.tankL / prefs.tankL).toFloat().coerceIn(0f, 1f) else 0f, tween(900), label = "fuel")
    val thrFrac by animateFloatAsState(if (live) (s.throttlePct / 100f).coerceIn(0f, 1f) else 0f, tween(250), label = "thr")

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ConnPill(conn) }
            SosButton()
        }

        // First thing on the page: who called, who wrote
        MissedStrip(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceContainer, Color(0xFFE5484D))

        BikeBrightness(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.primary)

        // Speed + gear
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (live && "speed" !in s.unknown) RollingNumber(s.speed, fontSize = 84.sp)
                    else Text("--", fontSize = 84.sp, fontWeight = FontWeight.SemiBold, lineHeight = 84.sp)
                    Text("km/h", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val gearColor by animateColorAsState(if (s.neutral) Color(0xFF1FA463) else MaterialTheme.colorScheme.primary, label = "gearC")
                Box(
                    Modifier.size(92.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = if (live) s.gearLabel else "–",
                        transitionSpec = {
                            // Shift up: new gear rolls in from below. Shift down: from above.
                            val up = (targetState.toIntOrNull() ?: 0) >= (initialState.toIntOrNull() ?: 0)
                            (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
                        }, label = "gear",
                    ) { g -> Text(g, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = gearColor) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Label("RPM", if ("rpm" in s.unknown) "–" else "$rpm")
            LinearProgressIndicator(progress = { rpmFrac }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = lerpColor(MaterialTheme.colorScheme.primary, Color(0xFFE5484D), ((rpmFrac - 0.7f) / 0.3f).coerceIn(0f, 1f)),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, gapSize = 0.dp, drawStopIndicator = {})
            Spacer(Modifier.height(10.dp))
            Label("Throttle", "${s.throttlePct}%")
            LinearProgressIndicator(progress = { thrFrac }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = Color(0xFF6EA8FF), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, gapSize = 0.dp, drawStopIndicator = {})
        }

        Telltales(s, live)

        Appear(warm != null) { warm?.let { WarmCard(it) } }

        Appear(ask != null) { ask?.let { FuelAskCard(it, prefs) } }

        // Live mileage: km/l now (smoothed), trip average, a bar for better/worse than average, idling L/h, trip litres and ₹
        Appear(f.tripKm > 0 || f.moving || f.idling) {
            Panel {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Mileage now", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        val mc by animateColorAsState(if (f.moving) mileColor(f.liveKmpl, dark) else MaterialTheme.colorScheme.onSurface, tween(1600), label = "mc")
                        when {
                            f.moving -> CountText(f.liveKmpl, { "%.0f km/l".format(it) }, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = mc, key = "kmpl")
                            f.idling -> CountText(f.lph, { "%.2f L/h".format(it) }, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), key = "lph")
                            else -> Text("–", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Trip average", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        CountText(f.tripKmpl.takeIf { it > 0 }, { "%.1f km/l".format(it) }, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
                Spacer(Modifier.height(10.dp))
                val pos by animateFloatAsState(if (f.moving && f.tripKmpl > 0) ((f.liveKmpl / f.tripKmpl) - .5).toFloat().coerceIn(0f, 1f) else .5f, tween(900), label = "mpos")
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxWidth(.5f).height(8.dp)) { Box(Modifier.align(Alignment.CenterEnd).width(2.dp).height(8.dp).background(MaterialTheme.colorScheme.onSurfaceVariant)) }
                    Box(Modifier.fillMaxWidth(pos).height(8.dp)) { Box(Modifier.align(Alignment.CenterEnd).size(8.dp).clip(CircleShape).background(if (f.moving) mileColor(f.liveKmpl, dark) else MaterialTheme.colorScheme.onSurfaceVariant)) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text("worse", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("trip average", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("better", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                Spacer(Modifier.height(8.dp))
                Text("This trip %.1f km  ·  %.2f L  ·  ₹%.0f".format(f.tripKm, f.tripL, f.tripL * prefs.fuelPrice) +
                    (if (f.idling) "  ·  idling" else ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Appear(nav != null) { nav?.let { NavCard(it) } }

        // Fuel: our own count of petrol left (fills minus fuel burned). Range = that × your mileage lately. The bike's DTE is never used.
        Panel {
            Label("Fuel", if (f.tankKnown) "%.1f L".format(f.tankL) else "–")
            LinearProgressIndicator(progress = { fuelFrac }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = if (f.low) Color(0xFFE5484D) else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, gapSize = 0.dp, drawStopIndicator = {})
            Spacer(Modifier.height(10.dp))
            Row { Stat("Range", if (f.tankKnown) "%.0f km".format(f.rangeKm) else "–", Modifier.weight(1f)); Stat("Odometer", if ("odo" in s.unknown) "–" else "%.1f km".format(s.odoKm), Modifier.weight(1f)) }
        }

        Panel {
            Row { Stat("Trip A", "%.1f km".format(s.tripAKm), Modifier.weight(1f)); Stat("Trip B", "%.1f km".format(s.tripBKm), Modifier.weight(1f)) }
            Spacer(Modifier.height(10.dp))
            Row {
                Stat("Engine", s.engineTempC?.let { "$it °C" } ?: "–", Modifier.weight(1f))
                Stat("Battery", s.batteryV?.let { "%.1f V".format(it) } ?: "–", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row { Stat("Top speed", "${s.topSpeed} km/h", Modifier.weight(1f)); Stat("Avg speed", "${s.avgSpeed} km/h", Modifier.weight(1f)) }
            if (lean.maxLeft > 0 || lean.maxRight > 0) {
                Spacer(Modifier.height(10.dp))
                Row { Stat("Max lean, left", "${lean.maxLeft}°", Modifier.weight(1f)); Stat("Max lean, right", "${lean.maxRight}°", Modifier.weight(1f)) }
            }
        }

        ServiceCard()

    }
}

/** Cards that slide and fade in/out instead of popping. */
@Composable
fun Appear(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(tween(250)) + expandVertically(tween(300)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(250))) { content() }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

@Composable
fun ConnPill(c: Conn) {
    val target = when (c.phase) {
        Phase.LIVE -> Color(0xFF1FA463)
        Phase.CONNECTING, Phase.WAITING -> MaterialTheme.colorScheme.primary
        Phase.PROBLEM -> Color(0xFFE5484D)
        Phase.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val color by animateColorAsState(target, tween(400), label = "conn")
    // Pulse while connecting or waiting.
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    val busy = c.phase == Phase.CONNECTING || c.phase == Phase.WAITING
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).alpha(if (busy) a else 1f).clip(CircleShape).background(color))
        AnimatedContent(c.detail, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "detail") {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun Panel(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(16.dp)) { content() }
    }
}

@Composable
private fun Label(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Stat(name: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Telltales(s: BikeState, live: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Lamp("◀ Left", live && s.leftInd, Color(0xFF5CC27C))
        Lamp("Right ▶", live && s.rightInd, Color(0xFF5CC27C))
        Lamp("⚠ Hazard", live && Bike.hazard.collectAsState().value, Color(0xFFFF7A6E))
        Lamp("High beam", live && s.highBeam, Color(0xFF6EA8FF))
        Lamp("Neutral", live && s.neutral, Color(0xFF5CC27C))
        Lamp("Engine on", live && s.engineOn, Color(0xFF5CC27C))
        Lamp("Side stand", live && s.sideStand, Color(0xFFFF7A6E))
        Lamp("Clutch", live && s.clutch, Color(0xFFFFAD33))
        // byte 11 of the live frame: the bike holds a press for ~5 s
        Lamp("I button", live && s.button == 4, Color(0xFF6EA8FF))
        Lamp("I accept", live && s.button == 1, Color(0xFF6EA8FF))
        Lamp("I reject", live && s.button == 2, Color(0xFF6EA8FF))
        Lamp("Mode", live && s.button == 3, Color(0xFF6EA8FF))
        Lamp(if (s.rideMode == 3) "Rain" else "Urban", live, Color(0xFF5CC27C))
        Lamp("Engine lamp", live && s.milLamp, Color(0xFFFFAD33))
        Lamp("ABS", live && s.absLamp, Color(0xFFFFAD33))
    }
}

@Composable
private fun Lamp(name: String, on: Boolean, color: Color) {
    val bg by animateColorAsState(if (on) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainer, tween(180), label = "lampBg")
    val fg by animateColorAsState(if (on) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), tween(180), label = "lampFg")
    Text(name, color = fg, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun NavCard(n: NavInfo) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(arrow(n.dir), fontSize = 44.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
            Column(Modifier.weight(1f)) {
                Text(if (n.nextM >= 1000) "%.1f km".format(n.nextM / 1000.0) else "${n.nextM} m",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(n.road.ifBlank { NavParser.word(n.dir) }, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                if (n.minutesLeft > 0) Text("${n.minutesLeft} min left", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun arrow(d: Dir) = when (d) {
    Dir.LEFT, Dir.SHARP_LEFT -> "↰"; Dir.RIGHT, Dir.SHARP_RIGHT -> "↱"; Dir.SLIGHT_LEFT -> "↖"; Dir.SLIGHT_RIGHT -> "↗"
    Dir.UTURN -> "↶"; Dir.ROUNDABOUT -> "↻"; Dir.ARRIVE -> "⚑"; else -> "↑"
}

/** Long-press to start the SOS countdown (cancelable, like a detected crash). */
@Composable
private fun SosButton() {
    Text("SOS · hold", color = Color(0xFFFF7A6E), fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(Color(0xFFFF7A6E).copy(alpha = 0.15f))
            .pointerInput(Unit) { detectTapGestures(onLongPress = { Bike.service?.manualSos() ?: Bike.log("Start the connection first (Settings)") }) }
            .padding(horizontal = 16.dp, vertical = 10.dp))
}

@Composable
private fun ServiceCard() {
    val left by Bike.serviceLeftKm.collectAsState()
    Appear(left != null) { ServiceCardBody(left ?: 0) }
}

@Composable
private fun ServiceCardBody(l: Int) {
    val due = l <= 0
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Service", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Text(if (due) "Overdue by ${-l} km" else "In $l km", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    color = if (due) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * Odometer-style number: each digit rolls up when the value rises and down when it falls.
 * Digits are keyed from the right so "99 → 100" rolls each place correctly.
 */
@Composable
fun RollingNumber(value: Int, fontSize: androidx.compose.ui.unit.TextUnit, color: Color = MaterialTheme.colorScheme.onSurface) {
    var last by remember { mutableIntStateOf(value) }
    val up = value >= last
    LaunchedEffect(value) { last = value }
    val digits = value.toString()
    Row {
        digits.forEachIndexed { i, ch ->
            key(digits.length - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        (slideInVertically(tween(300)) { if (up) it else -it } + fadeIn(tween(300))) togetherWith
                            (slideOutVertically(tween(300)) { if (up) -it else it } + fadeOut(tween(200))) using SizeTransform(clip = true)
                    }, label = "digit",
                ) { d -> Text("$d", fontSize = fontSize, fontWeight = FontWeight.SemiBold, lineHeight = fontSize, color = color) }
            }
        }
    }
}

/** Engine warm-up after a cold start: how warm it is, and a hint to go easy until it is. */
@Composable
private fun WarmCard(w: WarmUp) {
    val cs = MaterialTheme.colorScheme
    val c = DashColors(false, cs.surfaceContainer, cs.onSurface, cs.onSurfaceVariant, Color(0xFFE8930C), Color(0xFFE5484D), cs.background.red < .3f)
    val mins = (System.currentTimeMillis() - w.sinceMs) / 60_000.0
    val rate = if (mins > .5) (w.nowC - w.startC) / mins else 0.0
    val left = if (rate > .3) ((w.warmC - w.nowC) / rate).toInt() else null
    Panel {
        Text("Engine warming up", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        WarmBar(w, c, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("${w.nowC} → ${w.warmC} °C" + (left?.let { "  ·  ~$it min" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

/** "Filled up?" One question, answered with one tap (full tank) or a number (litres or ₹, the way pumps in India sell it). Optional. */
@Composable
private fun FuelAskCard(a: FuelAsk, prefs: Prefs) {
    var stage by remember(a) { mutableStateOf(a.stage) }
    var input by remember(stage) { mutableStateOf("") }
    fun answer(kind: String, v: Double? = null) { Bike.service?.answerFuel(kind, v) ?: run { Bike.ask.value = null } }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ic("pump", MaterialTheme.colorScheme.primary, 30.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(a.at))
                Text(when {
                    stage == "litres" -> "How many litres?"; stage == "rs" -> "How many rupees?"; stage == "pump" -> "Litres on the pump?"
                    a.late -> "Filled up${if (a.station.isNotBlank()) " at ${a.station}" else ""}, $time?"
                    else -> "Filled up?"
                }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val sub = when {
                    stage == "rs" -> "₹%.0f/L".format(prefs.fuelPrice)
                    stage == "ask" && a.late -> a.guess
                    stage == "ask" -> a.station
                    else -> ""
                }
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (stage) {
                "ask" -> {
                    androidx.compose.material3.Button(onClick = { stage = "pump" }) { Text("Full tank") }
                    androidx.compose.material3.FilledTonalButton(onClick = { stage = "litres" }) { Text("Litres") }
                    androidx.compose.material3.FilledTonalButton(onClick = { stage = "rs" }) { Text("₹") }
                    if (a.late) androidx.compose.material3.TextButton(onClick = { answer("keep") }) { Text("Looks right") }
                    androidx.compose.material3.TextButton(onClick = { answer("no") }) { Text(if (a.late) "Didn't fill" else "No") }
                }
                else -> {
                    if (stage != "pump") (if (stage == "rs") listOf(100, 200, 500, 1000) else listOf(1, 2, 5, 10)).forEach { v ->
                        androidx.compose.material3.AssistChip(onClick = { input = "$v" }, label = { Text(if (stage == "rs") "₹$v" else "$v L") })
                    }
                }
            }
        }
        if (stage != "ask") {
            androidx.compose.material3.OutlinedTextField(input, { input = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text(if (stage == "rs") "₹" else "litres") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                androidx.compose.material3.Button(onClick = {
                    val v = input.toDoubleOrNull()
                    when {
                        stage == "pump" -> answer("full", v)
                        v != null && v > 0 -> answer(stage, v)
                    }
                }) { Text("OK") }
                androidx.compose.material3.TextButton(onClick = { if (stage == "pump") answer("full", null) else stage = "ask" }) { Text(if (stage == "pump") "Skip" else "Back") }
            }
        }
    }
}
