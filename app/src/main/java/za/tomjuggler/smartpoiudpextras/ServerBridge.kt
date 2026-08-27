package za.tomjuggler.smartpoiudpextras

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Server Bridge — Android app as UDP relay between a stream server and the POIs.
 *
 * Flow (see .cecli.proxy_idea.txt, adapted to LAN + router, no hotspot):
 *
 *   Flask server --UDP rows--> this app --identical bytes--> POI(s) :2390
 *
 * ON:  1) prime POIs (LEDs OFF -> UDP mode)
 *      2) send SMARTPOI_REG datagram to server's data port — the reply completes
 *         the pinhole and tells the server where to stream; the server starts.
 *      3) relay thread forwards every received datagram byte-for-byte to both POIs,
 *         one row per message (the server already paces rows across the frame
 *         period, so relaying straight through preserves its timing).
 * OFF: HTTP /api/stop to server, then LEDs OFF to POIs.
 */
object ServerBridge {
    var serverIp by mutableStateOf("192.168.8.100")
    var httpPort by mutableStateOf(5005)
    var udpPort by mutableStateOf(2391)
    var connected by mutableStateOf(false)
    var packetsRelayed by mutableStateOf(0L)
    /** Frame rate the server actually assigned us (from REG_OK reply). */
    var serverFps by mutableStateOf(0f)

    private var rxThread: Thread? = null
    @Volatile var running = false
        private set
    private var socket: DatagramSocket? = null

    const val REG_MSG = "SMARTPOI_REG"
    const val REG_OK_PREFIX = "SMARTPOI_REG_OK"
    const val UNREG_MSG = "SMARTPOI_UNREG"
    const val PING_MSG = "SMARTPOI_PING"
    private const val PING_INTERVAL_MS = 5000L   // server prunes silent clients at 15s

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("SmartPoiPrefs", Context.MODE_PRIVATE)
        serverIp = p.getString("bridge_ip", serverIp) ?: serverIp
        httpPort = p.getInt("bridge_http", httpPort)
        udpPort = p.getInt("bridge_udp", udpPort)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("SmartPoiPrefs", Context.MODE_PRIVATE).edit()
            .putString("bridge_ip", serverIp)
            .putInt("bridge_http", httpPort)
            .putInt("bridge_udp", udpPort)
            .apply()
    }

    /** Quick reachability test of the control API (GET /api/status). */
    fun pingServer(onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://$serverIp:$httpPort/api/status")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                onResult("Server responded: $body")
            } catch (e: Exception) {
                onResult("No answer from http://$serverIp:$httpPort — ${e.message}")
            }
        }
    }

    /**
     * Relay loop. Binds an ephemeral local port, sends REGISTER from THAT socket,
     * then keeps receiving on it — so the server's stream flows back to the same
     * address:port (pinhole stays open, works across routers too).
     */
    fun start(onStatus: (String) -> Unit) {
        if (running) return
        packetsRelayed = 0
        try {
            val s = DatagramSocket()
            s.soTimeout = 1500   // poll so we can exit promptly and report staleness
            socket = s

            running = true
            rxThread = Thread {
                try {
                    val addr = InetAddress.getByName(serverIp)

                    // RESPONSIVE protocol: declare our pixel size + wanted fps.
                    // Values come from Settings (LED strip size / FPS cap).
                    // First client while the server is idle sets THE frame rate;
                    // later clients inherit it. Our own size is always honored.
                    val px = PoiState.pixelSize.coerceIn(16, 120)
                    val fps = PoiState.fpsCap.coerceIn(0.5f, 60f)
                    val regMsg = ("$REG_MSG {\"size\":$px,\"fps\":$fps}")
                        .toByteArray()
                    var registered = false
                    outer@ for (attempt in 1..3) {
                        s.send(DatagramPacket(regMsg, regMsg.size, addr, udpPort))
                        try {
                            val buf = ByteArray(128)
                            val rp = DatagramPacket(buf, buf.size)
                            s.receive(rp)
                            val reply = String(buf, 0, rp.length)
                            if (reply.startsWith(REG_OK_PREFIX)) {
                                // REG_OK format: "SMARTPOI_REG_OK fps=10.00"
                                serverFps = reply.substringAfter("fps=", "")
                                    .trim().toFloatOrNull() ?: fps
                                registered = true
                                break@outer
                            }
                        } catch (_: SocketTimeoutException) {}
                    }
                    if (!registered) {
                        onStatus("No reply from server $serverIp:$udpPort — check IP / firewall")
                        running = false
                        return@Thread
                    }
                    connected = true
                    onStatus("Bridging $serverIp → ${PoiState.ip1}/${PoiState.ip2} " +
                        "· ${px}px @ ${"%.2f".format(serverFps)} fps")

                    // pre-allocate once — no allocation in the hot loop (GC pause note
                    // in the research chat). One received datagram = one row.
                    val buf = ByteArray(1024)
                    val rx = DatagramPacket(buf, buf.size)
                    var lastPacketAt = System.currentTimeMillis()
                    var lastPingAt = 0L
                    while (running) {
                        val now = System.currentTimeMillis()
                        // keepalive so the server doesn't prune us as a silent client
                        if (now - lastPingAt > PING_INTERVAL_MS) {
                            lastPingAt = now
                            try {
                                val ping = PING_MSG.toByteArray()
                                s.send(DatagramPacket(ping, ping.size, addr, udpPort))
                            } catch (_: Exception) {}
                        }
                        try {
                            s.receive(rx)
                            lastPacketAt = System.currentTimeMillis()
                            // copy into a right-sized array; DatagramPacket reuse would
                            // otherwise let the next receive overwrite the sent bytes
                            val payload = ByteArray(rx.length)
                            System.arraycopy(rx.data, rx.offset, payload, 0, rx.length)
                            PoiState.sendRowTo(listOf(PoiState.ip1, PoiState.ip2), payload)
                            packetsRelayed++
                        } catch (_: SocketTimeoutException) {
                            if (running && System.currentTimeMillis() - lastPacketAt > 4000) {
                                onStatus("No packets from server for 4s… still listening")
                                lastPacketAt = System.currentTimeMillis() // report once per gap
                            }
                        } catch (e: Exception) {
                            if (running) onStatus("Relay error: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    if (running) onStatus("Relay stopped: ${e.message}")
                } finally {
                    connected = false
                    running = false
                }
            }.apply { name = "smartpoi-bridge-rx"; isDaemon = true }
            rxThread!!.start()
        } catch (e: Exception) {
            running = false
            onStatus("Start failed: ${e.message}")
        }
    }

    /**
     * Leave the stream: UNREG datagram must go out from the SAME socket we
     * registered with (the server identifies clients by source address:port).
     * The server stops generating only when the last client is gone and resets
     * its frame rate. /api/stop stays as a manual master kill.
     */
    fun stop(onStatus: (String) -> Unit) {
        val s = socket
        running = false
        connected = false
        if (s != null && !s.isClosed) {
            try {
                val msg = UNREG_MSG.toByteArray()
                s.send(DatagramPacket(msg, msg.size,
                    InetAddress.getByName(serverIp), udpPort))
                onStatus("Left stream ($packetsRelayed packets relayed)")
            } catch (e: Exception) {
                onStatus("Unregister error: ${e.message} ($packetsRelayed relayed)")
            }
        } else {
            onStatus("Left stream ($packetsRelayed packets relayed)")
        }
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}

// ----------------------------------------------------------------- Screen ----

@Composable
fun ServerBridgeScreen() {
    var busy by remember { mutableStateOf(false) }
    var stat by remember { mutableStateOf("Idle") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Use the phone as a bridge: a stream SERVER sends LED rows over the " +
            "network, this app relays them byte-for-byte to the POIs. Same pixel " +
            "protocol as Computer Generated (one row per UDP message, paced).",
            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp
        )

        Text("Server address", color = NeonCyan)
        OutlinedTextField(
            value = ServerBridge.serverIp,
            onValueChange = { ServerBridge.serverIp = it.trim() },
            label = { Text("Server IP or hostname") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ServerBridge.httpPort.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { ServerBridge.httpPort = it } },
                label = { Text("HTTP port") }, singleLine = true,
                modifier = Modifier.width(130.dp)
            )
            OutlinedTextField(
                value = ServerBridge.udpPort.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { ServerBridge.udpPort = it } },
                label = { Text("UDP data port") }, singleLine = true,
                modifier = Modifier.width(130.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                busy = true
                ServerBridge.pingServer {
                    stat = it.substringBefore("\n")   // status JSON is long — first part
                    busy = false
                }
            }, enabled = !busy) { Text("Test connection") }
        }

        // --- RESPONSIVE: stream parameters come from Settings (read-only here) --
        Text(
            "Stream parameters come from Settings — LED strip size sets your " +
            "pixel size, the FPS cap is your requested frame rate. The FIRST " +
            "phone to connect sets the server's frame rate; later phones get " +
            "the current rate. Each phone gets its own pixel-size stream " +
            "(server downscales from the largest, max 120).",
            color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp
        )
        Text(
            "Will request: ${PoiState.pixelSize}px @ ${"%.1f".format(PoiState.fpsCap)} fps",
            color = NeonYellow, fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
        if (ServerBridge.serverFps > 0f) {
            Text(
                "Server assigned: %.2f fps".format(ServerBridge.serverFps),
                color = Color(0xFF00E676), fontSize = 13.sp
            )
        }

        PrimeAndStopRow(
            running = ServerBridge.running || busy,
            onStart = {
                busy = true
                stat = "Priming POIs…"
                PoiState.primeForStreaming { _ ->
                    ServerBridge.start { msg ->
                        stat = msg
                        busy = false
                    }
                }
            },
            onStop = {
                busy = true
                ServerBridge.stop { stopMsg ->
                    PoiState.signalStop { poiMsg ->
                        stat = "$stopMsg · $poiMsg"
                        busy = false
                    }
                }
            }
        )

        Text(stat, color = NeonYellow, fontSize = 13.sp)
        Text(
            "Relayed: ${ServerBridge.packetsRelayed} pkts  ·  POIs ${PoiState.ip1} / ${PoiState.ip2}",
            color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Medium
        )
    }
}
