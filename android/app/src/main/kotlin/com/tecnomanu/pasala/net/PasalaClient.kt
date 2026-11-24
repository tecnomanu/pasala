package com.tecnomanu.pasala.net

import com.tecnomanu.pasala.data.FileResponse
import com.tecnomanu.pasala.data.HelloRequest
import com.tecnomanu.pasala.data.HelloResponse
import com.tecnomanu.pasala.data.OfferRequest
import com.tecnomanu.pasala.data.OfferResponse
import com.tecnomanu.pasala.data.PairRequest
import com.tecnomanu.pasala.data.PairResponse
import com.tecnomanu.pasala.data.PasalaJson
import com.tecnomanu.pasala.data.Peer
import com.tecnomanu.pasala.data.Store
import kotlinx.serialization.KSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class HttpStatusException(val code: Int, val error: String?) : IOException("HTTP $code ${error ?: ""}")

object PasalaClient {
    private val JSON = "application/json".toMediaType()
    private val OCTET = "application/octet-stream".toMediaType()

    private val base = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .build()

    /** hello / pair / unpair: 2 s to connect (protocol), short reads. */
    private val quick = base.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    /** offer: the receiver may hold the answer up to 60 s while the person decides. */
    private val offerClient = base.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS) // See HISTORY.md#offer-timeout
        .build()

    /** files: long streams. */
    private val upload = base.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun url(host: String, port: Int, path: String) = "http://$host:$port$path"

    private fun <T> exec(client: OkHttpClient, req: Request, ser: KSerializer<T>): T {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { PasalaJson.parseToJsonElement(text).let { (it as? kotlinx.serialization.json.JsonObject)?.get("error")?.toString()?.trim('"') } }.getOrNull()
                throw HttpStatusException(resp.code, err)
            }
            return PasalaJson.decodeFromString(ser, text)
        }
    }

    private fun Request.Builder.auth(peer: Peer) = this
        .header("X-Pasala-From", Store.identity.id)
        .header("Authorization", "Bearer ${peer.secret}")

    fun pair(host: String, port: Int, body: PairRequest): PairResponse {
        val req = Request.Builder().url(url(host, port, "/v1/pair"))
            .post(PasalaJson.encodeToString(PairRequest.serializer(), body).toRequestBody(JSON)).build()
        return exec(quick, req, PairResponse.serializer())
    }

    fun hello(host: String, port: Int, peer: Peer, me: HelloRequest): HelloResponse {
        val req = Request.Builder().url(url(host, port, "/v1/hello")).auth(peer)
            .post(PasalaJson.encodeToString(HelloRequest.serializer(), me).toRequestBody(JSON)).build()
        return exec(quick, req, HelloResponse.serializer())
    }

    fun offer(host: String, port: Int, peer: Peer, offer: OfferRequest): OfferResponse {
        val req = Request.Builder().url(url(host, port, "/v1/offer")).auth(peer)
            .post(PasalaJson.encodeToString(OfferRequest.serializer(), offer).toRequestBody(JSON)).build()
        return exec(offerClient, req, OfferResponse.serializer())
    }

    fun unpair(host: String, port: Int, peer: Peer) {
        val req = Request.Builder().url(url(host, port, "/v1/unpair")).auth(peer)
            .post(ByteArray(0).toRequestBody(null)).build()
        quick.newCall(req).execute().close()
    }

    /**
     * Streams one file. [open] is called once and the stream is copied straight to the
     * socket, so large videos never sit in memory.
     */
    fun sendFile(
        host: String, port: Int, peer: Peer,
        batch: String, index: Int, count: Int,
        name: String, mime: String, size: Long, modified: Long?,
        open: () -> InputStream,
        onProgress: (Long) -> Unit,
    ): FileResponse {
        val body = object : RequestBody() {
            override fun contentType() = OCTET
            override fun contentLength() = size
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buf = ByteArray(128 * 1024)
                    var sent = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        sink.write(buf, 0, n)
                        sent += n
                        if (sent - lastReport > 256 * 1024 || sent == size) {
                            lastReport = sent
                            onProgress(sent)
                        }
                    }
                    if (sent != size) throw IOException("file size changed while sending ($sent of $size)")
                    onProgress(sent)
                }
            }
        }
        val b = Request.Builder().url(url(host, port, "/v1/files")).auth(peer)
            .header("X-Pasala-Batch", batch)
            .header("X-Pasala-Index", index.toString())
            .header("X-Pasala-Count", count.toString())
            .header("X-Pasala-Name", URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
            .header("X-Pasala-Mime", mime)
        if (modified != null && modified > 0) b.header("X-Pasala-Modified", modified.toString())
        return exec(upload, b.post(body).build(), FileResponse.serializer())
    }
}
