package com.tecnomanu.pasala.desktop

import com.tecnomanu.pasala.core.IncomingFile
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.core.Log
import com.tecnomanu.pasala.core.Outgoing
import com.tecnomanu.pasala.core.Pairing
import com.tecnomanu.pasala.core.Platform
import com.tecnomanu.pasala.core.Receiver
import com.tecnomanu.pasala.core.ReceiverHost
import com.tecnomanu.pasala.core.Saved
import com.tecnomanu.pasala.core.Sender
import com.tecnomanu.pasala.core.SenderHost
import com.tecnomanu.pasala.data.Collection
import com.tecnomanu.pasala.data.HistoryItem
import com.tecnomanu.pasala.data.Peer
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.data.TEXT_MIME
import com.tecnomanu.pasala.data.TextStore
import com.tecnomanu.pasala.net.PasalaServer
import com.tecnomanu.pasala.net.Resolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** The desktop app's engine: owns the server, the index and what the windows show. */
object Desktop {
    private const val TAG = "Pasala"

    lateinit var index: Index
        private set
    private val server = PasalaServer(LocalApi::handle)

    /** Newest first; what the main window and the island show. */
    val recent = MutableStateFlow<List<FileRecord>>(emptyList())
    val collections = MutableStateFlow<List<FileCollection>>(emptyList())
    /** Last received text and until when the island shows it. */
    val lastText = MutableStateFlow<Pair<FileRecord, Long>?>(null)
    /** Text waiting for the person to pick a device. */
    val pendingText = MutableStateFlow<String?>(null)
    val inbox = MutableStateFlow(Paths.defaultInbox)
    /** Something that shows whether devices are online is visible (island open, a window). */
    val onScreen = MutableStateFlow(false)

    sealed interface Notice {
        data class Received(val from: String, val count: Int, val lastName: String, val file: File) : Notice
        data class Text(val from: String, val preview: String) : Notice
        data class Offer(val from: String, val count: Int) : Notice
        data object NoText : Notice
    }
    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 16)
    val notices = _notices.asSharedFlow()

    /** Identity, store and index; cheap, done before deciding whether another copy is already running. */
    fun setup() {
        Platform.name = if (Paths.isWindows) "windows" else "linux"
        Platform.debug = System.getenv("PASALA_DEBUG") == "1"
        Platform.logger = LogFile::write
        TextStore.init(Paths.textsDir)
        Store.init(Paths.stateFile) { defaultName() }
        index = Index()
        index.setting("inbox")?.let { inbox.value = File(it) }
        collections.value = index.collections()
        Receiver.host = DiskReceiver
        Sender.host = FileSender
        Store.onHistory = { item -> if (item.mime == TEXT_MIME) indexText(item) }
        refreshRecent()
        runCatching { DiskReceiver.cleanLeftovers() }
    }

    private fun defaultName(): String {
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?: System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
        return host?.substringBefore('.')?.takeIf { it.isNotBlank() && it != "localhost" }
            ?: if (Paths.isWindows) "Windows" else "Linux"
    }

    fun start() {
        LocalApi.ensureToken()
        val port = server.start()
        index.setSetting("port", port.toString())
        index.setSetting("device_name", Store.identity.name)
        Log.i(TAG, "listening on $port")
        if (Platform.debug) {
            // Like the Mac's PASALA_DEBUG: the current pairing link in debug-pair.txt, to pair without a camera.
            Live.appScope.launch { Pairing.current.collect { c -> c?.let { java.io.File(Paths.dataDir, "debug-pair.txt").writeText(it.payload) } } }
            Pairing.newCode()
        }
        // Protocol § "Cada cuánto": every 20 s while Pasala is on screen, every 10 min otherwise.
        Live.appScope.launch {
            var last = 0L
            while (true) {
                val watching = onScreen.value
                if (System.currentTimeMillis() - last >= (if (watching) 20_000L else 600_000L)) {
                    runCatching { Resolver.refreshAll(lastOnly = !watching && last > 0) }
                    last = System.currentTimeMillis()
                }
                delay(2_000)
            }
        }
    }

    fun stop() = server.stop()

    fun refreshRecent() { recent.value = index.files(limit = 300) }

    fun setInbox(dir: File) {
        inbox.value = dir
        index.setSetting("inbox", dir.path)
    }

    fun rename(name: String) {
        Store.setName(name)
        index.setSetting("device_name", Store.identity.name)
    }

    // ---- collections (receiver-defined folders, protocol § hello) ----

    fun addCollection(dir: File, name: String = dir.name) {
        index.saveCollection(FileCollection("c-" + UUID.randomUUID().toString().take(8), name.ifBlank { dir.name }, dir.path))
        collections.value = index.collections()
    }

    fun renameCollection(c: FileCollection, name: String) {
        if (name.isBlank()) return
        index.saveCollection(c.copy(name = name.trim()))
        collections.value = index.collections()
    }

    fun removeCollection(c: FileCollection) {
        index.deleteCollection(c.id)
        collections.value = index.collections()
    }

    // ---- sending ----

    fun describe(f: File): Outgoing? {
        if (!f.isFile || !f.canRead()) return null
        return Outgoing(f.absolutePath, f.name, f.length(), mimeFor(f.name), f.lastModified().takeIf { it > 0 })
    }

    /** Starts sending [files]; returns the job id to follow in [Sender.jobs]. */
    fun sendFiles(files: List<File>, peerId: String, collectionId: String? = null): String? {
        val out = files.mapNotNull(::describe)
        if (out.isEmpty()) return null
        val job = UUID.randomUUID().toString()
        Store.updatePeer(peerId) { it.copy(lastCollection = collectionId) }
        Sender.start(job, peerId, out, collectionId)
        return job
    }

    /** "Send the copied text": the island asks which device. */
    fun beginSendText(text: String?) {
        val t = text?.trim().orEmpty()
        Log.d(TAG, "send copied text: ${t.length} chars")
        if (t.isEmpty()) { _notices.tryEmit(Notice.NoText); return }
        pendingText.value = text
    }

    fun textOf(r: FileRecord): String? = runCatching { File(r.path).readText() }.getOrNull()

    private fun indexText(item: HistoryItem) {
        val name = TextStore.preview(item.text.orEmpty()).lineSequence().firstOrNull().orEmpty().take(60).ifBlank { "…" }
        index.addFile(item.id, item.direction, item.peerId, item.peerName, name, TextStore.file(item.id).path, TEXT_MIME, item.size, item.time)
        refreshRecent()
        if (item.direction == "in") {
            recent.value.firstOrNull { it.batch == item.id }?.let { lastText.value = it to (System.currentTimeMillis() + 8_000) }
        }
    }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return DiskReceiver.mimeFromExtension(ext) ?: "application/octet-stream"
    }

    internal fun notify(n: Notice) { _notices.tryEmit(n) }

    /** Receives into the inbox or the chosen collection's folder: temp file first, moved into place when complete. */
    private object DiskReceiver : ReceiverHost {
        private val known = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
            "heic" to "image/heic", "heif" to "image/heif", "mp4" to "video/mp4", "mov" to "video/quicktime", "m4v" to "video/x-m4v",
            "webm" to "video/webm", "mkv" to "video/x-matroska", "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "ogg" to "audio/ogg",
            "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "json" to "application/json",
            "zip" to "application/zip", "csv" to "text/csv", "apk" to "application/vnd.android.package-archive",
        )

        override fun collections(): List<Collection> = Desktop.collections.value.map { Collection(it.id, it.name) }

        override fun mimeFromExtension(ext: String): String? = known[ext]
            ?: runCatching { Files.probeContentType(File("x.$ext").toPath()) }.getOrNull()
            ?: java.net.URLConnection.guessContentTypeFromName("x.$ext")

        private fun destination(file: IncomingFile): File {
            val col = file.collection?.let { c -> Desktop.collections.value.firstOrNull { it.id == c.id } }
            return (col?.let { File(it.path) } ?: Desktop.inbox.value).apply { mkdirs() }
        }

        override fun save(file: IncomingFile, body: InputStream, progress: (String, Long) -> Unit): Saved {
            val dir = destination(file)
            val tmp = File(dir, ".pasala-${UUID.randomUUID()}.part")
            try {
                progress(file.name, 0)
                var written = 0L
                tmp.outputStream().buffered(256 * 1024).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = body.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        written += n
                        progress(file.name, written)
                    }
                }
                if (written != file.size) throw Receiver.Incomplete()
                val final = synchronized(this) {
                    val taken = dir.list()?.map { it.lowercase() }?.toSet().orEmpty()
                    val target = File(dir, Receiver.uniqueName(taken, safeName(file.name)))
                    // The name is sanitized already; still never write outside the destination folder.
                    if (target.canonicalFile.parentFile != dir.canonicalFile) throw IOException("bad name")
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    target
                }
                file.modified?.takeIf { it > 0 }?.let { final.setLastModified(it) }
                return Saved(final.name, final.absolutePath)
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }

        /**
         * On top of the shared sanitizing: no direction-override characters (a name that reads `photo.jpg` but is
         * an `.exe`), no trailing dots or spaces, and no names Windows reserves for devices.
         */
        private fun safeName(name: String): String {
            var n = name.replace(Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]"), "").trimEnd('.', ' ')
            if (n.substringBefore('.').uppercase() in RESERVED) n = "_$n"
            return n.ifBlank { "archivo" }
        }

        private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

        /** Leftovers from a transfer cut by closing the app. */
        fun cleanLeftovers() {
            val cutoff = System.currentTimeMillis() - 60 * 60 * 1000
            (listOf(Desktop.inbox.value) + Desktop.collections.value.map { File(it.path) }).forEach { dir ->
                dir.listFiles { f -> f.name.startsWith(".pasala-") && f.name.endsWith(".part") && f.lastModified() < cutoff }
                    ?.forEach { it.delete() }
            }
        }

        override fun offerWaiting(batch: String, peer: Peer, count: Int, totalSize: Long) =
            notify(Notice.Offer(peer.displayName, count))

        override fun received(file: IncomingFile, saved: Saved, item: HistoryItem, got: Int, batchDone: Boolean) {
            index.addFile(
                file.batch, "in", file.peer.id, file.peer.displayName, saved.name, saved.uri, file.mime, file.size,
                collectionId = file.collection?.id, collection = file.collection?.name,
            )
            refreshRecent()
            if (batchDone) notify(Notice.Received(file.peer.displayName, got, saved.name, File(saved.uri)))
        }

        override fun textReady(peer: Peer, itemId: String, preview: String) = notify(Notice.Text(peer.displayName, preview))
    }

    private object FileSender : SenderHost {
        override fun open(f: Outgoing): InputStream = FileInputStream(f.uri)

        override fun sent(f: Outgoing, item: HistoryItem) {
            val peer = Store.peer(item.peerId)
            val colId = item.collection?.let { name -> peer?.collections?.firstOrNull { it.name == name }?.id }
            index.addFile(
                Sender.jobs.value.values.firstOrNull { j -> j.files.any { it.file == f } }?.id ?: item.id,
                "out", item.peerId, item.peerName, f.name, f.uri, f.mime, f.size, collectionId = colId, collection = item.collection,
            )
            refreshRecent()
        }
    }
}
