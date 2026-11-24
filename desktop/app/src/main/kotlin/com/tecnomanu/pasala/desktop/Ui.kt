package com.tecnomanu.pasala.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Desktop as AwtDesktop
import java.io.File
import javax.swing.SwingUtilities

/** Which windows are open. Everything here runs on the UI thread. */
object Ui {
    var mainOpen by mutableStateOf(false)
    var pairOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)
    /** Files picked for the send window (empty = it shows "choose files"); null = closed. */
    var sendFiles by mutableStateOf<List<File>?>(null)
    var sendTo by mutableStateOf<String?>(null)
    /** The send job the open send window is following (it clears it when closed). */
    var sendJob by mutableStateOf<String?>(null)
    var islandHidden by mutableStateOf(false)
    /** Which window to bring to the front, and a counter so asking twice still works. */
    var focus by mutableStateOf("" to 0)
        private set

    private fun raise(id: String) { focus = id to focus.second + 1 }

    fun onMain(block: () -> Unit) = SwingUtilities.invokeLater(block)

    fun showMain() { mainOpen = true; raise("main") }
    fun showPair() { pairOpen = true; raise("pair") }
    fun showSettings() { settingsOpen = true; raise("settings") }
    fun showSend(files: List<File> = emptyList(), to: String? = null) {
        sendFiles = files; sendTo = to; sendJob = null; raise("send")
    }

    fun sendOpenFor(jobId: String) = sendFiles != null && sendJob == jobId

    fun hideIsland(hidden: Boolean) {
        islandHidden = hidden
        Desktop.index.setSetting("island_hidden", if (hidden) "1" else "0")
    }

    /** Opens a file or folder with the system's default app. */
    fun open(f: File) {
        Thread {
            val ok = runCatching { AwtDesktop.getDesktop().open(f); true }.getOrDefault(false)
            if (!ok && Paths.isLinux) runCatching { ProcessBuilder("xdg-open", f.path).start() }
        }.start()
    }

    /** Shows the file selected in its folder where the system allows it, otherwise opens the folder. */
    fun reveal(f: File) {
        Thread {
            if (Paths.isWindows) runCatching { ProcessBuilder("explorer.exe", "/select,", f.path).start(); return@Thread }
            val ok = runCatching { AwtDesktop.getDesktop().browseFileDirectory(f); true }.getOrDefault(false)
            if (!ok) open(f.parentFile ?: f)
        }.start()
    }
}
