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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Magic Poi party streaming — auth (JWT) + party list + join/start/stop.
 *
 * Server: MagicPoiAlphaServer (udp_upgrade branch) with magicpoi-streamd
 * behind it. Endpoints (all JSON):
 *   POST /api/stream/signup              {username,password} -> {token}
 *   POST /api/login                      {username,password} -> {token}
 *   GET  /api/stream/parties             Bearer -> {parties:[...],udp_port}
 *   POST /api/stream/party/<id>/join     Bearer -> {udp_host,udp_port,party_id,...}
 *   POST /api/stream/party/<id>/start    Bearer (OWNER) -> {success}
 *   POST /api/stream/party/<id>/stop     Bearer (OWNER) -> {success}
 *
 * UDP (to join's udp_host:udp_port):
 *   MAGICPOI_REG {"party":ID,"size":N}  -> MAGICPOI_REG_OK party=ID state=...
 *   MAGICPOI_PING / MAGICPOI_UNREG {"party":ID}
 * Joining before the owner starts gets the idle cylon ("waiting for start").
 */
object MagicPoi {
    var baseUrl by mutableStateOf("http://192.168.8.100:5000")
    var token by mutableStateOf<String?>(null)
    var username by mutableStateOf<String?>(null)
    var joinedPartyId by mutableStateOf<Int?>(null)
    var joinedIsOwner by mutableStateOf(false)
    /** Daemon party state: idle / ready (cylon) / playing / unknown */
    var partyState by mutableStateOf("unknown")

    const val REG_MSG = "MAGICPOI_REG"
    const val REG_OK_PREFIX = "MAGICPOI_REG_OK"
    const val PING_MSG = "MAGICPOI_PING"
    const val UNREG_MSG = "MAGICPOI_UNREG"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("SmartPoiPrefs", Context.MODE_PRIVATE)
        baseUrl = p.getString("magicpoi_url", baseUrl) ?: baseUrl
        token = p.getString("magicpoi_token", null)
        username = p.getString("magicpoi_user", null)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("SmartPoiPrefs", Context.MODE_PRIVATE).edit()
            .putString("magicpoi_url", baseUrl)
            .putString("magicpoi_token", token)
            .putString("magicpoi_user", username)
            .apply()
    }

    fun logout() {
        token = null
        username = null
        joinedPartyId = null
    }

    private val base: String
        get() = baseUrl.trimEnd('/')

    // ---------------------------------------------------------- HTTP ----

    private class HttpError(val code: Int, val body: String) : Exception("HTTP $code: $body")

    /** Blocking JSON POST; call from Dispatchers.IO. */
    private fun post(path: String, body: JSONObject, auth: Boolean): JSONObject {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (auth && token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) throw HttpError(code, text)
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking JSON GET; call from Dispatchers.IO. */
    private fun get(path: String): JSONObject {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "Bearer $token")
        try {
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) throw HttpError(code, text)
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------ API calls ----

    fun signupOrLogin(signup: Boolean, user: String, pass: String, onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val path = if (signup) "/api/stream/signup" else "/api/login"
                val body = JSONObject().put("username", user.trim()).put("password", pass)
                val resp = post(path, body, auth = false)
                token = resp.optString("token", null)
                username = resp.optString("username", user.trim())
                onResult("Welcome ${username}")
            } catch (e: HttpError) {
                // /api/login returns plain text on 401 in the legacy server
                onResult(if (e.code == 401) "Wrong username or password" else shortErr(e))
            } catch (e: Exception) {
                onResult("Connection failed — ${e.message}")
            }
        }
    }

    fun listParties(onResult: (String, JSONArray?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resp = get("/api/stream/parties")
                withContext(Dispatchers.Main) {
                    onResult("OK", resp.optJSONArray("parties"))
                }
            } catch (e: HttpError) {
                if (e.code == 401 || e.code == 422) logout()
                withContext(Dispatchers.Main) { onResult(shortErr(e), null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult("Connection failed — ${e.message}", null) }
            }
        }
    }

    fun join(partyId: Int, onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resp = post("/api/stream/party/$partyId/join", JSONObject(), auth = true)
                ServerBridge.magicPoiHost = resp.optString("udp_host")
                ServerBridge.magicPoiUdpPort = resp.optInt("udp_port", 2393)
                joinedPartyId = resp.optInt("party_id")
                joinedIsOwner = resp.optBoolean("is_owner")
                partyState = resp.optString("state", "ready")
                withContext(Dispatchers.Main) {
                    onResult("Joined — UDP ${ServerBridge.magicPoiHost}:${ServerBridge.magicPoiUdpPort}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult("Join failed — ${e.message}") }
            }
        }
    }

    fun start(onResult: (String) -> Unit) {
        val id = joinedPartyId ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                post("/api/stream/party/$id/start", JSONObject(), auth = true)
                withContext(Dispatchers.Main) { onResult("Party started!") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult("Start failed — ${e.message}") }
            }
        }
    }

    fun stop(onResult: (String) -> Unit) {
        val id = joinedPartyId ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                post("/api/stream/party/$id/stop", JSONObject(), auth = true)
                withContext(Dispatchers.Main) { onResult("Stopped (cylon resumes)") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult("Stop failed — ${e.message}") }
            }
        }
    }

    fun refreshState() {
        val id = joinedPartyId ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resp = get("/api/stream/party/$id/state")
                partyState = resp.optString("state", "unknown")
            } catch (_: Exception) {
            }
        }
    }

    private fun shortErr(e: HttpError): String {
        val m = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(e.body)?.groupValues?.get(1)
        return m ?: "Server error ${e.code}"
    }
}

// ------------------------------------------------------------- screens ----

@Composable
fun MagicPoiScreen() {
    var stat by remember { mutableStateOf("Idle") }
    var parties by remember { mutableStateOf<JSONArray?>(null) }

    // Logged out: auth form
    if (MagicPoi.token == null) {
        AuthForm { msg ->
            stat = msg
            if (MagicPoi.token != null) MagicPoi.listParties { m, arr ->
                stat = m; parties = arr
            }
        }
        Text(stat, color = NeonYellow, fontSize = 13.sp)
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Magic Poi · ${MagicPoi.username}", color = NeonCyan,
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { MagicPoi.logout(); parties = null }) { Text("Log out") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                MagicPoi.listParties { m, arr -> stat = m; parties = arr }
            }) { Text("Refresh parties") }
            if (MagicPoi.joinedPartyId != null && MagicPoi.joinedIsOwner) {
                if (MagicPoi.partyState == "playing") {
                    Button(onClick = {
                        MagicPoi.stop { stat = it; MagicPoi.partyState = "ready" }
                    }, colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta)) {
                        Text("STOP party")
                    }
                } else {
                    Button(onClick = {
                        MagicPoi.start { stat = it; MagicPoi.partyState = "playing" }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))) {
                        Text("START party")
                    }
                }
            }
        }

        if (MagicPoi.joinedPartyId != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Joined party #${MagicPoi.joinedPartyId}", color = NeonCyan, fontWeight = FontWeight.Bold)
                    val stateText = when (MagicPoi.partyState) {
                        "ready" -> "Ready — waiting for owner to start (cylon showing)"
                        "playing" -> "PLAYING — timeline streaming"
                        "idle" -> "Waiting for timeline load"
                        else -> "State: ${MagicPoi.partyState}"
                    }
                    Text(stateText, color = NeonYellow, fontSize = 13.sp)
                    Text(
                        "Turn ON the bridge below (or it may already be relaying) — " +
                        "POIs show the party stream.",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
                    )
                }
            }
        }

        val arr = parties
        if (arr == null) {
            Text("Tap Refresh parties to load", color = Color.White.copy(alpha = 0.6f))
        } else if (arr.length() == 0) {
            Text("No parties yet — create one on the Magic Poi website, or get invited.",
                color = Color.White.copy(alpha = 0.6f))
        } else {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val pid = p.optInt("party_id")
                val state = p.optString("state", "unknown")
                PartyCard(
                    title = p.optString("title", "Party $pid"),
                    subtitle = "by ${p.optString("owner", "?")} · timeline: " +
                        "${p.optString("timeline_title", "none")} · $state",
                    joined = MagicPoi.joinedPartyId == pid,
                    onJoin = {
                        stat = "Joining…"
                        MagicPoi.join(pid) { msg ->
                            stat = msg
                            MagicPoi.refreshState()
                        }
                    }
                )
            }
        }
        Text(stat, color = NeonYellow, fontSize = 13.sp)
    }
}

@Composable
fun PartyCard(title: String, subtitle: String, joined: Boolean, onJoin: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = NeonCyan, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
            }
            if (joined) {
                Text("JOINED", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            } else {
                Button(onClick = onJoin) { Text("Join") }
            }
        }
    }
}

@Composable
fun AuthForm(onResult: (String) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Magic Poi", color = NeonMagenta, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Sign in to join party streams", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)

        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                if (user.isBlank() || pass.isBlank()) return@Button
                busy = true
                MagicPoi.signupOrLogin(false, user, pass) { busy = false; onResult(it) }
            }, enabled = !busy) { Text("Log in") }
            OutlinedButton(onClick = {
                if (user.isBlank() || pass.isBlank()) return@OutlinedButton
                busy = true
                MagicPoi.signupOrLogin(true, user, pass) { busy = false; onResult(it) }
            }, enabled = !busy) { Text("Sign up") }
        }

        Text("Server address", color = NeonCyan, fontSize = 13.sp)
        OutlinedTextField(
            value = MagicPoi.baseUrl,
            onValueChange = { MagicPoi.baseUrl = it.trim() },
            label = { Text("http://host:port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
