package com.tecnomanu.pasala.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import com.tecnomanu.pasala.data.HistoryItem
import com.tecnomanu.pasala.data.OfferFile
import com.tecnomanu.pasala.data.OfferRequest
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.net.HttpStatusException
import com.tecnomanu.pasala.net.PasalaClient
import com.tecnomanu.pasala.net.Resolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** A file picked to send. Metadata comes from the content provider. */
data class Outgoing(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String,
    val modified: Long? = null,
)

object Sender {
    private const val TAG = "Pasala"

    enum class Phase { Connecting, Waiting, Sending, Done, Declined, Unreachable, Failed }

    data class FileState(val file: Outgoing, val sent: Long = 0, val done: Boolean = false, val failed: Boolean = false, val finalName: String? = null)

    data class JobState(
        val id: String,
        val peerId: String,
        val peerName: String,
        val collectionName: String?,
        val phase: Phase,
        val files: List<FileState>,
    ) {
        val total: Long get() = files.sumOf { it.file.size }
        val sent: Long get() = files.sumOf { if (it.done) it.file.size else it.sent }
        val progress: Float get() = if (total <= 0) (if (phase == Phase.Done) 1f else 0f) else (sent.toFloat() / total).coerceIn(0f, 1f)
        val finished: Boolean get() = phase in setOf(Phase.Done, Phase.Declined, Phase.Unreachable, Phase.Failed)
    }

    private val _jobs = MutableStateFlow<Map<String, JobState>>(emptyMap())
    val jobs: StateFlow<Map<String, JobState>> = _jobs.asStateFlow()
    private val running = HashMap<String, Job>()

    private fun update(id: String, block: (JobState) -> JobState) = _jobs.update { m -> m[id]?.let { m + (id to block(it)) } ?: m }

    fun describe(context: Context, uri: Uri, fallbackMime: String? = null): Outgoing? = runCatching {
        val cr = context.contentResolver
        var name: String? = null
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (uri.scheme == "file") {
            val f = File(uri.path!!)
            name = name ?: f.name
            if (size < 0) size = f.length()
        }
        if (size < 0) {
            size = cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        }
        if (size < 0) {
            // Last resort: count bytes (provider doesn't report a size).
            size = cr.openInputStream(uri)?.use { s -> var n = 0L; val b = ByteArray(64 * 1024); while (true) { val r = s.read(b); if (r == -1) break; n += r }; n } ?: 0
        }
        var mime = cr.getType(uri) ?: fallbackMime?.takeIf { !it.contains('*') }
        var finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
        // The system photo picker hides real file names ("1000006948.jpg"): name it like a camera would.
        if (uri.toString().contains("picker") && finalName.substringBeforeLast('.').all { it.isDigit() }) {
            finalName = cameraStyleName(cr, uri, mime, finalName)
        }
        if (mime == null || mime == "application/octet-stream") {
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(finalName.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        }
        Outgoing(uri, finalName, size, mime)
    }.onFailure { Log.w(TAG, "can't read $uri: ${it.message}") }.getOrNull()

    private fun cameraStyleName(cr: android.content.ContentResolver, uri: Uri, mime: String?, current: String): String {
        val taken = runCatching {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                listOf("date_taken_ms", "datetaken", "date_modified_ms").firstNotNullOfOrNull { col ->
                    c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                }
            }
        }.getOrNull()?.takeIf { it > 0 } ?: System.currentTimeMillis()
        // See HISTORY.md#picker-names
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(taken))
        val ext = current.substringAfterLast('.', "").ifEmpty {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime ?: "") ?: "bin"
        }
        val prefix = if (mime?.startsWith("video/") == true) "VID" else "IMG"
        return "${prefix}_$stamp.$ext"
    }

    /** Starts sending in the app scope (survives the screen closing). */
    fun start(context: Context, jobId: String, peerId: String, files: List<Outgoing>, collectionId: String? = null) {
        val peer = Store.peer(peerId) ?: return
        if (_jobs.value[jobId]?.let { !it.finished } == true) return
        val colName = collectionId?.let { id -> peer.collections.firstOrNull { it.id == id }?.name }
        _jobs.update { it + (jobId to JobState(jobId, peerId, peer.displayName, colName, Phase.Connecting, files.map { f -> FileState(f) })) }
        val app = context.applicationContext
        running[jobId] = Live.appScope.launch(Dispatchers.IO) { run(app, jobId, peerId, files, collectionId) }
    }

    private suspend fun run(context: Context, jobId: String, peerId: String, files: List<Outgoing>, collectionId: String?) {
        Radio.acquire(context)
        try { runInner(context, jobId, peerId, files, collectionId) } finally { Radio.release() }
    }

    private suspend fun runInner(context: Context, jobId: String, peerId: String, files: List<Outgoing>, collectionId: String?) {
        val peer = Store.peer(peerId) ?: return update(jobId) { it.copy(phase = Phase.Failed) }
        val route = Resolver.reach(peer) ?: return update(jobId) { it.copy(phase = Phase.Unreachable) }
        val fresh = Store.peer(peerId) ?: peer
        val batch = UUID.randomUUID().toString()
        update(jobId) { it.copy(phase = Phase.Waiting) }
        val accepted = try {
            PasalaClient.offer(route.host, route.port, fresh, OfferRequest(batch, files.map { OfferFile(it.name, it.size, it.mime) }, collectionId)).accepted
        } catch (e: Exception) {
            Log.w(TAG, "offer failed: ${e.message}")
            return update(jobId) { it.copy(phase = Phase.Failed) }
        }
        if (!accepted) return update(jobId) { it.copy(phase = Phase.Declined) }
        update(jobId) { it.copy(phase = Phase.Sending) }

        var anyFailed = false
        files.forEachIndexed { index, f ->
            try {
                val resp = PasalaClient.sendFile(
                    route.host, route.port, fresh, batch, index, files.size,
                    f.name, f.mime, f.size, f.modified,
                    open = { context.contentResolver.openInputStream(f.uri) ?: throw FileNotFoundException(f.uri.toString()) },
                    onProgress = { sent -> setFile(jobId, index) { it.copy(sent = sent) } },
                )
                setFile(jobId, index) { it.copy(sent = f.size, done = true, finalName = resp.name) }
                Store.addHistory(
                    HistoryItem(
                        id = UUID.randomUUID().toString(), direction = "out", peerId = peerId, peerName = fresh.displayName,
                        name = f.name, size = f.size, mime = f.mime, time = System.currentTimeMillis(),
                        thumbPath = thumbnail(context, f),
                        collection = collectionId?.let { id -> fresh.collections.firstOrNull { it.id == id }?.name },
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "send ${f.name} failed: ${e.message}")
                anyFailed = true
                setFile(jobId, index) { it.copy(failed = true) }
                if (e is HttpStatusException && e.code == 409) {
                    return update(jobId) { it.copy(phase = Phase.Failed) }
                }
            }
        }
        update(jobId) { it.copy(phase = if (anyFailed) Phase.Failed else Phase.Done) }
    }

    private fun setFile(jobId: String, index: Int, block: (FileState) -> FileState) = update(jobId) { j ->
        j.copy(files = j.files.mapIndexed { i, fs -> if (i == index) block(fs) else fs })
    }

    /** Sent items keep a small local thumbnail: the original may not be readable later. */
    private fun thumbnail(context: Context, f: Outgoing): String? {
        if (!f.mime.startsWith("image/") && !f.mime.startsWith("video/")) return null
        return runCatching {
            val bmp = context.contentResolver.loadThumbnail(f.uri, Size(240, 240), null)
            val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
            val out = File(dir, UUID.randomUUID().toString() + ".jpg")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            out.absolutePath
        }.getOrNull()
    }

    enum class TextResult { Ok, Unreachable, TooLarge, Failed }

    /** Sends one text (`/v1/text`) and records it in the activity list. */
    suspend fun sendText(context: Context, peerId: String, text: String): TextResult = withContext(Dispatchers.IO) {
        if (text.toByteArray(Charsets.UTF_8).size > com.tecnomanu.pasala.data.TEXT_MAX_BYTES) return@withContext TextResult.TooLarge
        val peer = Store.peer(peerId) ?: return@withContext TextResult.Failed
        val route = Resolver.reach(peer) ?: return@withContext TextResult.Unreachable
        val fresh = Store.peer(peerId) ?: peer
        val id = UUID.randomUUID().toString()
        try {
            PasalaClient.text(route.host, route.port, fresh, com.tecnomanu.pasala.data.TextRequest(id, text))
        } catch (e: HttpStatusException) {
            Log.w(TAG, "text failed: ${e.message}")
            return@withContext if (e.code == 413) TextResult.TooLarge else TextResult.Failed
        } catch (e: Exception) {
            Log.w(TAG, "text failed: ${e.message}")
            return@withContext TextResult.Failed
        }
        val itemId = "text-$id"
        runCatching { com.tecnomanu.pasala.data.TextStore.save(context, itemId, text) }
            .onFailure { Log.w(TAG, "could not keep sent text: ${it.message}") }
        Store.addHistory(
            HistoryItem(
                id = itemId, direction = "out", peerId = peerId, peerName = fresh.displayName, name = "",
                size = text.toByteArray(Charsets.UTF_8).size.toLong(), mime = com.tecnomanu.pasala.data.TEXT_MIME,
                time = System.currentTimeMillis(), text = com.tecnomanu.pasala.data.TextStore.preview(text),
            )
        )
        TextResult.Ok
    }

    fun forget(jobId: String) {
        running.remove(jobId)
        _jobs.update { it - jobId }
    }
}
