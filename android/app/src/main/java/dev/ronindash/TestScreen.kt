package dev.ronindash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.animation.togetherWith
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private class Step(
    val title: String,
    val body: String,
    val answers: List<String> = listOf("Done"),
    val waitS: Int = 0,
    val send: (() -> Unit)? = null,
)

private fun tx(label: String, f: ByteArray) { Bike.service?.send(label, f) ?: Bike.log("Not connected, can't send $label") }

private val SHOWN = listOf("Showed correctly", "Something else", "Nothing")
private val YN = listOf("Yes", "No")

private val STEPS = listOf(
    Step("Connect", "Close TVS Connect (Force stop). Ignition ON. Wait until the Ride tab says Connected.", listOf("Connected", "Won't connect")),
    Step("Hands off", "Don't touch anything for 20 seconds. Watch the bike and the phone.\n\nDid an assistant start by itself?", YN, waitS = 20),
    Step("Customer window", "Is the bike showing the customer window (phone signal + battery + call/SMS counts)?", YN),
    Step("Long press I", "On the customer window, HOLD the I button for 3 seconds.\n\nDid the assistant open on your phone?", YN, waitS = 8),
    Step("Long press I again", "Wait for it to close. HOLD I for 3 seconds again.\n\nDid it open?", YN, waitS = 8),
    Step("Short press I", "Press I once, quickly.\n\nDid anything happen?", YN, waitS = 8),
    Step("Mode button", "Press the Mode button once (Urban → Rain), then back.", listOf("Done")),
    Step("Greeting", "Tap Send, then look at the cluster. Expected: Hi RONIN", SHOWN, send = { tx("name", Protocol.riderName("RONIN")) }),
    Step("Caller", "Tap Send. Expected: incoming caller TEST CALLER", SHOWN, send = { tx("caller", Protocol.caller("TEST CALLER")) }),
    Step("SMS", "Tap Send. Expected: SMS from TESTSMS", SHOWN, send = { tx("sms", Protocol.sms("TESTSMS")) }),
    Step("Text", "Tap Send. Expected: HELLO RONIN", SHOWN, send = { tx("text", Protocol.text("HELLO RONIN")) }),
    Step("Counts", "Tap Send. Expected: 3 missed calls, 2 SMS (the app replaces these with real counts 2 s later)", SHOWN,
        send = { tx("status-test", Protocol.status(3, 2)) }),
    Step("Nav: turn ahead", "Tap Send. Expected: an arrow and 200 m", SHOWN, send = {
        tx("nav", Protocol.nav(200, 5, 1500, 7, 1)); tx("nav-text", Protocol.navText("LEFT MG ROAD"))
    }),
    Step("Nav: left now", "Tap Send. Expected: left arrow, 50 m", SHOWN, send = { tx("nav", Protocol.nav(50, 4, 1300, 7, 3)) }),
    Step("Nav: right now", "Tap Send. Expected: right arrow, 50 m", SHOWN, send = { tx("nav", Protocol.nav(50, 4, 1300, 7, 4)) }),
    Step("Nav: U-turn", "Tap Send. Expected: U-turn symbol", SHOWN, send = { tx("nav", Protocol.nav(0, 4, 1300, 41, 0)) }),
    Step("Nav: arrived", "Tap Send. Expected: arrived / flag", SHOWN, send = { tx("nav", Protocol.nav(0, 0, 0, 7, 8)) }),
    Step("Google Maps", "Start Google Maps navigation to any place nearby (no need to ride). Watch the cluster for 20 s.\n\nDid turns show on the bike?",
        listOf("Yes", "No", "Skip"), waitS = 20),
    Step("Call: answer", "Only if someone can call you. When the caller shows on the bike, HOLD I for 3 s.\n\nWas it answered?", listOf("Yes", "No", "Skip")),
    Step("Call: reject", "Only if someone can call. When the caller shows, press I once.\n\nWas it rejected?", listOf("Yes", "No", "Skip")),
    Step("Finished", "Ignition OFF. At home, plug the phone into the Mac and tell Claude: everything is in the log file.", listOf("Finish")),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestScreen(modifier: Modifier) {
    var idx by rememberSaveable { mutableIntStateOf(0) }
    var note by rememberSaveable { mutableStateOf("") }
    var left by remember { mutableIntStateOf(0) }
    val conn by Bike.conn.collectAsState()
    val log by Bike.log.collectAsState()
    val file by Bike.logFile.collectAsState()
    val step = STEPS[idx]

    LaunchedEffect(idx) {
        left = step.waitS
        while (left > 0) { delay(1000); left-- }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConnPill(conn)
        androidx.compose.animation.AnimatedContent(
            targetState = idx,
            transitionSpec = {
                val d = if (targetState > initialState) 1 else -1
                (androidx.compose.animation.slideInHorizontally { it * d } + androidx.compose.animation.fadeIn()) togetherWith
                    (androidx.compose.animation.slideOutHorizontally { -it * d } + androidx.compose.animation.fadeOut())
            }, label = "step",
        ) { shown ->
        val step = STEPS[shown]
        Panel {
            Text("Step ${shown + 1} of ${STEPS.size}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(step.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(step.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
            if (left > 0) Text("${left}s", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            step.send?.let { s ->
                Button(onClick = { Bike.log("[test] SEND ${step.title}"); s() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Send to bike") }
            }
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                step.answers.forEach { a ->
                    FilledTonalButton(onClick = {
                        Bike.log("[test] ${shown + 1}. ${step.title} → $a" + if (note.isNotBlank()) " ($note)" else "")
                        note = ""
                        if (idx < STEPS.size - 1) idx++
                    }, enabled = left == 0, modifier = Modifier.heightIn(min = 52.dp)) { Text(a) }
                }
            }
            Row {
                TextButton(onClick = { if (idx > 0) idx-- }, enabled = idx > 0) { Text("← Back") }
                TextButton(onClick = { Bike.log("[test] restarted"); idx = 0 }) { Text("Start over") }
            }
        }

        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Bike.log("[mark] ${note.ifBlank { "mark" }}"); note = "" }) { Text("Mark now") }
            OutlinedButton(onClick = { Bike.service?.clearDisplay() }) { Text("Clear bike text") }
            OutlinedButton(onClick = { Bike.service?.launchAssistant() }) { Text("Assistant") }
        }
        Text("Bike display brightness", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (lv in listOf(2, 3, 5, 7, 10)) OutlinedButton(onClick = { tx("brightness $lv", Protocol.brightness(lv)) }) { Text("$lv") }
        }
        if (file.isNotEmpty()) Text("Log file: $file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val listState = rememberLazyListState()
        LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(0) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            items(log.asReversed()) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                    color = if ("[test]" in line || "[button]" in line) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
