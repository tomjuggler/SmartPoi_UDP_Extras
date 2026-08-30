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
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("POI network — up to 8 POIs (blank = not sent to)", color = NeonCyan)

        // Router IP + Discover: probe the /24 subnet for real POIs, mirroring the
        // main control app's fastScanNetwork (GET /poi-available per host).
        var routerText by remember { mutableStateOf(PoiState.routerIp) }
        var scanning by remember { mutableStateOf(false) }
        var discoverMsg by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = routerText,
                onValueChange = { routerText = it },
                label = { Text("Router IP (subnet for Discover)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = {
                val detected = PoiState.detectRouterIp(ctx)
                if (detected != null) {
                    routerText = detected
                    PoiState.routerIp = detected
                    discoverMsg = "Router IP detected: $detected"
                } else {
                    discoverMsg = "Couldn't detect a router IP — enter it manually"
                }
            }) { Text("Detect") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val r = routerText.trim()
                    if (!PoiState.isValidIp(r)) {
                        discoverMsg = "Invalid router IP — use e.g. 192.168.1.1"
                        return@Button
                    }
                    PoiState.routerIp = r
                    scanning = true
                    discoverMsg = "Scanning ${r.substringBeforeLast(".")}.1–254…"
                    PoiState.discoverPois(
                        r,
                        onProgress = { scanned -> discoverMsg = "Scanning… $scanned/254" },
                        onResult = { found ->
                            scanning = false
                            if (found.isEmpty()) {
                                discoverMsg = "No POIs found — enter IPs manually below"
                            } else {
                                found.take(PoiState.MAX_POIS).forEachIndexed { i, ip -> PoiState.setPoiIp(i, ip) }
                                for (i in found.size until PoiState.MAX_POIS) PoiState.setPoiIp(i, "")
                                PoiState.clearAddressCache()
                                discoverMsg = "Discovered ${found.size} POI(s): ${found.joinToString(", ")}"
                            }
                        }
                    )
                },
                enabled = !scanning
            ) { Text(if (scanning) "Scanning…" else "Discover") }
        }
        if (discoverMsg.isNotEmpty()) {
            Text(discoverMsg, color = NeonYellow, fontSize = 13.sp)
        }

        // 8 POI slots in a compact 2-column grid; empty slots are simply skipped.
        // Label shows the per-POI detected LED size (via /get-pixels) when known.
        for (pair in 0 until PoiState.MAX_POIS step 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = PoiState.poiIps[pair],
                    onValueChange = { PoiState.setPoiIp(pair, it) },
                    label = { Text(poiLabel(pair)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                if (pair + 1 < PoiState.MAX_POIS) {
                    OutlinedTextField(
                        value = PoiState.poiIps[pair + 1],
                        onValueChange = { PoiState.setPoiIp(pair + 1, it) },
                        label = { Text(poiLabel(pair + 1)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Text(
            "Streaming, priming and on-board modes are sent to every POI with a valid IP — leave a field blank to skip that POI.",
            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp
        )

        // Per-POI LED size detection (firmware GET /get-pixels, as in the Cordova
        // SmartPoi_Controls app). Detected sizes persist per POI and drive the
        // multi-size party streams (one sized feed per distinct size).
        var sizeMsg by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    PoiState.detectPoiSizes(force = true) { sizeMsg = it }
                },
                enabled = PoiState.poiIndices().isNotEmpty()
            ) { Text("Detect POI sizes") }
            if (sizeMsg.isNotEmpty()) {
                Text(sizeMsg, color = NeonYellow, fontSize = 13.sp)
            }
        }
        Text(
            "Auto-detected: " + (
                PoiState.poiIndices().map { i -> "POI ${i + 1}: ${PoiState.poiSizes[i]}px" }
                    .joinToString(", ").ifEmpty { "none yet" }
                ),
            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp
        )

        Text("Default LED strip size (fallback when a POI's size wasn't detected)", color = NeonCyan)
        SizeSelector(PoiState.pixelSize) { }

        Text("Stream frame-rate cap (0.5 – 30.0 fps)", color = NeonCyan)
        var fpsText by remember(PoiState.fpsCap) { mutableStateOf("%.2f".format(PoiState.fpsCap)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = fpsText,
                onValueChange = { v ->
                    fpsText = v
                    v.toFloatOrNull()?.let { f ->
                        if (f in 0.5f..30.0f) PoiState.fpsCap = f
                    }
                },
                label = { Text("FPS") },
                singleLine = true,
                modifier = Modifier.width(140.dp)
            )
            Text(
                if (PoiState.fpsCap in 0.5f..30.0f) "✓" else "enter 0.5–30.0",
                color = if (PoiState.fpsCap in 0.5f..30.0f) Color(0xFF00E676) else Color(0xFFFF5252),
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
                value = MagicPoi.serverHost,
                onValueChange = { MagicPoi.serverHost = it.trim() },
                label = { Text("Server host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            // Raw-text state so out-of-range intermediates don't fight the keyboard.
            var portText by remember { mutableStateOf(MagicPoi.serverPort.toString()) }
            OutlinedTextField(
                value = portText,
                onValueChange = { v ->
                    portText = v
                    v.toIntOrNull()?.let { if (it in 1..65535) MagicPoi.serverPort = it }
                },
                label = { Text("Server port (default 80)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            var udpText by remember { mutableStateOf(MagicPoi.udpPort.toString()) }
            OutlinedTextField(
                value = udpText,
                onValueChange = { v ->
                    udpText = v
                    v.toIntOrNull()?.let { if (it in 1..65535) MagicPoi.udpPort = it }
                },
                label = { Text("UDP port (default 2393)") },
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

/** Settings field label: POI number + detected LED size when known. */
private fun poiLabel(index: Int): String {
    val sz = PoiState.poiSizes[index]
    return "POI ${index + 1}" + if (sz in 16..120) " (${sz}px)" else ""
}

private fun sendMode(m: String) {
    val n = PoiState.configuredIps().size
    if (n == 0) {
        PoiState.statusText = "No POI IPs configured — add them above"
        return
    }
    PoiState.statusText = "Sending mode $m to $n POI(s)…"
    PoiState.sendRawPattern(m) { ok ->
        PoiState.statusText =
            if (ok) "Mode $m activated on $n POI(s)"
            else "Mode $m failed — check POI WiFi"
    }
}


@Composable
fun FilterChipLocal(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
