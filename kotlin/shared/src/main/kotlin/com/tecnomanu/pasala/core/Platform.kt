package com.tecnomanu.pasala.core

/** What this process is (protocol § Identidad `platform`) and how it logs. Set once at startup by the app. */
object Platform {
    /** `android` | `linux` | `windows`. */
    @Volatile var name: String = "android"

    /** Debug builds log the pairing link. */
    @Volatile var debug: Boolean = false

    /** Receives every log line; the app routes it to its platform logger. */
    @Volatile var logger: (level: Char, tag: String, msg: String, error: Throwable?) -> Unit = { level, tag, msg, error ->
        System.err.println("$level/$tag: $msg")
        error?.printStackTrace()
    }
}

/** Same shape as android.util.Log so the shared code reads like the rest of the app. */
object Log {
    fun d(tag: String, msg: String) = Platform.logger('D', tag, msg, null)
    fun i(tag: String, msg: String) = Platform.logger('I', tag, msg, null)
    fun w(tag: String, msg: String, e: Throwable? = null) = Platform.logger('W', tag, msg, e)
    fun e(tag: String, msg: String, e: Throwable? = null) = Platform.logger('E', tag, msg, e)
}
