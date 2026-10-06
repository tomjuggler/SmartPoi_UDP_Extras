package za.tomjuggler.smartpoiudpextras

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Audio Reactive — ported from udp_send_SmartPoi_8_sound_activation.pde.
 * Microphone RMS volume drives a centred gradient line streamed to both POIs,
 * with an adjustable gain slider (0.1–5.0).
 */
@SuppressLint("MissingPermission")
@Composable
fun AudioReactiveScreen() {
    var running by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(0f) }
    var gain by remember { mutableFloatStateOf(1.0f) }
    var hasPermission by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    LaunchedEffect(Unit) {
        if (!hasPermission) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(running, hasPermission) {
        if (running && hasPermission) {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, 44100,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 8192
            )
            recorder.startRecording()
            val buf = ShortArray(2048)
            // Capture + RMS + UDP all run on a background dispatcher: recorder.read()
            // BLOCKS until a buffer fills (~46 ms) and the old code ran it (and the
            // per-sample RMS loop) on the main thread, freezing the UI. Sends happen
            // inline here — no per-buffer coroutine spawn.
            withContext(Dispatchers.Default) {
                try {
                    while (running) {
                        val n = recorder.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        var sum = 0L
                        for (i in 0 until n) sum += buf[i] * buf[i]
                        val rms = kotlin.math.sqrt(sum / n.toDouble())
                        val v = ((rms / 32767.0).coerceIn(0.0, 1.0)).toFloat()
                        volume = v
                        val eff = (v * gain).coerceIn(0f, 1f)
                        val size = PoiState.pixelSize.coerceAtLeast(16)
                        PoiState.sendFrame(volumeLine(eff, size), size, 1)
                    }
                } finally {
                    recorder.stop(); recorder.release()
                }
            }
        } else {
            volume = 0f
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Microphone volume lights the LED line from centre outwards.", color = Color.White.copy(alpha = 0.7f))
        Text("Volume: ${"%.1f".format(volume * 100)}%", color = NeonYellow)

        Text("Gain: ${"%.1f".format(gain)}", color = NeonCyan)
        Slider(value = gain, onValueChange = { gain = it }, valueRange = 0.1f..5.0f)

        SizeSelector(PoiState.pixelSize) { }

        PrimeAndStopRow(
            running = running && hasPermission,
            onStart = { running = true },
            onStop = {
                running = false
                PoiState.signalStop { PoiState.statusText = it }
            }
        )

        // live preview of the LED line
        Canvas(Modifier.fillMaxWidth().height(60.dp)) {
            drawRect(Color.Black)
            val lit = (volume * gain).coerceIn(0f, 1f) * size.width
            if (lit > 0) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Yellow, Color.Red)),
                    topLeft = Offset((size.width - lit) / 2, 0f),
                    size = androidx.compose.ui.geometry.Size(lit, size.height)
                )
            }
        }
    }
}

/** Centred green→yellow→red gradient line matching the original sketch. */
fun volumeLine(vol: Float, width: Int): IntArray {
    val out = IntArray(width)
    var lit = (vol * width).toInt()
    if (vol > 0 && lit < 2) lit = 2
    lit = lit.coerceIn(0, width)
    if (lit == 0) return out
    val start = (width - lit) / 2
    val center = (width - 1) / 2.0
    val half = width / 2.0
    for (i in 0 until lit) {
        val x = start + i
        val d = abs(x - center) / half
        out[x] = when {
            d <= 0.5 -> lerpRgb(0x0000FF00, 0x00FFFF00, d / 0.5)   // green→yellow
            else -> lerpRgb(0x00FFFF00, 0x00FF0000, (d - 0.5) / 0.5) // yellow→red
        } or 0xFF000000.toInt()
    }
    return out
}

private fun lerpRgb(a: Int, b: Int, t: Double): Int {
    val r = (a shr 16 and 0xFF) + ((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * t
    val g = (a shr 8 and 0xFF) + ((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * t
    val bl = (a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * t
    return (r.toInt() shl 16) or (g.toInt() shl 8) or bl.toInt()
}
