package za.tomjuggler.smartpoiudpextras

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Global settings: POI IPs, strip size, stream FPS cap, Magic Poi login, POI on-board modes. */
@Composable
fun SettingsScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("POI addresses (AP mode)", color = NeonCyan)
        OutlinedTextField(
            value = PoiState.ip1,
            onValueChange = { PoiState.ip1 = it; PoiState.clearAddressCache() },
            label = { Text("POI 1 IP address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = PoiState.ip2,
            onValueChange = { PoiState.ip2 = it; PoiState.clearAddressCache() },
            label = { Text("POI 2 IP address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Default LED strip size", color = NeonCyan)
        SizeSelector(PoiState.pixelSize) { }

        Text("Stream frame-rate cap (0.5 – 10.0 fps)", color = NeonCyan)
        var fpsText by remember(PoiState.fpsCap) { mutableStateOf("%.2f".format(PoiState.fpsCap)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = fpsText,
                onValueChange = { v ->
                    fpsText = v
                    v.toFloatOrNull()?.let { f ->
                        if (f in 0.5f..10.0f) PoiState.fpsCap = f
                    }
                },
                label = { Text("FPS") },
                singleLine = true,
                modifier = Modifier.width(140.dp)
            )
            Text(
                if (PoiState.fpsCap in 0.5f..10.0f) "✓" else "enter 0.5–10.0",
                color = if (PoiState.fpsCap in 0.5f..10.0f) Color(0xFF00E676) else Color(0xFFFF5252),
                fontSize = 14.sp
            )
        }

        Text("UDP repeats per row (1 = once; higher only for lossy WiFi)", color = NeonCyan)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 3).forEach { n ->
                FilterChipLocal("$n×", PoiState.packetRepeat == n) { PoiState.packetRepeat = n }
            }
        }

        // ---------------- Magic Poi account (login persists; used by party tab) --
        MagicPoiAccountSection()

        Text("POI on-board modes (patternChooserChange)", color = NeonCyan)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("1", "2", "3").forEach { m ->
                Button(onClick = { sendMode(m) }) { Text("Mode $m") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("4", "5", "6").forEach { m ->
                Button(onClick = { sendMode(m) }) { Text("Mode $m") }
            }
        }
        Text(
            "1 Generated · 2 IMG 1-5 · 3 IMG 6-10 · 4 IMG 10-20 · 5 IMG 1-52 · 6 On/Off Switch",
            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { PoiState.primeForStreaming { PoiState.statusText = it } }) {
                Text("Prime (LEDs OFF → UDP)")
            }
            Button(onClick = { PoiState.signalStop { PoiState.statusText = it } }) {
                Text("LEDs OFF")
            }
        }

        Text(
            "Protocol: UDP port 2390, RRRGGGBB + 127 offset per pixel. " +
            "Priming uses HTTP patternChooserChange (7=LEDs OFF, 0=UDP mode) on port 80.",
            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp
        )
    }
}

/**
 * Magic Poi login lives here so the party tab never asks for credentials.
 * Login is persisted (prefs) and refreshed silently when the JWT expires —
 * the user logs in once, ever.
 */
@Composable
private fun MagicPoiAccountSection() {
    Text("Magic Poi account", color = NeonCyan)
    if (MagicPoi.token != null) {
        Text(
            "Signed in: ${MagicPoi.username}",
            color = Color(0xFF00E676), fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
        Text(
            "Join parties on the Magic Poi tab — no need to log in again.",
            color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp
        )
        OutlinedButton(onClick = { MagicPoi.logout() }) {
            Text("Log out")
        }
    } else {
        var user by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var stat by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val ctx = LocalContext.current

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // feedback line FIRST (never below a fillMaxSize child — off-screen bug)
            if (stat.isNotEmpty()) Text(stat, color = NeonYellow, fontSize = 13.sp)
            OutlinedTextField(
                value = MagicPoi.baseUrl,
                onValueChange = { MagicPoi.baseUrl = it.trim() },
                label = { Text("Server address (https://…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    if (user.isBlank() || pass.isBlank()) { stat = "Enter username and password"; return@Button }
                    busy = true
                    MagicPoi.login(user, pass) { busy = false; stat = it }
                }, enabled = !busy) { Text("Log in") }
                // Sign-up needs an invitation code — that only happens on the web
                // site (magicpoi.com/auth). This opens it in the browser; the API
                // /api/stream/signup must NOT be used here (it accepts any string).
                OutlinedButton(onClick = {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://magicpoi.com/auth/")))
                    } catch (e: Exception) {
                        stat = "No browser available: ${e.message}"
                    }
                }) { Text("Sign Up") }
            }
            Text(
                "Don't have an account yet? Sign Up (opens magicpoi.com — you'll need an invitation code).",
                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp
            )
        }
    }
}

private fun sendMode(m: String) {
    PoiState.statusText = "Sending mode $m to both POIs…"
    PoiState.sendRawPattern(m) { ok ->
        PoiState.statusText =
            if (ok) "Mode $m activated on both POIs"
            else "Mode $m failed — check POI WiFi"
    }
}


@Composable
fun FilterChipLocal(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
