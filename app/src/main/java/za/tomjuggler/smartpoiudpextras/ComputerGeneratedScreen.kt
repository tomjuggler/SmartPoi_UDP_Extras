package za.tomjuggler.smartpoiudpextras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Computer Generated — shapes (triangle, twin-triangle, circle, star, spiral,
 * diamond, rings, wave, zigzag, checker, diamonds, tri-tile) masked over
 * animated Perlin-noise / arc-noise / plasma fills.
 * Background is always pure black; only the shape shows the pattern.
 */
@Composable
fun ComputerGeneratedScreen() {
    var running by remember { mutableStateOf(false) }
    var shape by remember { mutableStateOf(0) }
    var fill by remember { mutableStateOf(0) } // 0=perlin 1=arcnoise 2=plasma
    var playAll by remember { mutableStateOf(false) }
    var fps by remember { mutableStateOf(0f) }

    // latest rendered frame, shared between the render thread and the UI preview
    var previewFrame by remember { mutableStateOf<IntArray?>(null) }
    var previewSize by remember { mutableStateOf(36) }
    var previewShape by remember { mutableStateOf(0) }
    var previewFill by remember { mutableStateOf(0) }

    val shapeNames = listOf(
        "Big Tri", "Twin Tri", "Inv Tri", "Circle", "Star", "Spiral", "Diamond", "Rings",
        "Wave", "Zigzag", "Checker", "Diamonds", "Tri Tile"
    )
    val fillNames = listOf("Perlin", "Arc Noise", "Plasma")

    // render loop — fully on background thread
    LaunchedEffect(running, shape, fill, playAll, PoiState.pixelSize) {
        if (!running) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            // cheap value-noise (Perlin-style): precomputed permutation + smooth interp
            val perm = IntArray(512) { it and 255 }.let { p ->
                var seed = 1337
                for (i in 255 downTo 1) {
                    seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
                    val j = seed % (i + 1)
                    val tmp = p[i]; p[i] = p[j]; p[j] = tmp
                }
                IntArray(512) { p[it and 255] }
            }
            fun noise2(x: Float, y: Float): Float {
                val xi = x.toInt() and 255
                val yi = y.toInt() and 255
                val xf = x - x.toInt(); val yf = y - y.toInt()
                val u = xf * xf * (3f - 2f * xf)
                val v = yf * yf * (3f - 2f * yf)
                val aa = perm[perm[xi] + yi].toFloat()
                val ab = perm[perm[xi] + yi + 1].toFloat()
                val ba = perm[perm[xi + 1] + yi].toFloat()
                val bb = perm[perm[xi + 1] + yi + 1].toFloat()
                val x1 = aa + u * (ba - aa)
                val x2 = ab + u * (bb - ab)
                return (x1 + v * (x2 - x1)) / 255f
            }

            fun insideShape(shapeIdx: Int, x: Float, y: Float, px: Float): Boolean {
                // All shapes span (nearly) the FULL frame so the whole LED strip is used —
                // geometry ported from PGraphics_Pattern_Template variations 1-3.
                fun d(xx: Float, yy: Float): Float = sqrt(xx * xx + yy * yy)
                return when (shapeIdx) {
                    0 -> { // big downward triangle: full-width top edge, apex at bottom centre
                        //   (original: triangle(0,0, 35,0, 18,35))
                        val fy = y / px                       // 0 at top, 1 at bottom
                        val halfw = (1f - fy) * px * 0.5f     // width shrinks to 0 at apex
                        abs(x - px / 2f) <= halfw
                    }
                    1 -> { // twin mirrored triangles filling the frame
                        //   right: (18,18)(35,0)(35,35)  left: (0,18)(18,0)(18,35)
                        val fx = x / px; val fy = y / px
                        if (fx >= 0.5f) abs(fy - 0.5f) <= (1f - fx) // right: apex at centre
                        else abs(fy - 0.5f) <= fx                    // left: apex at centre
                    }
                    2 -> { // inverse triangle: plasma shows OUTSIDE the big triangle
                        !(abs(x - px / 2f) <= (1f - y / px) * px * 0.5f)
                    }
                    3 -> d(x - px / 2f, y - px / 2f) < px * 0.48f
                    4 -> { // star (5-point), full extent
                        val r = d(x - px / 2f, y - px / 2f)
                        if (r > px * 0.48f) false
                        else {
                            val ang = atan2(y - px / 2f, x - px / 2f)
                            val k = 0.5f + 0.5f * cos(5f * (ang + kotlin.math.PI.toFloat() / 2f))
                            r < px * (0.16f + 0.34f * k)
                        }
                    }
                    5 -> { // spiral
                        val r = d(x - px / 2f, y - px / 2f)
                        if (r > px * 0.48f) false
                        else abs(sin(4f * atan2(y - px / 2f, x - px / 2f) + r * 0.55f)) < 0.35f
                    }
                    6 -> abs(x - px / 2f) + abs(y - px / 2f) < px * 0.48f
                    7 -> { // rings, full extent
                        val r = d(x - px / 2f, y - px / 2f)
                        (r.toInt() / 4) % 2 == 0 && r < px * 0.48f
                    }
                    8 -> { // wave: two full sine-wave bands across the frame (tiles horizontally)
                        val f = (kotlin.math.PI.toFloat() * 4f) / px    // 2 full cycles across the width
                        val mid = px * 0.5f + (px * 0.22f) * sin(f * x) // wavy centreline
                        abs(y - mid) < px * 0.11f
                    }
                    9 -> { // zigzag: chevron bands, one period per quarter frame (tiles horizontally)
                        val p = px / 4f
                        val lx = x % p
                        val ly = y % (2f * p)
                        val arm = if (ly < p) ly else 2f * p - ly      // 0 -> p -> 0
                        abs(lx - arm) < p * 0.28f
                    }
                    10 -> { // checkerboard: 8x8 alternating tiles (tiles horizontally)
                        val c = px / 8f
                        ((x / c).toInt() + (y / c).toInt()) % 2 == 0
                    }
                    11 -> { // diamonds: 6x6 tiled diamonds (tiles horizontally)
                        val c = px / 6f
                        val lx = x % c; val ly = y % c
                        abs(lx - c / 2f) + abs(ly - c / 2f) < c * 0.4f
                    }
                    12 -> { // tri tile: alternating up/down triangles, 8 across (tiles horizontally)
                        val c = px / 8f
                        val lx = x % c; val ly = y % c
                        val even = ((x / c).toInt() + (y / c).toInt()) % 2 == 0
                        if (even) ly < lx else ly > lx
                    }
                    else -> true
                }
            }

            var t = 0
            var frames = 0
            var fpsMark = System.currentTimeMillis()
            // Double-buffered reusable frames + cached mask: zero per-frame allocation,
            // no tearing between render / preview / UDP, and the shape test runs ONCE
            // per pixel only when size or shape changes.
            val px0 = PoiState.pixelSize.coerceAtLeast(16)
            var bufs = Array(2) { IntArray(px0 * px0) }
            var bufIdx = 0
            var cachedPx = -1
            var cachedShapeIdx = -1
            var mask = BooleanArray(px0 * px0)
            while (kotlin.coroutines.coroutineContext.isActive) {
                val px = PoiState.pixelSize.coerceAtLeast(16)
                val curShape = if (playAll) (t / 90) % shapeNames.size else shape

                // rebuild mask (and grow buffers) only when size or shape actually changes
                if (px != cachedPx || curShape != cachedShapeIdx) {
                    if (mask.size < px * px) { mask = BooleanArray(px * px); bufs = Array(2) { IntArray(px * px) } }
                    val pxf = px.toFloat()
                    for (y in 0 until px) for (x in 0 until px)
                        mask[y * px + x] = insideShape(curShape, x.toFloat(), y.toFloat(), pxf)
                    cachedPx = px; cachedShapeIdx = curShape
                }

                val frame = bufs[bufIdx]
                // --- generate fill straight into the back buffer ---
                when (fill) {
                    0 -> { val sc = 6f / px
                        for (y in 0 until px) for (x in 0 until px) {
                            val i = y * px + x
                            frame[i] = if (mask[i])
                                hsvToRgb((noise2(x * sc + t * 0.03f, y * sc + t * 0.02f) * 380 + t * 2).toInt() and 255, 220, 255)
                            else 0xFF000000.toInt()
                        } }
                    1 -> { val c = px / 2f
                        for (y in 0 until px) for (x in 0 until px) {
                            val dx = x - c; val dy = y - c
                            val v = sin(atan2(dy, dx) * 3f + sqrt(dx * dx + dy * dy) * 0.5f - t * 0.08f)
                            val i = y * px + x
                            frame[i] = if (mask[i]) hsvToRgb(((v + 1f) * 127f).toInt(), 255, 255) else 0xFF000000.toInt()
                        } }
                    else -> { for (y in 0 until px) for (x in 0 until px) {
                            val v = sin(x * 0.3f + t * 0.05f) + sin(y * 0.28f - t * 0.04f) +
                                    sin((x + y) * 0.18f + t * 0.03f)
                            val i = y * px + x
                            frame[i] = if (mask[i]) hsvToRgb(((v + 3f) * 42f).toInt() and 255, 255, 255) else 0xFF000000.toInt()
                        } }
                }

                // publish for the UI preview (cheap reference swap)
                previewFrame = frame
                previewSize = px
                previewShape = curShape
                previewFill = fill

                // hand off UDP to the single background sender (never blocks render loop,
                // drops stale frames instead of building lag on slow WiFi)
                PoiState.sendFrameLatest(frame, px, px)
                bufIdx = bufIdx xor 1  // flip to the other buffer for next frame

                t++
                frames++
                val now = System.currentTimeMillis()
                if (now - fpsMark > 1000) {
                    fps = frames * 1000f / (now - fpsMark)
                    frames = 0; fpsMark = now
                }
                // frame-rate cap (Settings): cancellable delay, no UDP/CPU flood
                val cap = PoiState.fpsCap
                if (cap > 0f) {
                    val target = (1000f / cap).toLong().coerceAtLeast(1)
                    val spent = System.currentTimeMillis() - now
                    if (spent < target) delay(target - spent)
                }
            }
        }
    }

    // scrollable so the 13 shape chips + preview never overflow/cover the controls
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Shapes masked over Perlin / Arc Noise / Plasma — background stays black.",
            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp
        )

        Text("Shape", color = NeonCyan)
        FlowChips(shapeNames, if (playAll) -1 else shape) { idx ->
            if (idx == -1) playAll = true else { shape = idx; playAll = false }
        }

        Text("Pattern fill", color = NeonCyan)
        FlowChips(fillNames, fill) { fill = it }

        SizeSelector(PoiState.pixelSize) { }

        PrimeAndStopRow(
            running = running,
            onStart = { running = true },
            onStop = {
                running = false
                PoiState.signalStop { PoiState.statusText = it }
            }
        )

        Text("FPS: ${"%.0f".format(fps)}  |  ${PoiState.pixelSize}px", color = NeonYellow, fontSize = 13.sp)

        // live preview: single bitmap blit of the latest frame (no per-pixel Canvas work)
        val frame = previewFrame
        val bmp = remember { android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888) }
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
        )
        LaunchedEffect(frame, previewSize) {
            if (frame != null) {
                val px = previewSize
                // downscale/upscale frame ints into the fixed 256px bitmap in one setPixels call
                val scaled = IntArray(256 * 256) { i ->
                    frame[(i / 256 * px / 256) * px + (i % 256 * px / 256)]
                }
                bmp.setPixels(scaled, 0, 256, 0, 0, 256, 256)
            }
        }
    }
}

@Composable
fun FlowChips(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(4).forEach { rowChips ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowChips.forEachIndexed { _, name ->
                    val idx = options.indexOf(name)
                    FilterChipLocal(name, selected == idx) { onPick(idx) }
                }
            }
        }
    }
}

/** HSV (0-255 scales) -> ARGB int. */
fun hsvToRgb(h: Int, s: Int, v: Int): Int {
    val region = (h / 43) % 6
    val rem = (h % 43) * 6
    val p = (v * (255 - s)) shr 8
    val q = (v * (255 - (s * rem shr 8))) shr 8
    val tt = (v * (255 - (s * (255 - rem) shr 8))) shr 8
    val (r, g, b) = when (region) {
        0 -> listOf(v, tt, p); 1 -> listOf(q, v, p); 2 -> listOf(p, v, tt)
        3 -> listOf(p, q, v); 4 -> listOf(tt, p, v); else -> listOf(v, p, q)
    }
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
