package com.tecnomanu.pasala.desktop

import java.io.File
import java.time.LocalDateTime

/**
 * The installed app has no console (Windows launches it without one), so log lines also go to
 * `pasala.log` in the data folder: at most 1 MB plus the previous one as `pasala.log.1`. Nothing leaves the computer.
 */
object LogFile {
    private val file by lazy { File(Paths.dataDir, "pasala.log") }
    private var writes = 0

    @Synchronized
    fun write(level: Char, tag: String, msg: String, error: Throwable?) {
        System.err.println("$level/$tag: $msg")
        error?.printStackTrace()
        runCatching {
            if (writes++ % 200 == 0 && file.length() > 1 shl 20) {
                File(file.path + ".1").delete()
                file.renameTo(File(file.path + ".1"))
            }
            file.appendText("${LocalDateTime.now()} $level $msg${error?.let { " ($it)" } ?: ""}\n")
        }
    }
}
