package za.tomjuggler.smartpoiudpextras

import android.content.Context
import androidx.compose.foundation.layout.*
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Magic Poi account + party API client.
 *
 * Credentials are stored in SharedPreferences so the login survives app
 * restarts, and a JWT that expired server-side is silently re-acquired with
 * the stored credentials on the next 401/422 (no re-typing).
 *
 * Endpoints (all JSON):
 *   POST /api/stream/signup              {username,password} -> {token}
 *   POST /api/login                      {username,password} -> {token}
 *   GET  /api/stream/parties             Bearer -> {parties:[...],udp_port}
 *   POST /api/stream/party/<id>/join     Bearer -> {udp_host,udp_port,party_id,...}
 *   POST /api/stream/party/<id>/start    Bearer (OWNER) -> {success}
 *   POST /api/stream/party/<id>/stop     Bearer (OWNER) -> {success}
 *   GET  /api/stream/party/<id>/state    Bearer -> {state,...}
 *
 * Login lives on the SETTINGS tab; party join/start/stop + the ON/OFF bridge
 * live on the "Magic Poi" tab (ServerBridge screen, party mode).
 */
object MagicPoi {
    var serverHost by mutableStateOf("magicpoi.duckdns.org")
    var serverPort by mutableStateOf(80)
    /** Daemon UDP port (stream + REG/PING). Default 2393. */
    var udpPort by mutableStateOf(2393)

    /** Full base URL: https on port 443, otherwise http (lets a local test
     *  server on port 5000 be reached as http://host:5000). */
    val baseUrl: String
        get() {
            val host = serverHost.trim().trimEnd('/').ifBlank { "magicpoi.duckdns.org" }
            val scheme = if (serverPort == 443) "https" else "http"
            return "$scheme://$host:$serverPort"
        }

    var token by mutableStateOf<String?>(null)
    var username by mutableStateOf<String?>(null)
    /** Kept so an expired JWT can be refreshed silently. Cleared on explicit logout. */
    var password by mutableStateOf<String?>(null)
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
        serverHost = p.getString("magicpoi_host", serverHost) ?: serverHost
        serverPort = p.getInt("magicpoi_port", -1).takeIf { it > 0 } ?: serverPort
        udpPort = p.getInt("magicpoi_udpport", -1).takeIf { it > 0 } ?: udpPort
        token = p.getString("magicpoi_token", null)
        username = p.getString("magicpoi_user", null)
        password = p.getString("magicpoi_pass", null)
        // Migrate the legacy single-URL pref if present and no new host stored yet.
        val legacy = p.getString("magicpoi_url", null)
        if (legacy != null && !p.contains("magicpoi_host")) {
            runCatching { java.net.URI(legacy) }.getOrNull()?.let { u ->
                u.host?.let { serverHost = it }
                serverPort = if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80
            }
            save(ctx)
        }
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("SmartPoiPrefs", Context.MODE_PRIVATE).edit()
            .putString("magicpoi_host", serverHost)
            .putInt("magicpoi_port", serverPort)
            .putInt("magicpoi_udpport", udpPort)
            .putString("magicpoi_token", token)
            .putString("magicpoi_user", username)
            .putString("magicpoi_pass", password)
            .apply()
    }

    /** Explicit log out (Settings tab): clears everything including saved creds. */
    fun logout() {
        token = null
        username = null
        password = null
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

    // ------------------------------------------- silent JWT refresh ----

    /** Blocking; returns true if we now hold a fresh token. */
    private fun silentRelogin(): Boolean {
        val u = username ?: return false
        val p = password ?: return false
        return try {
            val r = post("/api/login", JSONObject().put("username", u).put("password", p), auth = false)
            token = r.optString("token", "").ifEmpty { null }
            token != null
        } catch (_: Exception) {
            false
        }
    }

    private fun isAuthStale(e: HttpError) = e.code == 401 || e.code == 422

    /** GET that transparently re-logs-in once when the stored JWT expired. */
    private fun getWithAuth(path: String): JSONObject =
        try {
            get(path)
        } catch (e: HttpError) {
            if (isAuthStale(e) && silentRelogin()) get(path) else throw e
        }

    /** Authed POST that transparently re-logs-in once when the stored JWT expired. */
    private fun postWithAuth(path: String, body: JSONObject): JSONObject =
        try {
            post(path, body, auth = true)
        } catch (e: HttpError) {
            if (isAuthStale(e) && silentRelogin()) post(path, body, auth = true) else throw e
        }

    // ------------------------------------------------------ API calls ----

    /** Log in only. Sign-up happens on the web (magicpoi.com/auth) — it needs an
     *  invitation code; the API /api/stream/signup must not be used from the app. */
    fun login(user: String, pass: String, onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().put("username", user.trim()).put("password", pass)
                val resp = post("/api/login", body, auth = false)
                token = resp.optString("token", "").ifEmpty { null }
                username = resp.optString("username", user.trim()).ifEmpty { user.trim() }
                password = pass            // persist for silent re-login later
                onResult("Welcome ${username}")
            } catch (e: HttpError) {
                // /api/login returns plain text on 401 in the legacy server
                onResult(if (e.code == 401) "Wrong username or password" else shortErr(e))
            } catch (e: Exception) {
                onResult("Connection failed — ${e.message}")
            }
        }
    }

    fun listParties(onResult: (String, org.json.JSONArray?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resp = getWithAuth("/api/stream/parties")
                withContext(Dispatchers.Main) {
                    onResult("OK", resp.optJSONArray("parties"))
                }
            } catch (e: HttpError) {
                if (isAuthStale(e)) logout()   // creds also gone/stale — real logout
                withContext(Dispatchers.Main) { onResult(shortErr(e), null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult("Connection failed — ${e.message}", null) }
            }
        }
    }

    fun join(partyId: Int, onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resp = postWithAuth("/api/stream/party/$partyId/join", JSONObject())
                ServerBridge.magicPoiHost = resp.optString("udp_host")
                // UDP destination port comes from Settings (default 2393) so the
                // user can point at a local test daemon on a different port.
                ServerBridge.magicPoiUdpPort = udpPort
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
                postWithAuth("/api/stream/party/$id/start", JSONObject())
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
                postWithAuth("/api/stream/party/$id/stop", JSONObject())
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
                val resp = getWithAuth("/api/stream/party/$id/state")
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
