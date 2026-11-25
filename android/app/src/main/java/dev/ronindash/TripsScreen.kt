package dev.ronindash

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val changed by Bike.tripsChanged.collectAsState()
    val liveKm by Bike.liveTripKm.collectAsState()
    val liveKmpl by Bike.liveTripKmpl.collectAsState()
    var local by remember { mutableIntStateOf(0) }
    val trips = remember(changed, local) { TripStore.list(ctx) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val df = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }
    val price = remember { Prefs(ctx).fuelPrice }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Trips", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        }
        if (liveKm > 0) item {
            val stopped by Bike.tripStoppedMin.collectAsState()
            Panel {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (stopped >= 3) "On a break · $stopped min" else "Riding now", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge)
                        Text("%.1f km  ·  %.0f km/l".format(liveKm, liveKmpl), style = MaterialTheme.typography.titleLarge)
                    }
                    TextButton(onClick = { Bike.service?.endRide() }) { Text("End ride") }
                }
            }
        }
        if (trips.isEmpty()) item {
            Text("Your rides appear here automatically.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(trips, key = { it.id }) { t ->
            Box(Modifier.animateItem()) { Panel {
                Column(Modifier.fillMaxWidth().clickable {
                    ctx.startActivity(Intent(ctx, TripMapActivity::class.java).putExtra("id", t.id))
                }) {
                    Text(df.format(Date(t.start)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%.1f km".format(t.km), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    val mins = ((t.end - t.start) / 60000).toInt()
                    val avg = if (t.movingS > 0) t.km / (t.movingS / 3600) else 0.0
                    Text("${mins / 60}h ${mins % 60}m  ·  avg %.0f  ·  top %.0f km/h".format(avg, t.maxKmh) +
                        if (t.breaks > 0) "  ·  ${t.breaks} break" + (if (t.breaks > 1) "s" else "") else "")
                    Text("Est. %.1f km/l  ·  %.2f L  ·  ₹%.0f".format(t.kmpl, t.fuelL, t.fuelL * price),
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
                Row {
                    TextButton(onClick = { ctx.startActivity(Intent(ctx, TripMapActivity::class.java).putExtra("id", t.id)) }) { Text("Map") }
                    TextButton(onClick = { ctx.startActivity(Intent(ctx, TripMapActivity::class.java).putExtra("id", t.id).putExtra("share", true)) }) { Text("Share") }
                    if (confirm == t.id) TextButton(onClick = { TripStore.delete(ctx, t.id); confirm = null; local++ }) { Text("Tap again to delete") }
                    else TextButton(onClick = { confirm = t.id }) { Text("Delete") }
                }
            } }
        }
        item { Text("") }
    }
}
