package com.tecnomanu.pasala.core

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import android.util.Size
import android.webkit.MimeTypeMap
import com.tecnomanu.pasala.BuildConfig
import com.tecnomanu.pasala.data.HistoryItem
import com.tecnomanu.pasala.data.Peer
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.data.TextStore
import com.tecnomanu.pasala.service.Notifications
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Plugs the shared core (kotlin/shared) into Android: identity, MediaStore, notifications, radio locks. */
object AndroidPlatform {
    private const val TAG = "Pasala"

    fun install(context: Context) {
        val app = context.applicationContext
        Platform.name = "android"
        Platform.debug = BuildConfig.DEBUG
        Platform.logger = { level, tag, msg, e ->
            when (level) {
                'D' -> android.util.Log.d(tag, msg, e)
                'I' -> android.util.Log.i(tag, msg, e)
                'W' -> android.util.Log.w(tag, msg, e)
                else -> android.util.Log.e(tag, msg, e)
            }
        }
        TextStore.init(File(app.filesDir, "texts"))
        Store.init(File(app.filesDir, "pasala.json")) { defaultName(app) }
        Receiver.host = MediaStoreReceiver(app)
        Sender.host = ContentSender(app)
    }

    private fun defaultName(context: Context): String {
        val n = runCatching { AndroidSettings.Global.getString(context.contentResolver, AndroidSettings.Global.DEVICE_NAME) }.getOrNull()
        if (!n.isNullOrBlank()) return n
        val model = Build.MODEL ?: "Android"
        val maker = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: ""
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }

    /** Describes a picked/shared file from its content provider. */
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
        Outgoing(uri.toString(), finalName, size, mime)
    }.onFailure { Log.w(TAG, "can't read $uri: ${it.message}") }.getOrNull()

    private fun cameraStyleName(cr: ContentResolver, uri: Uri, mime: String?, current: String): String {
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
}

/** Files are read in streaming from their content uri; never a whole video in memory. */
private class ContentSender(private val app: Context) : SenderHost {
    override fun open(f: Outgoing): InputStream =
        app.contentResolver.openInputStream(Uri.parse(f.uri)) ?: throw FileNotFoundException(f.uri)

    /** Sent items keep a small local thumbnail: the original may not be readable later. */
    override fun thumbnail(f: Outgoing): String? {
        if (!f.mime.startsWith("image/") && !f.mime.startsWith("video/")) return null
        return runCatching {
            val bmp = app.contentResolver.loadThumbnail(Uri.parse(f.uri), Size(240, 240), null)
            val dir = File(app.filesDir, "thumbs").apply { mkdirs() }
            val out = File(dir, UUID.randomUUID().toString() + ".jpg")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            out.absolutePath
        }.getOrNull()
    }

    override fun busy(on: Boolean) = if (on) Radio.acquire(app) else Radio.release()
}

/** Received files go through MediaStore, written as pending and published when complete. */
private class MediaStoreReceiver(private val app: Context) : ReceiverHost {
    enum class Kind { Image, Video, Other }
    class Target(val kind: Kind, val collection: Uri, val relativePath: String)

    private fun targetFor(mime: String): Target = when {
        mime.startsWith("image/") -> Target(Kind.Image, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Pictures/Pasala/")
        mime.startsWith("video/") -> Target(Kind.Video, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Movies/Pasala/")
        else -> Target(Kind.Other, MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Download/Pasala/")
    }

    override fun mimeFromExtension(ext: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)

    override fun save(file: IncomingFile, body: InputStream, progress: (String, Long) -> Unit): Saved {
        val target = targetFor(file.mime)
        val resolver = app.contentResolver
        var item: Uri? = null
        try {
            item = synchronized(this) {
                val unique = Receiver.uniqueName(runCatching { existingNames(target) }.getOrDefault(emptySet()), file.name)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, unique)
                    put(MediaStore.MediaColumns.MIME_TYPE, file.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    val modified = file.modified
                    if (modified != null && modified > 0 && target.kind != Kind.Other) {
                        put(MediaStore.MediaColumns.DATE_TAKEN, modified)
                    }
                }
                resolver.insert(target.collection, values) ?: throw IOException("could not create item")
            }
            val finalName = queryName(item) ?: file.name
            progress(finalName, 0)
            var written = 0L
            resolver.openOutputStream(item, "w")?.use { out ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = body.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                    written += n
                    progress(finalName, written)
                }
            } ?: throw IOException("could not open item")
            if (written != file.size) throw Receiver.Incomplete()
            resolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return Saved(queryName(item) ?: finalName, item.toString())
        } catch (e: Exception) {
            item?.let { runCatching { resolver.delete(it, null, null) } }
            throw e
        }
    }

    override fun offerWaiting(batch: String, peer: Peer, count: Int, totalSize: Long) =
        Notifications.offer(app, batch, peer.displayName, count, Format.size(totalSize))

    override fun offerClosed(batch: String) = Notifications.cancelOffer(app, batch)

    override fun receiving(file: IncomingFile, name: String, written: Long) =
        Notifications.receiving(app, file.peer.displayName, name, file.index, file.count, written, file.size)

    override fun receiveFailed(file: IncomingFile) = Notifications.cancelReceiving(app)

    override fun received(file: IncomingFile, saved: Saved, item: HistoryItem, got: Int, batchDone: Boolean) {
        if (!batchDone) return
        Notifications.cancelReceiving(app)
        Notifications.received(app, file.peer.displayName, got, saved.name)
    }

    override fun textReady(peer: Peer, itemId: String, preview: String) =
        Notifications.textReady(app, itemId, peer.displayName, preview)

    override fun busy(on: Boolean) = if (on) Radio.acquire(app) else Radio.release()

    private fun existingNames(t: Target): Set<String> {
        val names = HashSet<String>()
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(t.relativePath))
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

    private fun queryName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
