package com.tecnomanu.pasala.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.core.Pairing
import com.tecnomanu.pasala.core.Sender
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.data.TEXT_MIME
import com.tecnomanu.pasala.desktop.Clipboard
import com.tecnomanu.pasala.desktop.Desktop
import com.tecnomanu.pasala.desktop.FileCollection
import com.tecnomanu.pasala.desktop.FileRecord
import com.tecnomanu.pasala.desktop.Hotkey
import com.tecnomanu.pasala.desktop.Integrations
import com.tecnomanu.pasala.desktop.Ui
import com.tecnomanu.pasala.desktop.res.*
import com.tecnomanu.pasala.net.Addresses
import com.tecnomanu.pasala.net.Resolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.swing.JFileChooser

/** A Pasala window: dark surface, brand icon, brought to the front when asked again. */
@Composable
fun PasalaWindow(id: String, title: String, size: DpSize, onClose: () -> Unit, resizable: Boolean = true, content: @Composable ColumnScope.(java.awt.Window) -> Unit) {
    val state = rememberWindowState(size = size, position = WindowPosition(Alignment.Center))
    Window(onCloseRequest = onClose, state = state, title = title, icon = painterResource(Res.drawable.pasala_icon), resizable = resizable) {
        LaunchedEffect(window) { com.tecnomanu.pasala.desktop.DarkTitleBar.apply(window) }
        val focus = Ui.focus
        LaunchedEffect(focus) { if (focus.first == id) { window.toFront(); window.requestFocus() } }
        Column(Modifier.fillMaxSize().background(P.ink)) { content(window) }
    }
}

@Composable
private fun SectionLabel(text: String) = T(text.uppercase(), 10.sp, FontWeight.ExtraBold, P.dim)

@Composable
private fun relative(time: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - time) / 60_000
    return when {
        m < 1 -> stringResource(Res.string.just_now)
        m < 60 -> "${m} min"
        m < 60 * 24 -> "${m / 60} h"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(time))
    }
}

// ---------------- main window ----------------

@Composable
fun MainWindow() {
    val state by Store.state.collectAsState()
    val online by Live.online.collectAsState()
    val recent by Desktop.recent.collectAsState()
    val collections by Desktop.collections.collectAsState()
    var filter by remember { mutableStateOf("in") }
    PasalaWindow("main", "Pasala", DpSize(980.dp, 660.dp), onClose = { Ui.mainOpen = false }) { window ->
        Row(Modifier.fillMaxSize().fileDrop { files, _ -> Ui.showSend(files) }) {
            // Left: devices and collections.
            Column(Modifier.width(300.dp).fillMaxHeight().background(P.surface).padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mark(28.dp); Spacer(Modifier.width(10.dp)); T("Pasala", 22.sp, FontWeight.ExtraBold)
                }
                SectionLabel(stringResource(Res.string.devices))
                if (state.peers.isEmpty()) T(stringResource(Res.string.main_no_devices), 13.sp, color = P.dim)
                state.peers.forEach { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Ui.showSend(emptyList(), p.id) }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        DeviceGlyph(p.platform, online[p.id] == true, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            T(p.displayName, 13.5.sp, FontWeight.SemiBold, maxLines = 1)
                            T(when {
                                online[p.id] == true -> stringResource(Res.string.connected)
                                p.lastSeen > 0 -> stringResource(Res.string.seen_ago, relative(p.lastSeen))
                                else -> stringResource(Res.string.not_connected)
                            }, 11.5.sp, color = if (online[p.id] == true) P.mint else P.dim)
                        }
                    }
                }
                GlowButton(stringResource(Res.string.add_device)) { Ui.showPair() }
                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(Res.string.collections))
                T(stringResource(Res.string.collections_hint), 12.sp, color = P.dim)
                collections.forEach { c -> CollectionRow(c) }
                GlowButton(stringResource(Res.string.add_collection), prominent = false) { chooseFolder(window)?.let { Desktop.addCollection(it) } }
            }
            // Right: the deck.
            Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(stringResource(if (filter == "in") Res.string.what_arrived else Res.string.what_you_sent), 20.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                    Chip(stringResource(Res.string.received), filter == "in") { filter = "in" }
                    Chip(stringResource(Res.string.sent), filter == "out") { filter = "out" }
                }
                LiveBanner()
                val shown = recent.filter { it.direction == filter }
                if (shown.isEmpty()) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Mark(56.dp)
                        Spacer(Modifier.height(12.dp))
                        T(stringResource(if (state.peers.isEmpty()) Res.string.empty_pair_title else Res.string.empty_title), 20.sp, FontWeight.Bold)
                        T(stringResource(if (state.peers.isEmpty()) Res.string.empty_pair_body else Res.string.empty_body), 13.sp, color = P.dim)
                        if (state.peers.isEmpty()) { Spacer(Modifier.height(12.dp)); GlowButton(stringResource(Res.string.show_code)) { Ui.showPair() } }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.id }) { f -> DeckRow(f) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(c: FileCollection) {
    var editing by remember { mutableStateOf(false) }
    var name by remember(c.name) { mutableStateOf(c.name) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(P.surfaceHigh).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (editing) {
            Field(name, { name = it }, Modifier.weight(1f))
            T(stringResource(Res.string.save), 12.sp, FontWeight.Bold, P.mint, Modifier.clickable { Desktop.renameCollection(c, name); editing = false })
        } else {
            Column(Modifier.weight(1f).clickable { Ui.open(File(c.path)) }) {
                T(c.name, 13.sp, FontWeight.SemiBold, maxLines = 1)
                T(c.path, 10.5.sp, color = P.dim, maxLines = 1)
            }
            T(stringResource(Res.string.rename), 11.5.sp, FontWeight.Bold, P.dim, Modifier.clickable { editing = true })
            T(stringResource(Res.string.remove), 11.5.sp, FontWeight.Bold, P.orange, Modifier.clickable { Desktop.removeCollection(c) })
        }
    }
}

@Composable
private fun LiveBanner() {
    val jobs by Sender.jobs.collectAsState()
    val incoming by Live.incoming.collectAsState()
    val inc = incoming.firstOrNull()
    val job = jobs.values.firstOrNull { !it.finished }
    if (inc == null && job == null) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        val title = if (inc != null) stringResource(Res.string.arriving_from, inc.peerName) else stringResource(Res.string.sending_to, job!!.peerName)
        val progress = if (inc != null) (if (inc.size > 0) inc.received.toFloat() / inc.size else 0f) else job!!.progress
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T(title, 13.sp, FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(P.surfaceHigh)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(5.dp).background(P.brand))
            }
        }
    }
}

@Composable
private fun DeckRow(f: FileRecord) {
    val exists = remember(f.path) { File(f.path).exists() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.surface).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FileThumb(f.path, f.mime, f.name, 52.dp)
        Column(Modifier.weight(1f)) {
            T(if (f.mime == TEXT_MIME) (Desktop.textOf(f)?.replace('\n', ' ') ?: f.name) else f.name, 13.5.sp, FontWeight.SemiBold, maxLines = 1)
            val who = stringResource(if (f.direction == "in") Res.string.from_device else Res.string.to_device, f.peerName)
            T(listOfNotNull(who, f.collection, relative(f.createdAt), formatSize(f.size).takeIf { f.mime != TEXT_MIME }).joinToString(" · "), 11.5.sp, color = P.dim, maxLines = 1)
        }
        if (f.mime == TEXT_MIME) {
            GlowButton(stringResource(Res.string.copy)) { Desktop.textOf(f)?.let(Clipboard::copy) }
        } else if (exists) {
            GlowButton(stringResource(Res.string.open), prominent = false) { Ui.open(f.file) }
            GlowButton(stringResource(Res.string.show_in_folder), prominent = false) { Ui.reveal(f.file) }
        } else T(stringResource(Res.string.file_gone), 11.5.sp, color = P.dim)
    }
}

private fun chooseFolder(parent: java.awt.Window): File? {
    val c = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
    return if (c.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) c.selectedFile else null
}

private fun chooseFiles(parent: java.awt.Window, title: String): List<File> {
    val d = FileDialog(parent as? Frame, title, FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }
    return d.files?.toList().orEmpty().filter { it.isFile }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "") {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(P.surfaceHigh).border(1.dp, P.line, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (value.isEmpty() && placeholder.isNotEmpty()) T(placeholder, 13.sp, color = P.dim, maxLines = 1)
        BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(P.mint),
            textStyle = TextStyle(fontFamily = jakarta(), fontSize = 13.sp, color = P.text), modifier = Modifier.fillMaxWidth())
    }
}

// ---------------- pairing ----------------

@Composable
fun PairWindow() {
    val code by Pairing.current.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var link by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        Pairing.newCode()
        onDispose { Pairing.clear() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000); now = System.currentTimeMillis()
            val cur = Pairing.current.value
            if (cur == null || now >= cur.expiresAt) Pairing.newCode()
        }
    }
    val pairedText = stringResource(Res.string.paired_body)
    LaunchedEffect(Unit) {
        Live.paired.collect { id -> message = pairedText.format(Store.peer(id)?.displayName.orEmpty()) to true }
    }
    val errExpired = stringResource(Res.string.err_expired)
    val errUnreach = stringResource(Res.string.err_unreachable)
    val errSelf = stringResource(Res.string.err_self)
    val errNotCode = stringResource(Res.string.err_not_code)
    PasalaWindow("pair", stringResource(Res.string.add_device), DpSize(520.dp, 700.dp), onClose = { Ui.pairOpen = false }, resizable = false) {
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            T(stringResource(Res.string.add_device), 26.sp, FontWeight.ExtraBold)
            T(stringResource(Res.string.pair_instructions), 13.sp, color = P.dim)
            Box(Modifier.size(300.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(18.dp)) {
                code?.let { Qr(it.payload) }
            }
            code?.let {
                val left = ((it.expiresAt - now) / 1000).coerceAtLeast(0)
                T(stringResource(Res.string.code_changes_in, "%d:%02d".format(left / 60, left % 60)), 12.sp, color = P.dim)
                T(stringResource(Res.string.copy_my_code), 12.sp, FontWeight.SemiBold, P.violet, Modifier.clickable { Clipboard.copy(it.payload) })
            }
            Spacer(Modifier.height(4.dp))
            T(stringResource(Res.string.paste_other_code), 12.sp, FontWeight.SemiBold, P.dim)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(link, { link = it }, Modifier.weight(1f), "pasala://pair?d=…")
                GlowButton(stringResource(Res.string.pair_action), enabled = !working && link.isNotBlank()) {
                    val qr = Pairing.parse(link)
                    if (qr == null) { message = errNotCode to false; return@GlowButton }
                    working = true
                    scope.launch {
                        message = when (Pairing.pairWith(qr)) {
                            is Pairing.Result.Ok -> pairedText.format(qr.name) to true
                            Pairing.Result.Expired -> errExpired to false
                            Pairing.Result.Unreachable -> errUnreach.format(qr.name) to false
                            Pairing.Result.Self -> errSelf to false
                        }
                        working = false
                        link = ""
                    }
                }
            }
            message?.let { (m, ok) -> T(m, 13.sp, FontWeight.SemiBold, if (ok) P.mint else P.orange) }
        }
    }
}

@Composable
private fun Qr(text: String) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Canvas(Modifier.fillMaxSize()) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) drawRect(P.ink, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

// ---------------- send ----------------

@Composable
fun SendWindow() {
    val files = Ui.sendFiles ?: return
    val state by Store.state.collectAsState()
    val online by Live.online.collectAsState()
    val jobs by Sender.jobs.collectAsState()
    var peerId by remember { mutableStateOf(Ui.sendTo ?: state.peers.singleOrNull()?.id) }
    LaunchedEffect(Ui.sendTo) { Ui.sendTo?.let { peerId = it } }
    val peer = state.peers.firstOrNull { it.id == peerId }
    var collection by remember(peerId) { mutableStateOf(peer?.lastCollection) }
    LaunchedEffect(peerId) { peer?.let { Resolver.reach(it) } } // fresh collections from its hello
    val job = Ui.sendJob?.let { jobs[it] }
    val pickTitle = stringResource(Res.string.choose_files)
    PasalaWindow("send", stringResource(Res.string.send), DpSize(580.dp, 620.dp), onClose = { Ui.sendJob?.let(Sender::forget); Ui.sendFiles = null; Ui.sendJob = null }) { window ->
        Column(Modifier.fillMaxSize().fileDrop { dropped, _ -> if (Ui.sendJob == null) Ui.sendFiles = (files + dropped).distinct() }.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            T(stringResource(Res.string.send), 26.sp, FontWeight.ExtraBold)
            if (state.peers.isEmpty()) {
                T(stringResource(Res.string.pair_first), 13.sp, color = P.dim)
                GlowButton(stringResource(Res.string.add_device)) { Ui.showPair() }
                return@Column
            }
            // Files
            Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(P.surface).border(1.dp, P.line, RoundedCornerShape(18.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (files.isEmpty()) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        T(stringResource(Res.string.drag_files_here), 15.sp, FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        GlowButton(pickTitle, prominent = false) { Ui.sendFiles = chooseFiles(window, pickTitle) }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(files) { f ->
                            val fs = job?.files?.firstOrNull { it.file.uri == f.absolutePath }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FileThumb(f.path, Desktop.mimeFor(f.name), f.name, 38.dp, 8.dp)
                                T(f.name, 13.sp, FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                                T(when { fs?.done == true -> "✓"; fs?.failed == true -> "!"; else -> formatSize(f.length()) }, 12.sp, color = if (fs?.done == true) P.mint else P.dim)
                                if (job == null) T("✕", 12.sp, FontWeight.Bold, P.dim, Modifier.clickable { Ui.sendFiles = files - f })
                            }
                        }
                    }
                    if (job == null) T(stringResource(Res.string.add_more), 12.sp, FontWeight.SemiBold, P.violet, Modifier.clickable { Ui.sendFiles = (files + chooseFiles(window, pickTitle)).distinct() })
                }
            }
            // Device
            T(stringResource(Res.string.to_which_device), 12.sp, FontWeight.SemiBold, P.dim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.peers.forEach { p ->
                    Chip(p.displayName, p.id == peerId, leading = { DeviceGlyph(p.platform, online[p.id] == true, 22.dp) }) { if (job == null) peerId = p.id }
                }
            }
            // Collection (receiver-defined, protocol § hello)
            if (peer != null && peer.collections.isNotEmpty()) {
                T(stringResource(Res.string.which_collection), 12.sp, FontWeight.SemiBold, P.dim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(Res.string.none), collection == null) { collection = null }
                    peer.collections.forEach { c -> Chip(c.name, collection == c.id) { collection = c.id } }
                }
            }
            // Status + action
            when {
                job == null -> GlowButton(
                    if (files.isEmpty()) pickTitle else pluralStringResource(Res.plurals.send_n, files.size, files.size),
                    Modifier.fillMaxWidth(), enabled = peer != null,
                ) {
                    if (files.isEmpty()) Ui.sendFiles = chooseFiles(window, pickTitle)
                    else Ui.sendJob = Desktop.sendFiles(files, peer!!.id, collection?.takeIf { id -> peer.collections.any { it.id == id } })
                }
                !job.finished -> T(when (job.phase) {
                    Sender.Phase.Connecting -> stringResource(Res.string.phase_connecting)
                    Sender.Phase.Waiting -> stringResource(Res.string.phase_waiting)
                    else -> stringResource(Res.string.sending_progress, (job.progress * 100).toInt())
                }, 13.sp, color = P.dim)
                else -> {
                    val ok = job.phase == Sender.Phase.Done
                    T(when (job.phase) {
                        Sender.Phase.Done -> stringResource(Res.string.island_sent_done, job.peerName)
                        Sender.Phase.Declined -> stringResource(Res.string.island_declined, job.peerName)
                        Sender.Phase.Unreachable -> stringResource(Res.string.island_unreachable, job.peerName)
                        else -> stringResource(Res.string.island_send_failed)
                    }, 13.sp, FontWeight.SemiBold, if (ok) P.mint else P.orange)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlowButton(stringResource(Res.string.close), prominent = ok) { Sender.forget(job.id); Ui.sendFiles = null; Ui.sendJob = null }
                        if (!ok) GlowButton(stringResource(Res.string.retry)) { Sender.forget(job.id); Ui.sendJob = null }
                    }
                }
            }
        }
    }
}

// ---------------- settings ----------------

@Composable
fun SettingsWindow() {
    val state by Store.state.collectAsState()
    val inbox by Desktop.inbox.collectAsState()
    val collections by Desktop.collections.collectAsState()
    var name by remember { mutableStateOf(state.identity?.name.orEmpty()) }
    var atLogin by remember { mutableStateOf(Integrations.opensAtLogin()) }
    PasalaWindow("settings", stringResource(Res.string.settings), DpSize(560.dp, 680.dp), onClose = { Ui.settingsOpen = false; Desktop.rename(name) }) { window ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(stringResource(Res.string.settings), 26.sp, FontWeight.ExtraBold)
            T(stringResource(Res.string.this_computer_name), 12.sp, FontWeight.SemiBold, P.dim)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, { name = it }, Modifier.weight(1f))
                GlowButton(stringResource(Res.string.save), prominent = false, enabled = name.isNotBlank() && name != state.identity?.name) { Desktop.rename(name) }
            }
            T(stringResource(Res.string.where_to_save), 12.sp, FontWeight.SemiBold, P.dim)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T(inbox.path, 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                GlowButton(stringResource(Res.string.change), prominent = false) { chooseFolder(window)?.let(Desktop::setInbox) }
            }
            Toggle(stringResource(Res.string.ask_before), stringResource(Res.string.ask_before_hint), state.settings.askBeforeReceiving) { Store.setAskBeforeReceiving(it) }
            Toggle(stringResource(Res.string.show_island), null, !Ui.islandHidden) { Ui.hideIsland(!it) }
            if (Integrations.canOpenAtLogin()) Toggle(stringResource(Res.string.open_at_login), null, atLogin) { Integrations.setOpenAtLogin(it); atLogin = Integrations.opensAtLogin() }
            T(stringResource(Res.string.shortcut_hint, Hotkey.LABEL), 12.sp, color = P.dim)
            SectionLabel(stringResource(Res.string.collections))
            T(stringResource(Res.string.collections_settings_hint), 12.sp, color = P.dim)
            collections.forEach { c -> CollectionRow(c) }
            GlowButton(stringResource(Res.string.add_collection), prominent = false) { chooseFolder(window)?.let { Desktop.addCollection(it) } }
            SectionLabel(stringResource(Res.string.connection_details))
            T(stringResource(Res.string.addresses, Addresses.local().all.joinToString(", "), Live.advertisedPort), 12.sp, color = P.dim)
        }
    }
}

@Composable
private fun Toggle(title: String, hint: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!on) }.background(P.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, 13.5.sp, FontWeight.SemiBold)
            hint?.let { T(it, 11.5.sp, color = P.dim) }
        }
        Box(Modifier.width(40.dp).height(22.dp).clip(RoundedCornerShape(11.dp)).background(if (on) P.mint else P.surfaceHigh), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.padding(3.dp).size(16.dp).clip(RoundedCornerShape(8.dp)).background(if (on) P.ink else P.dim))
        }
    }
}
