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
    val calls by Bike.missedCalls.collectAsState()
    val msgs by Bike.unreadMessages.collectAsState()
    val nav by Bike.nav.collectAsState()
    val live = conn.phase == Phase.LIVE
    val tripKm by Bike.liveTripKm.collectAsState()
    val tripKmpl by Bike.liveTripKmpl.collectAsState()

    // Smoothly animated values (the bike only sends each frame type about once a second).
    val rpmFrac by animateFloatAsState(if (live) (s.rpm / 9000f).coerceIn(0f, 1f) else 0f, spring(stiffness = Spring.StiffnessLow), label = "rpm")
    val rpm by animateIntAsState(if (live) s.rpm else 0, tween(700), label = "rpmN")
    val fuelFrac by animateFloatAsState(s.fuelPct / 100f, tween(900), label = "fuel")
    val thrFrac by animateFloatAsState(if (live) (s.throttlePct / 100f).coerceIn(0f, 1f) else 0f, tween(250), label = "thr")

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ConnPill(conn) }
            SosButton()
        }

        // Speed + gear
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (live) RollingNumber(s.speed, fontSize = 84.sp)
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
            Label("RPM", "$rpm")
            LinearProgressIndicator(progress = { rpmFrac }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = lerpColor(MaterialTheme.colorScheme.primary, Color(0xFFE5484D), ((rpmFrac - 0.7f) / 0.3f).coerceIn(0f, 1f)),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, gapSize = 0.dp, drawStopIndicator = {})
            Spacer(Modifier.height(10.dp))
            Label("Throttle", "${s.throttlePct}%")
            LinearProgressIndicator(progress = { thrFrac }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = Color(0xFF6EA8FF), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, gapSize = 0.dp, drawStopIndicator = {})
        }

        Telltales(s, live)

        Appear(tripKm > 0) {
            Panel { Row { Stat("This trip", "%.1f km".format(tripKm), Modifier.weight(1f)); Stat("Est. mileage", "%.0f km/l".format(tripKmpl), Modifier.weight(1f)) } }
        }

        Appear(nav != null) { nav?.let { NavCard(it) } }

        // Fuel and range
        Panel {
            Label("Fuel", if (live) "${s.fuelBars} of $FUEL_BARS bars" else "–")
            LinearProgressIndicator(progress = { fuelFrac }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = if (live && s.fuelBars <= 1) Color(0xFFE5484D) else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, gapSize = 0.dp, drawStopIndicator = {})
            Spacer(Modifier.height(10.dp))
            Row { Stat("Range", "${s.dteKm} km", Modifier.weight(1f)); Stat("Odometer", "%.1f km".format(s.odoKm), Modifier.weight(1f)) }
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
        }

        ServiceCard()

        Panel {
            Row { Stat("Missed calls", "$calls", Modifier.weight(1f)); Stat("Unread messages", "$msgs", Modifier.weight(1f)) }
        }
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
