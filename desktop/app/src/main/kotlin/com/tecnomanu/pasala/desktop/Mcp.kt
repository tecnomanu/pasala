package com.tecnomanu.pasala.desktop

import com.tecnomanu.pasala.data.PasalaJson
import com.tecnomanu.pasala.data.PersistedState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.PrintStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

/**
 * `Pasala --mcp`: stdio MCP server over the index, with the same tools as the Mac's `pasala-mcp`
 * (apple/Sources/PasalaMCP/main.swift). Anything that sends goes through the running app (D-006).
 */
object Mcp {
    private lateinit var db: Index
    private val out = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8")

    fun run() {
        System.setOut(System.err) // keep stdout for JSON-RPC only
        db = Index()
        val input = System.`in`.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = input.readLine() ?: break
            val msg = runCatching { PasalaJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            val id = msg["id"] ?: continue // notifications
            val params = (msg["params"] as? JsonObject) ?: JsonObject(emptyMap())
            when (msg["method"]?.jsonPrimitive?.contentOrNull) {
                "initialize" -> reply(id, buildJsonObject {
                    put("protocolVersion", params["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: "2025-06-18")
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") { put("name", "pasala"); put("version", "0.1.0") }
                    put("instructions", "Pasala moves files between the user's phones and this computer. Use pasala_new_files to see what just arrived, pasala_get_file to look at one, pasala_send to push files to a phone.")
                })
                "tools/list" -> reply(id, buildJsonObject { put("tools", tools) })
                "tools/call" -> reply(id, call(
                    params["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    (params["arguments"] as? JsonObject) ?: JsonObject(emptyMap()),
                ))
                "ping" -> reply(id, JsonObject(emptyMap()))
                else -> write(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id)
                    putJsonObject("error") { put("code", -32601); put("message", "Method not found") }
                })
            }
        }
    }

    private fun reply(id: JsonElement, result: JsonElement) = write(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
    private fun write(o: JsonObject) { out.println(o.toString()) }

    private fun text(o: JsonElement) = buildJsonObject {
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", PasalaJsonPretty.encodeToString(JsonElement.serializer(), o)) }) }
    }
    private fun fail(msg: String) = buildJsonObject {
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", msg) }) }
        put("isError", true)
    }

    private val PasalaJsonPretty = kotlinx.serialization.json.Json { prettyPrint = true }

    private fun describe(f: FileRecord) = buildJsonObject {
        put("id", f.id); put("name", f.name); put("path", f.path); put("mime", f.mime); put("size", f.size)
        put("size_human", human(f.size)); put("direction", if (f.direction == "in") "received" else "sent")
        put("device", f.peerName); put("batch", f.batch); put("at", Instant.ofEpochMilli(f.createdAt).toString())
        put("exists", File(f.path).exists()); put("seen_by_agent", f.agentSeen)
        put("collection", f.collection?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun human(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1 shl 20 -> "%.0f KB".format(b / 1024.0)
        b < 1 shl 30 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.1f GB".format(b / 1073741824.0)
    }

    private fun str(a: JsonObject, k: String) = a[k]?.jsonPrimitive?.contentOrNull

    private fun filters(a: JsonObject): Pair<String, List<Any?>> {
        val w = mutableListOf<String>(); val args = mutableListOf<Any?>()
        when (str(a, "direction") ?: "received") {
            "sent" -> w += "direction='out'"
            "all" -> {}
            else -> w += "direction='in'"
        }
        str(a, "since")?.let { s ->
            val t = runCatching { Instant.parse(s) }.getOrNull()
                ?: runCatching { LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
            if (t != null) { w += "created_at>=?"; args += t.toEpochMilli() / 1000.0 }
        }
        str(a, "device")?.takeIf { it.isNotEmpty() }?.let { w += "(peer_name LIKE ? OR peer_id=?)"; args += "%$it%"; args += it }
        str(a, "query")?.takeIf { it.isNotEmpty() }?.let { w += "name LIKE ?"; args += "%$it%" }
        when (val k = str(a, "kind")) {
            "image", "video", "audio" -> { w += "mime LIKE ?"; args += "$k/%" }
            "pdf" -> w += "mime='application/pdf'"
        }
        if (a["only_new"]?.jsonPrimitive?.booleanOrNull == true) w += "agent_seen=0"
        str(a, "collection")?.takeIf { it.isNotEmpty() }?.let { w += "(collection LIKE ? OR collection_id=?)"; args += "%$it%"; args += it }
        return (if (w.isEmpty()) "1=1" else w.joinToString(" AND ")) to args
    }

    private fun prop(type: String, desc: String? = null, enum: List<String>? = null) = buildJsonObject {
        put("type", type); desc?.let { put("description", it) }
        enum?.let { e -> putJsonArray("enum") { e.forEach { add(JsonPrimitive(it)) } } }
    }

    private val common = mapOf(
        "direction" to prop("string", "Default: received", listOf("received", "sent", "all")),
        "since" to prop("string", "ISO 8601 date or datetime; only files at or after it"),
        "device" to prop("string", "Device name (partial match) or id"),
        "kind" to prop("string", enum = listOf("image", "video", "audio", "pdf")),
        "collection" to prop("string", "Collection name (partial match) or id the file was received into"),
        "limit" to prop("integer", "Default 50, max 500"),
    )

    private fun tool(name: String, desc: String, props: Map<String, JsonElement>, required: List<String> = emptyList()) = buildJsonObject {
        put("name", name); put("description", desc)
        putJsonObject("inputSchema") {
            put("type", "object"); put("properties", JsonObject(props))
            if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }
    }

    private val tools = buildJsonArray {
        add(tool("pasala_new_files", "Files received by Pasala on this computer that this agent has not seen yet (newest first). Marks them as seen unless mark_seen=false. Use it to answer 'did anything new arrive?'.",
            mapOf("mark_seen" to prop("boolean", "Default true"), "limit" to prop("integer"))))
        add(tool("pasala_list_files", "List files in the Pasala index (received from or sent to paired phones/computers) with path, type, size, device and date.",
            common + ("only_new" to prop("boolean"))))
        add(tool("pasala_search", "Search the Pasala index by file name.", common + ("query" to prop("string")), listOf("query")))
        add(tool("pasala_get_file", "Metadata of one file by id. With include_content=true returns the image itself (images up to 8 MB) or the text (text files up to 256 KB) so you can look at it.",
            mapOf("id" to prop("integer"), "include_content" to prop("boolean")), listOf("id")))
        add(tool("pasala_mark_seen", "Mark files as seen by the agent so pasala_new_files stops returning them.",
            mapOf("ids" to buildJsonObject { put("type", "array"); putJsonObject("items") { put("type", "integer") } }), listOf("ids")))
        add(tool("pasala_devices", "Paired devices, whether each is reachable now, this computer's inbox folder, its collections (named folders senders can pick) and stats.", emptyMap()))
        add(tool("pasala_pair", "Pair this computer with another device using its pairing link (pasala://pair?d=...), the same text its QR shows.",
            mapOf("link" to prop("string")), listOf("link")))
        add(tool("pasala_send_text", "Send a piece of text to a paired device; it shows there as 'text ready to copy' (the device's clipboard is not touched). Received texts appear in the index with mime text/x-pasala-snippet; use pasala_get_file with include_content to read one.",
            mapOf("text" to prop("string"), "device" to prop("string")), listOf("text", "device")))
        add(tool("pasala_send", "Send local files from this computer to a paired device (needs the Pasala app running). The device can be a name or id.",
            mapOf(
                "paths" to buildJsonObject { put("type", "array"); putJsonObject("items") { put("type", "string") } },
                "device" to prop("string"),
                "collection" to prop("string", "Optional id of one of the device's collections"),
            ), listOf("paths", "device")))
    }

    private const val NOT_RUNNING = "The Pasala app is not running on this computer. Open it and try again."

    private fun call(name: String, a: JsonObject): JsonObject {
        val limit = (a["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 500)
        return when (name) {
            "pasala_new_files" -> {
                val files = db.files("direction='in' AND agent_seen=0", limit = limit)
                if (a["mark_seen"]?.jsonPrimitive?.booleanOrNull != false) db.markAgentSeen(files.map { it.id })
                text(buildJsonObject { put("count", files.size); put("files", JsonArray(files.map(::describe))) })
            }
            "pasala_list_files", "pasala_search" -> {
                val (w, args) = filters(a)
                val files = db.files(w, args, limit)
                text(buildJsonObject { put("count", files.size); put("files", JsonArray(files.map(::describe))) })
            }
            "pasala_get_file" -> {
                val id = a["id"]?.jsonPrimitive?.longOrNull ?: return fail("No file with that id.")
                val f = db.files("id=?", listOf(id), 1).firstOrNull() ?: return fail("No file with that id.")
                val content = mutableListOf<JsonElement>(buildJsonObject { put("type", "text"); put("text", PasalaJsonPretty.encodeToString(JsonElement.serializer(), describe(f))) })
                val file = File(f.path)
                if (a["include_content"]?.jsonPrimitive?.booleanOrNull == true && file.exists()) {
                    content += when {
                        f.mime in listOf("image/png", "image/jpeg", "image/gif", "image/webp") && f.size <= 8 shl 20 ->
                            buildJsonObject { put("type", "image"); put("data", Base64.getEncoder().encodeToString(file.readBytes())); put("mimeType", f.mime) }
                        (f.mime.startsWith("text/") || f.mime == "application/json") && f.size <= 1 shl 20 ->
                            buildJsonObject { put("type", "text"); put("text", file.readText()) }
                        else -> buildJsonObject { put("type", "text"); put("text", "Content not inlined (type or size). Read it from the path.") }
                    }
                }
                db.markAgentSeen(listOf(f.id))
                buildJsonObject { put("content", JsonArray(content)) }
            }
            "pasala_mark_seen" -> {
                val ids = a["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty()
                db.markAgentSeen(ids)
                text(buildJsonObject { put("ok", true); put("marked", ids.size) })
            }
            "pasala_devices" -> devices()
            "pasala_pair" -> {
                val link = str(a, "link") ?: return fail("link is required.")
                val (status, body) = LocalApi.call("/local/pair", buildJsonObject { put("link", link) }, 30_000) ?: return fail(NOT_RUNNING)
                if (status == 200) text(body) else fail(body["error"]?.jsonPrimitive?.contentOrNull ?: "Pairing failed")
            }
            "pasala_send_text" -> {
                val t = str(a, "text"); val dev = str(a, "device")
                if (t.isNullOrEmpty() || dev == null) return fail("text and device are required.")
                val (status, body) = LocalApi.call("/local/text", buildJsonObject { put("text", t); put("device", dev) }, 30_000) ?: return fail(NOT_RUNNING)
                if (status == 200) text(body) else fail("Send failed ($status): $body")
            }
            "pasala_send" -> {
                val paths = a["paths"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                val dev = str(a, "device")
                if (paths.isNullOrEmpty() || dev == null) return fail("paths and device are required.")
                val req = buildJsonObject {
                    putJsonArray("paths") { paths.forEach { add(JsonPrimitive(it)) } }
                    put("device", dev); str(a, "collection")?.let { put("collection", it) }
                }
                val (status, body) = LocalApi.call("/local/send", req, 30 * 60_000) ?: return fail(NOT_RUNNING)
                if (status == 200) text(body) else fail("Send failed ($status): $body")
            }
            else -> fail("Unknown tool $name")
        }
    }

    private fun devices(): JsonObject {
        val live = LocalApi.call("/local/status", timeoutMs = 4000)?.second
        val onlineIds = live?.get("devices")?.jsonArray?.mapNotNull { d ->
            d.jsonObject.takeIf { it["online"]?.jsonPrimitive?.booleanOrNull == true }?.get("id")?.jsonPrimitive?.contentOrNull
        }.orEmpty().toSet()
        val state = runCatching { PasalaJson.decodeFromString(PersistedState.serializer(), Paths.stateFile.readText()) }.getOrNull() ?: PersistedState()
        val counts = db.query("SELECT peer_id, direction, COUNT(*) n FROM files GROUP BY peer_id, direction")
        fun count(id: String, dir: String) = (counts.firstOrNull { it["peer_id"] == id && it["direction"] == dir }?.get("n") as? Number)?.toLong() ?: 0L
        return text(buildJsonObject {
            put("app_running", live != null)
            put("inbox", live?.get("inbox")?.jsonPrimitive?.contentOrNull ?: db.setting("inbox") ?: Paths.defaultInbox.path)
            put("this_computer", db.setting("device_name") ?: state.identity?.name ?: "")
            putJsonArray("collections") { db.collections().forEach { c -> add(buildJsonObject { put("id", c.id); put("name", c.name); put("folder", c.path) }) } }
            putJsonArray("devices") {
                state.peers.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id); put("name", p.displayName); put("platform", p.platform); put("online", p.id in onlineIds)
                        put("last_seen", if (p.lastSeen > 0) JsonPrimitive(Instant.ofEpochMilli(p.lastSeen).toString()) else JsonNull)
                        put("received", count(p.id, "in")); put("sent", count(p.id, "out"))
                    })
                }
            }
        })
    }
}
