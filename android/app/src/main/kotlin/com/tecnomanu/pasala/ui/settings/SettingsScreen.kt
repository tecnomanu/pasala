package com.tecnomanu.pasala.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.net.Addresses
import com.tecnomanu.pasala.ui.components.Backdrop
import com.tecnomanu.pasala.ui.components.PasalaCard
import com.tecnomanu.pasala.ui.components.SectionLabel
import com.tecnomanu.pasala.ui.theme.Orange
import com.tecnomanu.pasala.ui.theme.P

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = P.c
    val state by Store.state.collectAsState()
    val port by Live.port.collectAsState()
    val focus = LocalFocusManager.current
    var name by remember { mutableStateOf(state.identity?.name.orEmpty()) }
    var details by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Save the name when leaving the screen too.
    DisposableEffect(Unit) {
        onDispose {
            if (name.isNotBlank() && name.trim() != Store.identity.name) {
                Store.setName(name)
                com.tecnomanu.pasala.service.PasalaService.reannounce()
            }
        }
    }

    Backdrop {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = top + 8.dp, bottom = bottom + 24.dp),
        ) {
            IconButton(onClick = { focus.clearFocus(); onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver", tint = c.text) }
            Text("Ajustes", style = MaterialTheme.typography.displaySmall, color = c.text, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 22.dp))

            SectionLabel("Este teléfono")
            Spacer(Modifier.height(10.dp))
            PasalaCard(Modifier.fillMaxWidth()) {
                Text("Nombre", style = MaterialTheme.typography.titleSmall, color = c.text)
                Text("Así te ven tus otros dispositivos.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { Store.setName(name); focus.clearFocus() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Orange, cursorColor = Orange, unfocusedBorderColor = c.outline),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Al recibir")
            Spacer(Modifier.height(10.dp))
            PasalaCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Pedir confirmación antes de recibir", style = MaterialTheme.typography.titleSmall, color = c.text)
                        Text(
                            "Te preguntamos cada vez que alguien te quiere pasar algo. Si no contestás en un minuto, no se recibe.",
                            style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Switch(
                        checked = state.settings.askBeforeReceiving,
                        onCheckedChange = { Store.setAskBeforeReceiving(it) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Orange, checkedThumbColor = Color.White, checkedBorderColor = Orange,
                            uncheckedTrackColor = c.surfaceHigh, uncheckedThumbColor = c.muted, uncheckedBorderColor = c.faint,
                        ),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("Dónde se guarda", style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(
                    "Fotos en Imágenes › Pasala, videos en Películas › Pasala y todo lo demás en Descargas › Pasala. Las fotos y videos aparecen en tu galería.",
                    style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            val rot by animateFloatAsState(if (details) 180f else 0f, label = "chev")
            PasalaCard(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { details = !details },
                ) {
                    Text("Detalles", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ExpandMore, null, tint = c.muted, modifier = Modifier.rotate(rot))
                }
                AnimatedVisibility(details) {
                    val addrs = remember(details) { Addresses.local() }
                    Column(Modifier.padding(top = 12.dp)) {
                        Detail("Puerto", if (port > 0) port.toString() else "—")
                        Detail("Wi-Fi", addrs.lan.joinToString("\n").ifEmpty { "Sin conexión" })
                        Detail("Tailscale", addrs.tailscale.joinToString("\n").ifEmpty { "Apagado" })
                        Detail("Identificador", state.identity?.id?.take(8).orEmpty())
                        Detail("Dispositivos sumados", state.peers.size.toString())
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    val c = P.c
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.text)
    }
}
