package com.tecnomanu.pasala.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.res.stringResource
import com.tecnomanu.pasala.R
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
            IconButton(onClick = { focus.clearFocus(); onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), tint = c.text) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.displaySmall, color = c.text, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 22.dp))

            SectionLabel(stringResource(R.string.settings_this_phone))
            Spacer(Modifier.height(10.dp))
            PasalaCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_name), style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(stringResource(R.string.settings_name_hint), style = MaterialTheme.typography.bodySmall, color = c.muted)
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
            SectionLabel(stringResource(R.string.settings_on_receive))
            Spacer(Modifier.height(10.dp))
            PasalaCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.settings_ask_title), style = MaterialTheme.typography.titleSmall, color = c.text)
                        Text(
                            stringResource(R.string.settings_ask_body),
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
                Text(stringResource(R.string.settings_where_title), style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(
                    stringResource(R.string.settings_where_body),
                    style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            var exempt by remember { mutableStateOf(true) }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                exempt = com.tecnomanu.pasala.service.Background.isExempt(ctx)
                onPauseOrDispose { }
            }
            // A state with a way to change it, not a switch: only the system can turn the exemption off.
            PasalaCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.settings_background_title), style = MaterialTheme.typography.titleSmall, color = c.text)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Box(
                                Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(if (exempt) c.success else c.faint),
                            )
                            Text(
                                stringResource(if (exempt) R.string.settings_background_state_on else R.string.settings_background_state_off),
                                style = MaterialTheme.typography.labelMedium, color = if (exempt) c.success else c.muted,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        Text(
                            stringResource(if (exempt) R.string.settings_background_on else R.string.settings_background_off),
                            style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.action_change),
                        style = MaterialTheme.typography.titleSmall, color = Orange,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { com.tecnomanu.pasala.service.Background.change(ctx) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            val launchSettings = remember { com.tecnomanu.pasala.service.Background.vendorLaunchSettings(ctx) }
            if (launchSettings != null) {
                Spacer(Modifier.height(12.dp))
                PasalaCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(stringResource(R.string.settings_autostart_title), style = MaterialTheme.typography.titleSmall, color = c.text)
                            Text(
                                stringResource(R.string.settings_autostart_body),
                                style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Text(
                            stringResource(R.string.action_change),
                            style = MaterialTheme.typography.titleSmall, color = Orange,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { runCatching { ctx.startActivity(launchSettings) } }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            val rot by animateFloatAsState(if (details) 180f else 0f, label = "chev")
            PasalaCard(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { details = !details },
                ) {
                    Text(stringResource(R.string.settings_details), style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ExpandMore, null, tint = c.muted, modifier = Modifier.rotate(rot))
                }
                AnimatedVisibility(details) {
                    val addrs = remember(details) { Addresses.local() }
                    Column(Modifier.padding(top = 12.dp)) {
                        Detail(stringResource(R.string.detail_port), if (port > 0) port.toString() else "—")
                        Detail(stringResource(R.string.detail_wifi), addrs.lan.joinToString("\n").ifEmpty { stringResource(R.string.detail_no_connection) })
                        Detail(stringResource(R.string.detail_tailscale), addrs.tailscale.joinToString("\n").ifEmpty { stringResource(R.string.detail_off) })
                        Detail(stringResource(R.string.detail_id), state.identity?.id?.take(8).orEmpty())
                        Detail(stringResource(R.string.detail_devices), state.peers.size.toString())
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
