package za.tomjuggler.smartpoiudpextras

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

/** Shared app state: POI addresses, strip size, and the UDP/HTTP transport. */
object PoiState {
    /** Router/gateway IP — subnet base used by the Discover scan. */
    var routerIp by mutableStateOf("192.168.1.1")

    /** Up to 8 POI IPs; a blank or 0.0.0.0 slot means "not set" → never sent to. */
    const val MAX_POIS = 8
    val poiIps = mutableStateListOf(
        "192.168.1.1", "192.168.1.78", "", "", "", "", "", ""
    )

    /**
     * Per-POI LED strip size (NUM_PX) as reported by the device's HTTP API
     * (GET /get-pixels — same endpoint the Cordova SmartPoi_Controls app uses).
     * 0 = not detected yet → that POI falls back to [pixelSize].
     */
    val poiSizes = mutableStateListOf(0, 0, 0, 0, 0, 0, 0, 0)
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

    /** True when [ip] looks like a valid IPv4 address. */
    fun isValidIp(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }

    /** IPs we actually send to: set, valid, and not 0.0.0.0. */
    fun configuredIps(): List<String> =
        poiIps.filter { it.isNotBlank() && it != "0.0.0.0" && isValidIp(it) }

    /**
     * Effective streaming size for the POI at [index]: its detected NUM_PX when
     * known, else the global [pixelSize] default. Clamped to the daemon cap.
     */
    fun sizeAt(index: Int): Int =
        (poiSizes.getOrNull(index)?.takeIf { it in 16..120 } ?: pixelSize).coerceIn(16, 120)

    /** First detected size across configured POIs (for display); 0 when none detected. */
    fun anyDetectedSize(): Int = poiIndices().firstNotNullOfOrNull { poiSizes[it] } ?: 0

    /** Indices of POI slots that have a valid IP (0-based). */
    fun poiIndices(): List<Int> =
        poiIps.indices.filter { poiIps[it].isNotBlank() && poiIps[it] != "0.0.0.0" && isValidIp(poiIps[it]) }

    /**
     * Query one POI's LED count via GET /get-pixels (firmware returns NUM_PX as
     * plain text — the same endpoint SmartPoi_Controls uses). Returns null on
     * any failure (POI offline, old firmware, timeout).
     */
    fun fetchPoiSize(ip: String, onResult: (Int?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            val size = try {
                conn = URL("http://$ip/get-pixels").openConnection() as HttpURLConnection
                conn.connectTimeout = 1200
                conn.readTimeout = 1200
                if (conn.responseCode in 200..299)
                    conn.inputStream.bufferedReader().use { it.readText().trim().toIntOrNull() }
                else null
            } catch (_: Exception) {
                null
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
            onResult(size)
        }
    }

    /**
     * Detect sizes for every configured POI that doesn't have one yet (or all
     * when [force]). Fills [poiSizes] as answers arrive.
     */
    fun detectPoiSizes(force: Boolean = false, onProgress: (String) -> Unit = {}) {
        val targets = poiIndices().filter { force || poiSizes[it] !in 16..120 }
        if (targets.isEmpty()) {
            onProgress(if (poiSizes.any { it in 16..120 }) "Sizes already detected" else "No POIs to probe")
            return
        }
        onProgress("Probing ${targets.size} POI(s)…")
        var done = 0
        for (i in targets) {
            fetchPoiSize(poiIps[i]) { size ->
                if (size != null && size in 1..1000) {
                    poiSizes[i] = size.coerceIn(16, 120)
                    onProgress("POI ${i + 1} (${poiIps[i]}): ${poiSizes[i]}px")
                } else {
                    onProgress("POI ${i + 1} (${poiIps[i]}): size unknown — using ${pixelSize}px default")
                }
                done++
                if (done == targets.size) onProgress("Size detection done (${targets.size} probed)")
            }
        }
    }

    /** IP at 0-based [index], or "" when out of range. */
    fun ipAt(index: Int): String = if (index in poiIps.indices) poiIps[index] else ""

    /** Set one POI IP (0-based), invalidate the DNS cache, and drop a stale
     *  detected size — a new IP may be a different device with a different strip. */
    fun setPoiIp(index: Int, ip: String) {
        if (index in poiIps.indices) {
            val changed = poiIps[index].trim() != ip.trim()
            poiIps[index] = ip.trim()
            if (changed) poiSizes[index] = 0
            clearAddressCache()
        }
    }

    /** 3:3:2 RGB compression + 127 offset — matches the SmartPoi firmware protocol. */
    fun encodePixel(r: Int, g: Int, b: Int): Byte =
        (((r and 0xE0) or ((g and 0xE0) shr 3) or (b shr 6)) + 127).toByte()

    /** Send one raw row of encoded bytes to every configured POI. */
    fun sendRow(row: ByteArray) {
        sendRowTo(configuredIps(), row)
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
                    sendFrameTo(configuredIps(), f.first, f.second, f.third, rowInterval)
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
        sendFrameTo(configuredIps(), pixels, width, height, 0)
    }

    /** Send a full frame with rows spread [rowIntervalMs] ms apart (even pacing). */
    fun sendFramePaced(pixels: IntArray, width: Int, height: Int, rowIntervalMs: Long) {
        sendFrameTo(configuredIps(), pixels, width, height, rowIntervalMs)
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

    /** Prime every configured POI before streaming: LEDs OFF (7) -> UDP Mode (0). Best effort. */
    fun primeForStreaming(onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val ips = configuredIps()
            if (ips.isEmpty()) {
                onResult("No POI IPs configured — add them in Settings")
                return@launch
            }
            val results = ips.map { ip ->
                ip to (httpCommand(ip, PATTERN_LED_OFF) && httpCommand(ip, PATTERN_UDP_MODE))
            }
            val failed = results.filterNot { it.second }.map { it.first }
            onResult(
                if (failed.isEmpty()) "POIs primed: LEDs OFF → UDP mode (${ips.size} OK)"
                else "Prime failed for: ${failed.joinToString()} — check POI WiFi"
            )
        }
    }

    /** Turn LEDs off on every configured POI (stop streaming). */
    fun signalStop(onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val ips = configuredIps()
            if (ips.isEmpty()) {
                onResult("No POI IPs configured — nothing to do")
                return@launch
            }
            val failed = ips.filterNot { httpCommand(it, PATTERN_LED_OFF) }
            onResult(
                if (failed.isEmpty()) "LEDs OFF sent to ${ips.size} POI(s)"
                else "LED OFF failed for: ${failed.joinToString()}"
            )
        }
    }

    /** Send any raw patternChooserChange value (on-board modes 1-6 etc.) to every configured POI. */
    fun sendRawPattern(pattern: String, onResult: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val ips = configuredIps()
            val ok = ips.isNotEmpty() && ips.all { httpCommand(it, pattern) }
            onResult(ok)
        }
    }

    // ---- network discovery (mirrors the Cordova app's fastScanNetwork: probe /poi-available) ----
    /**
     * Probe every host on [routerIp]'s /24 subnet for a POI: a real device answers
     * GET /poi-available with 200. [onProgress] reports hosts checked so far,
     * [onResult] returns the found IPs in ascending host order.
     */
    fun discoverPois(routerIp: String, onProgress: (Int) -> Unit, onResult: (List<String>) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val octets = routerIp.trim().split(".")
            if (octets.size != 4) { onResult(emptyList()); return@launch }
            val subnet = octets.take(3).joinToString(".") + "."
            val found = java.util.Collections.synchronizedList(mutableListOf<String>())
            val scanned = java.util.concurrent.atomic.AtomicInteger(0)
            // Back-off scan: ESP8266 POI radios only serve a few simultaneous TCP
            // connections, so a wide parallel burst overwhelms them and the devices
            // reboot mid-scan. Keep concurrency low and pace probe starts.
            val gate = java.util.concurrent.Semaphore(8)
            val jobs = (1..254).map { n ->
                val job = launch {
                    gate.acquire()
                    try {
                        val ip = subnet + n
                        if (isPoiAvailable(ip)) found.add(ip)
                    } finally {
                        gate.release()
                        onProgress(scanned.incrementAndGet())
                    }
                }
                delay(60) // stagger probe starts so the AP radio isn't flooded
                job
            }
            jobs.forEach { it.join() }
            onResult(found.sortedBy { it.substringAfterLast(".").toIntOrNull() ?: 0 })
        }
    }

    /** One /poi-available probe: HTTP 200 → a POI answered. */
    private fun isPoiAvailable(ip: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("http://$ip/poi-available").openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 1500
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Detect the router IP for Discover — two cases:
     * 1. Hotspot/AP mode (phone IS the gateway): Android creates an AP
     *    interface (swlan0, ap0, wlan1, softap0…) whose address is the subnet
     *    the POIs live on.
     * 2. WiFi client on a normal router: return the default route's gateway IP.
     * Returns null when neither can be found (caller asks for manual entry).
     */
    fun detectRouterIp(ctx: Context): String? {
        // 1) hotspot / local-only hotspot: the phone is the AP
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (networkInterface in interfaces) {
                val name = networkInterface.name.lowercase()
                if (name.contains("ap") || name.contains("swlan") || name.contains("wlan1") || name.contains("softap")) {
                    val addresses = networkInterface.inetAddresses
                    for (inetAddress in addresses) {
                        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                            return inetAddress.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // fall through to the router gateway check
        }
        // 2) normal router: the default route's gateway (WiFi client or LAN)
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
            for (route in lp.routes) {
                if (route.isDefaultRoute()) {
                    // only trust gateways on a WiFi interface (wlan0/wlan1/…);
                    // the carrier's default route (rmnet/eth) is useless for POIs
                    val iface = route.getInterface()?.lowercase()
                    if (iface?.contains("wlan") != true) continue
                    val gw = route.gateway
                    if (gw != null && !gw.isLoopbackAddress && gw is Inet4Address) {
                        return gw.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            // no permission / no network — caller shows a manual-entry hint
        }
        return null
    }

    // ---- persistence ----
    private const val PREFS = "SmartPoiPrefs"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        routerIp = p.getString("routerIp", routerIp) ?: routerIp
        // migrate the old single-IP keys ("ip"/"ip2") into slots 0/1
        val old1 = p.getString("ip", null)
        val old2 = p.getString("ip2", null)
        for (i in 0 until MAX_POIS) {
            val saved = p.getString("poi$i", null)
            when {
                saved != null -> poiIps[i] = saved
                i == 0 && old1 != null -> poiIps[i] = old1
                i == 1 && old2 != null -> poiIps[i] = old2
            }
            // per-POI detected size (0 = unknown → global default is used)
            poiSizes[i] = p.getInt("poiSize$i", 0)
        }
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
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        e.putString("routerIp", routerIp)
        for (i in poiIps.indices) {
            e.putString("poi$i", poiIps[i])
            e.putInt("poiSize$i", poiSizes[i])
        }
        e.putInt("px", pixelSize)
        e.putFloat("fpsCap", fpsCap)
        e.putInt("packetRepeat", packetRepeat)
        e.apply()
    }
}

fun Color.toArgbInt(): Int =
    ((alpha * 255).toInt() shl 24) or ((red * 255).toInt() shl 16) or
    ((green * 255).toInt() shl 8) or (blue * 255).toInt()
