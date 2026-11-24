package com.tecnomanu.pasala.core

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
import java.io.InputStream
import java.util.UUID

/** A file picked to send. [uri] is wherever [Sender.host] can open it from (a content uri, a file path). */
data class Outgoing(
    val uri: String,
    val name: String,
    val size: Long,
    val mime: String,
    val modified: Long? = null,
)

/** What sending needs from the platform. */
interface SenderHost {
    fun open(f: Outgoing): InputStream
    /** Path of a small local thumbnail kept for the activity list, or null. */
    fun thumbnail(f: Outgoing): String? = null
    /** Keep the device and its radio awake while a transfer runs. */
    fun busy(on: Boolean) {}
    /** A file reached the other device (desktop indexes it). */
    fun sent(f: Outgoing, item: HistoryItem) {}
}

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

    lateinit var host: SenderHost

    private fun update(id: String, block: (JobState) -> JobState) = _jobs.update { m -> m[id]?.let { m + (id to block(it)) } ?: m }

    /** Starts sending in the app scope (survives the screen closing). */
    fun start(jobId: String, peerId: String, files: List<Outgoing>, collectionId: String? = null) {
        val peer = Store.peer(peerId) ?: return
        if (_jobs.value[jobId]?.let { !it.finished } == true) return
        val colName = collectionId?.let { id -> peer.collections.firstOrNull { it.id == id }?.name }
        _jobs.update { it + (jobId to JobState(jobId, peerId, peer.displayName, colName, Phase.Connecting, files.map { f -> FileState(f) })) }
        running[jobId] = Live.appScope.launch(Dispatchers.IO) { run(jobId, peerId, files, collectionId) }
    }

    private suspend fun run(jobId: String, peerId: String, files: List<Outgoing>, collectionId: String?) {
        host.busy(true)
        try { runInner(jobId, peerId, files, collectionId) } finally { host.busy(false) }
    }

    private suspend fun runInner(jobId: String, peerId: String, files: List<Outgoing>, collectionId: String?) {
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
                    open = { host.open(f) },
                    onProgress = { sent -> setFile(jobId, index) { it.copy(sent = sent) } },
                )
                setFile(jobId, index) { it.copy(sent = f.size, done = true, finalName = resp.name) }
                val item = HistoryItem(
                    id = UUID.randomUUID().toString(), direction = "out", peerId = peerId, peerName = fresh.displayName,
                    name = f.name, size = f.size, mime = f.mime, time = System.currentTimeMillis(),
                    thumbPath = runCatching { host.thumbnail(f) }.getOrNull(),
                    collection = collectionId?.let { id -> fresh.collections.firstOrNull { it.id == id }?.name },
                )
                Store.addHistory(item)
                runCatching { host.sent(f, item) }
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

    enum class TextResult { Ok, Unreachable, TooLarge, Failed }

    /** Sends one text (`/v1/text`) and records it in the activity list. */
    suspend fun sendText(peerId: String, text: String): TextResult = withContext(Dispatchers.IO) {
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
        runCatching { com.tecnomanu.pasala.data.TextStore.save(itemId, text) }
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
