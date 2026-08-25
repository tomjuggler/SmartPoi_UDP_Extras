package za.tomjuggler.smartpoiudpextras

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** Shared app state: POI addresses, strip size, and the UDP/HTTP transport. */
object PoiState {
    var ip1 by mutableStateOf("192.168.1.1")
    var ip2 by mutableStateOf("192.168.1.78")
    var pixelSize by mutableStateOf(60)
    var statusText by mutableStateOf("Ready")
    /** Max frames per second for streaming loops. */
    var fpsCap by mutableStateOf(1.0f)
    /**
     * UDP repeats per row/column. The ESP32 firmware (UDPHandler.handleUDP) does a
     * full FastLED.show() per received datagram (~2ms for 60 LEDs), so each repeat
     * costs receiver bandwidth: 3 repeats x 60 cols x fps quickly exceeds the
     * ~300-500 packets/s the device can absorb. 1 is the right default; raise only
     * for lossy WiFi at low fps.
     */
    var packetRepeat by mutableStateOf(1)

    const val UDP_PORT = 2390

    private val socket = DatagramSocket()

    // Cached address lookups: never do DNS/getByName inside the per-packet hot path.
    private val addrCache = java.util.concurrent.ConcurrentHashMap<String, InetAddress>()
    private fun resolve(ip: String): InetAddress =
        addrCache.getOrPut(ip) { InetAddress.getByName(ip) }

    /** Invalidate cached addresses (call when IPs change in Settings). */
    fun clearAddressCache() = addrCache.clear()

    /** 3:3:2 RGB compression + 127 offset — matches the SmartPoi firmware protocol. */
    fun encodePixel(r: Int, g: Int, b: Int): Byte =
        (((r and 0xE0) or ((g and 0xE0) shr 3) or (b shr 6)) + 127).toByte()

    /** Send one raw row of encoded bytes to both POIs. */
    fun sendRow(row: ByteArray) {
        sendRowTo(listOf(ip1, ip2), row)
    }

    // ---- non-blocking frame sender (single worker, drops stale frames) ----
    // Streaming screens call sendFrameLatest() instead of spawning a coroutine per
    // frame: one daemon thread does ALL UDP work, and if it falls behind on slow
    // WiFi it skips ahead to the newest frame instead of building up lag. The rows
    // of each frame are additionally paced so packets hit the wire evenly over the
    // frame period, instead of all bursting out at once and overloading the ESP32
    // receiver (it runs FastLED.show() per datagram, ~2ms for 60 LEDs).
    private val latestFrame = java.util.concurrent.atomic.AtomicReference<Triple<IntArray, Int, Int>?>(null)
    private val udpWorker = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue(4),
    ) { r -> Thread(r, "smartpoi-udp").apply { isDaemon = true } }.apply {
        java.util.concurrent.RejectedExecutionHandler { _, _ -> /* queue full: drop */ }
    }

    /** Queue a frame for sending; keeps only the most recent one if we fall behind. */
    fun sendFrameLatest(pixels: IntArray, width: Int, height: Int) {
        latestFrame.set(Triple(pixels, width, height))
        try {
            udpWorker.execute {
                val f = latestFrame.getAndSet(null) ?: return@execute
                try {
                    val period = if (PoiState.fpsCap > 0f)
                        (1000f / PoiState.fpsCap).toLong().coerceAtLeast(1L) else 0L
                    val nRows = f.second
                    // space the width rows evenly across the whole frame period
                    val rowInterval = if (period > 0 && nRows > 0)
                        (period / nRows).coerceAtLeast(1L) else 0L
                    sendFrameTo(listOf(ip1, ip2), f.first, f.second, f.third, rowInterval)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }


    /** Send one raw row to specific POIs (Zap Game: one POI at a time). */
    fun sendRowTo(ips: List<String>, row: ByteArray) {
        try {
            for (ip in ips) {
                val packet = DatagramPacket(row, row.size, resolve(ip), UDP_PORT)
                repeat(PoiState.packetRepeat) { socket.send(packet) }
            }
        } catch (e: Exception) {
            statusText = "UDP error: ${e.message}"
        }
    }

    /**
     * Send a full frame (width x height ARGB ints) to both POIs.
     * IMPORTANT (matches original Processing sketches):
     *  - the frame is rotated 90° clockwise before sending
     *  - each rotated ROW is one datagram of exactly [height] bytes
     *    (after rotation, a row = one original column = one LED "line")
     */
    fun sendFrame(pixels: IntArray, width: Int, height: Int) {
        sendFrameTo(listOf(ip1, ip2), pixels, width, height, 0)
    }

    /** Send a full frame with rows spread [rowIntervalMs] ms apart (even pacing). */
    fun sendFramePaced(pixels: IntArray, width: Int, height: Int, rowIntervalMs: Long) {
        sendFrameTo(listOf(ip1, ip2), pixels, width, height, rowIntervalMs)
    }

    /** Single-POI variant used by the Zap Game. */
    fun sendFrameTo(ips: List<String>, pixels: IntArray, width: Int, height: Int, rowIntervalMs: Long = 0) {
        if (pixels.size < width * height) return
        if (height == 1) {
            // single-row (line modes): no rotation, one packet of [width] bytes
            val row = ByteArray(width)
            for (x in 0 until width) {
                val c = pixels[x]
                row[x] = encodePixel((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            }
            sendRowTo(ips, row)
            return
        }
        // rotate 90° clockwise: rotated row r (0..width-1) contains src column x=r,
        // with src pixel (x=r, y) placed at rotated newX = height-1-y.
        // Each rotated row = one datagram of exactly [height] bytes.
        val row = ByteArray(height)
        for (r in 0 until width) {
            java.util.Arrays.fill(row, 0)
            for (y in 0 until height) {
                val c = pixels[y * width + r]
                val newX = height - 1 - y
                row[newX] = encodePixel((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            }
            sendRowTo(ips, row)
            // Evenly space rows across the frame period so the receiver isn't flooded.
            if (rowIntervalMs > 0) Thread.sleep(rowIntervalMs)
        }
    }

    /** Black frame to blank a strip. */
    fun sendBlackFrame(size: Int) {
        val row = ByteArray(size) // all zero bytes = black
        repeat(size) { sendRow(row) }
    }

    // ---- SmartPoi HTTP pattern-chooser API (port 80), same as PixelBlaze project ----
    const val PATTERN_LED_OFF = "7"
    const val PATTERN_UDP_MODE = "0"

    private fun httpCommand(ip: String, pattern: String): Boolean {
        // try twice — POIs occasionally drop a single request
        repeat(2) { attempt ->
            try {
                val url = URL("http://$ip/pattern?patternChooserChange=$pattern")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1200
                conn.readTimeout = 1200
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return true
            } catch (e: Exception) {
                if (attempt == 1) return false
            }
        }
        return false
    }

    /** Prime both POIs before streaming: LEDs OFF (7) -> UDP Mode (0). Best effort. */
    fun primeForStreaming(onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val results = listOf(ip1, ip2).map { ip ->
                ip to (httpCommand(ip, PATTERN_LED_OFF) && httpCommand(ip, PATTERN_UDP_MODE))
            }
            val failed = results.filterNot { it.second }.map { it.first }
            onResult(
                if (failed.isEmpty()) "POIs primed: LEDs OFF → UDP mode (both OK)"
                else "Prime failed for: ${failed.joinToString()} — check POI WiFi"
            )
        }
    }

    /** Turn LEDs off on both POIs (stop streaming). */
    fun signalStop(onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val failed = listOf(ip1, ip2).filterNot { httpCommand(it, PATTERN_LED_OFF) }
            onResult(
                if (failed.isEmpty()) "LEDs OFF sent to both POIs"
                else "LED OFF failed for: ${failed.joinToString()}"
            )
        }
    }

    /** Send any raw patternChooserChange value (on-board modes 1-6 etc.) to both POIs. */
    fun sendRawPattern(pattern: String, onResult: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val ok = listOf(ip1, ip2).all { httpCommand(it, pattern) }
            onResult(ok)
        }
    }

    // ---- persistence ----
    private const val PREFS = "SmartPoiPrefs"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ip1 = p.getString("ip", ip1) ?: ip1
        ip2 = p.getString("ip2", ip2) ?: ip2
        pixelSize = p.getInt("px", pixelSize)
        // fpsCap may be stored as Int (old builds) or Float (current) — accept both
        fpsCap = when (val v = p.all["fpsCap"]) {
            is Float -> v
            is Int -> v.toFloat()
            is Double -> v.toFloat()
            else -> fpsCap
        }
        packetRepeat = when (val v = p.all["packetRepeat"]) {
            is Int -> v.coerceIn(1, 3)
            is Long -> v.toInt().coerceIn(1, 3)
            else -> packetRepeat
        }
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ip", ip1)
            .putString("ip2", ip2)
            .putInt("px", pixelSize)
            .putFloat("fpsCap", fpsCap)
            .putInt("packetRepeat", packetRepeat)
            .apply()
    }
}

fun Color.toArgbInt(): Int =
    ((alpha * 255).toInt() shl 24) or ((red * 255).toInt() shl 16) or
    ((green * 255).toInt() shl 8) or (blue * 255).toInt()
