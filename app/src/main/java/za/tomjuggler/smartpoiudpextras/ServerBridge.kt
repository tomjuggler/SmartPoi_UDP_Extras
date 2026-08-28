package za.tomjuggler.smartpoiudpextras

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
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

    // --- Magic Poi party mode: set by MagicPoi.join(); this is the DEFAULT now ---
    /** true = relay from magicpoi-streamd with MAGICPOI_* protocol. */
    var magicPoiMode by mutableStateOf(true)
    var magicPoiHost by mutableStateOf("")
    var magicPoiUdpPort by mutableStateOf(2393)
    /** Daemon-side party state: idle / ready (cylon) / playing. */
    var magicPoiPartyState by mutableStateOf("unknown")

    private var rxThread: Thread? = null
    @Volatile var running = false
        private set
    private var socket: DatagramSocket? = null

    const val REG_MSG = "SMARTPOI_REG"
    const val REG_OK_PREFIX = "SMARTPOI_REG_OK"
    const val UNREG_MSG = "SMARTPOI_UNREG"
    const val PING_MSG = "SMARTPOI_PING"
    // Magic Poi party protocol (same socket discipline, different keywords)
    const val MP_REG_MSG = "MAGICPOI_REG"
    const val MP_REG_OK_PREFIX = "MAGICPOI_REG_OK"
    const val MP_UNREG_MSG = "MAGICPOI_UNREG"
    const val MP_PING_MSG = "MAGICPOI_PING"
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
     *
     * Two modes:
     *  - legacy (default): SMARTPOI_* protocol to the old Flask server
     *  - Magic Poi party (magicPoiMode): MAGICPOI_* protocol to magicpoi-streamd,
     *    host/port from the join API. state=ready means the idle cylon is
     *    flowing ("waiting for start"); the owner STARTs from the Magic Poi tab.
     */
    fun start(onStatus: (String) -> Unit) {
        if (running) return
        packetsRelayed = 0
        val mp = magicPoiMode
        val host = if (mp) magicPoiHost else serverIp
        val port = if (mp) magicPoiUdpPort else udpPort
        val partyId = MagicPoi.joinedPartyId
        if (mp && (host.isBlank() || partyId == null)) {
            onStatus("Join a party in the Magic Poi tab first")
            return
        }
        try {
            val s = DatagramSocket()
            s.soTimeout = 1500   // poll so we can exit promptly and report staleness
            socket = s

            running = true
            rxThread = Thread {
                try {
                    val addr = InetAddress.getByName(host)

                    // Declare our pixel size (Settings is the single source of
                    // truth). OWNER's phone also sets the PARTY playback fps
                    // from its Settings fps cap (daemon: owner REG wins).
                    val px = PoiState.pixelSize.coerceIn(16, 120)
                    val fps = PoiState.fpsCap.coerceIn(0.5f, 60f)
                    val regMsg = if (mp) {
                        val ownerFlag = if (MagicPoi.joinedIsOwner) ",\"owner\":true" else ""
                        "$MP_REG_MSG {\"party\":$partyId,\"size\":$px,\"fps\":$fps$ownerFlag}"
                    } else {
                        "$REG_MSG {\"size\":$px,\"fps\":$fps}"
                    }.toByteArray()
                    var registered = false
                    outer@ for (attempt in 1..3) {
                        s.send(DatagramPacket(regMsg, regMsg.size, addr, port))
                        try {
                            var tries = 0
                            while (tries < 8) {   // reply may race stream packets
                                tries++
                                val buf = ByteArray(256)
                                val rp = DatagramPacket(buf, buf.size)
                                s.receive(rp)
                                val reply = String(buf, 0, rp.length)
                                val okPrefix = if (mp) MP_REG_OK_PREFIX else REG_OK_PREFIX
                                if (reply.startsWith(okPrefix)) {
                                    if (mp) {
                                        // "MAGICPOI_REG_OK party=7 state=ready fps=5.00"
                                        magicPoiPartyState = reply
                                            .substringAfter("state=", "").trim()
                                            .substringBefore(" ").trim()
                                        serverFps = reply.substringAfter("fps=", "")
                                            .trim().toFloatOrNull() ?: 0f
                                    } else {
                                        // "SMARTPOI_REG_OK fps=10.00"
                                        serverFps = reply.substringAfter("fps=", "")
                                            .trim().toFloatOrNull() ?: fps
                                    }
                                    registered = true
                                    break@outer
                                }
                                // else: stream datagram — keep draining
                            }
                        } catch (_: SocketTimeoutException) {}
                    }
                    if (!registered) {
                        onStatus("No reply from server $host:$port — check IP / firewall")
                        running = false
                        return@Thread
                    }
                    connected = true
                    val mode = if (mp) "Magic Poi party #$partyId" else "legacy server"
                    val stateNote = if (mp && magicPoiPartyState == "ready")
                        " — waiting for start (cylon)" else ""
                    onStatus("Bridging $host → ${PoiState.ip1}/${PoiState.ip2} " +
                        "· $mode · ${px}px$stateNote")

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
                                val ping = (if (mp)
                                    "$MP_PING_MSG {\"party\":$partyId}"
                                else PING_MSG).toByteArray()
                                s.send(DatagramPacket(ping, ping.size, addr, port))
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
                val partyId = MagicPoi.joinedPartyId
                val msg = (if (magicPoiMode && partyId != null)
                    "$MP_UNREG_MSG {\"party\":$partyId}"
                else UNREG_MSG).toByteArray()
                val host = if (magicPoiMode) magicPoiHost else serverIp
                val port = if (magicPoiMode) magicPoiUdpPort else udpPort
                s.send(DatagramPacket(msg, msg.size,
                    InetAddress.getByName(host), port))
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
            "Magic Poi parties: join a party, turn the bridge ON, and the POIs " +
            "show the party stream. The phone relays server LED rows to the POIs " +
            "byte-for-byte (one row per UDP message, paced).",
            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp
        )

        // Login moved to Settings — point there instead of embedding a form.
        if (MagicPoi.token == null) {
            Text(
                "Log in on the Settings tab first (one time — it's remembered).",
                color = NeonMagenta, fontSize = 14.sp
            )
        }

        // --- mode: Magic Poi party (default) or standalone stream server ------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ServerBridge.magicPoiMode,
                onClick = { ServerBridge.magicPoiMode = true },
                label = { Text("Magic Poi party") }
            )
            FilterChip(
                selected = !ServerBridge.magicPoiMode,
                onClick = { ServerBridge.magicPoiMode = false },
                label = { Text("Test Stream") }
            )
        }

        if (ServerBridge.magicPoiMode) {
            Text(
                "Party: " + (MagicPoi.joinedPartyId?.let { "#$it (${MagicPoi.partyState})" }
                    ?: "none — join below"),
                color = NeonYellow, fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
            MagicPoiPartiesInline()
        } else {
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
        }

        // --- RESPONSIVE: stream parameters come from Settings (read-only here) --
        Text(
            "LED strip size in Settings sets your pixel size (the server " +
            "downscales the party master frame to your size, max 120).",
            color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp
        )
        Text(
            "Will request: ${PoiState.pixelSize}px",
            color = NeonYellow, fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
        if (ServerBridge.serverFps > 0f) {
            Text(
                "Server assigned: %.2f fps".format(ServerBridge.serverFps),
                color = Color(0xFF00E676), fontSize = 13.sp
            )
        }

        // Test Stream (non-party) mode uses the classic ON/OFF strip; in Magic
        // Poi mode the global "Poi Connection" toggle lives inside the party list.
        if (!ServerBridge.magicPoiMode) {
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
        }

        Text(stat, color = NeonYellow, fontSize = 13.sp)
        Text(
            "Relayed: ${ServerBridge.packetsRelayed} pkts  ·  POIs ${PoiState.ip1} / ${PoiState.ip2}",
            color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Medium
        )
    }
}

/** Compact party list shown inside the bridge's Magic Poi mode. Login is on Settings. */
@Composable
fun MagicPoiPartiesInline() {
    if (MagicPoi.token == null) {
        Text("Log in on the Settings tab first.", color = NeonMagenta, fontSize = 13.sp)
        return
    }
    var parties by remember { mutableStateOf<JSONArray?>(null) }
    var stat by remember { mutableStateOf("") }

    LaunchedEffect(MagicPoi.token, MagicPoi.joinedPartyId) {
        MagicPoi.listParties { m, arr -> stat = m; parties = arr }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Signed in: ${MagicPoi.username}", color = NeonCyan, fontSize = 13.sp)
        val arr = parties
        when {
            arr == null -> Text(stat, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            arr.length() == 0 -> Text("No parties yet", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            else -> for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val pid = p.optInt("party_id")
                val joined = MagicPoi.joinedPartyId == pid
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${p.optString("title")} · ${p.optString("state", "?")}" +
                            if (p.optBoolean("is_owner")) " (you own)" else "",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    // Join/Leave toggle per party — one party joined at a time
                    // (MagicPoi.join leaves any previous party first).
                    if (joined) {
                        Button(
                            onClick = { MagicPoi.leave { MagicPoi.refreshState() } },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta)
                        ) { Text("Leave") }
                    } else {
                        OutlinedButton(onClick = {
                            MagicPoi.join(pid) { MagicPoi.refreshState() }
                        }) { Text("Join") }
                    }
                }
            }
        }
        if (MagicPoi.joinedPartyId != null) {
            val stateText = when (MagicPoi.partyState) {
                "ready" -> "Ready — waiting for owner to start (cylon on POIs)"
                "playing" -> "PLAYING — timeline streaming"
                "idle" -> "Waiting for timeline"
                else -> MagicPoi.partyState
            }
            Text(stateText, color = NeonYellow, fontSize = 12.sp)
        }

        // ---- Global START/STOP (owner playback) + Poi Connection on/off ----
        // Two separate switches, both underneath all parties:
        //  1) START/STOP — owner starts/stops the daemon party (all POIs play
        //     or stop together). Disabled for non-owners / until joined.
        //  2) Poi Connection — connects/disconnects the phone's UDP relay to the
        //     POIs (primes them + streams server rows). This is the physical
        //     ON/OFF tie to the hardware.
        val joined = MagicPoi.joinedPartyId != null
        val owner = joined && MagicPoi.joinedIsOwner
        val playing = joined && MagicPoi.partyState == "playing"
        val bridgeOn = ServerBridge.running || ServerBridge.connected

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (playing) MagicPoi.stop { MagicPoi.partyState = "ready" }
                    else MagicPoi.start { MagicPoi.partyState = "playing" }
                },
                enabled = owner,
                modifier = Modifier.weight(1f).height(52.dp)
            ) {
                Text(
                    if (playing) "⏹ STOP" else "▶ START",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
            }
            Button(
                onClick = {
                    if (bridgeOn) {
                        ServerBridge.stop { }
                        PoiState.signalStop { }
                    } else {
                        PoiState.primeForStreaming { _ ->
                            ServerBridge.start { }
                        }
                    }
                },
                enabled = joined,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (bridgeOn) NeonMagenta else Color(0xFF00E676)
                ),
                modifier = Modifier.weight(1f).height(52.dp)
            ) {
                Text(
                    if (bridgeOn) "Poi Connection: ON" else "Poi Connection: OFF",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
            }
        }
    }
}
