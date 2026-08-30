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

    /** True while at least one size-group relay is running. */
    @Volatile var running = false
        private set

    // --- multi-size streaming: ONE relay stream per distinct POI size ---------
    // Each relay owns its own DatagramSocket + MAGICPOI_REG with its own size,
    // so the daemon fans a separate sized feed per group (server keys clients
    // by addr:port; every distinct size = one registered client).

    private class StreamRelay(
        val size: Int,
        val ips: List<String>,
        val magicPoi: Boolean,
        val host: String,
        val port: Int,
        val partyId: Int?,
        /** Owner fps flag goes on the FIRST registration only (one voice). */
        val ownerFlag: Boolean,
    ) {
        var thread: Thread? = null
        var socket: DatagramSocket? = null
        @Volatile var running = false
        @Volatile var connected = false
        val relayed = java.util.concurrent.atomic.AtomicLong(0)

        // Jitter buffer: absorbs WAN/WiFi UDP bursts so columns go to the POIs
        // at a steady interval instead of burst-then-gap.
        private val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(size.coerceIn(16, 120))
        private var sender: Thread? = null
        @Volatile private var lastUiUpdate = 0L

        fun start(onStatus: (String) -> Unit) {
            running = true
            val t = Thread {
                try {
                    val addr = InetAddress.getByName(host)
                    val s = DatagramSocket()
                    s.soTimeout = 1500
                    socket = s

                    val px = size.coerceIn(16, 120)
                    val fps = PoiState.fpsCap.coerceIn(0.5f, 30f)
                    val regMsg = if (magicPoi) {
                        val owner = if (ownerFlag) ",\"owner\":true" else ""
                        "$MP_REG_MSG {\"party\":$partyId,\"size\":$px,\"fps\":$fps$owner}"
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
                                val okPrefix = if (magicPoi) MP_REG_OK_PREFIX else REG_OK_PREFIX
                                if (reply.startsWith(okPrefix)) {
                                    // every relay of a party gets the same fps back
                                    ServerBridge.serverFps = reply.substringAfter("fps=", "")
                                        .trim().toFloatOrNull() ?: ServerBridge.serverFps
                                    if (magicPoi) {
                                        ServerBridge.magicPoiPartyState = reply
                                            .substringAfter("state=", "").trim()
                                            .substringBefore(" ").trim()
                                    }
                                    registered = true
                                    break@outer
                                }
                                // else: stream datagram — keep draining
                            }
                        } catch (_: SocketTimeoutException) {}
                    }
                    if (!registered) {
                        onStatus("[$px px] No reply from server $host:$port — check IP / firewall")
                        running = false
                        return@Thread
                    }
                    connected = true

                    // Sender thread drains the jitter buffer at the target column
                    // interval, decoupling POI pacing from network burstiness.
                    sender = Thread(Runnable { senderLoop() }, "smartpoi-send-${size}px").apply {
                        isDaemon = true
                        start()
                    }

                    val buf = ByteArray(1024)
                    val rx = DatagramPacket(buf, buf.size)
                    var lastPacketAt = System.currentTimeMillis()
                    var lastPingAt = 0L
                    while (running) {
                        val now = System.currentTimeMillis()
                        // per-relay keepalive: each socket is an independent client —
                        // a shared ping would NOT keep the other sizes alive (15s prune)
                        if (now - lastPingAt > PING_INTERVAL_MS) {
                            lastPingAt = now
                            try {
                                val ping = (if (magicPoi)
                                    "$MP_PING_MSG {\"party\":$partyId}"
                                else PING_MSG).toByteArray()
                                s.send(DatagramPacket(ping, ping.size, addr, port))
                            } catch (_: Exception) {}
                        }
                        try {
                            s.receive(rx)
                            lastPacketAt = System.currentTimeMillis()
                            val payload = ByteArray(rx.length)
                            System.arraycopy(rx.data, rx.offset, payload, 0, rx.length)
                            // drop-oldest if the buffer is full (stay current)
                            if (!queue.offer(payload)) {
                                queue.poll()
                                queue.offer(payload)
                            }
                        } catch (_: SocketTimeoutException) {
                            if (running && System.currentTimeMillis() - lastPacketAt > 4000) {
                                onStatus("[$px px] No packets from server for 4s… still listening")
                                lastPacketAt = System.currentTimeMillis()
                            }
                        } catch (e: Exception) {
                            if (running) onStatus("[$px px] Relay error: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    if (running) onStatus("[${size} px] Relay stopped: ${e.message}")
                } finally {
                    connected = false
                    running = false
                    ServerBridge.recomputeConnected()
                }
            }.apply { name = "smartpoi-bridge-${size}px"; isDaemon = true }
            thread = t
            t.start()
        }

        private fun targetStepMs(): Long {
            val fps = ServerBridge.serverFps.takeIf { it > 0f } ?: PoiState.fpsCap.coerceIn(0.5f, 30f)
            val cols = (size.coerceIn(16, 120) / 2).coerceAtLeast(1)
            return (1000.0 / (fps * cols)).toLong().coerceAtLeast(1L)
        }

        private fun senderLoop() {
            val stepMs = targetStepMs()
            var nextSendAt = 0L
            while (true) {
                val payload = try {
                    queue.take()
                } catch (e: InterruptedException) {
                    break
                }
                val now = System.nanoTime()
                if (nextSendAt > now) {
                    Thread.sleep((nextSendAt - now) / 1_000_000)
                }
                PoiState.sendRowTo(ips, payload)
                relayed.incrementAndGet()
                nextSendAt = System.nanoTime() + stepMs * 1_000_000L
                // throttled UI counter update (~4/s) — never per-packet
                val t = System.currentTimeMillis()
                if (t - lastUiUpdate >= 250) {
                    lastUiUpdate = t
                    ServerBridge.packetsRelayed = ServerBridge.streams.sumOf { it.relayed.get() }
                }
            }
        }

        /** UNREG from the SAME socket that registered, then close. */
        fun stop() {
            running = false
            sender?.interrupt()
            thread?.interrupt()
            val s = socket
            if (s != null && !s.isClosed) {
                try {
                    val msg = (if (magicPoi && partyId != null)
                        "$MP_UNREG_MSG {\"party\":$partyId}"
                    else UNREG_MSG).toByteArray()
                    s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(host), port))
                } catch (_: Exception) {}
            }
            try { s?.close() } catch (_: Exception) {}
            socket = null
        }
    }

    private val streams = java.util.concurrent.CopyOnWriteArrayList<StreamRelay>()

    private fun recomputeConnected() {
        connected = streams.any { it.connected }
    }


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
     * Multi-size relay. Groups the configured POIs by their per-POI size
     * (PoiState.sizeAt — detected via /get-pixels, falls back to the global
     * Settings default) and starts ONE StreamRelay per distinct size, each with
     * its own socket + REG. The daemon streams a separate sized feed per group.
     */
    fun start(onStatus: (String) -> Unit) {
        if (running) return
        val mp = magicPoiMode
        val host = if (mp) magicPoiHost else serverIp
        val port = if (mp) magicPoiUdpPort else udpPort
        val partyId = MagicPoi.joinedPartyId
        if (mp && (host.isBlank() || partyId == null)) {
            onStatus("Join a party in the Magic Poi tab first")
            return
        }

        // Group configured POI slots by effective size.
        val groups = LinkedHashMap<Int, MutableList<String>>()
        for (i in PoiState.poiIndices()) {
            groups.getOrPut(PoiState.sizeAt(i)) { mutableListOf() }.add(PoiState.poiIps[i])
        }
        if (groups.isEmpty()) {
            onStatus("No POI IPs configured — add them in Settings")
            return
        }

        PoiState.startPresenceMonitor()
        packetsRelayed = 0
        running = true
        val sizes = groups.keys.toList()
        val mode = if (mp) "Magic Poi party #$partyId" else "legacy server"
        onStatus(
            "Bridging $host → ${PoiState.configuredIps().joinToString("/")} · $mode · " +
                sizes.joinToString("+") { "${it}px×${groups[it]!!.size}" }
        )

        // Start every size group; the OWNER fps flag rides on the first REG only
        // (owner sets the party rate once — N owner REGs would race each other).
        var first = true
        for ((px, ips) in groups) {
            val relay = StreamRelay(
                size = px,
                ips = ips,
                magicPoi = mp,
                host = host,
                port = port,
                partyId = partyId,
                ownerFlag = first && MagicPoi.joinedIsOwner,
            )
            streams.add(relay)
            relay.start(onStatus)
            first = false
        }
    }

    /**
     * Leave the stream: UNREG from EACH registered socket (the server keys
     * clients by source addr:port, so every size group must leave itself).
     */
    fun stop(onStatus: (String) -> Unit) {
        running = false
        connected = false
        PoiState.stopPresenceMonitor()
        val total = packetsRelayed
        val relays = streams.toList()
        streams.clear()
        for (r in relays) r.stop()
        onStatus("Left stream ($total packets relayed)")
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
            "Each POI gets its own stream at its own LED size (auto-detected via " +
            "/get-pixels, falling back to the Settings default, max 120).",
            color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp
        )
        val sizeSummary = PoiState.poiIndices()
            .map { "${PoiState.sizeAt(it)}px" }
            .distinct()
            .joinToString(" + ")
        Text(
            "Will request: $sizeSummary",
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
            "Relayed: ${ServerBridge.packetsRelayed} pkts  ·  POIs ${PoiState.configuredIps().joinToString(" / ")}",
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

        // ---- Global START/STOP (owner playback) under all parties ----
        // The relay + POI priming are handled by join/leave (Join primes POIs
        // and starts the relay; Leave stops it and turns LEDs off), so the only
        // remaining master control is playback for the party.
        val joined = MagicPoi.joinedPartyId != null
        val owner = joined && MagicPoi.joinedIsOwner
        val playing = joined && MagicPoi.partyState == "playing"

        Button(
            onClick = {
                if (playing) MagicPoi.stop { MagicPoi.partyState = "ready" }
                else MagicPoi.start { MagicPoi.partyState = "playing" }
            },
            enabled = owner,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(
                if (playing) "⏹ STOP" else "▶ START",
                fontWeight = FontWeight.Bold, fontSize = 16.sp
            )
        }
    }
}
