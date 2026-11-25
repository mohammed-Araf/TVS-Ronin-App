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
            Item("Connection", if (BikeService.running) "Running in the background" else "Stopped") {
                if (BikeService.running) OutlinedButton(onClick = { BikeService.stop(ctx); refresh++ }) { Text("Stop") }
                else Button(onClick = { BikeService.start(ctx); refresh++ }) { Text("Start") }
            }
        }

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

        Section("I button") {
            var a by remember { mutableStateOf(prefs.assist) }
            Text("Hold 2–3 seconds, then let go", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
            listOf("default" to "Phone's assistant (Google Assistant or Gemini)", "gemini" to "Gemini app", "none" to "Nothing").forEach { (k, label) ->
                Row(Modifier.fillMaxWidth().clickable { a = k; prefs.assist = k }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(a == k, { a = k; prefs.assist = k }); Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Divider()
            SwitchItem("Show \"Listening\" on the bike", initial = prefs.vaFeedback) { prefs.vaFeedback = it }
            Hint("During a call: a tap rejects; hold about 2½ seconds and let go to answer. Holding much longer does nothing. Nothing starts on its own when you connect.")
        }

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
            Hint("Mileage is estimated from speed (60 km/h ≈ 44 km/l, 120 km/h ≈ 32 km/l) and RPM. " +
                "Learned top-gear ratio: %.0f rpm per km/h.".format(prefs.rpmPerKmhTop))
        }

        PermissionsSection(perm::launch)

        Section("App") {
            SwitchItem("Start after phone restart", initial = prefs.autoStart) { prefs.autoStart = it }
            Divider(); SwitchItem("Keep a log file", "For testing and bug reports", prefs.fileLog) { prefs.fileLog = it }
            Divider(); Item("Version", "Ronin Dash 0.4 · unofficial, not affiliated with TVS Motor")
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
            Divider(); SwitchItem("Beep and vibrate on the phone", initial = prefs.speedBeep) { prefs.speedBeep = it }
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
private fun PermissionsSection(launch: (Array<String>) -> Unit) {
    val ctx = LocalContext.current
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val pm = ctx.getSystemService(PowerManager::class.java)
    fun open(i: Intent) = try { ctx.startActivity(i) } catch (_: Exception) {}
    Section("Permissions") {
        Perm("Notifications", "Connection status while riding", granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) { launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
        Divider(); Perm("Location", "Trips and the location in SOS messages", granted(ctx, *LOC)) { launch(LOC) }
        Divider(); Perm("Calls and contacts", "Caller name, answer/reject with I, missed calls", granted(ctx, *PHONE)) { launch(PHONE) }
        Divider(); Perm("SMS and calls for SOS", "Sends the emergency message", granted(ctx, *SOS)) { launch(SOS) }
        Divider(); Perm("Notification access", "SMS, WhatsApp and Google Maps turns",
            nm.isNotificationListenerAccessGranted(ComponentName(ctx, NotifListener::class.java))) { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        Divider(); Perm("Display over other apps", "Opens the assistant and crash alert from the pocket", Settings.canDrawOverlays(ctx)) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
        Divider(); Perm("No battery limits", "Keeps the connection alive", pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            @SuppressLint("BatteryLife") val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            open(i)
        }
        Divider()
        Item("Xiaomi / Redmi", "App info → Autostart on, Battery saver → No restrictions",
            onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }) {
            Text("Open", color = MaterialTheme.colorScheme.primary)
        }
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
