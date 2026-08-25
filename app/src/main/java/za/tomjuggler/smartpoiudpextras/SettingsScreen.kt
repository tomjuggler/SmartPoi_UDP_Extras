package za.tomjuggler.smartpoiudpextras

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Global settings: POI IPs, strip size, stream FPS cap, and POI on-board modes 1-6. */
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
