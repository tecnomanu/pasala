package com.tecnomanu.pasala.desktop

import com.tecnomanu.pasala.core.Log
import java.io.File

/**
 * "Send with Pasala" from the file manager and opening at login. Only user-level files are written, and only
 * when the app runs from an installed copy (a .msi, .deb or AppImage), so they always point at a real launcher.
 */
object Integrations {
    private const val TAG = "Pasala"
    private val home = File(System.getProperty("user.home"))

    fun install(sendLabel: String) {
        val exe = Paths.launcher ?: return
        runCatching { if (Paths.isWindows) windows(exe, sendLabel) else linux(exe, sendLabel) }
            .onFailure { Log.w(TAG, "file manager integration failed", it) }
    }

    /** Explorer › Send to › Pasala: a shortcut in the SendTo folder. */
    private fun windows(exe: String, label: String) {
        val sendTo = File(System.getenv("APPDATA") ?: return, "Microsoft/Windows/SendTo")
        val lnk = File(sendTo, "Pasala.lnk")
        val stamp = File(Paths.dataDir, "sendto-target")
        if (lnk.exists() && stamp.takeIf { it.exists() }?.readText() == exe) return
        sendTo.mkdirs()
        val ps = "\$s=(New-Object -ComObject WScript.Shell).CreateShortcut('${lnk.path.replace("'", "''")}');" +
            "\$s.TargetPath='${exe.replace("'", "''")}';\$s.IconLocation='${exe.replace("'", "''")},0';" +
            "\$s.Description='${label.replace("'", "''")}';\$s.Save()"
        val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        if (p.waitFor() == 0 && lnk.exists()) stamp.writeText(exe)
    }

    /** Thunar, Nautilus, Nemo and Dolphin each read their own folder; a desktop entry covers "Open with". */
    private fun linux(exe: String, label: String) {
        val data = File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".local/share").path)
        val q = "\"${exe.replace("\"", "\\\"")}\""
        // Thunar › Send To
        write(File(data, "Thunar/sendto/pasala.desktop"), """
            [Desktop Entry]
            Type=Application
            Version=1.0
            Name=Pasala
            Icon=pasala
            Exec=$q %F
        """)
        // Nautilus › Scripts
        write(File(data, "nautilus/scripts/$label"), """
            #!/bin/sh
            exec $q "$@"
        """, executable = true)
        // Nemo › actions
        write(File(data, "nemo/actions/pasala.nemo_action"), """
            [Nemo Action]
            Name=$label
            Exec=$q %F
            Icon-Name=pasala
            Selection=notnone
            Extensions=nodirs;
        """)
        // Dolphin › service menu
        write(File(data, "kio/servicemenus/pasala.desktop"), """
            [Desktop Entry]
            Type=Service
            MimeType=application/octet-stream;
            Actions=send
            X-KDE-Priority=TopLevel

            [Desktop Action send]
            Name=$label
            Icon=pasala
            Exec=$q %F
        """, executable = true)
    }

    private fun write(f: File, body: String, executable: Boolean = false) {
        val text = body.trimIndent() + "\n"
        if (f.exists() && f.readText() == text) return
        f.parentFile.mkdirs()
        f.writeText(text)
        if (executable) f.setExecutable(true, true)
    }

    // ---- open at login ----

    private val linuxAutostart get() = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".config").path, "autostart/pasala.desktop")

    fun canOpenAtLogin(): Boolean = Paths.launcher != null

    fun opensAtLogin(): Boolean = when {
        Paths.isWindows -> runCatching {
            val p = ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "Pasala")
                .redirectErrorStream(true).start()
            p.inputStream.readAllBytes(); p.waitFor() == 0
        }.getOrDefault(false)
        else -> linuxAutostart.exists()
    }

    fun setOpenAtLogin(on: Boolean) {
        val exe = Paths.launcher ?: return
        runCatching {
            if (Paths.isWindows) {
                val args = if (on) listOf("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "Pasala", "/t", "REG_SZ", "/d", "\"$exe\" --background", "/f")
                else listOf("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "Pasala", "/f")
                ProcessBuilder(args).redirectErrorStream(true).start().apply { inputStream.readAllBytes(); waitFor() }
            } else if (on) {
                write(linuxAutostart, """
                    [Desktop Entry]
                    Type=Application
                    Name=Pasala
                    Icon=pasala
                    Exec="$exe" --background
                    X-GNOME-Autostart-enabled=true
                """)
            } else linuxAutostart.delete()
        }.onFailure { Log.w(TAG, "open at login failed", it) }
    }
}
