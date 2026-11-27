package dev.ronindash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
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
    Step("Starter seen?", "Engine running. Watch the Starter lamp and count at the top of this screen. Press the starter firmly for about half a second.\n\nDid the lamp light and the count go up?",
        listOf("Yes", "No"), waitS = 3),
    Step("Starter: quick tap", "Now tap the starter as quickly as you can, once.\n\nDid the count go up?", listOf("Yes", "No"), waitS = 3),
    Step("Starter: one press", "Play music. Press the starter once. In ~2 s the next song should play.\n\nDid it?", listOf("Yes", "No", "Skip"), waitS = 4),
    Step("Starter: two presses", "Press the starter twice, quickly.\n\nDid the previous song play?", listOf("Yes", "No", "Next song instead", "Skip"), waitS = 4),
    Step("Starter: hold", "Hold the starter for 2 s.\n\nDid the music pause / play?", listOf("Yes", "No", "Skip"), waitS = 4),
    Step("Mode: double press", "Engine on. Press Mode twice, quickly.\n\nDid the map open? Is the ride mode back where it was?", listOf("Both yes", "Map only", "Neither", "Skip"), waitS = 5),
    Step("Mode: hold", "With the map open, hold Mode for 3 s.\n\nDid the dashboard come back? Did the bike open a menu?", listOf("Worked", "Nothing", "Bike menu opened", "Skip"), waitS = 5),
    Step("Finished", "Ignition OFF. Everything is saved in the ride log file (Android/data/dev.ronindash/files/logs).", listOf("Finish")),
)

@Composable
fun TestScreen(modifier: Modifier) {
    var pane by rememberSaveable { mutableIntStateOf(0) }
    val notes by Notes.list.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { Notes.load(ctx) }
    Column(modifier.fillMaxSize()) {
        androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            listOf("Tests", "Cluster", if (notes.isEmpty()) "Issues" else "Issues (${notes.size})").forEachIndexed { i, label ->
                SegmentedButton(pane == i, { pane = i },
                    androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, 3)) { Text(label, maxLines = 1) }
            }
        }
        when (pane) {
            0 -> TestsPane(Modifier.weight(1f))
            1 -> ClusterPane(Modifier.weight(1f))
            else -> IssuesPane(Modifier.weight(1f))
        }
    }
}

private val AGO = listOf<Pair<String, Int?>>("Not sure" to null, "Just now" to 0, "5 min ago" to 5, "15 min" to 15,
    "30 min" to 30, "1 hour" to 60, "2 hours" to 120)

/** Write down every issue seen on the ride; saved with the time and the ride log, read later over USB. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IssuesPane(modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val notes by Notes.list.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var ago by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf(-1L) }
    val hm = remember { java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()) }
    val t = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel {
                Text("What went wrong?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("One issue per note. Each is saved with the time and that ride's log, so it can be matched to what the bike sent.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                OutlinedTextField(text, { text = it }, placeholder = { Text("e.g. turn arrow stayed on the bike after the turn") },
                    minLines = 3, modifier = Modifier.fillMaxWidth())
                Text("When did it happen?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AGO.forEach { (label, m) ->
                        androidx.compose.material3.FilterChip(ago == m, { ago = m }, { Text(label) })
                    }
                }
                Button(onClick = { Notes.add(ctx, text, ago); text = ""; ago = null }, enabled = text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 6.dp)) { Text("Save issue") }
            }
        }
        if (notes.isNotEmpty()) item {
            Text("Saved (${notes.size}) · Android/data/dev.ronindash/files/notes", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(notes.asReversed(), key = { it.id }) { n ->
            Panel {
                Text(n.text, style = MaterialTheme.typography.bodyLarge)
                Text(buildList {
                    add(hm.format(java.util.Date(n.written)))
                    n.happened?.let { add(if (n.agoMin == 0) "just then" else "happened ~${t.format(java.util.Date(it))}") }
                    if (n.ride.isNotEmpty()) add(n.ride.removePrefix("ride_").removeSuffix(".csv"))
                }.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { if (confirm == n.id) { Notes.delete(n.id); confirm = -1 } else confirm = n.id }) {
                        Text(if (confirm == n.id) "Tap again to delete" else "Delete",
                            color = if (confirm == n.id) androidx.compose.ui.graphics.Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 16.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestsPane(modifier: Modifier) {
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
        Diagnostics()
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

/** Health line for bike tests: bad data from the bike, odometer vs GPS, memory, battery per ride, Android's bike link. */
@Composable
private fun Diagnostics() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val failing by Bike.guardFailing.collectAsState()
    val odo by Bike.odoVsGps.collectAsState()
    val batt by Bike.rideBatteryUsed.collectAsState()
    var memMb by remember { mutableIntStateOf(0) }
    var audioOut by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val rt = Runtime.getRuntime()
            memMb = (((rt.totalMemory() - rt.freeMemory()) + android.os.Debug.getNativeHeapAllocatedSize()) / 1_048_576).toInt()
            audioOut = Sounds.privateOutput(ctx)
            delay(3000)
        }
    }
    val assoc = remember { CompanionLink.isAssociated(ctx) }
    val st by Bike.state.collectAsState()
    val seen by Bike.starterSeen.collectAsState()
    val lastBtn by Bike.lastBtn.collectAsState()
    val warn = androidx.compose.ui.graphics.Color(0xFFE5484D)
    Column {
        if (failing.isNotEmpty()) Text("Bike data looks wrong: ${failing.joinToString()}", color = warn, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(buildList {
            add(audioOut?.let { "sounds → $it" } ?: "sounds muted (no earphones / intercom)")
            add("Memory $memMb MB")
            batt?.let { add("battery this ride $it%") }
            add(if (assoc) "Android wakes the app" else "not linked to Android (Settings → Bike)")
        }.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)) {
            Lamp("Starter $seen", st.starter); Lamp("Mode", st.button == 3); Lamp("I", st.button == 4); Lamp("Clutch", st.clutch)
            Lamp("Engine", st.engineOn)
        }
        if (lastBtn.isNotEmpty()) Text("Last button: $lastBtn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (odo.isNotBlank()) Text(odo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A dot that lights while the bike reports the button / switch. */
@Composable
private fun Lamp(label: String, on: Boolean) {
    val c by androidx.compose.animation.animateColorAsState(
        if (on) androidx.compose.ui.graphics.Color(0xFF1FA463) else MaterialTheme.colorScheme.outlineVariant, label = "lamp")
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.padding(0.dp).then(Modifier.size(10.dp))
            .background(c, androidx.compose.foundation.shape.CircleShape))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Test & log → Cluster: scripted tests ([ClusterTests]); the rider watches the bike and taps what they see. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClusterPane(modifier: Modifier) {
    val running by ClusterTests.now.collectAsState()
    val conn by Bike.conn.collectAsState()
    var note by rememberSaveable { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var picked by rememberSaveable { mutableStateOf(ClusterTests.TESTS.first().key) }
    val test = ClusterTests.TESTS.first { it.key == picked }
    androidx.compose.foundation.lazy.LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.heightIn(min = 6.dp)); ConnPill(conn) }
        item {
            Text("Google Maps navigation off. Watch the bike, tap what you see. Everything is timed in the log.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ClusterTests.TESTS.forEach { t ->
                    androidx.compose.material3.FilterChip(t.key == picked, { if (running == null) picked = t.key }, { Text(t.title) })
                }
            }
        }
        item {
            Panel {
                Text(test.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(test.how, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
                if (running == null)
                    Button(onClick = { msg = ClusterTests.start(test) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Start") }
                else {
                    Text(running ?: "", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { ClusterTests.stop("stopped by you") }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                }
                msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item {
            Text("Tap when it happens", style = MaterialTheme.typography.labelLarge)
            val marks = when (picked) {
                "arrows" -> listOf("Looks right", "Wrong arrow", "No arrow")
                "hold" -> listOf("Gone")
                "km" -> listOf("Shows km", "Odd")
                "official", "every5", "every2", "every1" -> listOf("Flash")
                else -> listOf("Odd")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp)) {
                marks.forEach { m ->
                    FilledTonalButton(onClick = { ClusterTests.mark(m, note); note = "" },
                        modifier = Modifier.heightIn(min = 64.dp).widthIn(min = 120.dp)) { Text(m, fontSize = 18.sp) }
                }
            }
        }
        item {
            OutlinedTextField(note, { note = it }, label = { Text("Note for the next tap (optional)") },
                placeholder = { Text("e.g. showed a U-turn, 60 km appeared") }, modifier = Modifier.fillMaxWidth())
        }
        item { Spacer(Modifier.heightIn(min = 24.dp)) }
    }
}
