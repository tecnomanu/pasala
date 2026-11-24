package com.tecnomanu.pasala.core

import com.tecnomanu.pasala.data.Collection
import com.tecnomanu.pasala.data.HistoryItem
import com.tecnomanu.pasala.data.OfferRequest
import com.tecnomanu.pasala.data.Peer
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.data.TEXT_MIME
import com.tecnomanu.pasala.data.TextStore
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One file arriving, already sanitized and with its destination collection resolved. */
data class IncomingFile(
    val peer: Peer,
    val batch: String,
    val index: Int,
    val count: Int,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long?,
    /** Null = the receiver's default destination. */
    val collection: Collection?,
)

/** Where a received file ended up: its final name and a uri/path to open it. */
data class Saved(val name: String, val uri: String)

/** What receiving needs from the platform: where files go and how the person is told. */
interface ReceiverHost {
    /** This device's collections (protocol § hello). */
    fun collections(): List<Collection> = emptyList()
    fun mimeFromExtension(ext: String): String?
    /** Writes [body] to its destination and returns where it went. Calls [progress] with the final name and bytes
     * written. Must delete any partial output and throw on failure; throws [Receiver.Incomplete] if the body ended short. */
    fun save(file: IncomingFile, body: InputStream, progress: (name: String, written: Long) -> Unit): Saved
    /** An offer waits for a yes/no (only with "ask before receiving"). */
    fun offerWaiting(batch: String, peer: Peer, count: Int, totalSize: Long) {}
    fun offerClosed(batch: String) {}
    fun receiving(file: IncomingFile, name: String, written: Long) {}
    fun receiveFailed(file: IncomingFile) {}
    /** A file is in place. [batchDone] when it was the last of its batch; [got] = files of the batch so far. */
    fun received(file: IncomingFile, saved: Saved, item: HistoryItem, got: Int, batchDone: Boolean) {}
    fun textReady(peer: Peer, itemId: String, preview: String) {}
    /** Keep the device and its radio awake while a transfer runs. */
    fun busy(on: Boolean) {}
}

/** Receiving side: offers (with optional confirmation), batches and bookkeeping; [host] writes the bytes. */
object Receiver {
    private const val TAG = "Pasala"
    private const val BATCH_TTL_MS = 30 * 60 * 1000L

    lateinit var host: ReceiverHost

    private class Batch(val peerId: String, val count: Int, val collection: Collection?, @Volatile var touchedAt: Long) {
        val received = AtomicInteger()
    }
    private val accepted = ConcurrentHashMap<String, Batch>()

    private class Decision { val latch = CountDownLatch(1); val accept = AtomicBoolean(false) }
    private val waiting = ConcurrentHashMap<String, Decision>()

    fun collections(): List<Collection> = runCatching { host.collections() }.getOrDefault(emptyList())

    /** Blocks the calling (connection) thread up to 60 s when confirmation is on. */
    fun handleOffer(peer: Peer, offer: OfferRequest): Boolean {
        prune()
        if (offer.files.isEmpty()) return false
        val ok = if (!Store.settings.askBeforeReceiving) true else askPerson(peer, offer)
        // An unknown collection id (deleted meanwhile) falls back to the default destination (protocol § offer).
        val collection = offer.collection?.let { id -> collections().firstOrNull { it.id == id } }
        if (ok) accepted[offer.batch] = Batch(peer.id, offer.files.size, collection, System.currentTimeMillis())
        Log.i(TAG, "offer ${offer.batch} from ${peer.displayName}: ${offer.files.size} files -> ${if (ok) "accepted" else "declined"}")
        return ok
    }

    private fun askPerson(peer: Peer, offer: OfferRequest): Boolean {
        val d = Decision()
        waiting[offer.batch] = d
        val total = offer.files.sumOf { it.size }
        Live.addPendingOffer(Live.PendingOffer(offer.batch, peer.displayName, offer.files.size, total))
        runCatching { host.offerWaiting(offer.batch, peer, offer.files.size, total) }
        val answered = d.latch.await(60, TimeUnit.SECONDS)
        waiting.remove(offer.batch)
        Live.removePendingOffer(offer.batch)
        runCatching { host.offerClosed(offer.batch) }
        return answered && d.accept.get()
    }

    /** From the notification buttons or the in-app dialog. */
    fun decide(batch: String, accept: Boolean) {
        val d = waiting[batch] ?: return
        d.accept.set(accept)
        d.latch.countDown()
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        accepted.entries.removeIf { now - it.value.touchedAt > BATCH_TTL_MS }
    }

    fun isAccepted(batch: String, peerId: String): Boolean = accepted[batch]?.peerId == peerId

    class Incomplete : IOException("incomplete")

    /** Streams [body] through the host and records it. Returns the final file name. */
    fun receiveFile(
        peer: Peer, batchId: String, index: Int, count: Int,
        rawName: String, rawMime: String, size: Long, modified: Long?,
        body: InputStream,
    ): String {
        val batch = accepted[batchId] ?: throw IllegalStateException("not accepted")
        batch.touchedAt = System.currentTimeMillis()
        val name = sanitize(rawName)
        val file = IncomingFile(peer, batchId, index, count, name, resolveMime(name, rawMime), size, modified, batch.collection)
        val key = "$batchId/$index"
        host.busy(true)
        try {
            var lastUi = 0L
            val saved = host.save(file, body) { finalName, written ->
                val now = System.currentTimeMillis()
                if (written == 0L || now - lastUi > 250) {
                    lastUi = now
                    Live.putIncoming(Live.Incoming(key, peer.displayName, finalName, file.mime, size, written, index, count))
                    runCatching { host.receiving(file, finalName, written) }
                }
            }
            val item = HistoryItem(
                id = UUID.randomUUID().toString(), direction = "in", peerId = peer.id, peerName = peer.displayName,
                name = saved.name, size = size, mime = file.mime, time = System.currentTimeMillis(), uri = saved.uri,
                collection = batch.collection?.name,
            )
            Store.addHistory(item)
            val got = batch.received.incrementAndGet()
            Live.removeIncoming(key)
            runCatching { host.received(file, saved, item, got, index >= count - 1 || got >= batch.count) }
            Log.i(TAG, "received ${saved.name} ($size bytes) from ${peer.displayName}")
            return saved.name
        } catch (e: Exception) {
            Live.removeIncoming(key)
            runCatching { host.receiveFailed(file) }
            Log.w(TAG, "receive of $name failed: ${e.message}")
            throw e
        } finally {
            host.busy(false)
        }
    }

    /** `/v1/text`: kept as "text ready to copy"; never written to the clipboard by itself. */
    fun receiveText(peer: Peer, id: String, text: String) {
        val itemId = "text-$id"
        synchronized(this) {
            if (Store.state.value.history.any { it.id == itemId }) return // retry of one we already have
            TextStore.save(itemId, text)
            Store.addHistory(
                HistoryItem(
                    id = itemId, direction = "in", peerId = peer.id, peerName = peer.displayName,
                    name = "", size = text.toByteArray(Charsets.UTF_8).size.toLong(), mime = TEXT_MIME,
                    time = System.currentTimeMillis(), text = TextStore.preview(text),
                )
            )
        }
        runCatching { host.textReady(peer, itemId, TextStore.preview(text)) }
        Log.i(TAG, "text (${text.length} chars) from ${peer.displayName}")
    }

    private fun resolveMime(name: String, mime: String): String {
        val m = mime.trim().lowercase()
        if (m.isNotEmpty() && m != "application/octet-stream" && m.contains('/')) return m
        val ext = name.substringAfterLast('.', "").lowercase()
        return runCatching { host.mimeFromExtension(ext) }.getOrNull() ?: "application/octet-stream"
    }

    /** Names from a peer are untrusted: keep only a plain file name. */
    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().trimStart('.')
        return base.ifBlank { "archivo" }.take(200)
    }

    /** `foto.jpg` -> `foto (1).jpg` -> `foto (2).jpg` (protocol). [taken] holds lower-cased names. */
    fun uniqueName(taken: Set<String>, name: String): String {
        if (name.lowercase() !in taken) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (candidate.lowercase() !in taken) return candidate
            i++
        }
    }
}
