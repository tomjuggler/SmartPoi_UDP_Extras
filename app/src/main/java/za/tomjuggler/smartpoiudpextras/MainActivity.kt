package za.tomjuggler.smartpoiudpextras

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Streaming drives the POIs — the screen must never time out while the app is open
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PoiState.load(this)
        ServerBridge.load(this)
        setContent {
            SmartPoiApp()
        }
    }

    override fun onPause() {
        super.onPause()
        PoiState.save(this)
        ServerBridge.save(this)
    }
}

// ---------- flashy theme ----------
val NeonCyan = Color(0xFF00E5FF)
val NeonMagenta = Color(0xFFFF2D95)
val NeonYellow = Color(0xFFFFEA00)
val DarkBg = Color(0xFF0B0E14)

@Composable
fun SmartPoiApp() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NeonCyan,
            secondary = NeonMagenta,
            tertiary = NeonYellow,
            background = DarkBg,
            surface = Color(0xFF141824),
            surfaceVariant = Color(0xFF1B2130)
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppScaffold()
        }
    }
}

enum class Screen(val title: String, val icon: ImageVector) {
    Home("SmartPoi Extras", Icons.Filled.Bolt),
    ComputerGenerated("Computer Generated", Icons.Filled.Palette),
    ZapGame("Zap Game", Icons.Filled.Bolt),
    AudioReactive("Audio Reactive", Icons.Filled.GraphicEq),
    LightSaber("Light Saber", Icons.Filled.Bolt),
    ServerBridge("Server Bridge", Icons.Filled.Cloud),
    Settings("Settings", Icons.Filled.Settings)
}

@Composable
fun AppScaffold() {
    var screen by remember { mutableStateOf(Screen.Home) }

    UtilitiesDrawer(
        current = screen,
        onSelect = { screen = it }
    ) {
        Column(Modifier.fillMaxSize()) {
            // leaving a streaming screen cuts the UDP stream (LEDs OFF)
            DisposableEffect(screen) {
                onDispose {
                    PoiState.signalStop { }
                }
            }
            when (screen) {
                Screen.Home -> HomeMenu(onSelect = { screen = it })
                Screen.ComputerGenerated -> ComputerGeneratedScreen()
                Screen.ZapGame -> ZapGameScreen()
                Screen.AudioReactive -> AudioReactiveScreen()
                Screen.LightSaber -> LightSaberScreen()
                Screen.ServerBridge -> ServerBridgeScreen()
                Screen.Settings -> SettingsScreen()
            }

            // status bar at bottom
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF060810))
                    .padding(vertical = 6.dp, horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(PoiState.statusText, color = NeonCyan, fontSize = 13.sp)
                if (screen != Screen.Home && screen != Screen.Settings) {
                    AssistButton("Home") { screen = Screen.Home }
                }
            }
        }
    }
}

@Composable
fun AssistButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
        Text(label, fontSize = 13.sp)
    }
}

// ---------- HOME: menu of the four features ----------
@Composable
fun HomeMenu(onSelect: (Screen) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MenuCard("Computer Generated", "Animated plasma & shape patterns streamed live",
            listOf(NeonCyan, Color(0xFF2979FF))) { onSelect(Screen.ComputerGenerated) }
        MenuCard("Zap Game", "Fast colour zap races across both POIs",
            listOf(NeonMagenta, Color(0xFF7C4DFF))) { onSelect(Screen.ZapGame) }
        MenuCard("Audio Reactive", "Microphone volume drives the LED line",
            listOf(NeonYellow, Color(0xFFFF6D00))) { onSelect(Screen.AudioReactive) }
        MenuCard("Light Saber", "Colour triangle saber with swing sounds",
            listOf(Color(0xFF00E676), Color(0xFF00BFA5))) { onSelect(Screen.LightSaber) }
        MenuCard("Server Bridge", "Relay an LED stream from a network server to the POIs",
            listOf(Color(0xFF7C4DFF), NeonCyan)) { onSelect(Screen.ServerBridge) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { PoiState.primeForStreaming { PoiState.statusText = it } }) {
            Text("Prime POIs (LEDs OFF → UDP mode)")
        }
    }
}

@Composable
fun MenuCard(title: String, subtitle: String, colors: List<Color>, onClick: () -> Unit) {
    val border by animateColorAsState(colors[0], label = "border")
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(2.dp, border, RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(colors.map { it.copy(alpha = 0.15f) }))) {
            Column(Modifier.align(Alignment.CenterStart).padding(18.dp)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors[0])
                Spacer(Modifier.height(4.dp))
                Text(subtitle, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

// shared controls used by feature screens -------------------------------------
@Composable
fun SizeSelector(selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(36, 60, 72, 120).forEach { px ->
            FilterChip(
                selected = selected == px,
                onClick = { onPick(px); PoiState.pixelSize = px },
                label = { Text("${px}px") }
            )
        }
    }
}

/** Common ON/OFF strip: primes POIs then starts/stops a render loop. */
@Composable
fun PrimeAndStopRow(running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { PoiState.primeForStreaming { }; onStart() }, enabled = !running) {
            Text("ON")
        }
        Button(
            onClick = onStop, enabled = running,
            colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta)
        ) {
            Text("OFF")
        }
    }
}
