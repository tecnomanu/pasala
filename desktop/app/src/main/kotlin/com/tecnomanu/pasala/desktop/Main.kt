package com.tecnomanu.pasala.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.desktop.res.*
import com.tecnomanu.pasala.desktop.ui.Island
import com.tecnomanu.pasala.desktop.ui.MainWindow
import com.tecnomanu.pasala.desktop.ui.PairWindow
import com.tecnomanu.pasala.desktop.ui.SendWindow
import com.tecnomanu.pasala.desktop.ui.SettingsWindow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.io.File
import kotlin.system.exitProcess

/**
 * `Pasala [files…]` opens the send window with them (SendTo, file manager actions).
 * `--send-text` sends the copied text, `--pair` / `--settings` open those windows,
 * `--background` starts without the main window, `--mcp` runs the MCP server on stdio.
 */
fun main(args: Array<String>) {
    if ("--mcp" in args) { Mcp.run(); return }
    val files = args.filterNot { it.startsWith("--") }
    val action = when {
        "--send-text" in args -> "send-text"
        "--pair" in args -> "pair"
        "--settings" in args -> "settings"
        else -> null
    }
    // A copy is already running: hand it the request and leave.
    if (LocalApi.openArgs(files, action)) exitProcess(0)

    // File and folder pickers look like the rest of the system (GTK on Linux, Windows dialogs on Windows).
    runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
    Desktop.setup()
    Desktop.start()
    Ui.islandHidden = Desktop.index.setting("island_hidden") == "1"
    Hotkey.start { Ui.onMain { Desktop.beginSendText(Clipboard.text()) } }
    when {
        files.isNotEmpty() -> Ui.showSend(files.map(::File))
        action == "pair" -> Ui.showPair()
        action == "settings" -> Ui.showSettings()
        action == "send-text" -> Desktop.beginSendText(Clipboard.text())
        "--background" !in args && (Store.state.value.peers.isEmpty() || "--show" in args) -> Ui.showMain()
    }

    application {
        Pasala()
    }
}

@Composable
private fun ApplicationScope.Pasala() {
    val state by Store.state.collectAsState()
    val online by Live.online.collectAsState()
    val tray = rememberTrayState()
    val sendLabel = stringResource(Res.string.send_with_pasala)
    LaunchedEffect(Unit) { Thread { Integrations.install(sendLabel) }.start() }

    Notices(tray)

    Tray(
        icon = painterResource(Res.drawable.pasala_icon),
        state = tray,
        tooltip = "Pasala",
        onAction = { Ui.showMain() },
    ) {
        // AWT tray menus on Windows use a font without "…".
        Item(stringResource(Res.string.send_files_ellipsis).dots()) { Ui.showSend() }
        Item(stringResource(Res.string.send_copied_text)) { Desktop.beginSendText(Clipboard.text()) }
        if (state.peers.isNotEmpty()) {
            Menu(stringResource(Res.string.send_to)) {
                state.peers.forEach { p ->
                    Item((if (online[p.id] == true) "● " else "○ ") + p.displayName) { Ui.showSend(emptyList(), p.id) }
                }
            }
        }
        Separator()
        CheckboxItem(stringResource(Res.string.show_island), !Ui.islandHidden) { Ui.hideIsland(!it) }
        Item(stringResource(Res.string.open_pasala)) { Ui.showMain() }
        Item(stringResource(Res.string.open_received_folder)) { Ui.open(Desktop.inbox.value.apply { mkdirs() }) }
        Item(stringResource(Res.string.add_device_ellipsis).dots()) { Ui.showPair() }
        Separator()
        Item(stringResource(Res.string.settings_ellipsis).dots()) { Ui.showSettings() }
        Item(stringResource(Res.string.quit)) { Desktop.stop(); exitApplication() }
    }

    Island()
    if (Ui.mainOpen) MainWindow()
    if (Ui.pairOpen) PairWindow()
    if (Ui.sendFiles != null) SendWindow()
    if (Ui.settingsOpen) SettingsWindow()
}

/** System notifications for what arrives while the person looks elsewhere. */
@Composable
private fun Notices(tray: androidx.compose.ui.window.TrayState) {
    LaunchedEffect(Unit) {
        Desktop.notices.collect { n ->
            val (title, body) = when (n) {
                is Desktop.Notice.Received -> getString(Res.string.island_arrived_from, n.from) to
                    (if (n.count == 1) n.lastName else getPluralString(Res.plurals.files_count, n.count, n.count))
                is Desktop.Notice.Text -> getString(Res.string.island_text_ready) to n.preview.take(120)
                is Desktop.Notice.Offer -> getString(Res.string.notify_offer_title, n.from) to getString(Res.string.notify_offer_body)
                Desktop.Notice.NoText -> "Pasala" to getString(Res.string.no_copied_text)
            }
            if (!Notify.native(title, body)) tray.sendNotification(Notification(title, body, Notification.Type.Info))
        }
    }
}

private fun String.dots() = replace("…", "...")
