package com.tecnomanu.pasala.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.window.DialogWindow
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.core.Receiver
import com.tecnomanu.pasala.core.Sender
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.data.TEXT_MIME
import com.tecnomanu.pasala.desktop.Clipboard
import com.tecnomanu.pasala.desktop.Desktop
import com.tecnomanu.pasala.desktop.FileRecord
import com.tecnomanu.pasala.desktop.Ui
import com.tecnomanu.pasala.desktop.res.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit
import java.io.File

private enum class Mode { Idle, Open, Transfer, Done, Drop, Offer, Paired, PickText, TextArrived }

private const val FRESH_MS = 10 * 60 * 1000L
private val AWT_METRICS = java.awt.Canvas().getFontMetrics(java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 12))

/** A short message the island shows for a few seconds after something finished. */
private data class Toast(val title: String, val subtitle: String, val ok: Boolean, val until: Long)

/**
 * The floating pill at the top center of the chosen screen. Says what just arrived or how many devices are
 * connected; opens on hover; takes dropped files; long-press and drag moves it to another screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Island() {
    val peers by Store.state.collectAsState()
    val online by Live.online.collectAsState()
    val recent by Desktop.recent.collectAsState()
    val jobs by Sender.jobs.collectAsState()
    val incoming by Live.incoming.collectAsState()
    val offers by Live.pendingOffers.collectAsState()
    val pendingText by Desktop.pendingText.collectAsState()
    val lastText by Desktop.lastText.collectAsState()
    var hovering by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<Toast?>(null) }
    var pairedUntil by remember { mutableLongStateOf(0L) }
    var clearedAt by remember { mutableLongStateOf(Desktop.index.setting("island_cleared_at")?.toLongOrNull() ?: 0L) }
    var screenId by remember { mutableStateOf(Desktop.index.setting("island_screen")) }
    val pinned = remember { System.getenv("PASALA_ISLAND") == "open" }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    LaunchedEffect(Unit) { Live.paired.collect { pairedUntil = System.currentTimeMillis() + 4000; now = System.currentTimeMillis() } }

    // Finished sends become a short "done" message.
    val sentDone = stringResource(Res.string.island_sent_done)
    val sentFailed = stringResource(Res.string.island_send_failed)
    val declinedText = stringResource(Res.string.island_declined)
    val unreachText = stringResource(Res.string.island_unreachable)
    val seenFinished = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(jobs) {
        jobs.values.filter { it.finished && seenFinished[it.id] == null }.forEach { j ->
            seenFinished[j.id] = true
            val ok = j.phase == Sender.Phase.Done
            val title = when (j.phase) {
                Sender.Phase.Done -> sentDone.format(j.peerName)
                Sender.Phase.Declined -> declinedText.format(j.peerName)
                Sender.Phase.Unreachable -> unreachText.format(j.peerName)
                else -> sentFailed
            }
            toast = Toast(title, "", ok, System.currentTimeMillis() + 5000)
            if (!Ui.sendOpenFor(j.id)) scope.launch { delay(5000); Sender.forget(j.id) }
        }
    }
    val arrivedFrom = stringResource(Res.string.island_arrived_from)
    LaunchedEffect(Unit) {
        Desktop.notices.collect { n ->
            if (n is Desktop.Notice.Received) toast = Toast(arrivedFrom.format(n.from), n.lastName, true, System.currentTimeMillis() + 5000)
        }
    }

    val running = jobs.values.filter { !it.finished }
    val offer = offers.firstOrNull()
    val mode = when {
        dragging -> Mode.Drop
        pendingText != null -> Mode.PickText
        offer != null -> Mode.Offer
        running.isNotEmpty() || incoming.isNotEmpty() -> Mode.Transfer
        pairedUntil > now -> Mode.Paired
        lastText != null && lastText!!.second > now -> Mode.TextArrived
        hovering || pinned || moving -> Mode.Open
        toast != null && toast!!.until > now -> Mode.Done
        else -> Mode.Idle
    }
    val mustShow = mode == Mode.PickText || mode == Mode.Offer || mode == Mode.Drop
    val visible = !Ui.islandHidden || mustShow

    // What the closed pill says.
    val since = maxOf(clearedAt, now - FRESH_MS)
    val lastIn = recent.firstOrNull { it.direction == "in" }?.takeIf { it.createdAt > since }
    val onlineCount = peers.peers.count { online[it.id] == true }
    val idleText: String? = when {
        lastIn != null && lastIn.mime == TEXT_MIME -> stringResource(Res.string.island_text_ready)
        lastIn != null -> arrivedText(recent.filter { it.batch == lastIn.batch && it.direction == "in" })
        onlineCount > 0 -> pluralStringResource(Res.plurals.island_devices_connected, onlineCount, onlineCount)
        else -> null
    }
    // As wide as its text needs (mark + text + dot), never narrower than the bare pill. Measured with AWT:
    // this runs outside any window, where Compose text measuring isn't available.
    val idleWidth = idleText?.let {
        val w = AWT_METRICS.stringWidth(it).toFloat()
        maxOf(190f, w + (if (lastIn != null) 24f else 0f) + 76f)
    } ?: 150f

    val size = when (mode) {
        Mode.Idle -> DpSize(idleWidth.dp, 34.dp)
        Mode.Open -> DpSize(640.dp, 246.dp)
        Mode.Transfer -> DpSize(560.dp, 132.dp)
        Mode.Done -> DpSize(480.dp, 92.dp)
        Mode.Drop -> DpSize(600.dp, 176.dp)
        Mode.Offer -> DpSize(540.dp, 140.dp)
        Mode.Paired -> DpSize(440.dp, 88.dp)
        Mode.PickText -> DpSize(600.dp, 186.dp)
        Mode.TextArrived -> DpSize(540.dp, 172.dp)
    }

    val bounds = islandBounds(screenId, size)

    DialogWindow(
        visible = visible,
        create = {
            // An owner-less dialog stays out of the taskbar (Windows and X11) and never takes focus.
            ComposeDialog().apply {
                type = java.awt.Window.Type.UTILITY
                isUndecorated = true
                isTransparent = true
                isAlwaysOnTop = true
                focusableWindowState = false
                title = "Pasala"
                setBounds(islandBounds(screenId, size))
            }
        },
        dispose = { it.dispose() },
        // One setBounds: window managers ignore size changes that arrive apart from the move.
        update = { w -> if (!moving && w.bounds != bounds) w.bounds = bounds },
    ) {
        val tiles = remember { mutableStateMapOf<String, Rect>() }
        val density = window.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0

        val shape = RoundedCornerShape(if (mode == Mode.Idle) 17.dp else 24.dp)
        val alpha by animateFloatAsState(if (moving) 0.6f else 1f)
        Box(
            Modifier.fillMaxSize().clip(shape).background(P.ink.copy(alpha = 0.97f * alpha))
                .border(1.dp, if (mode == Mode.Drop || moving) P.violet else P.line, shape)
                .fileDrop(onHover = { dragging = it }) { files, p ->
                    // Drop point is in AWT units; tile bounds are in pixels.
                    val target = p?.let { pt ->
                        val at = androidx.compose.ui.geometry.Offset((pt.x * density).toFloat(), (pt.y * density).toFloat())
                        tiles.entries.firstOrNull { it.value.contains(at) }?.key
                    }
                    val list = Store.state.value.peers
                    when {
                        target != null -> sendOrAsk(files, target)
                        list.size == 1 -> sendOrAsk(files, list.first().id)
                        else -> Ui.showSend(files)
                    }
                }
                .onPointerEvent(PointerEventType.Enter) { hovering = true }
                .onPointerEvent(PointerEventType.Exit) {
                    scope.launch {
                        delay(250)
                        if (!isPointerInside(window)) {
                            hovering = false
                            // A text the person already looked at closes soon after the pointer leaves.
                            Desktop.lastText.value?.let { (f, until) -> if (until > System.currentTimeMillis()) Desktop.lastText.value = f to (System.currentTimeMillis() + 1500) }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { moving = true },
                        onDragEnd = {
                            moving = false
                            screenId = screenUnderPointer()?.also { Desktop.index.setSetting("island_screen", it) } ?: screenId
                        },
                        onDragCancel = { moving = false },
                        onDrag = { change, _ ->
                            change.consume()
                            MouseInfo.getPointerInfo()?.location?.let { p -> window.setLocation(p.x - window.width / 2, p.y - 17) }
                        },
                    )
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { Ui.showMain() }) },
        ) {
            when (mode) {
                Mode.Idle -> IdleBar(idleText, lastIn != null)
                Mode.Open -> OpenPanel(recent.filter { it.direction == "in" && it.createdAt > clearedAt }.take(6), online) {
                    clearedAt = System.currentTimeMillis()
                    Desktop.index.setSetting("island_cleared_at", clearedAt.toString())
                }
                Mode.Transfer -> TransferPanel(running, incoming)
                Mode.Done -> toast?.let { DonePanel(it) }
                Mode.Drop -> DropPanel(online) { id, rect -> tiles[id] = rect }
                Mode.Offer -> offer?.let { OfferPanel(it) }
                Mode.Paired -> PairedPanel()
                Mode.PickText -> PickTextPanel(pendingText.orEmpty(), online) { peerId ->
                    val text = Desktop.pendingText.value ?: return@PickTextPanel
                    Desktop.pendingText.value = null
                    scope.launch {
                        val name = Store.peer(peerId)?.displayName.orEmpty()
                        val r = Sender.sendText(peerId, text)
                        Store.setLastTextPeer(peerId)
                        toast = Toast(if (r == Sender.TextResult.Ok) sentDone.format(name) else unreachText.format(name), "", r == Sender.TextResult.Ok, System.currentTimeMillis() + 4000)
                    }
                }
                Mode.TextArrived -> lastText?.first?.let { TextArrivedPanel(it) { Desktop.lastText.value = it to (System.currentTimeMillis() + 1200) } }
            }
        }
    }
}

private fun sendOrAsk(files: List<File>, peerId: String) {
    val peer = Store.peer(peerId) ?: return
    // Receivers with collections get asked where to put it, in the send window.
    if (peer.collections.isNotEmpty()) Ui.showSend(files, peerId) else Desktop.sendFiles(files, peerId)
}

private fun isPointerInside(w: java.awt.Window): Boolean {
    val p = MouseInfo.getPointerInfo()?.location ?: return false
    return Rectangle(w.locationOnScreen, w.size).contains(p)
}

private fun screens() = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.toList()

private fun screenUnderPointer(): String? {
    val p = MouseInfo.getPointerInfo()?.location ?: return null
    return screens().firstOrNull { it.defaultConfiguration.bounds.contains(p) }?.iDstring
}

/** Top center of the chosen screen (the main one by default), below any top panel. AWT units = dp. */
private fun islandBounds(screenId: String?, size: DpSize): Rectangle {
    val dev = screens().firstOrNull { it.iDstring == screenId } ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
    val gc = dev.defaultConfiguration
    val b = gc.bounds
    val top = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc).top }.getOrDefault(0)
    val w = size.width.value.toInt(); val h = size.height.value.toInt()
    return Rectangle(b.x + (b.width - w) / 2, b.y + top + 6, w, h)
}

@Composable
private fun arrivedText(files: List<FileRecord>): String {
    val n = files.size
    val kinds = files.map { it.mime.substringBefore('/') }.toSet()
    return when {
        n == 1 && kinds == setOf("image") -> stringResource(Res.string.arrived_one_photo)
        n == 1 && kinds == setOf("video") -> stringResource(Res.string.arrived_one_video)
        n == 1 -> stringResource(Res.string.arrived_one_file)
        kinds == setOf("image") -> pluralStringResource(Res.plurals.arrived_photos, n, n)
        kinds == setOf("video") -> pluralStringResource(Res.plurals.arrived_videos, n, n)
        else -> pluralStringResource(Res.plurals.arrived_files, n, n)
    }
}

@Composable
private fun IdleBar(text: String?, arrived: Boolean) {
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Mark(16.dp)
        if (text != null) {
            Spacer(Modifier.width(9.dp))
            T(text, 12.sp, FontWeight.SemiBold, maxLines = 1)
            if (arrived) {
                Spacer(Modifier.width(9.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(P.mint))
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mark(22.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(title, 14.sp, FontWeight.Bold, maxLines = 1)
            subtitle?.let { T(it, 11.5.sp, color = P.dim, maxLines = 1) }
        }
        trailing()
    }
}

@Composable
private fun Label(text: String) = T(text.uppercase(), 10.sp, FontWeight.ExtraBold, P.dim)

@Composable
private fun OpenPanel(latest: List<FileRecord>, online: Map<String, Boolean>, onClear: () -> Unit) {
    val peers = Store.state.collectAsState().value.peers
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header("Pasala", stringResource(if (peers.isEmpty()) Res.string.island_no_devices else Res.string.island_hint)) {
            T("✕", 12.sp, FontWeight.Bold, P.dim, Modifier.clickable { Ui.hideIsland(true) }.padding(4.dp))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label(stringResource(Res.string.devices))
                if (peers.isEmpty()) T(stringResource(Res.string.island_add_hint), 12.sp, color = P.dim)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    peers.forEach { p ->
                        Chip(p.displayName, false, leading = { DeviceGlyph(p.platform, online[p.id] == true, 22.dp) }) { Ui.showSend(emptyList(), p.id) }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(stringResource(Res.string.island_latest))
                    Spacer(Modifier.weight(1f))
                    if (latest.isNotEmpty()) T(stringResource(Res.string.clear), 10.5.sp, FontWeight.Bold, P.dim, Modifier.clickable(onClick = onClear))
                }
                if (latest.isEmpty()) T(stringResource(Res.string.island_latest_empty), 12.sp, color = P.dim)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    latest.forEach { f ->
                        Box(Modifier.clickable { if (f.mime == TEXT_MIME) Desktop.textOf(f)?.let(Clipboard::copy) else Ui.open(f.file) }) {
                            FileThumb(f.path, f.mime, f.name, 46.dp, 10.dp)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlowButton(stringResource(Res.string.send_files)) { Ui.showSend() }
            GlowButton(stringResource(Res.string.send_copied_text), prominent = false) { Desktop.beginSendText(Clipboard.text()) }
            GlowButton(stringResource(Res.string.open_folder), prominent = false) { Ui.open(Desktop.inbox.value.apply { mkdirs() }) }
            GlowButton(stringResource(Res.string.add_short), prominent = false) { Ui.showPair() }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(P.surfaceHigh)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp).background(P.brand))
    }
}

@Composable
private fun TransferPanel(running: List<Sender.JobState>, incoming: List<Live.Incoming>) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val inc = incoming.firstOrNull()
        if (inc != null) {
            Header(stringResource(Res.string.arriving_from, inc.peerName), "${inc.index + 1}/${inc.count} · ${inc.name}")
            ProgressBar(if (inc.size > 0) inc.received.toFloat() / inc.size else 0f)
        } else running.firstOrNull()?.let { j ->
            val sub = when (j.phase) {
                Sender.Phase.Connecting -> stringResource(Res.string.phase_connecting)
                Sender.Phase.Waiting -> stringResource(Res.string.phase_waiting)
                else -> "${j.files.count { it.done }}/${j.files.size} · ${(j.progress * 100).toInt()}%"
            }
            Header(stringResource(Res.string.sending_to, j.peerName), sub)
            ProgressBar(j.progress)
        }
    }
}

@Composable
private fun DonePanel(t: Toast) {
    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(if (t.ok) P.mint else P.orange), contentAlignment = Alignment.Center) {
            T(if (t.ok) "✓" else "!", 15.sp, FontWeight.ExtraBold, P.ink)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            T(t.title, 14.sp, FontWeight.Bold, maxLines = 1)
            if (t.subtitle.isNotEmpty()) T(t.subtitle, 12.sp, color = P.dim, maxLines = 1)
        }
    }
}

@Composable
private fun DropPanel(online: Map<String, Boolean>, onTile: (String, Rect) -> Unit) {
    val peers = Store.state.collectAsState().value.peers
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(stringResource(if (peers.isEmpty()) Res.string.island_no_devices else Res.string.drop_on_device))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            peers.forEach { p ->
                Column(
                    Modifier.width(110.dp).height(92.dp).clip(RoundedCornerShape(16.dp)).background(P.surface)
                        .border(1.dp, P.line, RoundedCornerShape(16.dp))
                        .onGloballyPositioned { onTile(p.id, it.boundsInWindow()) }.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DeviceGlyph(p.platform, online[p.id] == true, 36.dp)
                    T(p.displayName, 12.sp, FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun OfferPanel(o: Live.PendingOffer) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(pluralStringResource(Res.plurals.offer_title, o.count, o.peerName, o.count), formatSize(o.totalSize))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlowButton(stringResource(Res.string.decline), prominent = false) { Receiver.decide(o.batch, false) }
            GlowButton(stringResource(Res.string.accept)) { Receiver.decide(o.batch, true) }
        }
    }
}

@Composable
private fun PairedPanel() {
    val name = Store.state.collectAsState().value.peers.maxByOrNull { it.pairedAt }?.displayName.orEmpty()
    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Mark(28.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            T(stringResource(Res.string.paired_title), 14.sp, FontWeight.Bold)
            T(stringResource(Res.string.paired_body, name), 12.sp, color = P.dim, maxLines = 1)
        }
    }
}

@Composable
private fun PickTextPanel(text: String, online: Map<String, Boolean>, onPick: (String) -> Unit) {
    val peers = Store.state.collectAsState().value.peers
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header(stringResource(if (peers.isEmpty()) Res.string.island_no_devices else Res.string.send_text_to)) {
            T("✕", 13.sp, FontWeight.Bold, P.dim, Modifier.clickable { Desktop.pendingText.value = null }.padding(4.dp))
        }
        T(text.replace('\n', ' '), 12.sp, color = P.text.copy(alpha = 0.85f), maxLines = 1,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(P.surface).padding(horizontal = 10.dp, vertical = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            peers.forEach { p ->
                Chip(p.displayName, false, leading = { DeviceGlyph(p.platform, online[p.id] == true, 24.dp) }) { onPick(p.id) }
            }
        }
    }
}

@Composable
private fun TextArrivedPanel(f: FileRecord, onCopied: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header(stringResource(Res.string.island_text_ready), stringResource(Res.string.from_device, f.peerName))
        T(Desktop.textOf(f) ?: f.name, 12.5.sp, color = P.text.copy(alpha = 0.9f), maxLines = 2,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(P.surface).padding(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlowButton(stringResource(if (copied) Res.string.copied else Res.string.copy)) {
                Desktop.textOf(f)?.let(Clipboard::copy); copied = true; onCopied()
            }
            GlowButton(stringResource(Res.string.open), prominent = false) { Ui.open(f.file) }
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1 shl 20 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1 shl 30 -> (if (bytes < 10L shl 20) "%.1f MB" else "%.0f MB").format(bytes / 1048576.0)
    else -> "%.1f GB".format(bytes / 1073741824.0)
}
