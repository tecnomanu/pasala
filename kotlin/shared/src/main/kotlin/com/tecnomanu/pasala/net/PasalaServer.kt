package com.tecnomanu.pasala.net

import com.tecnomanu.pasala.core.Log
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.core.Platform
import com.tecnomanu.pasala.core.Pairing
import com.tecnomanu.pasala.core.Receiver
import com.tecnomanu.pasala.data.FileResponse
import com.tecnomanu.pasala.data.HelloRequest
import com.tecnomanu.pasala.data.HelloResponse
import com.tecnomanu.pasala.data.OfferRequest
import com.tecnomanu.pasala.data.OfferResponse
import com.tecnomanu.pasala.data.OkResponse
import com.tecnomanu.pasala.data.PairRequest
import com.tecnomanu.pasala.data.PairResponse
import com.tecnomanu.pasala.data.PasalaJson
import com.tecnomanu.pasala.data.Peer
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.data.TEXT_MAX_BYTES
import com.tecnomanu.pasala.data.TextRequest
import com.tecnomanu.pasala.net.MiniHttpServer.HttpRequest
import com.tecnomanu.pasala.net.MiniHttpServer.HttpResponse
import kotlinx.serialization.SerializationException
import java.io.EOFException
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Protocol routes (docs/PROTOCOL.md § Rutas). [local] handles the app's own `/local/...` routes
 * (desktop only; it checks loopback + its token itself) and returns null for anything else.
 */
class PasalaServer(private val local: ((HttpRequest) -> HttpResponse?)? = null) {
    private val http = MiniHttpServer(::route)

    fun start(): Int = http.start(Live.DEFAULT_PORT).also { Live.setPort(it) }
    fun stop() { http.stop(); Live.setPort(0) }

    private fun route(req: HttpRequest): HttpResponse {
        if (req.path.startsWith("/local/")) return local?.invoke(req) ?: HttpResponse.error(404, "not_found")
        if (req.method != "POST") return HttpResponse.error(405, "method_not_allowed")
        return try {
            when (req.path) {
                "/v1/pair" -> pair(req)
                "/v1/hello" -> authed(req) { hello(it, req) }
                "/v1/offer" -> authed(req) { offer(it, req) }
                "/v1/files" -> authed(req) { files(it, req) }
                "/v1/unpair" -> authed(req) { unpair(it) }
                "/v1/text" -> authed(req) { text(it, req) }
                else -> HttpResponse.error(404, "not_found")
            }
        } catch (e: SerializationException) {
            HttpResponse.error(400, "bad_request")
        } catch (e: IllegalArgumentException) {
            HttpResponse.error(400, "bad_request")
        }
    }

    private fun authed(req: HttpRequest, block: (Peer) -> HttpResponse): HttpResponse {
        val from = req.header("X-Pasala-From") ?: return HttpResponse.error(401, "unauthorized")
        val auth = req.header("Authorization") ?: return HttpResponse.error(401, "unauthorized")
        val token = auth.removePrefix("Bearer ").trim()
        val peer = Store.peer(from)
        // Compare even when the peer is unknown so timing doesn't reveal which ids exist.
        val expected = peer?.secret ?: "x".repeat(43)
        val ok = MessageDigest.isEqual(expected.toByteArray(), token.toByteArray())
        if (peer == null || !ok) return HttpResponse.error(401, "unauthorized")
        return block(peer)
    }

    private fun me(): HelloResponse {
        val id = Store.identity
        return HelloResponse(id.id, id.name, Platform.name, Live.advertisedPort, Addresses.local().all, Receiver.collections())
    }

    private fun pair(req: HttpRequest): HttpResponse {
        val body = PasalaJson.decodeFromString(PairRequest.serializer(), req.body.readAllText())
        if (body.id.isBlank() || body.code.isBlank()) return HttpResponse.error(400, "bad_request")
        val resp = Pairing.acceptIncoming(body) ?: return HttpResponse.error(403, "bad_code")
        Log.i(TAG, "paired with ${body.name} (${body.platform}) from ${req.remoteAddr}")
        return HttpResponse.ok(PasalaJson.encodeToString(PairResponse.serializer(), resp))
    }

    private fun hello(peer: Peer, req: HttpRequest): HttpResponse {
        val body = PasalaJson.decodeFromString(HelloRequest.serializer(), req.body.readAllText())
        Store.seen(peer.id, body.name, body.port, body.addrs)
        Live.setOnline(peer.id, true)
        return HttpResponse.ok(PasalaJson.encodeToString(HelloResponse.serializer(), me()))
    }

    private fun offer(peer: Peer, req: HttpRequest): HttpResponse {
        val body = PasalaJson.decodeFromString(OfferRequest.serializer(), req.body.readAllText())
        if (body.batch.isBlank()) return HttpResponse.error(400, "bad_request")
        Store.seen(peer.id)
        val accepted = Receiver.handleOffer(peer, body)
        return HttpResponse.ok(PasalaJson.encodeToString(OfferResponse.serializer(), OfferResponse(accepted)))
    }

    private fun files(peer: Peer, req: HttpRequest): HttpResponse {
        val batch = req.header("X-Pasala-Batch") ?: return HttpResponse.error(400, "bad_request")
        if (!Receiver.isAccepted(batch, peer.id)) return HttpResponse.error(409, "not_accepted")
        val index = req.header("X-Pasala-Index")?.toIntOrNull() ?: return HttpResponse.error(400, "bad_request")
        val count = req.header("X-Pasala-Count")?.toIntOrNull() ?: return HttpResponse.error(400, "bad_request")
        val rawName = req.header("X-Pasala-Name") ?: return HttpResponse.error(400, "bad_request")
        val name = runCatching { URLDecoder.decode(rawName.replace("+", "%2B"), "UTF-8") }.getOrDefault(rawName)
        val mime = req.header("X-Pasala-Mime") ?: "application/octet-stream"
        val modified = req.header("X-Pasala-Modified")?.toLongOrNull()
        val size = req.body.declaredLength
        if (size < 0) return HttpResponse.error(411, "length_required")
        return try {
            val final = Receiver.receiveFile(peer, batch, index, count, name, mime, size, modified, req.body)
            Store.seen(peer.id)
            HttpResponse.ok(PasalaJson.encodeToString(FileResponse.serializer(), FileResponse(true, final)))
        } catch (e: IllegalStateException) {
            HttpResponse.error(409, "not_accepted")
        } catch (e: EOFException) {
            HttpResponse.error(400, "incomplete")
        } catch (e: Receiver.Incomplete) {
            HttpResponse.error(400, "incomplete")
        } catch (e: IOException) {
            HttpResponse.error(500, "write_failed")
        }
    }

    private fun text(peer: Peer, req: HttpRequest): HttpResponse {
        // JSON escaping can grow the body; the 1 MB limit applies to the text itself.
        if (req.body.declaredLength > TEXT_MAX_BYTES * 2L) return HttpResponse.error(413, "too_large")
        val raw = try {
            req.body.readAllText(TEXT_MAX_BYTES * 2)
        } catch (e: EOFException) {
            return HttpResponse.error(400, "incomplete")
        } catch (e: IOException) {
            return HttpResponse.error(413, "too_large")
        }
        val body = PasalaJson.decodeFromString(TextRequest.serializer(), raw)
        // The id names a file on disk: only accept plain ids (UUIDs and the like).
        if (!TEXT_ID.matches(body.id)) return HttpResponse.error(400, "bad_request")
        if (body.text.toByteArray(Charsets.UTF_8).size > TEXT_MAX_BYTES) return HttpResponse.error(413, "too_large")
        Store.seen(peer.id)
        Receiver.receiveText(peer, body.id, body.text)
        return HttpResponse.ok(PasalaJson.encodeToString(OkResponse.serializer(), OkResponse(true)))
    }

    private fun unpair(peer: Peer): HttpResponse {
        Store.removePeer(peer.id)
        Log.i(TAG, "${peer.displayName} unpaired us")
        return HttpResponse.ok(PasalaJson.encodeToString(OkResponse.serializer(), OkResponse(true)))
    }

    companion object {
        private const val TAG = "Pasala"
        private val TEXT_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
