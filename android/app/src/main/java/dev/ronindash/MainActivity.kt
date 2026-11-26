package dev.ronindash

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

class MainActivity : ComponentActivity(), SensorEventListener {
    companion object { const val BY_BIKE = "byBike" }

    private lateinit var prefs: Prefs
    private val screenState = MutableStateFlow("normal")
    private var byBike = false
    private val sm by lazy { getSystemService(SensorManager::class.java) }
    private var lux = -1f
    private var near = false
    private var sunS = 0
    private var shadeS = 0
    private var thermal = PowerManager.THERMAL_STATUS_NONE
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { thermal = it; applyScreen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        Bike.theme.value = prefs.theme
        Bike.accent.value = prefs.accent
        // nothing on these screens needs 120 Hz
        window.attributes = window.attributes.apply { preferredRefreshRate = 60f }
        onIntent(intent)
        // Bike already on but the service isn't running (no companion association yet, or first launch after an update)
        if (prefs.setupDone && prefs.mac != null && !BikeService.running) BikeService.start(this)
        prefs.mac?.let { if (prefs.setupDone && !CompanionLink.isAssociated(this)) Bike.log("[companion] not associated yet: Settings → Bike → Let Android wake the app") }
        val fromIcon = savedInstanceState == null && (intent?.action == Intent.ACTION_MAIN || byBike)
        setContent { RoninTheme { Root(showOpening = fromIcon, needsSetup = !prefs.setupDone, screen = screenState) } }
        lifecycleScopeLaunch()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); onIntent(intent) }

    /** Opened by the bike (phone on the mount): over the lock screen, and it closes again when the bike turns off. */
    private fun onIntent(i: Intent?) {
        if (i?.getBooleanExtra(BY_BIKE, false) == true) { byBike = true; setShowWhenLocked(true); setTurnScreenOn(true) }
    }

    private fun lifecycleScopeLaunch() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Bike.closeDash.collect { if (it && byBike) { byBike = false; finish() } } }
                launch {
                    Bike.conn.collect { c ->
                        // keep the screen on only while the dashboard is open and the bike is on
                        if (prefs.keepScreenOn && c.phase == Phase.LIVE) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Bike.resumeTick.value++
        // the phone's light sensor (already on for its own auto-brightness) + proximity: sun on the screen, or in a pocket
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 500_000) }
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 500_000) }
        val pm = getSystemService(PowerManager::class.java)
        thermal = pm.currentThermalStatus
        pm.addThermalStatusListener(mainExecutor, thermalListener)
        screenTick.run()
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
        getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
        window.decorView.removeCallbacks(screenTick)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_LIGHT -> lux = if (lux < 0) e.values[0] else lux * .6f + e.values[0] * .4f
            Sensor.TYPE_PROXIMITY -> near = e.values[0] < (e.sensor.maximumRange).coerceAtMost(3f)
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /**
     * Sun and heat (mock FIX 24). Sun straight on the screen (~40,000+ lux for 5 s) can't be read even at full brightness, and
     * full brightness in the sun is what overheats phones. Default: the screen rests (black, lowest brightness) and comes back
     * after 10 s of less light; sounds and the bike display carry on. "Fight direct sunlight" = full brightness instead. Hot phone
     * (thermal "severe") = rest anyway; "moderate" = brightness capped. In a pocket (proximity covered + dark) = screen off.
     */
    private val screenTick = object : Runnable {
        override fun run() {
            window.decorView.postDelayed(this, 1000)
            val direct = lux >= 40_000
            sunS = if (direct) sunS + 1 else 0
            shadeS = if (!direct) shadeS + 1 else 0
            val cur = screenState.value
            val want = when {
                near && lux in 0f..5f -> "off"
                thermal >= PowerManager.THERMAL_STATUS_SEVERE -> "rest-hot"
                sunS >= 5 && !prefs.fightSun -> "rest"
                (cur == "rest" || cur == "rest-hot") && shadeS < 10 && thermal < PowerManager.THERMAL_STATUS_SEVERE -> cur
                direct && prefs.fightSun -> "max"
                lux >= 12_000 -> "bright"
                else -> "normal"
            }
            if (want != cur) { screenState.value = want; Bike.log("[screen] $cur → $want (light ${lux.toInt()} lux, thermal $thermal)"); applyScreen() }
        }
    }

    private fun applyScreen() {
        val st = screenState.value
        var b = when (st) {
            "off", "rest", "rest-hot" -> 0.01f
            "max" -> 1f
            "bright" -> .9f
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (thermal >= PowerManager.THERMAL_STATUS_MODERATE && b > .6f) b = .6f
        window.attributes = window.attributes.apply { screenBrightness = b }
    }
}

@Composable
fun RoninTheme(content: @Composable () -> Unit) {
    val mode by Bike.theme.collectAsState()
    val accentIdx by Bike.accent.collectAsState()
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val accent = Color(ACCENTS.getOrElse(accentIdx) { ACCENTS[0] }.second)
    // Calm neutral surfaces with a single accent colour. surfaceVariant and secondaryContainer are set too, so nothing falls back
    // to Material's default purple (tab highlight, tonal buttons, the theme switch background).
    val scheme = if (dark) {
        val bg = Color(0xFF0F1012)
        darkColorScheme(
            primary = accent, onPrimary = Color.White, secondary = accent,
            secondaryContainer = accent.copy(alpha = .22f).compositeOver(bg), onSecondaryContainer = Color(0xFFEDEDEF),
            background = bg, surface = bg, surfaceVariant = Color(0xFF222327), onSurfaceVariant = Color(0xFF9A9BA1),
            surfaceContainer = Color(0xFF18191C), surfaceContainerHigh = Color(0xFF222327), surfaceContainerHighest = Color(0xFF2A2B30),
            onBackground = Color(0xFFEDEDEF), onSurface = Color(0xFFEDEDEF),
            outline = Color(0xFF2E3035), outlineVariant = Color(0xFF26272B),
        )
    } else {
        val bg = Color(0xFFF6F6F7)
        lightColorScheme(
            primary = accent, onPrimary = Color.White, secondary = accent,
            secondaryContainer = accent.copy(alpha = .16f).compositeOver(Color.White), onSecondaryContainer = Color(0xFF16171A),
            background = bg, surface = bg, surfaceVariant = Color(0xFFEDEDF0), onSurfaceVariant = Color(0xFF6B6D74),
            surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF0F0F2), surfaceContainerHighest = Color(0xFFE8E8EB),
            onBackground = Color(0xFF16171A), onSurface = Color(0xFF16171A),
            outline = Color(0xFFDCDDE1), outlineVariant = Color(0xFFE8E8EB),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun Root(showOpening: Boolean, needsSetup: Boolean, screen: MutableStateFlow<String>) {
    var setup by remember { mutableStateOf(needsSetup) }
    var opening by rememberSaveable { mutableStateOf(showOpening && !needsSetup) }
    val scr by screen.collectAsState()
    // Surface sets the default text colour to the theme's (otherwise text with no colour of its own is black on dark)
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = MaterialTheme.colorScheme.onBackground) { Box(Modifier.fillMaxSize()) {
        when {
            setup -> SetupFlow { setup = false; opening = true }
            else -> App(startTab = 0)
        }
        AnimatedVisibility(opening, enter = fadeIn(tween(1)), exit = fadeOut(tween(500))) { Opening { opening = false } }
        // resting screen: black (lowest brightness); a small sun or thermometer shows why
        AnimatedVisibility(scr == "rest" || scr == "rest-hot" || scr == "off", enter = fadeIn(tween(600)), exit = fadeOut(tween(900))) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                if (scr != "off") Ic(if (scr == "rest-hot") "thermo" else "speedo", Color(0xFF3A3B40), 34.dp)
            }
        }
    }}
}

@Composable
fun App(startTab: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    val mode by Bike.mode.collectAsState()
    val resume by Bike.resumeTick.collectAsState()
    var revealUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(revealUntil) { while (System.currentTimeMillis() < revealUntil) { delay(500); now = System.currentTimeMillis() }; now = System.currentTimeMillis() }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val night = remember(now, resume) { nightActive(ctx, prefs) }
    val revealing = now < revealUntil
    // the focus screens (riding and fast, day and night; parked at night) vs the full dashboard (parked in the day, or swiped up)
    val focus = tab == 0 && !revealing && (night || mode != RideMode.PARK)
    AnimatedContent(focus, transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(400)) }, label = "focus") { f ->
        if (f) FocusScreen(mode, night) { ms -> revealUntil = System.currentTimeMillis() + ms; now = System.currentTimeMillis() }
        else Tabs(tab) { tab = it }
    }
}

/** Night look: "Always", or "At night" from sunset to sunrise where you are (last known location; 6:30–18:30 without one). */
private fun nightActive(ctx: android.content.Context, prefs: Prefs): Boolean = when (prefs.night) {
    "always" -> true
    "night" -> {
        val l = try { ctx.getSystemService(LocationManager::class.java).getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (_: SecurityException) { null }
        Sun.isNight(l?.latitude, l?.longitude)
    }
    else -> false
}

@Composable
private fun Tabs(tab: Int, setTab: (Int) -> Unit) {
    val ind = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.onSurface)
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(tab == 0, { setTab(0) }, { Icon(Icons.Filled.Home, null) }, label = { Text("Ride") }, colors = ind)
                NavigationBarItem(tab == 1, { setTab(1) }, { Icon(Icons.Filled.Place, null) }, label = { Text("Trips") }, colors = ind)
                NavigationBarItem(tab == 2, { setTab(2) }, { Icon(Icons.Filled.Build, null) }, label = { Text("Test & log") }, colors = ind)
                NavigationBarItem(tab == 3, { setTab(3) }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") }, colors = ind)
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        // Gentle slide + fade between tabs, direction follows the tab order.
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (androidx.compose.animation.slideInHorizontally(tween(260)) { it / 8 * dir } + fadeIn(tween(260))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(tween(200)) { -it / 8 * dir } + fadeOut(tween(160)))
            }, label = "tabs",
        ) { t ->
            when (t) {
                0 -> RideScreen(m)
                1 -> TripsScreen(m)
                2 -> TestScreen(m)
                else -> SettingsScreen(m)
            }
        }
    }
}
