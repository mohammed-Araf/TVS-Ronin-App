package dev.ronindash

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private fun granted(c: Context, vararg p: String) = p.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private val BT = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
private val LOC = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
private val SOS = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE)
private val PHONE = arrayOf(
    Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
    Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CONTACTS,
)

// ------------------------------------------------------------------ building blocks (one clean list style)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) { content() }
    }
}

@Composable
private fun Item(title: String, sub: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) { Spacer(Modifier.size(12.dp)); trailing() }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun SwitchItem(title: String, sub: String? = null, initial: Boolean, set: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Item(title, sub, onClick = { v = !v; set(v) }) { Switch(v, { v = it; set(it) }) }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))

// ------------------------------------------------------------------ screen

@SuppressLint("MissingPermission")
@Composable
fun SettingsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val resume by Bike.resumeTick.collectAsState()
    val conn by Bike.conn.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    @Suppress("UNUSED_VARIABLE") val tick = resume + refresh

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            ConnPill(conn)
        }

        AppearanceSection(prefs)

        Section("Bike") {
            if (!granted(ctx, *BT)) {
                Item("Bluetooth permission", "Needed to talk to the bike") { Button(onClick = { perm.launch(BT) }) { Text("Allow") } }
            } else BikePicker(ctx, prefs) { refresh++ }
            Divider()
            val assoc = remember(refresh) { CompanionLink.isAssociated(ctx) }
            var err by remember { mutableStateOf<String?>(null) }
            val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
                if (r.resultCode == android.app.Activity.RESULT_OK) CompanionLink.onChosen(ctx, r.data); refresh++
            }
            Item("Let Android wake the app", err ?: if (assoc) "On: the app starts by itself when the bike turns on, and stops when it turns off"
                else "Off: Android doesn't know your bike yet, so the app can't start by itself") {
                if (!assoc) Button(onClick = {
                    err = null
                    CompanionLink.associate(ctx, { chooser.launch(androidx.activity.result.IntentSenderRequest.Builder(it).build()) }, { err = it })
                }) { Text("Set up") }
                else Text("On", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
            Divider()
            Item("Connection", when {
                conn.phase == Phase.LIVE -> "Connected"
                BikeService.running -> "Looking for the bike"
                else -> "Bike off: nothing is running"
            })
        }

        RidingScreensSection(prefs)
        NightSection(prefs)
        SoundsSection(prefs)
        WarmSection(prefs)

        SpeedSection(prefs)
        ServiceSection(prefs)
        SosSection(prefs)

        Section("On the bike display") {
            var name by remember { mutableStateOf(prefs.riderName) }
            OutlinedTextField(name, { name = it; prefs.riderName = it }, label = { Text("Greeting name") },
                supportingText = { Text("Shown as \"Hi NAME\". The cluster drops spaces.") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            SwitchItem("Caller name", initial = prefs.calls) { prefs.calls = it }
            Divider(); SwitchItem("SMS", initial = prefs.sms) { prefs.sms = it }
            Divider(); SwitchItem("WhatsApp", initial = prefs.whatsapp) { prefs.whatsapp = it }
            Divider(); SwitchItem("Google Maps turns", initial = prefs.navOn) { prefs.navOn = it }
        }

        HoldSection(prefs)

        ButtonsSection(prefs)

        Section("Trips and fuel") {
            SwitchItem("Record trips automatically", initial = prefs.tripRecord) { prefs.tripRecord = it }
            Divider()
            var price by remember { mutableStateOf(prefs.fuelPrice.toInt().toString()) }
            var tank by remember { mutableStateOf(prefs.tankL.toInt().toString()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                OutlinedTextField(price, { price = it; it.toDoubleOrNull()?.let { v -> prefs.fuelPrice = v } }, label = { Text("Petrol ₹/L") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(tank, { tank = it; it.toDoubleOrNull()?.let { v -> prefs.tankL = v } }, label = { Text("Tank (L)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            Hint("%.1f km/l".format(prefs.recKm / prefs.recL.coerceAtLeast(.1)) +
                (if (prefs.speedoK != 1.0) "  ·  speedo %+.1f%%".format((prefs.speedoK - 1) * 100) else ""))
        }

        PermissionsSection(refresh)

        Section("App") {
            SwitchItem("Keep a log file", "For testing and bug reports", prefs.fileLog) { prefs.fileLog = it }
            Divider(); Item("Version", "Ronin Dash ${BuildConfig.VERSION_NAME} · unofficial, not affiliated with TVS Motor")
            Hint("Don't use the phone while riding.")
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------ appearance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(prefs: Prefs) {
    val theme by Bike.theme.collectAsState()
    val accent by Bike.accent.collectAsState()
    Section("Appearance") {
        Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        val opts = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
        SlidingSegments(opts.map { it.second }, opts.indexOfFirst { it.first == theme }.coerceAtLeast(0), { prefs.theme = opts[it].first })
        Text("Accent colour", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            ACCENTS.forEachIndexed { i, (name, argb) ->
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color(argb))
                        .border(3.dp, if (accent == i) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                        .clickable { prefs.accent = i },
                    contentAlignment = Alignment.Center,
                ) { if (accent == i) Text("✓", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
        Divider()
        SwitchItem("Keep screen on", "While the app is open", prefs.keepScreenOn) { prefs.keepScreenOn = it }
    }
}


// ------------------------------------------------------------------ speed alert

@Composable
private fun SpeedSection(prefs: Prefs) {
    var on by remember { mutableStateOf(prefs.speedAlert) }
    var limit by remember { mutableFloatStateOf(prefs.speedLimit.toFloat()) }
    Section("Speed alert") {
        SwitchItem("Warn me above a speed", initial = on) { on = it; prefs.speedAlert = it }
        androidx.compose.animation.AnimatedVisibility(on,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()) { Column {
            Divider()
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Limit", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text("${limit.roundToInt()} km/h", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(limit, { limit = (it / 5).roundToInt() * 5f }, valueRange = 30f..150f, steps = 23,
                onValueChangeFinished = { prefs.speedLimit = limit.roundToInt() })
            Divider(); SwitchItem("Vibrate the phone too", "A sound plays in your helmet either way", prefs.speedBeep) { prefs.speedBeep = it }
            Divider(); SwitchItem("Use the bike's own overspeed warning", "Sends this limit to the cluster", prefs.clusterOverspeed) { prefs.clusterOverspeed = it }
            Hint("Alerts after 3 s above the limit, at most every 30 s.")
        } }
    }
}

// ------------------------------------------------------------------ service reminder

@Composable
private fun ServiceSection(prefs: Prefs) {
    val left by Bike.serviceLeftKm.collectAsState()
    val odoNow by Bike.state.collectAsState()
    val odo = if (odoNow.odoKm > 0) odoNow.odoKm else prefs.lastOdo
    var interval by remember { mutableStateOf(prefs.serviceIntervalKm.toString()) }
    var lastSvc by remember { mutableStateOf(if (prefs.lastServiceOdo >= 0) prefs.lastServiceOdo.toInt().toString() else "") }
    var warn by remember { mutableStateOf(prefs.serviceWarnKm.toString()) }
    var version by remember { mutableIntStateOf(0) }
    val nextAt = remember(version, odo) { if (odo > 0) prefs.nextServiceAt(odo) else 0 }

    fun recalc() {
        version++
        if (odo > 0) Bike.serviceLeftKm.value = (prefs.nextServiceAt(odo) - odo).toInt()
        prefs.serviceWarnedFor = 0
    }

    Section("Service reminder") {
        Item(
            when { odo <= 0 -> "Waiting for the odometer"; (left ?: 1) <= 0 -> "Service overdue"; else -> "Next service in ${left ?: (nextAt - odo).toInt()} km" },
            if (odo > 0) "Due at $nextAt km · odometer now %.0f km".format(odo) else "Connect to the bike once to read it",
        )
        Divider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
            OutlinedTextField(interval, { interval = it; it.toIntOrNull()?.let { v -> if (v >= 500) { prefs.serviceIntervalKm = v; recalc() } } },
                label = { Text("Every (km)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(warn, { warn = it; it.toIntOrNull()?.let { v -> prefs.serviceWarnKm = v; recalc() } },
                label = { Text("Warn before (km)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }
        OutlinedTextField(lastSvc, {
            lastSvc = it
            prefs.lastServiceOdo = it.toDoubleOrNull() ?: -1.0; recalc()
        }, label = { Text("Last service at (km)") },
            supportingText = { Text("Leave empty to follow every ${prefs.serviceIntervalKm} km on the odometer") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        TextButton(enabled = odo > 0, onClick = {
            prefs.lastServiceOdo = odo; lastSvc = odo.toInt().toString(); recalc()
        }, modifier = Modifier.padding(bottom = 4.dp)) { Text("I just got it serviced") }
    }
}

// ------------------------------------------------------------------ SOS

@Composable
private fun SosSection(prefs: Prefs) {
    val ctx = LocalContext.current
    var contacts by remember { mutableStateOf(prefs.sosContacts) }
    var input by remember { mutableStateOf("") }
    var testArmed by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val id = ctx.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return@rememberLauncherForActivityResult
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?", arrayOf(id), null)?.use {
                if (it.moveToFirst()) {
                    val n = it.getString(0).filter { c -> c.isDigit() || c == '+' }
                    if (n.isNotEmpty() && n !in contacts) { contacts = (contacts + n).take(5); prefs.sosContacts = contacts }
                }
            }
        } catch (e: Exception) { Bike.log("Contact pick failed: ${e.message}") }
    }
    Section("Crash detection and SOS") {
        SwitchItem("Detect crashes", "Hard impact while moving, then the bike stops, or the engine cuts out at speed", prefs.crashDetect) { prefs.crashDetect = it }
        Divider()
        Text("Emergency contacts", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        if (contacts.isEmpty()) Text("None yet. Add at least one.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        contacts.forEach { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n, Modifier.weight(1f)); TextButton(onClick = { contacts = contacts - n; prefs.sosContacts = contacts }) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedTextField(input, { input = it }, label = { Text("Phone number") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                val n = input.filter { it.isDigit() || it == '+' }
                if (n.length >= 7 && n !in contacts) { contacts = (contacts + n).take(5); prefs.sosContacts = contacts }
                input = ""
            }) { Text("Add") }
        }
        TextButton(onClick = { pick.launch(null) }) { Text("Pick from contacts") }
        Divider()
        SwitchItem("Also call the first contact", initial = prefs.sosCall) { prefs.sosCall = it }
        Divider()
        Item("Test the alert", if (testArmed) "Tap again to start. Cancel before 0 or the SMS really goes out." else "Runs the real 30 s countdown") {
            OutlinedButton(onClick = {
                if (!testArmed) testArmed = true
                else { testArmed = false; Bike.service?.crash?.trigger("TEST alert (tap I'M OK)") ?: Bike.log("Start the connection first") }
            }) { Text(if (testArmed) "Start" else "Test") }
        }
        Hint("When triggered: 30 s alarm and an \"I'M OK\" screen. Any bike button also cancels. Otherwise your contacts get an SMS with a map link to your location.")
    }
}

// ------------------------------------------------------------------ permissions

@Composable
private fun PermissionsSection(tick: Int) {
    Section("Permissions") {
        val allowAll = PermissionRows(tick, rowDivider = { Divider() }) { p, ok, grant ->
            Item(p.name, p.why) {
                if (ok) Text("On", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                else OutlinedButton(onClick = grant) { Text("Allow") }
            }
        }
        TextButton(onClick = allowAll, modifier = Modifier.padding(top = 4.dp)) { Text("Allow everything that's missing") }
    }
}

@Composable
private fun Perm(name: String, why: String, ok: Boolean, grant: () -> Unit) {
    Item(name, why) {
        if (ok) Text("On", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        else OutlinedButton(onClick = grant) { Text("Allow") }
    }
}

// ------------------------------------------------------------------ bike picker + pairing

@SuppressLint("MissingPermission")
@Composable
private fun BikePicker(ctx: Context, prefs: Prefs, changed: () -> Unit) {
    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java).adapter }
    if (adapter == null || !adapter.isEnabled) { Item("Bluetooth is off", "Turn it on to connect"); return }
    var chosen by remember { mutableStateOf(prefs.mac) }
    val bonded = adapter.bondedDevices.filter { it.name?.startsWith("TVS") == true }
    val found = remember { mutableStateListOf<BluetoothDevice>() }
    var scanning by remember { mutableStateOf(false) }

    fun choose(d: BluetoothDevice) {
        prefs.mac = d.address; prefs.bikeName = d.name ?: d.address; chosen = d.address
        if (BikeService.running) Bike.service?.reconnect() else BikeService.start(ctx)
        changed()
    }

    if (bonded.isEmpty()) Item("No paired bike yet", "Pair one below")
    bonded.forEach { d ->
        Row(Modifier.fillMaxWidth().clickable { choose(d) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(chosen == d.address, { choose(d) })
            Column { Text(d.name ?: "TVS"); Text(d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    val cb = remember {
        object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val d = r.device
                if (d.name?.startsWith("TVS") == true && found.none { it.address == d.address } && d.bondState != BluetoothDevice.BOND_BONDED) found.add(d)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { if (scanning) try { adapter.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {} } }

    Divider()
    Item("Pair a new bike", "Close TVS Connect. On the bike: long-press Mode → CLOCK SETTING → press Mode once. Then search and type the code shown on the bike.") {
        if (!scanning) OutlinedButton(onClick = { found.clear(); scanning = true; adapter.bluetoothLeScanner?.startScan(cb) }) { Text("Search") }
        else OutlinedButton(onClick = { scanning = false; adapter.bluetoothLeScanner?.stopScan(cb) }) { Text("Stop") }
    }
    found.forEach { d ->
        Item(d.name ?: "TVS", "Tap to pair", onClick = {
            scanning = false; adapter.bluetoothLeScanner?.stopScan(cb)
            Bike.log("Pairing ${d.name}: createBond=${d.createBond()}")
            prefs.mac = d.address; prefs.bikeName = d.name ?: ""; chosen = d.address; changed()
        })
    }
}

// ------------------------------------------------------------------ new sections

@Composable
private fun Choice(options: List<Pair<String, String>>, initial: String, set: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        options.forEachIndexed { i, (k, label) ->
            SegmentedButton(v == k, { v = k; set(k) },
                androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun SliderItem(title: String, initial: Int, range: IntRange, step: Int, unit: String, set: (Int) -> Unit) {
    var v by remember { mutableFloatStateOf(initial.toFloat()) }
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text("${v.roundToInt()}$unit", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    Slider(v, { v = ((it / step).roundToInt() * step).toFloat() }, valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first) / step - 1, onValueChangeFinished = { set(v.roundToInt()) })
}

@Composable
private fun RidingScreensSection(prefs: Prefs) {
    Section("Riding screens") {
        SliderItem("Fast-riding screen above", prefs.fastKmh, 40..120, 5, " km/h") { prefs.fastKmh = it }
        Hint("Parked: everything. Riding: one big number and the next turn. Fast: just a glow and the turn arrow. Swipe up any time to see everything for 10 s.")
        Divider(); SwitchItem("Open the dashboard when the bike connects", "Only when the phone is on its mount, not in a pocket", prefs.autoOpen) { prefs.autoOpen = it }
        Divider(); SwitchItem("Fight the sun", "Max brightness in strong light; rests the screen when the phone gets hot", prefs.fightSun) { prefs.fightSun = it }
        Divider(); SwitchItem("Bike display brightness follows the light", "Uses the phone's light sensor; stops if you change it on the bike", prefs.clusterBright) { prefs.clusterBright = it }
        Divider(); SwitchItem("Let the assistant change bike brightness", "\"Bike brightness up\" via app shortcuts", prefs.agentBright) { prefs.agentBright = it }
    }
}

@Composable
private fun NightSection(prefs: Prefs) {
    var mode by remember { mutableStateOf(prefs.night) }
    Section("Night panel") {
        Choice(listOf("off" to "Off", "night" to "Sunset to sunrise", "always" to "Always"), mode) { mode = it; prefs.night = it }
        androidx.compose.animation.AnimatedVisibility(mode != "off") { Column {
            Text("Glow colour", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            var glow by remember { mutableStateOf(prefs.nightGlow) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                listOf("accent" to MaterialTheme.colorScheme.primary, "red" to Color(0xFFFF453A), "amber" to Color(0xFFFFB020),
                    "green" to Color(0xFF3DDC84), "white" to Color.White).forEach { (k, c) ->
                    Box(Modifier.size(34.dp).clip(CircleShape).background(c)
                        .border(if (glow == k) 3.dp else 1.dp, if (glow == k) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable { glow = k; prefs.nightGlow = k })
                }
            }
            SliderItem("Brightness", prefs.nightBright, 20..100, 10, "%") { prefs.nightBright = it }
            Text("While riding, show", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            Choice(listOf("mileage" to "Mileage", "speed" to "Speed", "nothing" to "Nothing"), prefs.nightShow) { prefs.nightShow = it }
            SwitchItem("Show the next turn", initial = prefs.nightTurns) { prefs.nightTurns = it }
            Hint("A dark panel with one dim colour, so it doesn't dazzle you at night. Parked, it shows the full dashboard.")
        } }
    }
}

@Composable
private fun SoundsSection(prefs: Prefs) {
    val ctx = LocalContext.current
    val local = remember { mutableStateOf<Sounds?>(null) }
    DisposableEffect(Unit) { onDispose { local.value?.release() } }
    fun sounds(): Sounds = (if (BikeService.running) Bike.service?.sounds else null) ?: local.value ?: Sounds(ctx).also { local.value = it }
    Section("Sounds in your helmet") {
        SwitchItem("Indicator ticks", "Left and right sound different; a nudge if you leave it on", prefs.sndInd) { prefs.sndInd = it }
        Divider(); SwitchItem("Turn tones", "Before each Google Maps turn", prefs.sndTurns) { prefs.sndTurns = it }
        Divider(); SwitchItem("Say the turns too", initial = prefs.sayTurns) { prefs.sayTurns = it }
        Divider(); SwitchItem("Speed alert", initial = prefs.sndSpeed) { prefs.sndSpeed = it }
        Divider(); SwitchItem("Warnings", "Warning lamp, fuel low, side stand", prefs.sndWarn) { prefs.sndWarn = it }
        Divider(); SwitchItem("Missed calls and messages", "One chirp each, once you slow down", prefs.sndMissed) { prefs.sndMissed = it }
        Divider(); SwitchItem("Engine warmed up", initial = prefs.sndWarm) { prefs.sndWarm = it }
        Divider(); SwitchItem("Engine start when the app opens", "Plays with the opening animation; silent when the phone is on silent", prefs.openSound) { prefs.openSound = it }
        Divider()
        Text("Voice", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        Choice(listOf("off" to "Off", "emergency" to "Emergencies", "more" to "More"), prefs.voice) { prefs.voice = it }
        Text("Whose voice", style = MaterialTheme.typography.bodyLarge)
        var who by remember { mutableStateOf(prefs.voiceName) }
        Sounds.VOICES.forEach { (k, name, kind) ->
            Row(Modifier.fillMaxWidth().clickable { who = k; prefs.voiceName = k; sounds().loadVoice(k); sounds().say("Bike connected", 300, preview = true) },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(who == k, { who = k; prefs.voiceName = k; sounds().loadVoice(k); sounds().say("Bike connected", 300, preview = true) })
                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Divider()
        var open by remember { mutableStateOf(false) }
        Item("Hear the sounds", if (open) null else "${SOUND_DEMO.size} sounds", onClick = { open = !open }) {
            Text(if (open) "Hide" else "Show", color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.animation.AnimatedVisibility(open) { Column {
            SOUND_DEMO.forEach { (label, steps) ->
                Item(label, onClick = { val s = sounds(); steps.forEach { (n, d) -> s.play(n, d, preview = true) } }) {
                    Text("▶", color = MaterialTheme.colorScheme.primary)
                }
            }
        } }
        Hint("Short sounds, not sentences: music dips for a moment. Voice is kept for emergencies unless you pick More. " +
            "Sounds play only through an intercom, Bluetooth or wired earphones, never the phone speaker (the previews above are the exception).")
    }
}

@Composable
private fun HoldSection(prefs: Prefs) {
    var on by remember { mutableStateOf(prefs.holdAlerts) }
    Section("Calls and messages while riding") {
        SwitchItem("Hold them until I slow down", "Caller names and messages wait; you hear one chirp each when you slow down", on) { on = it; prefs.holdAlerts = it }
        androidx.compose.animation.AnimatedVisibility(on) { Column {
            SliderItem("Slow means below", prefs.slowKmh, 5..60, 5, " km/h") { prefs.slowKmh = it }
        } }
        Hint("Emergencies are never held.")
    }
}

/** Handlebar buttons: what each extra press does (Buttons.kt). Normal button jobs (ride mode, calls) never change. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ButtonsSection(prefs: Prefs) {
    Section("Handlebar buttons") {
        Hint("Tap any press below to choose what it does: songs, volume, map, dashboard, assistant or nothing.")
        Text("I button", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        ActionPick("Hold 2–3 s, then let go", prefs.actIHold) { prefs.actIHold = it }
        var a by remember { mutableStateOf(prefs.assist) }
        Text("Assistant", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("default" to "Phone's assistant (Google Assistant or Gemini)", "gemini" to "Gemini app", "none" to "Nothing").forEach { (k, label) ->
            Row(Modifier.fillMaxWidth().clickable { a = k; prefs.assist = k }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(a == k, { a = k; prefs.assist = k }); Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        SwitchItem("Show \"Listening\" on the bike", initial = prefs.vaFeedback) { prefs.vaFeedback = it }
        Hint("During a call: a tap rejects; hold about 2½ seconds and let go to answer. During a crash alert: hold to say you're OK.")
        Divider()

        var modeOn by remember { mutableStateOf(prefs.modeOn) }
        SwitchItem("Mode button extras", "One press still changes the ride mode", prefs.modeOn) { modeOn = it; prefs.modeOn = it }
        Appear(modeOn) {
            Column {
                ActionPick("Double press", prefs.actModeDouble) { prefs.actModeDouble = it }
                ActionPick("Hold", prefs.actModeLong) { prefs.actModeLong = it }
                Hint("Double press flips the mode and back (Urban → Rain → Urban), so you end in the same mode. Engine on only. Untested on the bike yet.")
            }
        }
        Divider()

        var starterOn by remember { mutableStateOf(prefs.starterOn) }
        SwitchItem("Starter button while riding", "The engine ignores it once running", prefs.starterOn) { starterOn = it; prefs.starterOn = it }
        Appear(starterOn) {
            Column {
                ActionPick("Hold about 3 s", prefs.actStarter1) { prefs.actStarter1 = it }
                ActionPick("Keep holding, about 6 s", prefs.actStarterLong) { prefs.actStarterLong = it }
                Hint("Counts only 5 s after the engine started, and never during a call. Press firmly: the bike reports the button " +
                    "a few times a second, so a very quick tap can be missed.")
            }
        }
        if (BikeService.running) {
            Divider()
            Text("Try an action now", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                BtnAction.entries.filter { it != BtnAction.NONE }.forEach { x ->
                    androidx.compose.material3.OutlinedButton(onClick = { Bike.service?.doAction(x, "settings try") }) { Text(x.label) }
                }
            }
        }
    }
}

@Composable
private fun ActionPick(title: String, initial: String, set: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    var open by remember { mutableStateOf(false) }
    Box {
        Item(title, BtnAction.of(v).label, onClick = { open = true })
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            BtnAction.entries.forEach { a ->
                androidx.compose.material3.DropdownMenuItem({ Text(a.label) }, onClick = { v = a.key; set(a.key); open = false })
            }
        }
    }
}

@Composable
private fun WarmSection(prefs: Prefs) {
    Section("Engine warm-up") {
        SwitchItem("Chime when the engine is warm", "After a cold start; the dashboard shows a warm-up bar", prefs.sndWarm) { prefs.sndWarm = it }
        Divider()
        SliderItem("Warm at", prefs.warmC, 40..80, 5, " °C") { prefs.warmC = it }
        Hint("A start counts as cold when the engine is more than 5 °C below this. On your rides it took about 9 minutes to go from cold to 60 °C.")
    }
}
