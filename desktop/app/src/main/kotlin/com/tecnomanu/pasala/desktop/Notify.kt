package com.tecnomanu.pasala.desktop

/** Linux desktops show `notify-send` as a real notification; the tray balloon is the fallback (and Windows' path). */
object Notify {
    private val notifySend: Boolean by lazy {
        Paths.isLinux && runCatching { ProcessBuilder("notify-send", "--version").start().waitFor() == 0 }.getOrDefault(false)
    }

    fun native(title: String, body: String): Boolean {
        if (!notifySend) return false
        return runCatching {
            ProcessBuilder(listOf("notify-send", "-a", "Pasala", "-i", "pasala", title) + listOfNotNull(body.takeIf { it.isNotBlank() })).start()
            true
        }.getOrDefault(false)
    }
}
