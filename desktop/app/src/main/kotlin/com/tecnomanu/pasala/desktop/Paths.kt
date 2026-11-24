package com.tecnomanu.pasala.desktop

import java.io.File

/** Where Pasala keeps its things on Windows and Linux. `PASALA_HOME` overrides the data folder (tests, two copies). */
object Paths {
    val os: String = System.getProperty("os.name").orEmpty().lowercase().let {
        when {
            it.startsWith("windows") -> "windows"
            it.contains("mac") -> "mac"
            else -> "linux"
        }
    }
    val isWindows get() = os == "windows"
    val isLinux get() = os == "linux"

    private val home = File(System.getProperty("user.home"))

    val dataDir: File by lazy {
        val override = System.getenv("PASALA_HOME")?.takeIf { it.isNotBlank() }
        val dir = when {
            override != null -> File(override)
            isWindows -> File(System.getenv("APPDATA") ?: File(home, "AppData/Roaming").path, "Pasala")
            else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".local/share").path, "pasala")
        }
        dir.apply { mkdirs() }
    }

    val stateFile get() = File(dataDir, "pasala.json")
    val databaseFile get() = File(dataDir, "pasala.db")
    val tokenFile get() = File(dataDir, "local-token")
    val textsDir get() = File(dataDir, "texts")

    /** `~/Downloads/Pasala`, using the desktop's own name for Downloads on Linux (`xdg-user-dir`). */
    val defaultInbox: File by lazy {
        val downloads = if (isLinux) {
            runCatching {
                val p = ProcessBuilder("xdg-user-dir", "DOWNLOAD").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
            }.getOrNull()?.takeIf { it.isNotBlank() && it != home.path }?.let(::File)
        } else null
        File(downloads ?: File(home, "Downloads"), "Pasala")
    }

    /** The installed launcher, to point SendTo / file manager actions at. Null when running from Gradle. */
    val launcher: String? by lazy {
        System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }
    }
}
