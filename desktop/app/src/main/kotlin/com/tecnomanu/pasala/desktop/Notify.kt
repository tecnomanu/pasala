package com.tecnomanu.pasala.desktop

/** Linux desktops show `notify-send` as a real notification; the tray balloon is the fallback (and Windows' path). */
object Notify {
    private val notifySend: Boolean by lazy {
        Paths.isLinux && runCatching { ProcessBuilder("notify-send", "--version").start().waitFor() == 0 }.getOrDefault(false)
    }

    /** The installed icon (deb: lib/Pasala.png next to bin/), else the theme name. */
    private val icon: String by lazy {
        Paths.launcher?.let { java.io.File(it).parentFile?.parentFile?.resolve("lib/Pasala.png") }?.takeIf { it.exists() }?.path ?: "pasala"
    }

    fun native(title: String, body: String): Boolean {
        if (!notifySend) return false
        return runCatching {
            ProcessBuilder(listOf("notify-send", "-a", "Pasala", "-i", icon, "--", title) + listOfNotNull(body.takeIf { it.isNotBlank() })).start()
            true
        }.getOrDefault(false)
    }
}
