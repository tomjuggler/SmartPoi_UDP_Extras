package za.tomjuggler.smartpoiudpextras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Zap Game — ported from ProcessingAndroidDemo's ZapGame.java.
 * Two big squares: magenta (POI 1) and cyan (POI 2). Tapping a square runs ONE
 * zap cycle on that POI: a bright pixel sweeps down the LED strip (one packet
 * per position, ~20ms apart), like the original drawColouredThing loop.
 * Tapping the other square mid-cycle switches to that POI.
 */
@Composable
fun ZapGameScreen() {
    val scope = rememberCoroutineScope()
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var activePoi by remember { mutableStateOf(0) } // 0=none 1=poi1 2=poi2
    var zapPos by remember { mutableIntStateOf(0) }
    var cycles by remember { mutableIntStateOf(0) }

    val size = PoiState.pixelSize.coerceAtLeast(16)

    // Prime both POIs into UDP mode when the screen opens (LEDs OFF -> UDP mode),
    // otherwise the tap zaps are sent but the POIs aren't listening on 2390.
    LaunchedEffect(Unit) {
        PoiState.primeForStreaming { PoiState.statusText = it }
    }

    fun runZap(poi: Int) {
        activeJob?.cancel()
        activePoi = poi
        activeJob = scope.launch {
            val ip = PoiState.ipAt(poi - 1)
            val zapColor = (if (poi == 1) Color(255, 0, 255) else Color(0, 255, 255)).toArgbInt()
            for (pos in 0 until size) {
                zapPos = pos
                // incremental single-pixel zap: only the pixel at `pos` is lit
                val frame = IntArray(size) { i ->
                    if (i == pos) zapColor else 0xFF000000.toInt()
                }
                launch(Dispatchers.IO) { PoiState.sendFrameTo(listOf(ip), frame, size, 1) }
                delay(20) // matches the original's delay(20)
            }
            cycles++
            activePoi = 0
            zapPos = 0
            PoiState.statusText = "Zap cycle $cycles complete on POI $poi"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Tap a square to zap one full LED cycle on that POI. " +
            "Tap the other square to switch mid-zap.",
            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp
        )

        Text("Strip size", color = NeonCyan)
        SizeSelector(PoiState.pixelSize) { }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ZapSquare(
                label = "POI 1\n${PoiState.ipAt(0)}",
                color = Color(255, 0, 255),
                active = activePoi == 1,
                progress = if (activePoi == 1) zapPos.toFloat() / size else 0f,
                modifier = Modifier.weight(1f)
            ) { runZap(1) }

            ZapSquare(
                label = "POI 2\n${PoiState.ipAt(1)}",
                color = Color(0, 255, 255),
                active = activePoi == 2,
                progress = if (activePoi == 2) zapPos.toFloat() / size else 0f,
                modifier = Modifier.weight(1f)
            ) { runZap(2) }
        }

        Button(
            onClick = {
                activeJob?.cancel()
                activePoi = 0
                PoiState.signalStop { PoiState.statusText = it }
            },
            enabled = activePoi != 0,
            colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta)
        ) { Text("STOP") }

        if (cycles > 0) {
            Text("Cycles completed: $cycles", color = NeonYellow, fontSize = 13.sp)
        }
    }
}

@Composable
fun ZapSquare(
    label: String,
    color: Color,
    active: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
    onTap: () -> Unit
) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) color.copy(alpha = 0.25f) else Color(0xFF141824))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(40f, 40f),
                style = Stroke(width = if (active) 12f else 5f)
            )
            if (active) {
                drawRect(
                    color = color,
                    topLeft = Offset(0f, size.height * 0.9f),
                    size = Size(size.width * progress, size.height * 0.1f)
                )
            }
        }
        Text(label, color = Color.White, fontSize = 16.sp, lineHeight = 20.sp)
    }
}
