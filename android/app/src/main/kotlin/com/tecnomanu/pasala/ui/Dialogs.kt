package com.tecnomanu.pasala.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tecnomanu.pasala.core.Format
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.core.Receiver
import com.tecnomanu.pasala.data.Peer
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.net.PasalaClient
import com.tecnomanu.pasala.net.Resolver
import com.tecnomanu.pasala.ui.components.GradientButton
import com.tecnomanu.pasala.ui.components.PasalaCard
import com.tecnomanu.pasala.ui.components.BrandMark
import com.tecnomanu.pasala.ui.components.SoftButton
import com.tecnomanu.pasala.ui.theme.Orange
import com.tecnomanu.pasala.ui.theme.P
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Branded dialog shell used across the app. */
@Composable
fun PasalaDialog(
    title: String,
    body: String? = null,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit = {},
    actions: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        PasalaCard(Modifier.fillMaxWidth(), padding = 24.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                BrandMark(48.dp, 16.dp)
                Spacer(Modifier.height(16.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, color = P.c.text, textAlign = TextAlign.Center)
                if (body != null) {
                    Text(
                        body, style = MaterialTheme.typography.bodyMedium, color = P.c.muted, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                content()
                Spacer(Modifier.height(22.dp))
                actions()
            }
        }
    }
}

@Composable
fun PendingOfferDialog() {
    val offers by Live.pendingOffers.collectAsState()
    val o = offers.firstOrNull() ?: return
    val what = if (o.count == 1) "1 archivo" else "${o.count} archivos"
    PasalaDialog(
        title = "${o.peerName} te quiere pasar $what",
        body = "Son ${Format.size(o.totalSize)}. Si no contestás en un minuto, no se recibe nada.",
        onDismiss = {},
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SoftButton("Rechazar", Modifier.weight(1f)) { Receiver.decide(o.batch, false) }
            GradientButton("Aceptar", Modifier.weight(1f)) { Receiver.decide(o.batch, true) }
        }
    }
}

@Composable
fun RenameDialog(peer: Peer, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(peer.displayName) }
    PasalaDialog(
        title = "Cambiar nombre",
        body = "Solo cambia cómo lo ves en este teléfono.",
        onDismiss = onDismiss,
        content = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Orange, cursorColor = Orange),
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SoftButton("Cancelar", Modifier.weight(1f), onDismiss)
            GradientButton("Guardar", Modifier.weight(1f)) {
                val n = text.trim()
                Store.updatePeer(peer.id) { it.copy(localName = n.takeIf { v -> v.isNotEmpty() && v != it.name }) }
                onDismiss()
            }
        }
    }
}

@Composable
fun UnpairDialog(peer: Peer, onDismiss: () -> Unit) {
    PasalaDialog(
        title = "¿Desvincular ${peer.displayName}?",
        body = "Van a dejar de poder pasarse archivos. Para volver a hacerlo, escaneen el código otra vez.",
        onDismiss = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SoftButton("Cancelar", Modifier.weight(1f), onDismiss)
            GradientButton("Desvincular", Modifier.weight(1f)) {
                unpair(peer)
                onDismiss()
            }
        }
    }
}

/** Tells the other side (best effort) and forgets the peer here right away. */
fun unpair(peer: Peer) {
    Store.removePeer(peer.id)
    Live.appScope.launch(Dispatchers.IO) {
        val route = Resolver.candidates(peer).firstOrNull { r ->
            runCatching { PasalaClient.unpair(r.host, r.port, peer); true }.getOrDefault(false)
        }
        android.util.Log.i("Pasala", "unpaired ${peer.displayName}, told other side: ${route != null}")
    }
}
