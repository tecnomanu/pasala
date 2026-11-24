package com.tecnomanu.pasala.desktop

import java.io.File
import java.time.LocalDateTime

/**
 * The installed app has no console (Windows launches it without one), so log lines also go to
 * `pasala.log` in the data folder. Kept under 1 MB; nothing leaves the computer.
 */
object LogFile {
    private val file by lazy { File(Paths.dataDir, "pasala.log").apply { if (length() > 1 shl 20) delete() } }

    @Synchronized
    fun write(level: Char, tag: String, msg: String, error: Throwable?) {
        System.err.println("$level/$tag: $msg")
        error?.printStackTrace()
        runCatching { file.appendText("${LocalDateTime.now()} $level $msg${error?.let { " (" + it + ")" } ?: ""}\n") }
    }
}
