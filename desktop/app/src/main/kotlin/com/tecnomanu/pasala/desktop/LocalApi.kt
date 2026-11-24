package com.tecnomanu.pasala.desktop

import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.core.Pairing
import com.tecnomanu.pasala.core.Sender
import com.tecnomanu.pasala.data.PasalaJson
import com.tecnomanu.pasala.data.Peer
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.net.Addresses
import com.tecnomanu.pasala.net.MiniHttpServer.HttpRequest
import com.tecnomanu.pasala.net.MiniHttpServer.HttpResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * `/local/...`: how `pasala-mcp`-style tools and a second launch (SendTo, file manager actions, the
 * hotkey fallback) talk to the running app. Loopback only, with a per-install token kept 0600 (D-006).
 */
object LocalApi {
    private var token = ""

    fun ensureToken() {
        val f = Paths.tokenFile
        token = f.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (token.isEmpty()) {
            token = Pairing.randomB64(32)
            f.writeText(token)
        }
        runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }

    fun handle(req: HttpRequest): HttpResponse {
        val loopback = req.remoteAddr == "127.0.0.1" || req.remoteAddr == "::1" || req.remoteAddr == "0:0:0:0:0:0:0:1"
        val given = req.header("X-Pasala-Local").orEmpty()
        if (!loopback || token.isEmpty() || !MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
            return HttpResponse.error(401, "unauthorized")
        }
        val body: JsonObject = if (req.method == "POST") {
            runCatching { PasalaJson.parseToJsonElement(req.body.readAllText(2 shl 20)).jsonObject }.getOrNull()
                ?: return HttpResponse.error(400, "bad_request")
        } else JsonObject(emptyMap())
        return when (req.path) {
            "/local/status" -> status()
            "/local/open" -> open(body)
            "/local/pair" -> pair(body)
            "/local/text" -> text(body)
            "/local/send" -> send(body)
            else -> HttpResponse.error(404, "not_found")
        }
    }

    private fun json(o: JsonObject, status: Int = 200) = HttpResponse(status, o.toString())

    private fun status(): HttpResponse {
        val online = Live.online.value
        return json(buildJsonObject {
            put("device", Store.identity.name)
            put("platform", com.tecnomanu.pasala.core.Platform.name)
            put("port", Live.advertisedPort)
            putJsonArray("addrs") { Addresses.local().all.forEach { add(JsonPrimitive(it)) } }
            put("inbox", Desktop.inbox.value.path)
            putJsonArray("devices") {
                Store.state.value.peers.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id); put("name", p.displayName); put("platform", p.platform)
                        put("online", online[p.id] == true)
                    })
                }
            }
        })
    }

    /** A second launch hands its arguments over and quits. */
    private fun open(o: JsonObject): HttpResponse {
        val files = o["files"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.map(::File).orEmpty()
        when (o["action"]?.jsonPrimitive?.contentOrNull) {
            "send-text" -> Ui.onMain { Desktop.beginSendText(Clipboard.text()) }
            "pair" -> Ui.onMain { Ui.showPair() }
            "settings" -> Ui.onMain { Ui.showSettings() }
            else -> Ui.onMain { if (files.isNotEmpty()) Ui.showSend(files) else Ui.showMain() }
        }
        return json(buildJsonObject { put("ok", true) })
    }

    private fun pair(o: JsonObject): HttpResponse {
        val link = o["link"]?.jsonPrimitive?.contentOrNull ?: return HttpResponse.error(400, "bad_request")
        val qr = Pairing.parse(link) ?: return json(buildJsonObject { put("error", "That is not a Pasala pairing link.") }, 400)
        return when (val r = runBlocking { Pairing.pairWith(qr) }) {
            is Pairing.Result.Ok -> json(buildJsonObject { put("ok", true); put("device", r.peer.displayName) })
            Pairing.Result.Expired -> json(buildJsonObject { put("error", "The code expired or was already used. Show a new one and try again.") }, 400)
            Pairing.Result.Unreachable -> json(buildJsonObject { put("error", "Could not reach ${qr.name}.") }, 400)
            Pairing.Result.Self -> json(buildJsonObject { put("error", "That is this computer's own code.") }, 400)
        }
    }

    private fun findPeer(dev: String): Peer? {
        val peers = Store.state.value.peers
        return peers.firstOrNull { it.id == dev }
            ?: peers.firstOrNull { it.displayName.equals(dev, ignoreCase = true) }
            ?: peers.firstOrNull { it.displayName.contains(dev, ignoreCase = true) }
    }

    private fun text(o: JsonObject): HttpResponse {
        val t = o["text"]?.jsonPrimitive?.contentOrNull
        val dev = o["device"]?.jsonPrimitive?.contentOrNull
        if (t.isNullOrEmpty() || dev == null) return HttpResponse.error(400, "bad_request")
        val peer = findPeer(dev) ?: return HttpResponse.error(404, "unknown_device")
        return when (val r = runBlocking { Sender.sendText(peer.id, t) }) {
            Sender.TextResult.Ok -> json(buildJsonObject { put("ok", true); put("device", peer.displayName) })
            else -> json(buildJsonObject { put("error", r.name.lowercase()) }, 500)
        }
    }

    private fun send(o: JsonObject): HttpResponse {
        val paths = o["paths"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
        val dev = o["device"]?.jsonPrimitive?.contentOrNull
        if (paths.isNullOrEmpty() || dev == null) return HttpResponse.error(400, "bad_request")
        val peer = findPeer(dev) ?: return HttpResponse.error(404, "unknown_device")
        val files = paths.map { File(it.replaceFirst(Regex("^~"), System.getProperty("user.home"))) }
        val missing = files.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            return json(buildJsonObject { put("error", "missing_files"); putJsonArray("paths") { missing.forEach { add(JsonPrimitive(it.path)) } } }, 400)
        }
        val job = Desktop.sendFiles(files, peer.id, o["collection"]?.jsonPrimitive?.contentOrNull)
            ?: return HttpResponse.error(400, "bad_request")
        val end = runBlocking { Sender.jobs.first { it[job]?.finished == true }[job]!! }
        Sender.forget(job)
        return if (end.phase == Sender.Phase.Done) {
            json(buildJsonObject { put("ok", true); put("device", peer.displayName); put("count", files.size) })
        } else json(buildJsonObject { put("error", end.phase.name.lowercase()) }, 500)
    }

    // ---- client side (second launch, MCP) ----

    /** Calls the running app; null if it is not running. */
    fun call(path: String, body: JsonElement? = null, timeoutMs: Int = 10_000): Pair<Int, JsonObject>? {
        if (!Paths.databaseFile.exists()) return null
        val port = runCatching { Index(Paths.databaseFile, readOnly = true).let { db -> db.setting("port").also { db.close() } } }.getOrNull() ?: return null
        val tok = Paths.tokenFile.takeIf { it.exists() }?.readText()?.trim() ?: return null
        return runCatching {
            val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 1500
            c.readTimeout = timeoutMs
            c.setRequestProperty("X-Pasala-Local", tok)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            code to (runCatching { PasalaJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap()))
        }.getOrNull()
    }

    fun openArgs(files: List<String>, action: String?): Boolean = call(
        "/local/open",
        buildJsonObject {
            putJsonArray("files") { files.forEach { add(JsonPrimitive(File(it).absolutePath)) } }
            action?.let { put("action", it) }
        },
    )?.first == 200
}
