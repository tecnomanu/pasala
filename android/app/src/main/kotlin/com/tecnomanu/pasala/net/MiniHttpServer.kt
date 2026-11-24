package com.tecnomanu.pasala.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small HTTP/1.1 server, one thread per connection, one request per connection
 * (`Connection: close`). Request bodies are streamed: fixed `Content-Length` or
 * `Transfer-Encoding: chunked`. Enough for the Pasala protocol and nothing more.
 */
class MiniHttpServer(private val handler: (HttpRequest) -> HttpResponse) {

    class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>, // lower-case keys
        val body: BodyStream,
        val remoteAddr: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    class HttpResponse(val status: Int, val json: String) {
        companion object {
            fun ok(json: String) = HttpResponse(200, json)
            fun error(status: Int, code: String) = HttpResponse(status, "{\"error\":\"$code\"}")
        }
    }

    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null
    @Volatile var port: Int = 0
        private set

    /** Binds on [preferredPort] or the next free one (up to +20). Returns the bound port. */
    @Synchronized
    fun start(preferredPort: Int): Int {
        stop()
        var lastError: IOException? = null
        for (p in preferredPort until preferredPort + 20) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p), 64)
                socket = ss
                port = p
                break
            } catch (e: IOException) {
                lastError = e
            }
        }
        val ss = socket ?: throw lastError ?: IOException("no port")
        val counter = AtomicInteger()
        val executor = Executors.newCachedThreadPool { r -> Thread(r, "pasala-http-${counter.incrementAndGet()}").apply { isDaemon = true } }
        pool = executor
        Thread({
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (e: IOException) { break }
                executor.execute { serve(client) }
            }
        }, "pasala-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on port $port")
        return port
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun serve(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = 30_000
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
                val out = s.getOutputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 3) { write(out, HttpResponse.error(400, "bad_request")); return }
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush()
                }
                val body: BodyStream = when {
                    headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> ChunkedBody(input)
                    else -> FixedBody(input, headers["content-length"]?.toLongOrNull() ?: 0L)
                }
                val path = parts[1].substringBefore('?')
                val remote = (s.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: ""
                val response = try {
                    handler(HttpRequest(parts[0].uppercase(), path, headers, body, remote))
                } catch (e: Exception) {
                    Log.e(TAG, "handler failed for $path", e)
                    HttpResponse.error(500, "internal")
                }
                // Let a small unread body drain so the client sees our answer instead of a reset.
                if (!body.isComplete && body.declaredLength in 0..(1L shl 20)) runCatching { body.drain() }
                write(out, response)
            } catch (e: SocketException) {
                // client went away
            } catch (e: IOException) {
                Log.w(TAG, "connection error: ${e.message}")
            }
        }
    }

    private fun write(out: OutputStream, r: HttpResponse) {
        val bytes = r.json.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 ${r.status} ${reason(r.status)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; else -> "Error"
    }

    companion object {
        private const val TAG = "Pasala"

        /** Reads a CRLF (or LF) terminated ISO-8859-1 line; null on EOF before any byte. */
        internal fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > 16 * 1024) throw IOException("header line too long")
            }
            return sb.toString()
        }
    }
}

/** Request body. Throws [EOFException] if the peer closes before the declared end. */
abstract class BodyStream : InputStream() {
    abstract val isComplete: Boolean
    /** -1 when unknown (chunked). */
    abstract val declaredLength: Long
    fun drain() { val buf = ByteArray(8192); while (read(buf) != -1) Unit }
    fun readAllText(limit: Int = 1 shl 20): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf); if (n == -1) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("body too large")
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

private class FixedBody(private val input: InputStream, private val length: Long) : BodyStream() {
    private var remaining = length
    override val isComplete get() = remaining == 0L
    override val declaredLength get() = length
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = input.read()
        if (b == -1) throw EOFException("body ended early")
        remaining--
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n == -1) throw EOFException("body ended early")
        remaining -= n
        return n
    }
}

private class ChunkedBody(private val input: InputStream) : BodyStream() {
    private var chunkLeft = 0L
    private var done = false
    override val isComplete get() = done
    override val declaredLength get() = -1L

    private fun nextChunk(): Boolean {
        if (done) return false
        val line = MiniHttpServer.readLine(input) ?: throw EOFException("chunk header missing")
        val size = line.substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("bad chunk size")
        if (size == 0L) {
            // trailers
            while (true) { val t = MiniHttpServer.readLine(input) ?: break; if (t.isEmpty()) break }
            done = true
            return false
        }
        chunkLeft = size
        return true
    }

    private fun endChunkIfNeeded() {
        if (chunkLeft == 0L) MiniHttpServer.readLine(input) // CRLF after chunk data
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (done) return -1
        if (chunkLeft == 0L && !nextChunk()) return -1
        val n = input.read(b, off, minOf(len.toLong(), chunkLeft).toInt())
        if (n == -1) throw EOFException("chunk ended early")
        chunkLeft -= n
        endChunkIfNeeded()
        return n
    }
}
