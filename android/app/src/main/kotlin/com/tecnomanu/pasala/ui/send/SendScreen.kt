package com.tecnomanu.pasala.ui.send

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tecnomanu.pasala.R
import com.tecnomanu.pasala.core.Format
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.core.Outgoing
import com.tecnomanu.pasala.core.Sender
import com.tecnomanu.pasala.data.Peer
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.service.PasalaService
import com.tecnomanu.pasala.ui.components.Backdrop
import com.tecnomanu.pasala.ui.components.FileThumb
import com.tecnomanu.pasala.ui.components.GradientButton
import com.tecnomanu.pasala.ui.components.PasalaCard
import com.tecnomanu.pasala.ui.components.PeerAvatar
import com.tecnomanu.pasala.ui.components.ProgressRing
import com.tecnomanu.pasala.ui.components.SectionLabel
import com.tecnomanu.pasala.ui.components.SoftButton
import com.tecnomanu.pasala.ui.theme.Orange
import com.tecnomanu.pasala.ui.theme.P
import com.tecnomanu.pasala.ui.theme.Violet
import java.util.UUID
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.ui.graphics.vector.ImageVector
import com.tecnomanu.pasala.data.Collection
import com.tecnomanu.pasala.net.Resolver

@Composable
fun SendScreen(
    files: List<Outgoing>,
    preselectedPeer: String?,
    autoStart: Boolean,
    onClose: () -> Unit,
    onAddDevice: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val state by Store.state.collectAsState()
    val online by Live.online.collectAsState()
    val checking by Live.checking.collectAsState()
    val jobs by Sender.jobs.collectAsState()
    val c = P.c

    var jobId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var selected by rememberSaveable {
        mutableStateOf(preselectedPeer ?: state.peers.singleOrNull()?.id)
    }
    // Default to whichever peer is online when the person didn't pick one yet.
    LaunchedEffect(online) {
        if (selected == null) selected = state.peers.firstOrNull { online[it.id] == true }?.id
    }
    val job = jobs[jobId]
    // The finished panel keeps showing while it animates out, even after the job is forgotten.
    val lastJob = remember { arrayOfNulls<Sender.JobState>(1) }
    if (job != null) lastJob[0] = job
    val shownJob = job ?: lastJob[0]

    // Collection on the destination (null = Ninguna, the destination's default folder).
    var collection by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedByHand by rememberSaveable { mutableStateOf(false) }
    var destChecking by remember { mutableStateOf(false) }
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    val selPeer = state.peers.firstOrNull { it.id == selected }
    val collections = selPeer?.collections.orEmpty()

    fun start() {
        val peer = selected ?: return
        val col = collection?.takeIf { id -> Store.peer(peer)?.collections?.any { it.id == id } == true }
        Store.updatePeer(peer) { it.copy(lastCollection = col) }
        PasalaService.send(context, jobId, peer, files, col)
    }

    // Fresh hello whenever the destination changes: it brings that device's current collections.
    LaunchedEffect(selected) {
        val p = selected?.let { Store.peer(it) } ?: return@LaunchedEffect
        if (!pickedByHand) collection = p.lastCollection?.takeIf { id -> p.collections.any { it.id == id } }
        destChecking = true
        Resolver.reach(p)
        destChecking = false
        val fresh = Store.peer(p.id) ?: p
        if (collection != null && fresh.collections.none { it.id == collection }) collection = null
        if (!pickedByHand && collection == null) {
            collection = fresh.lastCollection?.takeIf { id -> fresh.collections.any { it.id == id } }
        }
        // From Home the destination is already chosen: go straight away unless there's a question to ask.
        if (autoStart && preselectedPeer != null && !autoStarted && jobs[jobId] == null && fresh.collections.isEmpty()) {
            autoStarted = true
            start()
        }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val total = files.sumOf { it.size }

    Backdrop {
        Column(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Cerrar", tint = c.text) }
                        }
                        Text(
                            if (files.size == 1) "Pasar 1 archivo" else "Pasar ${files.size} archivos",
                            style = MaterialTheme.typography.displaySmall, color = c.text,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                        )
                        Text(
                            "${Format.size(total)} en total",
                            style = MaterialTheme.typography.bodyMedium, color = c.muted,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 14.dp),
                        )
                    }
                }
                itemsIndexed(files, key = { i, f -> "$i-${f.uri}" }) { i, f ->
                    FileTile(f, job?.files?.getOrNull(i), job?.phase)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (job == null) {
                        DestinationPicker(
                            peers = state.peers, online = online, checking = checking,
                            selected = selected,
                            onSelect = { if (it != selected) { selected = it; pickedByHand = false; collection = null } },
                            onAddDevice = onAddDevice,
                        )
                    } else Spacer(Modifier.height(1.dp))
                }
                if (job == null && selPeer != null && collections.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "collections-${selPeer.id}") {
                        CollectionPicker(
                            peerName = selPeer.displayName,
                            collections = collections,
                            selected = collection,
                            checking = destChecking,
                            onSelect = { collection = it; pickedByHand = true },
                        )
                    }
                }
            }

            // Bottom action area
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, c.ink, c.ink)))
                    .padding(start = 20.dp, end = 20.dp, bottom = bottom + 16.dp, top = 8.dp),
            ) {
                AnimatedContent(
                    targetState = job?.phase,
                    transitionSpec = { (fadeIn(tween(250)) + slideInVertically { it / 3 }).togetherWith(fadeOut(tween(150))) },
                    label = "phase",
                ) { phase ->
                    when (phase) {
                        null -> GradientButton(
                            text = selPeer?.let { p ->
                                "Enviar a ${p.displayName}" + (collections.firstOrNull { it.id == collection }?.let { " · ${it.name}" } ?: "")
                            } ?: "Elegí a dónde",
                            enabled = selected != null && files.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            icon = { androidx.compose.foundation.Image(painterResource(R.drawable.ic_mark), null, modifier = Modifier.size(24.dp)) },
                        ) { start() }
                        Sender.Phase.Done -> ResultPanel(
                            ok = true,
                            title = "Listo, ya está en ${shownJob!!.peerName}",
                            body = (if (files.size == 1) "Llegó el archivo" else "Llegaron los ${files.size} archivos") +
                                (shownJob!!.collectionName?.let { " a $it." } ?: "."),
                            primary = "Cerrar", onPrimary = { Sender.forget(jobId); onClose() },
                        )
                        Sender.Phase.Declined, Sender.Phase.Unreachable, Sender.Phase.Failed -> ResultPanel(
                            ok = false,
                            title = when (phase) {
                                Sender.Phase.Declined -> "${shownJob!!.peerName} no lo aceptó"
                                Sender.Phase.Unreachable -> "No encontramos a ${shownJob!!.peerName}"
                                else -> "Se cortó en el camino"
                            },
                            body = when (phase) {
                                Sender.Phase.Declined -> "Del otro lado dijeron que no, o no contestaron a tiempo."
                                Sender.Phase.Unreachable -> "Fijate que esté prendido y con Pasala abierta, en la misma red o con Tailscale."
                                else -> "Algunos archivos no llegaron. Podés probar de nuevo."
                            },
                            primary = "Reintentar",
                            onPrimary = {
                                Sender.forget(jobId)
                                jobId = UUID.randomUUID().toString()
                                // new id -> start fresh with the same destination
                                val col = shownJob!!.collectionName?.let { n -> Store.peer(shownJob.peerId)?.collections?.firstOrNull { it.name == n }?.id }
                                PasalaService.send(context, jobId, selected ?: shownJob.peerId, files, col)
                            },
                            secondary = "Cerrar", onSecondary = { Sender.forget(jobId); onClose() },
                        )
                        else -> ProgressPanel(shownJob!!)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileTile(f: Outgoing, fs: Sender.FileState?, phase: Sender.Phase?) {
    val c = P.c
    val media = f.mime.startsWith("image/") || f.mime.startsWith("video/")
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            FileThumb(f.uri, f.mime, f.name, Modifier.fillMaxSize(), corner = 20.dp, showLabel = true)
            if (fs != null && phase != null) {
                val sending = !fs.done && !fs.failed && phase == Sender.Phase.Sending
                if (sending || fs.done || fs.failed) {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = if (fs.done) 0.25f else 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            fs.done -> DoneBadge()
                            fs.failed -> Icon(Icons.Rounded.ErrorOutline, null, tint = Color(0xFFFF8FA3), modifier = Modifier.size(30.dp))
                            else -> ProgressRing(if (f.size > 0) fs.sent.toFloat() / f.size else 0f, Modifier.size(40.dp))
                        }
                    }
                }
            }
        }
        if (!media) {
            Text(f.name, style = MaterialTheme.typography.bodySmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        }
        Text(Format.size(f.size), style = MaterialTheme.typography.labelSmall, color = c.muted, modifier = Modifier.padding(top = if (media) 6.dp else 0.dp, start = 2.dp))
    }
}

@Composable
private fun DoneBadge() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val s by animateFloatAsState(if (shown) 1f else 0.3f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "done")
    Box(Modifier.size(36.dp).scale(s).clip(CircleShape).background(P.c.success), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Check, null, tint = Color(0xFF0E0F1A), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DestinationPicker(
    peers: List<Peer>,
    online: Map<String, Boolean>,
    checking: Boolean,
    selected: String?,
    onSelect: (String) -> Unit,
    onAddDevice: (() -> Unit)?,
) {
    val c = P.c
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("¿A dónde?") {
            if (checking) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.muted)
        }
        if (peers.isEmpty()) {
            PasalaCard(Modifier.fillMaxWidth(), onClick = onAddDevice) {
                Text("Todavía no sumaste dispositivos", style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(
                    "Abrí Pasala y escaneá el código de tu Mac o de otro teléfono. Después vas a poder elegirlo acá.",
                    style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        peers.sortedByDescending { online[it.id] == true }.forEach { p ->
            val isOn = online[p.id] == true
            val isSel = selected == p.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(c.surface)
                    .border(
                        if (isSel) 2.dp else 1.dp,
                        if (isSel) Brush.linearGradient(listOf(Orange, Violet)) else Brush.linearGradient(listOf(c.outline, c.outline)),
                        RoundedCornerShape(24.dp),
                    )
                    .clickable { onSelect(p.id) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PeerAvatar(p.platform, isOn, 46.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(p.displayName, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (isOn) "En línea" else if (checking) "Buscando…" else "No aparece ahora",
                        style = MaterialTheme.typography.bodySmall, color = if (isOn) c.success else c.muted,
                    )
                }
                Box(
                    Modifier.size(24.dp).clip(CircleShape)
                        .background(if (isSel) Brush.linearGradient(listOf(Orange, Violet)) else Brush.linearGradient(listOf(c.surfaceHigh, c.surfaceHigh))),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSel) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionPicker(
    peerName: String,
    collections: List<Collection>,
    selected: String?,
    checking: Boolean,
    onSelect: (String?) -> Unit,
) {
    val c = P.c
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("¿En qué colección?") {
            if (checking) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.muted)
        }
        PasalaCard(Modifier.fillMaxWidth(), padding = 14.dp) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CollectionChip("Ninguna", Icons.Rounded.Inbox, selected == null) { onSelect(null) }
                collections.forEach { col ->
                    CollectionChip(col.name, Icons.Rounded.Folder, selected == col.id) { onSelect(col.id) }
                }
            }
            Text(
                if (selected == null) "Queda donde $peerName guarda siempre."
                else "Queda en la colección que armaste en $peerName.",
                style = MaterialTheme.typography.bodySmall, color = c.muted,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp),
            )
        }
    }
}

@Composable
private fun CollectionChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val c = P.c
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(
                if (selected) Brush.horizontalGradient(listOf(Orange, Violet))
                else Brush.horizontalGradient(listOf(c.surfaceHigh, c.surfaceHigh)),
            )
            .clickable(onClick = onClick)
            .animateContentSize()
            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Rounded.Check else icon, null,
            tint = if (selected) Color.White else Orange, modifier = Modifier.size(18.dp),
        )
        Text(
            label, style = MaterialTheme.typography.titleSmall,
            color = if (selected) Color.White else c.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).widthIn(max = 220.dp),
        )
    }
}

@Composable
private fun ProgressPanel(job: Sender.JobState) {
    val c = P.c
    val pct = (job.progress * 100).toInt()
    PasalaCard(Modifier.fillMaxWidth(), padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                if (job.phase == Sender.Phase.Sending) {
                    ProgressRing(job.progress, Modifier.size(54.dp), track = c.outline)
                    Text("$pct%", style = MaterialTheme.typography.labelSmall, color = c.text)
                } else {
                    CircularProgressIndicator(Modifier.size(40.dp), color = Orange, strokeWidth = 3.dp, trackColor = c.outline)
                }
            }
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(
                    when (job.phase) {
                        Sender.Phase.Connecting -> "Buscando a ${job.peerName}…"
                        Sender.Phase.Waiting -> "Esperando que ${job.peerName} acepte…"
                        else -> "Pasando a ${job.peerName}" + (job.collectionName?.let { " · $it" } ?: "") + "…"
                    },
                    style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 2,
                )
                val doneCount = job.files.count { it.done }
                Text(
                    if (job.phase == Sender.Phase.Sending) "$doneCount de ${job.files.size} · ${Format.size(job.sent)} de ${Format.size(job.total)}"
                    else "Podés salir de esta pantalla, sigue solo.",
                    style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ResultPanel(
    ok: Boolean,
    title: String,
    body: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val c = P.c
    Column {
        PasalaCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }
                val s by animateFloatAsState(if (shown) 1f else 0.4f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "res")
                Box(
                    Modifier.size(52.dp).scale(s).clip(CircleShape).background(if (ok) c.success else c.danger.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (ok) Icons.Rounded.Check else Icons.Rounded.ErrorOutline, null,
                        tint = if (ok) Color(0xFF0E0F1A) else c.danger, modifier = Modifier.size(28.dp),
                    )
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = c.text)
                    Text(body, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (secondary != null && onSecondary != null) SoftButton(secondary, Modifier.weight(1f).height(60.dp), onSecondary)
            GradientButton(primary, Modifier.weight(if (secondary != null) 1.4f else 1f), onClick = onPrimary)
        }
    }
}

