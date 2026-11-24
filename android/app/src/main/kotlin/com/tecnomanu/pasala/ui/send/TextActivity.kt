package com.tecnomanu.pasala.ui.send

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tecnomanu.pasala.R
import com.tecnomanu.pasala.core.Clip
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.core.Sender
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.net.Resolver
import com.tecnomanu.pasala.service.PasalaService
import com.tecnomanu.pasala.ui.components.GradientButton
import com.tecnomanu.pasala.ui.theme.P
import com.tecnomanu.pasala.ui.theme.PasalaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Small sheet that sends a text to one device. Opened from the text-selection menu, the share sheet
 * (plain text), the quick-settings tile or Home ("Send what you copied").
 */
class TextActivity : ComponentActivity() {
    private var text by mutableStateOf<String?>(null)
    /** True until the clipboard was read (it can only be read once our window has focus). */
    private var waitingForClipboard by mutableStateOf(false)
    /** Bumped on every new request so the sheet starts over (no stale "sent" panel). */
    private var opened by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PasalaService.start(this)
        load(intent)
        Live.appScope.launch { Resolver.refreshAll() }
        setContent { PasalaTheme { androidx.compose.runtime.key(opened) { Sheet() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    private fun load(intent: Intent) {
        text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        waitingForClipboard = text == null && intent.getBooleanExtra(EXTRA_FROM_CLIPBOARD, false)
        if (text == null && !waitingForClipboard) text = "" // nothing to send: show the empty state
        opened++
    }


    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Sheet() {
        val c = P.c
        val state by Store.state.collectAsState()
        val online by Live.online.collectAsState()
        val checking by Live.checking.collectAsState()
        val scope = rememberCoroutineScope()
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var selected by remember {
            mutableStateOf(state.settings.lastTextPeer?.takeIf { id -> state.peers.any { it.id == id } } ?: state.peers.singleOrNull()?.id)
        }
        LaunchedEffect(online) {
            if (selected == null) selected = state.peers.firstOrNull { online[it.id] == true }?.id
        }
        var sending by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<Sender.TextResult?>(null) }
        val t = text
        // Read only on the person's request (tile / button). Android allows it once one of our windows
        // (here the sheet) has focus, which takes a moment after opening.
        LaunchedEffect(waitingForClipboard) {
            if (!waitingForClipboard) return@LaunchedEffect
            var read: String? = null
            for (i in 0 until 30) {
                delay(100)
                read = Clip.read(this@TextActivity)
                if (read != null) break
            }
            text = read.orEmpty()
            waitingForClipboard = false
        }

        ModalBottomSheet(onDismissRequest = { finish() }, sheetState = sheet, containerColor = c.ink) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            ) {
                Text(stringResource(R.string.text_sheet_title), style = MaterialTheme.typography.headlineSmall, color = c.text)
                Spacer(Modifier.height(14.dp))
                when {
                    t == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.muted)
                    }
                    t.isBlank() -> {
                        Text(stringResource(R.string.text_nothing_title), style = MaterialTheme.typography.titleMedium, color = c.text)
                        Text(
                            stringResource(R.string.text_nothing_body), style = MaterialTheme.typography.bodyMedium, color = c.muted,
                            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                        )
                        GradientButton(stringResource(R.string.action_close), Modifier.fillMaxWidth()) { finish() }
                    }
                    else -> {
                        QuoteBlock(t, maxLines = 6)
                        val r = result
                        if (r == null) {
                            DestinationPicker(
                                peers = state.peers, online = online, checking = checking,
                                selected = selected, onSelect = { selected = it }, onAddDevice = null,
                            )
                            Spacer(Modifier.height(18.dp))
                            val peerName = state.peers.firstOrNull { it.id == selected }?.displayName
                            GradientButton(
                                text = peerName?.let { stringResource(R.string.send_to, it) } ?: stringResource(R.string.send_pick),
                                enabled = selected != null,
                                loading = sending,
                                modifier = Modifier.fillMaxWidth(),
                                icon = { androidx.compose.foundation.Image(painterResource(R.drawable.ic_mark), null, Modifier.size(24.dp)) },
                            ) {
                                val id = selected ?: return@GradientButton
                                sending = true
                                Store.setLastTextPeer(id)
                                scope.launch {
                                    result = Sender.sendText(applicationContext, id, t)
                                    sending = false
                                }
                            }
                        } else {
                            Spacer(Modifier.height(16.dp))
                            val name = state.peers.firstOrNull { it.id == selected }?.displayName.orEmpty()
                            ResultPanel(
                                ok = r == Sender.TextResult.Ok,
                                title = when (r) {
                                    Sender.TextResult.Ok -> stringResource(R.string.text_sent, name)
                                    Sender.TextResult.Unreachable -> stringResource(R.string.unreach_title, name)
                                    Sender.TextResult.TooLarge -> stringResource(R.string.text_too_large)
                                    Sender.TextResult.Failed -> stringResource(R.string.failed_title)
                                },
                                body = when (r) {
                                    Sender.TextResult.Ok -> stringResource(R.string.text_sent_body)
                                    Sender.TextResult.Unreachable -> stringResource(R.string.unreach_body)
                                    else -> ""
                                },
                                primary = stringResource(if (r == Sender.TextResult.Ok) R.string.action_done else R.string.action_retry),
                                onPrimary = { if (r == Sender.TextResult.Ok) finish() else result = null },
                                secondary = if (r == Sender.TextResult.Ok) null else stringResource(R.string.action_close),
                                onSecondary = { finish() },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_FROM_CLIPBOARD = "from_clipboard"

        fun fromClipboard(context: Context): Intent =
            Intent(context, TextActivity::class.java).putExtra(EXTRA_FROM_CLIPBOARD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/** A text shown as a quote: used in the send sheet and the activity list. */
@Composable
fun QuoteBlock(text: String, maxLines: Int, modifier: Modifier = Modifier) {
    val c = P.c
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).heightIn(min = 52.dp).padding(14.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(Icons.Rounded.FormatQuote, null, tint = c.success, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        // Only the start is ever visible; don't lay out a megabyte of text.
        Text(text.take(2000), style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}
