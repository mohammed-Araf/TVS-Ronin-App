package dev.ronindash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Full-screen "Are you OK?" countdown, shown over the lock screen. */
class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        setContent {
            val left by Bike.sos.collectAsState()
            val reason by Bike.sosReason.collectAsState()
            LaunchedEffect(left) { if (left == null) finish() }
            Column(
                Modifier.fillMaxSize().background(Color(0xFF3A0B08)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Crash detected", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(reason, color = Color(0xFFFFC9C2), textAlign = TextAlign.Center)
                Text("${left ?: 0}", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Bold)
                Text("SOS with your location goes to your emergency contacts when this reaches 0. " +
                    "Hold the I button for 2–3 seconds and let go, or tap below, if you're OK.", color = Color.White, textAlign = TextAlign.Center)
                Button(
                    onClick = { Bike.service?.crash?.cancel("rider (phone)"); finish() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5CC27C), contentColor = Color.Black),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                ) { Text("I'M OK", fontSize = 36.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = { Bike.service?.crash?.sendNow(); finish() }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text("Send SOS now", color = Color.White, fontSize = 20.sp)
                }
            }
        }
    }
}
