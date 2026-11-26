package dev.ronindash

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One permission row: shared by first-time setup and Settings → Permissions. */
class PermItem(
    val name: String, val why: String, val required: Boolean,
    val runtime: Array<String> = emptyArray(), val settings: ((Context) -> Intent)? = null,
    val check: (Context) -> Boolean,
)

private fun granted(c: Context, vararg p: String) = p.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
private val isXiaomi = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

@SuppressLint("BatteryLife")
fun permItems(): List<PermItem> = listOfNotNull(
    PermItem("Nearby devices", "Talk to the bike", true, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) {
        granted(it, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) },
    PermItem("Location", "Trips, mileage, fuel stops, location in an SOS", true,
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { granted(it, Manifest.permission.ACCESS_FINE_LOCATION) },
    // Android asks for "all the time" separately, after the normal location permission.
    PermItem("Location, all the time", "So trips work when the app starts by itself", true, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
        granted(it, Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
    PermItem("Phone and contacts", "Caller name on the bike, answer with the I button, missed calls", true, arrayOf(
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CONTACTS)) {
        granted(it, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CONTACTS) },
    PermItem("Notification access", "WhatsApp, SMS and Google Maps turns on the bike", true, settings = { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) {
        it.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(it, NotifListener::class.java)) },
    PermItem("Notifications", "Service reminders only", true, arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { granted(it, Manifest.permission.POST_NOTIFICATIONS) },
    PermItem("Battery: no restrictions", "Lets Android wake the app when the bike turns on", true,
        settings = { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}")) }) {
        it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
    PermItem("SMS and calls for SOS", "Crash alert to your contacts (optional)", false, arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE)) {
        granted(it, Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE) },
    PermItem("Display over other apps", "Assistant and crash alert from your pocket (optional)", false,
        settings = { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${it.packageName}")) }) { Settings.canDrawOverlays(it) },
    if (isXiaomi) PermItem("Autostart (Xiaomi, Redmi, POCO)", "App info → Autostart on, Battery saver → No restrictions", true,
        settings = { Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${it.packageName}")) }) {
        it.getSharedPreferences("ronin", Context.MODE_PRIVATE).getBoolean("xiaomiOpened", false) } else null,
)

/** The permission list with Allow per row and Allow all. [tick] changes whenever the app resumes, so rows refresh. */
@Composable
fun PermissionRows(tick: Int, rowDivider: @Composable () -> Unit = {}, row: @Composable (PermItem, Boolean, () -> Unit) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    @Suppress("UNUSED_VARIABLE") val t = tick + refresh
    val items = permItems()
    fun grant(p: PermItem) {
        if (p.runtime.isNotEmpty()) {
            if (Manifest.permission.ACCESS_BACKGROUND_LOCATION in p.runtime && !granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION))
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else launcher.launch(p.runtime)
        } else p.settings?.let {
            if (p.name.startsWith("Autostart")) ctx.getSharedPreferences("ronin", Context.MODE_PRIVATE).edit().putBoolean("xiaomiOpened", true).apply()
            try { ctx.startActivity(it(ctx)) } catch (_: Exception) {}
        }
        refresh++
    }
    items.forEachIndexed { i, p -> if (i > 0) rowDivider(); row(p, p.check(ctx)) { grant(p) } }
    // Allow all: every runtime permission in one request (background location needs a second round), then the first missing system screen.
    return {
        val runtime = items.filter { !it.check(ctx) && it.runtime.isNotEmpty() && Manifest.permission.ACCESS_BACKGROUND_LOCATION !in it.runtime }
            .flatMap { it.runtime.toList() }
        when {
            runtime.isNotEmpty() -> launcher.launch(runtime.toTypedArray())
            else -> items.firstOrNull { !it.check(ctx) }?.let { grant(it) }
        }
    }
}

/**
 * First-time setup (mock FIX 30), one flow and then never again: what it does → find the bike (Android's companion-device dialog
 * + the code on the bike display) → every permission on one screen → done → opening animation → dashboard.
 */
@Composable
fun SetupFlow(done: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val resume by Bike.resumeTick.collectAsState()
    var step by remember { mutableIntStateOf(0) }
    var finding by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        finding = false
        if (r.resultCode == android.app.Activity.RESULT_OK && CompanionLink.onChosen(ctx, r.data) != null) { err = null; step = 2 }
        else err = "No bike chosen. Try again, or skip and choose it later in Settings."
    }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { i -> Box(Modifier.width(if (i == step) 22.dp else 8.dp).height(8.dp).clip(CircleShape)
                .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) }
        }
        Spacer(Modifier.height(24.dp))
        AnimatedContent(step, transitionSpec = { (slideInHorizontally { it / 6 } + fadeIn()) togetherWith fadeOut() }, label = "setup",
            modifier = Modifier.weight(1f)) { st ->
            Column(Modifier.fillMaxSize()) {
                when (st) {
                    0 -> {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Mark(150.dp, MaterialTheme.colorScheme.onBackground, MaterialTheme.colorScheme.primary)
                        }
                        H("Set up once")
                        P("After this Ronin Dash connects by itself every time you turn the bike on, like your intercom. You never have to open it. Nothing runs while the bike is off.")
                        Big("Start") { step = 1 }
                    }
                    1 -> {
                        H("Find your bike")
                        P("Close TVS Connect. Turn the ignition on. On the bike: long-press Mode → CLOCK SETTING → press Mode once. A code shows on the bike; type it when Android asks.")
                        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
                        Spacer(Modifier.weight(1f))
                        Big(if (finding) "Searching…" else "Find my bike", enabled = !finding) {
                            if (!granted(ctx, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) {
                                btPerm.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)); return@Big
                            }
                            finding = true
                            CompanionLink.associate(ctx, { chooser.launch(IntentSenderRequest.Builder(it).build()) }) { e -> finding = false; err = e }
                        }
                        androidx.compose.material3.TextButton(onClick = { step = 2 }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text(if (prefs.mac != null) "Use the bike I already paired" else "Skip for now")
                        }
                    }
                    2 -> {
                        H("Allow once")
                        P("So it can work without you opening it. Tap Allow on each, or Allow all.")
                        Spacer(Modifier.height(8.dp))
                        var allowAll: () -> Unit = {}
                        var allOk = true
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            allowAll = PermissionRows(resume) { p, ok, grant ->
                                if (p.required && !ok) allOk = false
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, fontWeight = FontWeight.SemiBold)
                                        Text(p.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (ok) Text("✓", color = Color(0xFF1FA463), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    else FilledTonalButton(onClick = grant) { Text("Allow") }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                            OutlinedButton(onClick = { allowAll() }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Allow all") }
                            Button(onClick = { step = 3 }, modifier = Modifier.weight(1f).height(52.dp)) { Text(if (allOk) "Continue" else "Later") }
                        }
                    }
                    else -> {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("✓", fontSize = 72.sp, color = Color(0xFF1FA463)) }
                        H("All set")
                        P("Turn the bike off and on: it connects in a few seconds with the app closed. Open the app only when you want to look at trips or change settings. The only notification you'll get is when service is due.")
                        Big("Done") {
                            prefs.setupDone = true
                            prefs.mac?.let { CompanionLink.observe(ctx, it) }
                            done()
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun H(t: String) = Text(t, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
@Composable private fun P(t: String) = Text(t, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 10.dp))
@Composable private fun Big(t: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 0.dp)) { Text(t, fontSize = 17.sp) }

