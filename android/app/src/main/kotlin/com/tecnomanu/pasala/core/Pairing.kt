package com.tecnomanu.pasala.core

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.tecnomanu.pasala.BuildConfig
import com.tecnomanu.pasala.data.PairRequest
import com.tecnomanu.pasala.data.PairResponse
import com.tecnomanu.pasala.data.PasalaJson
import com.tecnomanu.pasala.data.Peer
import com.tecnomanu.pasala.data.QrPayload
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.net.Addresses
import com.tecnomanu.pasala.net.HttpStatusException
import com.tecnomanu.pasala.net.PasalaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom

object Pairing {
    private const val TAG = "Pasala"
    const val CODE_TTL_MS = 5 * 60 * 1000L
    private val random = SecureRandom()

    fun randomB64(bytes: Int): String {
        val b = ByteArray(bytes).also { random.nextBytes(it) }
        return b64url(b)
    }

    fun b64url(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    data class Code(val code: String, val expiresAt: Long, val payload: String)

    private val _current = MutableStateFlow<Code?>(null)
    /** The code currently shown on screen, if any. */
    val current: StateFlow<Code?> = _current.asStateFlow()

    /** Creates a fresh one-time code and the QR text for it. Replaces any previous code. */
    @Synchronized
    fun newCode(): Code {
        val code = randomB64(16)
        val payload = QrPayload(
            id = Store.identity.id,
            name = Store.identity.name,
            platform = "android",
            port = Live.advertisedPort,
            addrs = Addresses.local().all,
            code = code,
        )
        val json = PasalaJson.encodeToString(QrPayload.serializer(), payload)
        val text = "pasala://pair?d=" + b64url(json.toByteArray(Charsets.UTF_8))
        if (BuildConfig.DEBUG) Log.i(TAG, "QR $text")
        return Code(code, System.currentTimeMillis() + CODE_TTL_MS, text).also { _current.value = it }
    }

    /** Stops accepting the current code (screen closed). */
    @Synchronized
    fun clear() { _current.value = null }

    /** Called by the receiving side of /v1/pair. One use only. */
    @Synchronized
    fun consume(code: String): Boolean {
        val c = _current.value ?: return false
        if (System.currentTimeMillis() > c.expiresAt) return false
        if (!MessageDigest.isEqual(c.code.toByteArray(), code.toByteArray())) return false
        _current.value = null
        return true
    }

    /** Parses `pasala://pair?d=...`. Returns null if it isn't a Pasala code. */
    fun parse(text: String): QrPayload? = runCatching {
        val uri = Uri.parse(text.trim())
        if (uri.scheme != "pasala" || uri.host != "pair") return null
        val d = uri.getQueryParameter("d") ?: return null
        val json = String(Base64.decode(d, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        PasalaJson.decodeFromString(QrPayload.serializer(), json).takeIf { it.v == 1 && it.id.isNotBlank() && it.code.isNotBlank() }
    }.getOrNull()

    sealed class Result {
        data class Ok(val peer: Peer) : Result()
        data object Expired : Result()
        data object Unreachable : Result()
        data object Self : Result()
    }

    /** This device scanned [qr]: contact the other device and store it as a peer. */
    suspend fun pairWith(qr: QrPayload): Result = withContext(Dispatchers.IO) {
        if (qr.id == Store.identity.id) return@withContext Result.Self
        val me = Store.identity
        val req = PairRequest(
            code = qr.code, id = me.id, name = me.name, platform = "android",
            port = Live.advertisedPort, addrs = Addresses.local().all,
        )
        var expired = false
        for (addr in qr.addrs) {
            try {
                val resp: PairResponse = PasalaClient.pair(addr, qr.port, req)
                val peer = Peer(
                    id = resp.id, name = resp.name, platform = resp.platform,
                    port = resp.port, addrs = resp.addrs.ifEmpty { qr.addrs }, secret = resp.secret,
                    pairedAt = System.currentTimeMillis(), lastSeen = System.currentTimeMillis(),
                    lastGoodAddr = addr,
                )
                Store.upsertPeer(peer)
                Live.setOnline(peer.id, true)
                Live.emitPaired(peer.id)
                return@withContext Result.Ok(peer)
            } catch (e: HttpStatusException) {
                if (e.code == 403) { expired = true; break }
                Log.w(TAG, "pair via $addr failed: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "pair via $addr failed: ${e.message}")
            }
        }
        if (expired) Result.Expired else Result.Unreachable
    }

    /** The other device scanned our code and called /v1/pair. */
    fun acceptIncoming(req: PairRequest): PairResponse? {
        if (!consume(req.code)) return null
        val secret = randomB64(32)
        val now = System.currentTimeMillis()
        Store.upsertPeer(
            Peer(
                id = req.id, name = req.name, platform = req.platform, port = req.port,
                addrs = req.addrs, secret = secret, pairedAt = now, lastSeen = now,
            )
        )
        Live.setOnline(req.id, true)
        Live.emitPaired(req.id)
        val me = Store.identity
        return PairResponse(me.id, me.name, "android", Live.advertisedPort, Addresses.local().all, secret)
    }
}
