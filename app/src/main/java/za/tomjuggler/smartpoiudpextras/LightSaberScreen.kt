package za.tomjuggler.smartpoiudpextras

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Light Saber — ported from udp_send_SmartPoi_8.pde.
 * ON sends an incremental line frame per LED (1, 1+2, 1+2+3, …): the lit bar
 * grows to cover the whole strip and stays lit. OFF is the same in reverse
 * (fully lit → one fewer LED per step → black). ON plays lightup.mp3, OFF plays
 * lightoff.mp3, swinging the phone (accelerometer) plays wave.mp3.
 */
@Composable
fun LightSaberScreen() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(Color(0, 0, 255)) }
    var mp by remember { mutableStateOf<MediaPlayer?>(null) }

    fun playSound(name: String) {
        mp?.release()
        try {
            val afd = ctx.assets.openFd(name)
            MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                prepare(); afd.close(); start()
            }.also { mp = it }
        } catch (e: Exception) {
            PoiState.statusText = "Audio: ${e.message}"
        }
    }

    // ON: grow a lit bar across each strip, one LED per step (1, 1+2, 1+2+3, …).
    // Same incremental line frames as the Zap Game, but cumulative — each frame
    // resends every LED lit so far, so the earlier ones stay lit. Datagrams are
    // sized per POI (firmware maps byte i -> LED i and needs exactly NUM_PX bytes).
    LaunchedEffect(on, color, PoiState.pixelSize, PoiState.poiSizes.toList()) {
        if (!on) return@LaunchedEffect
        val argb = color.toArgbInt()
        for ((size, ips) in poiGroupsBySize()) {
            launch(Dispatchers.IO) {
                val frame = IntArray(size)
                for (pos in 0 until size) {
                    frame[pos] = argb
                    PoiState.sendFrameTo(ips, frame, size, 1)
                    delay(20)  // Zap Game / original sketch cadence
                }
            }
        }
    }

    // accelerometer wave trigger — plays wave.mp3 when the phone is swung while ON
    // (threshold + 1s cooldown, same as the original sketch)
    DisposableEffect(on) {
        if (!on) return@DisposableEffect onDispose { }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        var lastWaveTime = 0L
        var prev = floatArrayOf(0f, 0f, 0f)
        val threshold = 2.0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values
                if (prev[0] == 0f && prev[1] == 0f && prev[2] == 0f) { prev = v.clone(); return }
                val movement = sqrt(
                    (v[0] - prev[0]) * (v[0] - prev[0]) +
                    (v[1] - prev[1]) * (v[1] - prev[1]) +
                    (v[2] - prev[2]) * (v[2] - prev[2])
                )
                val now = System.currentTimeMillis()
                if (movement > threshold && now - lastWaveTime > 1000) {
                    playSound("wave.mp3")
                    lastWaveTime = now
                }
                prev = v.clone()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { sm.unregisterListener(listener) }
    }

    // OFF: the reverse of ON — start fully lit and remove one LED per step
    // (full, full-1, … , black), then hand the POIs back to LEDs-OFF mode.
    var ramping by remember { mutableStateOf(false) }
    LaunchedEffect(ramping) {
        if (!ramping) return@LaunchedEffect
        val argb = color.toArgbInt()
        for ((size, ips) in poiGroupsBySize()) {
            launch(Dispatchers.IO) {
                val frame = IntArray(size) { argb }
                PoiState.sendFrameTo(ips, frame, size, 1)  // start fully lit
                delay(20)
                for (pos in size - 1 downTo 0) {
                    frame[pos] = 0
                    PoiState.sendFrameTo(ips, frame, size, 1)
                    delay(20)
                }
            }
        }
        ramping = false
        PoiState.signalStop { PoiState.statusText = it }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            if (on) "Light saber: ON" else "Light saber: OFF",
            color = if (on) Color(0xFF00E676) else Color(0xFFFF5252),
            style = MaterialTheme.typography.titleMedium
        )

        Text("Colour", color = NeonCyan)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Red" to Color(255, 0, 0), "Green" to Color(0, 255, 0),
                "Blue" to Color(0, 0, 255), "Yellow" to Color(255, 255, 0),
                "Cyan" to Color(0, 255, 255), "Magenta" to Color(255, 0, 255),
                "White" to Color.White
            ).forEach { (name, c) ->
                FilterChipLocal(name, color == c) { color = c }
            }
        }

        Text("Strip size", color = NeonCyan)
        SizeSelector(PoiState.pixelSize) { }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                PoiState.primeForStreaming { }
                PoiState.detectPoiSizes()  // learn each POI's NUM_PX → correctly-sized frames
                on = true
                playSound("lightup.mp3")
            }) { Text("ON") }
            Button(
                onClick = {
                    on = false
                    playSound("lightoff.mp3")
                    ramping = true // LEDs cycle down to 0 with the sound
                },
                enabled = on || ramping,
                colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta)
            ) { Text(if (ramping) "OFF…" else "OFF") }
        }

        Text(
            "Tip: swing sounds are triggered by the accelerometer — wave the phone while ON.",
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}

/** Configured POIs grouped by effective LED count (detected NUM_PX, else the
 *  global Settings default) so each strip gets correctly-sized datagrams. */
private fun poiGroupsBySize(): LinkedHashMap<Int, MutableList<String>> {
    val groups = LinkedHashMap<Int, MutableList<String>>()
    for (i in PoiState.poiIndices()) {
        groups.getOrPut(PoiState.sizeAt(i)) { mutableListOf() }.add(PoiState.poiIps[i])
    }
    return groups
}

/** Scale a colour toward black by [f] (0..1). */
fun dimColor(c: Color, f: Float): Int {
    val r = (c.red * 255 * f).toInt()
    val g = (c.green * 255 * f).toInt()
    val b = (c.blue * 255 * f).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
