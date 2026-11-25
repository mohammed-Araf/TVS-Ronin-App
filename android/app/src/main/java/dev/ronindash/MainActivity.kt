package dev.ronindash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        Bike.theme.value = prefs.theme
        Bike.accent.value = prefs.accent
        if (prefs.keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Keep the bike link alive whenever a bike has been chosen.
        if (prefs.mac != null && !BikeService.running) try { BikeService.start(this) } catch (_: Exception) {}
        setContent { RoninTheme { App(startTab = if (prefs.mac == null) 3 else 0) } }
    }

    override fun onResume() {
        super.onResume()
        Bike.resumeTick.value++
    }
}

@Composable
fun RoninTheme(content: @Composable () -> Unit) {
    val mode by Bike.theme.collectAsState()
    val accentIdx by Bike.accent.collectAsState()
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val accent = Color(ACCENTS.getOrElse(accentIdx) { ACCENTS[0] }.second)
    // Calm neutral surfaces with a single accent colour.
    val scheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = Color.White, secondary = accent,
        background = Color(0xFF0F1012), surface = Color(0xFF0F1012),
        surfaceContainer = Color(0xFF18191C), surfaceContainerHigh = Color(0xFF222327), surfaceContainerHighest = Color(0xFF2A2B30),
        onBackground = Color(0xFFEDEDEF), onSurface = Color(0xFFEDEDEF), onSurfaceVariant = Color(0xFF9A9BA1),
        outline = Color(0xFF2E3035), outlineVariant = Color(0xFF26272B),
    ) else lightColorScheme(
        primary = accent, onPrimary = Color.White, secondary = accent,
        background = Color(0xFFF6F6F7), surface = Color(0xFFF6F6F7),
        surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF0F0F2), surfaceContainerHighest = Color(0xFFE8E8EB),
        onBackground = Color(0xFF16171A), onSurface = Color(0xFF16171A), onSurfaceVariant = Color(0xFF6B6D74),
        outline = Color(0xFFDCDDE1), outlineVariant = Color(0xFFE8E8EB),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(startTab: Int) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Ride") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Place, null) }, label = { Text("Trips") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Build, null) }, label = { Text("Test & log") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        // Gentle slide + fade between tabs, direction follows the tab order.
        androidx.compose.animation.AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { it / 8 * dir } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(200)) { -it / 8 * dir } +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)))
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
