package com.tecnomanu.pasala.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.tecnomanu.pasala.data.HistoryItem
import com.tecnomanu.pasala.data.OfferRequest
import com.tecnomanu.pasala.data.Peer
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.service.Notifications
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Receiving side: offers (with optional confirmation) and saving to MediaStore. */
object Receiver {
    private const val TAG = "Pasala"
    private const val BATCH_TTL_MS = 30 * 60 * 1000L

    private lateinit var app: Context
    fun init(context: Context) { app = context.applicationContext }

    private class Batch(val peerId: String, val count: Int, @Volatile var touchedAt: Long) {
        val received = AtomicInteger()
        @Volatile var lastName: String = ""
    }
    private val accepted = ConcurrentHashMap<String, Batch>()

    private class Decision { val latch = CountDownLatch(1); val accept = AtomicBoolean(false) }
    private val waiting = ConcurrentHashMap<String, Decision>()

    /** Blocks the calling (connection) thread up to 60 s when confirmation is on. */
    fun handleOffer(peer: Peer, offer: OfferRequest): Boolean {
        prune()
        if (offer.files.isEmpty()) return false
        val ok = if (!Store.settings.askBeforeReceiving) true else askPerson(peer, offer)
        if (ok) accepted[offer.batch] = Batch(peer.id, offer.files.size, System.currentTimeMillis())
        Log.i(TAG, "offer ${offer.batch} from ${peer.displayName}: ${offer.files.size} files -> ${if (ok) "accepted" else "declined"}")
        return ok
    }

    private fun askPerson(peer: Peer, offer: OfferRequest): Boolean {
        val d = Decision()
        waiting[offer.batch] = d
        val total = offer.files.sumOf { it.size }
        Live.addPendingOffer(Live.PendingOffer(offer.batch, peer.displayName, offer.files.size, total))
        Notifications.offer(app, offer.batch, peer.displayName, offer.files.size, Format.size(total))
        val answered = d.latch.await(60, TimeUnit.SECONDS)
        waiting.remove(offer.batch)
        Live.removePendingOffer(offer.batch)
        Notifications.cancelOffer(app, offer.batch)
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

    /**
     * Streams [body] into a pending MediaStore item and publishes it when complete.
     * Returns the final display name. On any failure the pending item is deleted.
     */
    fun receiveFile(
        peer: Peer, batchId: String, index: Int, count: Int,
        rawName: String, rawMime: String, size: Long, modified: Long?,
        body: InputStream,
    ): String {
        val batch = accepted[batchId] ?: throw IllegalStateException("not accepted")
        batch.touchedAt = System.currentTimeMillis()
        val name = sanitize(rawName)
        val mime = resolveMime(name, rawMime)
        val target = targetFor(mime)
        val resolver = app.contentResolver
        val key = "$batchId/$index"
        Radio.acquire(app)

        var item: Uri? = null
        try {
            item = synchronized(this) {
                val unique = uniqueName(target, name)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, unique)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    if (modified != null && modified > 0 && target.kind != Kind.Other) {
                        put(MediaStore.MediaColumns.DATE_TAKEN, modified)
                    }
                }
                resolver.insert(target.collection, values) ?: throw IOException("could not create item")
            }
            val finalName = queryName(item) ?: name
            Live.putIncoming(Live.Incoming(key, peer.displayName, finalName, mime, size, 0, index, count))
            Notifications.receiving(app, peer.displayName, finalName, index, count, 0, size)

            var written = 0L
            resolver.openOutputStream(item, "w")?.use { out ->
                val buf = ByteArray(128 * 1024)
                var lastUi = 0L
                while (true) {
                    val n = body.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 250) {
                        lastUi = now
                        Live.putIncoming(Live.Incoming(key, peer.displayName, finalName, mime, size, written, index, count))
                        Notifications.receiving(app, peer.displayName, finalName, index, count, written, size)
                    }
                }
            } ?: throw IOException("could not open item")
            if (written != size) throw Incomplete()

            resolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val published = queryName(item) ?: finalName
            Store.addHistory(
                HistoryItem(
                    id = UUID.randomUUID().toString(), direction = "in", peerId = peer.id, peerName = peer.displayName,
                    name = published, size = size, mime = mime, time = System.currentTimeMillis(), uri = item.toString(),
                )
            )
            batch.lastName = published
            val got = batch.received.incrementAndGet()
            Live.removeIncoming(key)
            if (index >= count - 1 || got >= batch.count) {
                Notifications.cancelReceiving(app)
                Notifications.received(app, peer.displayName, got, published)
            }
            Log.i(TAG, "received $published ($size bytes) from ${peer.displayName}")
            return published
        } catch (e: Exception) {
            item?.let { runCatching { resolver.delete(it, null, null) } }
            Live.removeIncoming(key)
            Notifications.cancelReceiving(app)
            Log.w(TAG, "receive of $name failed: ${e.message}")
            throw e
        } finally {
            Radio.release()
        }
    }

    enum class Kind { Image, Video, Other }
    class Target(val kind: Kind, val collection: Uri, val relativePath: String)

    fun targetFor(mime: String): Target = when {
        mime.startsWith("image/") -> Target(Kind.Image, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Pictures/Pasala/")
        mime.startsWith("video/") -> Target(Kind.Video, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Movies/Pasala/")
        else -> Target(Kind.Other, MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Download/Pasala/")
    }

    private fun resolveMime(name: String, mime: String): String {
        val m = mime.trim().lowercase()
        if (m.isNotEmpty() && m != "application/octet-stream" && m.contains('/')) return m
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().trimStart('.')
        return base.ifBlank { "archivo" }.take(200)
    }

    private fun existingNames(t: Target): Set<String> {
        val names = HashSet<String>()
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            val args = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(t.relativePath))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            app.contentResolver.query(t.collection, projection, args, null)
        } else {
            @Suppress("DEPRECATION")
            app.contentResolver.query(
                MediaStore.setIncludePending(t.collection), projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(t.relativePath), null,
            )
        }
        cursor?.use { c -> while (c.moveToNext()) c.getString(0)?.let { names += it.lowercase() } }
        return names
    }

    /** `foto.jpg` -> `foto (1).jpg` -> `foto (2).jpg` (protocol). */
    fun uniqueName(t: Target, name: String): String {
        val taken = runCatching { existingNames(t) }.getOrDefault(emptySet())
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

    private fun queryName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
