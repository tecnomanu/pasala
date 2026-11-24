package com.tecnomanu.pasala.ui.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.tecnomanu.pasala.R
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tecnomanu.pasala.service.Background
import com.tecnomanu.pasala.ui.components.GradientButton
import com.tecnomanu.pasala.ui.components.SoftButton
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FormatQuote
import com.tecnomanu.pasala.core.Clip
import com.tecnomanu.pasala.data.TEXT_MIME
import com.tecnomanu.pasala.data.TextStore
import com.tecnomanu.pasala.service.Notifications
import com.tecnomanu.pasala.ui.send.QuoteBlock
import com.tecnomanu.pasala.ui.send.TextActivity
import com.tecnomanu.pasala.core.Format
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.core.AndroidPlatform
import com.tecnomanu.pasala.core.Outgoing
import com.tecnomanu.pasala.data.HistoryItem
import com.tecnomanu.pasala.data.Peer
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.ui.RenameDialog
import com.tecnomanu.pasala.ui.UnpairDialog
import com.tecnomanu.pasala.ui.components.Backdrop
import com.tecnomanu.pasala.ui.components.EmptyNote
import com.tecnomanu.pasala.ui.components.FileThumb
import com.tecnomanu.pasala.ui.components.PasalaCard
import com.tecnomanu.pasala.ui.components.PeerAvatar
import com.tecnomanu.pasala.ui.components.BrandMark
import com.tecnomanu.pasala.ui.components.ProgressRing
import com.tecnomanu.pasala.ui.components.PulseDot
import com.tecnomanu.pasala.ui.components.SectionLabel
import com.tecnomanu.pasala.ui.theme.BrandGradient
import com.tecnomanu.pasala.ui.theme.Orange
import com.tecnomanu.pasala.ui.theme.P
import com.tecnomanu.pasala.ui.theme.Violet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddDevice: () -> Unit,
    onSettings: () -> Unit,
    onSend: (peerId: String, files: List<Outgoing>) -> Unit,
) {
    val state by Store.state.collectAsState()
    val online by Live.online.collectAsState()
    val incoming by Live.incoming.collectAsState()
    val port by Live.port.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = P.c

    var exempt by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        exempt = Background.isExempt(context)
        onPauseOrDispose { }
    }
    val clipHasText = rememberClipHasText()
    var pickFor by remember { mutableStateOf<Peer?>(null) }
    var sheetFor by remember { mutableStateOf<Peer?>(null) }
    var renaming by remember { mutableStateOf<Peer?>(null) }
    var unpairing by remember { mutableStateOf<Peer?>(null) }

    fun deliver(uris: List<Uri>) {
        val peer = pickFor ?: return
        if (uris.isEmpty()) return
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { AndroidPlatform.describe(context, it) } }
            if (files.isNotEmpty()) onSend(peer.id, files)
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { deliver(it) }
    val pickDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { deliver(it) }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Backdrop {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 12.dp, bottom = bottom + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    BrandMark(44.dp, 15.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = c.text, modifier = Modifier.weight(1f))
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Tune, stringResource(R.string.cd_settings), tint = c.text) }
                }
            }
            item(key = "status") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(c.surface.copy(alpha = 0.7f))
                        .padding(start = 4.dp, end = 14.dp, top = 2.dp, bottom = 2.dp),
                ) {
                    if (port > 0) PulseDot(c.success) else Box(Modifier.size(20.dp))
                    Text(
                        if (port > 0) stringResource(R.string.status_ready, state.identity?.name.orEmpty()) else stringResource(R.string.status_starting),
                        style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (state.peers.isNotEmpty() && clipHasText) {
                item(key = "send-clip") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .animateItem()
                            .padding(top = 2.dp)
                            .clip(RoundedCornerShape(50))
                            .background(c.surface)
                            .border(1.dp, c.outline, RoundedCornerShape(50))
                            .clickable { context.startActivity(TextActivity.fromClipboard(context)) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Icon(Icons.Rounded.ContentPaste, null, tint = Orange, modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(R.string.home_send_clip), style = MaterialTheme.typography.titleSmall, color = c.text,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            items(incoming, key = { "in-" + it.key }) { inc ->
                IncomingCard(inc, Modifier.animateItem())
            }

            item(key = "devices-label") {
                SectionLabel(stringResource(R.string.home_devices), Modifier.padding(top = 14.dp)) {
                    if (state.peers.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onAddDevice).padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Rounded.Add, null, tint = Orange, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.home_add), style = MaterialTheme.typography.labelMedium, color = Orange)
                        }
                    }
                }
            }

            // Only once there is someone to receive from, never on first launch.
            if (state.peers.isNotEmpty() && !exempt && !state.settings.backgroundCardDismissed) {
                item(key = "background-card") {
                    BackgroundCard(
                        onAllow = { Background.open(context) },
                        onLater = { Store.dismissBackgroundCard() },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            if (state.peers.isEmpty()) {
                item(key = "empty-peers") { AddFirstDeviceCard(onAddDevice) }
            } else {
                items(state.peers.sortedByDescending { it.lastSeen }, key = { "peer-" + it.id }) { peer ->
                    PeerCard(
                        peer = peer,
                        online = online[peer.id] == true,
                        onClick = { sheetFor = peer },
                        onRename = { renaming = peer },
                        onUnpair = { unpairing = peer },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            item(key = "activity-label") { SectionLabel(stringResource(R.string.home_activity), Modifier.padding(top = 18.dp)) }
            if (state.history.isEmpty()) {
                item(key = "empty-history") {
                    PasalaCard(Modifier.fillMaxWidth()) {
                        EmptyNote(
                            stringResource(R.string.home_empty_title),
                            stringResource(R.string.home_empty_body),
                        )
                    }
                }
            } else {
                items(state.history.take(60), key = { "h-" + it.id }) { h ->
                    if (h.mime == TEXT_MIME) {
                        TextHistoryRow(h, Modifier.animateItem())
                        return@items
                    }
                    HistoryRow(h, Modifier.animateItem()) {
                        if (h.direction == "in" && h.uri != null) {
                            val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(h.uri), h.mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try { context.startActivity(view) } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, R.string.toast_no_app, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    sheetFor?.let { peer ->
        ModalBottomSheet(onDismissRequest = { sheetFor = null }, containerColor = c.surface) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text(stringResource(R.string.sheet_title, peer.displayName), style = MaterialTheme.typography.headlineSmall, color = c.text)
                Text(
                    stringResource(if (online[peer.id] == true) R.string.sheet_online else R.string.sheet_offline),
                    style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PickOption(stringResource(R.string.pick_media), Icons.Rounded.PhotoLibrary, Modifier.weight(1f)) {
                        pickFor = peer; sheetFor = null
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                    PickOption(stringResource(R.string.pick_files), Icons.Rounded.Folder, Modifier.weight(1f)) {
                        pickFor = peer; sheetFor = null
                        pickDocs.launch(arrayOf("*/*"))
                    }
                }
            }
        }
    }
    renaming?.let { RenameDialog(it) { renaming = null } }
    unpairing?.let { UnpairDialog(it) { unpairing = null } }
}

@Composable
private fun PickOption(text: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val c = P.c
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(c.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(BrandGradient), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White)
        }
        Text(text, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun BackgroundCard(onAllow: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val c = P.c
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface)
            .border(1.dp, c.success.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.success.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.BatteryChargingFull, null, tint = c.success, modifier = Modifier.size(24.dp))
            }
            Text(
                stringResource(R.string.battery_title), style = MaterialTheme.typography.titleMedium, color = c.text,
                modifier = Modifier.padding(start = 14.dp).weight(1f),
            )
        }
        Text(
            stringResource(R.string.battery_body), style = MaterialTheme.typography.bodyMedium, color = c.muted,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
            SoftButton(stringResource(R.string.battery_later), Modifier.weight(1f), onLater)
            GradientButton(stringResource(R.string.battery_allow), Modifier.weight(1f).height(52.dp), onClick = onAllow)
        }
    }
}

@Composable
private fun AddFirstDeviceCard(onAdd: () -> Unit) {
    val c = P.c
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brushes.cardGlow(c.dark))
            .border(1.dp, Orange.copy(alpha = 0.35f), RoundedCornerShape(28.dp))
            .clickable(onClick = onAdd)
            .padding(24.dp),
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(BrandGradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.QrCode2, null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Text(stringResource(R.string.first_title), style = MaterialTheme.typography.headlineSmall, color = c.text, modifier = Modifier.padding(top = 18.dp))
        Text(
            stringResource(R.string.first_body),
            style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.first_cta), style = MaterialTheme.typography.titleSmall, color = Orange)
            Icon(Icons.Rounded.ArrowOutward, null, tint = Orange, modifier = Modifier.size(18.dp).padding(start = 4.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeerCard(
    peer: Peer,
    online: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = P.c
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface)
            .border(1.dp, c.outline.copy(alpha = if (c.dark) 0.7f else 1f), RoundedCornerShape(24.dp))
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(peer.platform, online)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(peer.displayName, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (online) stringResource(R.string.online) else Format.seen(LocalContext.current, peer.lastSeen),
                style = MaterialTheme.typography.bodySmall,
                color = if (online) c.success else c.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (online) BrandGradient else Brushes.solid(c.surfaceHigh)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ArrowOutward, stringResource(R.string.cd_send), tint = if (online) Color.White else c.muted, modifier = Modifier.size(20.dp))
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.cd_options), tint = c.muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surfaceHigh) {
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_unpair), color = c.danger) }, onClick = { menu = false; onUnpair() })
            }
        }
    }
}

@Composable
private fun IncomingCard(inc: Live.Incoming, modifier: Modifier = Modifier) {
    val c = P.c
    val progress = if (inc.size > 0) inc.received.toFloat() / inc.size else 0f
    AnimatedVisibility(visible = true, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Row(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brushes.cardGlow(c.dark))
                .border(1.dp, Violet.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                ProgressRing(progress, Modifier.size(52.dp), track = c.outline)
                Icon(Icons.Rounded.SouthWest, null, tint = c.text, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    if (inc.count > 1) stringResource(R.string.incoming_many, inc.index + 1, inc.count, inc.peerName) else stringResource(R.string.incoming_one, inc.peerName),
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
                Text(inc.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.size_of, Format.size(inc.received), Format.size(inc.size)),
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(h: HistoryItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = P.c
    val model: Any? = when {
        h.direction == "in" && h.uri != null -> Uri.parse(h.uri)
        h.thumbPath != null -> File(h.thumbPath)
        else -> null
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = h.direction == "in", onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(model, h.mime, h.name, Modifier.size(56.dp), corner = 16.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(h.name, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(if (h.direction == "in") R.string.history_from else R.string.history_to, h.peerName) +
                    (h.collection?.let { " · $it" } ?: "") + " · ${Format.size(h.size)} · ${Format.ago(LocalContext.current, h.time)}",
                style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(if (h.direction == "in") c.success.copy(alpha = 0.14f) else Orange.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (h.direction == "in") Icons.Rounded.SouthWest else Icons.Rounded.ArrowOutward, null,
                tint = if (h.direction == "in") c.success else Orange, modifier = Modifier.size(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextHistoryRow(h: HistoryItem, modifier: Modifier = Modifier) {
    val c = P.c
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Up to 1 MB on disk: read it off the main thread, then act.
    fun withFull(block: (String) -> Unit) = scope.launch {
        val t = withContext(Dispatchers.IO) { TextStore.load(h.id) } ?: h.text.orEmpty()
        block(t)
    }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(
                    onClick = {
                        withFull { Clip.copy(context, it) }
                        Notifications.cancelText(context, h.id)
                    },
                    onLongClick = { menu = true },
                )
                .padding(vertical = 6.dp, horizontal = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (h.direction == "in") R.string.history_from else R.string.history_to, h.peerName) +
                        " · ${Format.ago(context, h.time)} · " + stringResource(R.string.text_tap_hint),
                    style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            QuoteBlock(h.text.orEmpty(), maxLines = 3, modifier = Modifier.padding(top = 8.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surfaceHigh) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_copy)) }, onClick = {
                menu = false
                withFull { Clip.copy(context, it) }
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = {
                menu = false
                withFull { Clip.share(context, it) }
            })
        }
    }
}

object Brushes {
    fun solid(color: Color) = androidx.compose.ui.graphics.SolidColor(color)
    fun cardGlow(dark: Boolean) = androidx.compose.ui.graphics.Brush.linearGradient(
        listOf(Orange.copy(alpha = if (dark) 0.16f else 0.10f), Violet.copy(alpha = if (dark) 0.20f else 0.10f)),
    )
}
