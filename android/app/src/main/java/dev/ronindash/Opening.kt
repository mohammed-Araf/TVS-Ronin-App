package dev.ronindash

import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Opening (~2.8 s, tap to skip, instant when the phone has animations off): an instrument cluster waking up. Black screen; a
 * hairline gauge arc draws itself and its ticks fade in one by one; the needle sweeps to full and back (the ignition sweep) with
 * one light haptic tick at the top; RONIN DASH settles in as its letter spacing tightens; a quiet status line; then it fades.
 * Compose Canvas only, no video or Lottie.
 */
@Composable
fun Opening(done: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val accent = MaterialTheme.colorScheme.primary
    val conn by Bike.conn.collectAsState()
    val arc = remember { Animatable(0f) }
    val ticks = remember { Animatable(0f) }
    val needle = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val out = remember { Animatable(1f) }
    val animOff = remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    LaunchedEffect(Unit) {
        if (animOff) { done(); return@LaunchedEffect }
        playEngineStart(ctx)            // with the first frame: the engine catches as the gauge wakes up
        coroutineScope {
            val a = async { arc.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
            val t = async { delay(450); ticks.animateTo(1f, tween(500)) }
            val n = async {
                delay(600)
                needle.animateTo(1f, tween(650, easing = CubicBezierEasing(.3f, 0f, .2f, 1f)))
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                needle.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
            }
            val w = async { delay(1300); word.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
            a.await(); t.await(); n.await(); w.await()
        }
        delay(250)
        out.animateTo(0f, tween(450))
        done()
    }
    Box(
        Modifier.fillMaxSize().alpha(out.value).background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) { done() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.width(260.dp).height(160.dp)) {
                val r = size.width / 2 * .85f
                val c = Offset(size.width / 2, size.height * .9f)
                val tl = Offset(c.x - r, c.y - r); val sz = Size(r * 2, r * 2)
                drawArc(Color.White.copy(alpha = .18f), 180f, 180f * arc.value, false, tl, sz, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                for (i in 0..10) {
                    val vis = ((ticks.value * 11) - i).coerceIn(0f, 1f)
                    if (vis <= 0f) continue
                    val a = Math.PI * (1 - i / 10.0)
                    val r0 = r * .88f; val r1 = r * .8f
                    drawLine(Color.White.copy(alpha = .55f * vis), Offset(c.x + r0 * cos(a).toFloat(), c.y - r0 * sin(a).toFloat()),
                        Offset(c.x + r1 * cos(a).toFloat(), c.y - r1 * sin(a).toFloat()), 2.dp.toPx(), StrokeCap.Round)
                }
                if (needle.value > 0f) drawArc(accent, 180f, 180f * needle.value, false, tl, sz, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                val na = Math.PI * (1 - needle.value)
                val nl = r * .78f
                if (arc.value > .5f) {
                    drawLine(Color.White, c, Offset(c.x + nl * cos(na).toFloat(), c.y - nl * sin(na).toFloat()), 2.5.dp.toPx(), StrokeCap.Round)
                    drawCircle(Color.White, 4.dp.toPx(), c)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.alpha(word.value)) {
                val spacing = (14 - 10 * word.value).sp
                Text("RONIN ", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = spacing)
                Text("DASH", color = accent, fontSize = 26.sp, fontWeight = FontWeight.Light, letterSpacing = spacing)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.alpha(word.value * .7f), verticalAlignment = Alignment.CenterVertically) {
                val live = conn.phase == Phase.LIVE
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (live) Color(0xFF1FA463) else Color(0xFF9A9BA1)))
                Spacer(Modifier.width(8.dp))
                val p = Prefs(ctx)
                Text(if (live) "${p.bikeName.ifBlank { "Bike" }} · connected" else "Waiting for your bike", color = Color(0xFFBDBEC4), fontSize = 13.sp)
            }
        }
    }
}

/** The app's mark: a gauge arc and a needle (also the launcher icon). */
@Composable
fun Mark(size: androidx.compose.ui.unit.Dp, color: Color, accent: Color) {
    Canvas(Modifier.width(size).height(size * 2 / 3)) {
        val s = this.size.width / 48f
        val tl = Offset(6 * s, 10 * s); val sz = Size(36 * s, 36 * s)
        drawArc(color.copy(alpha = .3f), 180f, 180f, false, tl, sz, style = Stroke(2.5f * s, cap = StrokeCap.Round))
        drawArc(accent, 180f, 120f, false, tl, sz, style = Stroke(3f * s, cap = StrokeCap.Round))
        drawLine(color, Offset(24 * s, 28 * s), Offset(33 * s, 15 * s), 2.5f * s, StrokeCap.Round)
    }
}

/** Held so the player isn't garbage-collected mid-sound (that's what cut it off when the dashboard appeared). */
private var enginePlayer: android.media.MediaPlayer? = null

/** Engine start, from the first frame of the opening. Media volume; skipped on silent/vibrate or if turned off. */
private fun playEngineStart(ctx: android.content.Context) {
    if (!Prefs(ctx).openSound) return
    val am = ctx.getSystemService(android.media.AudioManager::class.java)
    if (am?.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return
    try {
        val afd = ctx.resources.openRawResourceFd(R.raw.snd_engine_start) ?: return
        android.media.MediaPlayer().apply {
            setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length); afd.close()
            setOnCompletionListener { it.release(); if (enginePlayer === it) enginePlayer = null }
            setOnErrorListener { mp, _, _ -> mp.release(); if (enginePlayer === mp) enginePlayer = null; true }
            prepare()
            enginePlayer?.release()
            enginePlayer = this
            start()
        }
    } catch (_: Exception) {}
}
