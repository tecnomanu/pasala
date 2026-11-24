package com.tecnomanu.pasala.ui.pair

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.core.Pairing
import com.tecnomanu.pasala.data.QrPayload
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.ui.PasalaDialog
import com.tecnomanu.pasala.ui.components.Backdrop
import com.tecnomanu.pasala.ui.components.GradientButton
import com.tecnomanu.pasala.ui.components.BrandMark
import com.tecnomanu.pasala.ui.components.SoftButton
import com.tecnomanu.pasala.ui.theme.Orange
import com.tecnomanu.pasala.ui.theme.P
import com.tecnomanu.pasala.ui.theme.Violet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AddDeviceScreen(startOnScan: Boolean, onDone: () -> Unit) {
    val c = P.c
    var scan by remember { mutableStateOf(startOnScan) }
    var pairedName by remember { mutableStateOf<String?>(null) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Someone scanned our code (or we scanned theirs): celebrate.
    LaunchedEffect(Unit) {
        Live.paired.collect { id -> pairedName = Store.peer(id)?.displayName ?: "tu dispositivo" }
    }

    Backdrop {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = top + 8.dp, bottom = bottom + 24.dp),
        ) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver", tint = c.text) }
            Text("Sumar dispositivo", style = MaterialTheme.typography.displaySmall, color = c.text, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            Text(
                "Uno muestra el código y el otro lo escanea. Da igual cuál: después se pueden pasar cosas para los dos lados.",
                style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 20.dp),
            )

            val name = pairedName
            if (name != null) {
                PairedSuccess(name, onDone = onDone, onAnother = { pairedName = null })
                return@Column
            }

            Segmented(scan) { scan = it }
            Spacer(Modifier.height(20.dp))
            AnimatedContent(scan, transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(150))) }, label = "tab") { s ->
                if (s) ScanTab() else ShowCodeTab()
            }
        }
    }
}

@Composable
private fun Segmented(scan: Boolean, onChange: (Boolean) -> Unit) {
    val c = P.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(c.surface)
            .border(1.dp, c.outline, RoundedCornerShape(50)).padding(5.dp),
    ) {
        listOf(false to "Mostrar mi código", true to "Escanear código").forEach { (value, label) ->
            val sel = scan == value
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(50))
                    .background(if (sel) Brush.horizontalGradient(listOf(Orange, Violet)) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onChange(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = if (sel) Color.White else c.muted)
            }
        }
    }
}

private fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 0)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(m.width * m.height)
    val ink = 0xFF0E0F1A.toInt()
    for (y in 0 until m.height) for (x in 0 until m.width) pixels[y * m.width + x] = if (m[x, y]) ink else 0xFFFFFFFF.toInt()
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
}

@Composable
private fun ShowCodeTab() {
    val c = P.c
    val code by Pairing.current.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(Unit) {
        Pairing.newCode()
        onDispose { Pairing.clear() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            val cur = Pairing.current.value
            if (cur == null || now >= cur.expiresAt) Pairing.newCode()
            delay(1000)
        }
    }
    val current = code
    val bmp = remember(current?.payload) { current?.let { qrBitmap(it.payload).asImageBitmap() } }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth(0.86f).aspectRatio(1f).clip(RoundedCornerShape(32.dp))
                .background(Brush.linearGradient(listOf(Orange, Violet))).padding(4.dp),
        ) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Color.White).padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(bmp, "Código para sumar este teléfono", Modifier.fillMaxSize())
                    Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(5.dp)) {
                        BrandMark(48.dp, 14.dp)
                    }
                } else CircularProgressIndicator(color = Orange)
            }
        }
        val left = ((current?.expiresAt ?: now) - now).coerceAtLeast(0) / 1000
        Text(
            Store.identity.name,
            style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(top = 22.dp),
        )
        Text(
            "Escaneálo desde la Mac o desde otro teléfono con Pasala.",
            style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp),
        )
        Text(
            "Se renueva en %d:%02d · sirve una sola vez".format(left / 60, left % 60),
            style = MaterialTheme.typography.bodySmall, color = c.faint, modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun ScanTab() {
    val c = P.c
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) ask.launch(Manifest.permission.CAMERA) }

    var found by remember { mutableStateOf<QrPayload?>(null) }
    var status by remember { mutableStateOf<PairStatus>(PairStatus.Idle) }

    fun pair(qr: QrPayload) {
        found = qr
        status = PairStatus.Working
        scope.launch {
            status = when (Pairing.pairWith(qr)) {
                is Pairing.Result.Ok -> PairStatus.Idle // success screen comes from Live.paired
                Pairing.Result.Expired -> PairStatus.Error("Ese código ya se usó o venció. Mostrá uno nuevo en el otro dispositivo y volvé a escanear.")
                Pairing.Result.Unreachable -> PairStatus.Error("No pudimos llegar a ${qr.name}. Fijate que estén en la misma red Wi-Fi o con Tailscale prendido.")
                Pairing.Result.Self -> PairStatus.Error("Ese es el código de este mismo teléfono.")
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.9f).clip(RoundedCornerShape(32.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (hasCamera && status !is PairStatus.Working) {
                CameraScanner(paused = found != null) { text ->
                    val qr = Pairing.parse(text)
                    if (qr != null && found == null) pair(qr)
                }
                // framing corners
                Box(Modifier.fillMaxWidth(0.62f).aspectRatio(1f).border(3.dp, Brush.linearGradient(listOf(Orange, Violet)), RoundedCornerShape(28.dp)))
            } else if (!hasCamera) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                    Icon(Icons.Rounded.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(36.dp))
                    Text(
                        "Para escanear necesitamos usar la cámara.",
                        style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
                    )
                    GradientButton("Permitir cámara", Modifier.fillMaxWidth(0.8f)) { ask.launch(Manifest.permission.CAMERA) }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Orange)
                    Text("Conectando con ${found?.name}…", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
        when (val s = status) {
            is PairStatus.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(20.dp))
                    Text(s.message, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.padding(start = 8.dp))
                }
                SoftButton("Escanear de nuevo", Modifier.padding(top = 14.dp)) { found = null; status = PairStatus.Idle }
            }
            else -> Text(
                "Apuntá al código que muestra Pasala en el otro dispositivo.",
                style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp, start = 12.dp, end = 12.dp),
            )
        }
    }
}

private sealed interface PairStatus {
    data object Idle : PairStatus
    data object Working : PairStatus
    data class Error(val message: String) : PairStatus
}

@Composable
private fun CameraScanner(paused: Boolean, onText: (String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val view = remember {
        BarcodeView(context).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> view.resume()
                Lifecycle.Event.ON_PAUSE -> view.pause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
        onDispose { lifecycle.removeObserver(obs); view.pause() }
    }
    LaunchedEffect(paused) {
        if (paused) view.stopDecoding()
        else view.decodeContinuous(BarcodeCallback { r -> r?.text?.let(onText) })
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun PairedSuccess(name: String, onDone: () -> Unit, onAnother: () -> Unit) {
    val c = P.c
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val s by animateFloatAsState(if (shown) 1f else 0.3f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "ok")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Box(Modifier.size(120.dp).scale(s).clip(CircleShape).background(c.success.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(c.success), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Color(0xFF0E0F1A), modifier = Modifier.size(46.dp))
            }
        }
        Text("¡Listo!", style = MaterialTheme.typography.displaySmall, color = c.text, modifier = Modifier.padding(top = 26.dp))
        Text(
            "Ya podés pasarte archivos con $name, para los dos lados.",
            style = MaterialTheme.typography.bodyLarge, color = c.muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
        )
        Spacer(Modifier.height(32.dp))
        GradientButton("Listo", Modifier.fillMaxWidth(), onClick = onDone)
        Spacer(Modifier.height(12.dp))
        SoftButton("Sumar otro", Modifier.fillMaxWidth(), onClick = onAnother)
    }
}

/** A Pasala code opened as a link (system camera, or adb). Confirms, then pairs. */
@Composable
fun DeepLinkPairDialog(qr: QrPayload, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pairing.Result?>(null) }
    val title = when (result) {
        null -> "¿Vincular con ${qr.name}?"
        is Pairing.Result.Ok -> "¡Listo!"
        Pairing.Result.Expired -> "Ese código ya no sirve"
        Pairing.Result.Unreachable -> "No pudimos llegar a ${qr.name}"
        Pairing.Result.Self -> "Es este mismo teléfono"
    }
    val body = when (result) {
        null -> "Después se van a poder pasar archivos para los dos lados."
        is Pairing.Result.Ok -> "Ya podés pasarte archivos con ${qr.name}."
        Pairing.Result.Expired -> "Se usó o venció. Mostrá uno nuevo en ${qr.name} y volvé a escanearlo."
        Pairing.Result.Unreachable -> "Fijate que estén en la misma red Wi-Fi o con Tailscale prendido."
        Pairing.Result.Self -> "Escaneá el código del otro dispositivo."
    }
    PasalaDialog(title = title, body = body, onDismiss = { if (!working) onDismiss() }) {
        if (result == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SoftButton("Cancelar", Modifier.weight(1f)) { if (!working) onDismiss() }
                GradientButton("Vincular", Modifier.weight(1f), loading = working) {
                    working = true
                    scope.launch { result = Pairing.pairWith(qr); working = false }
                }
            }
        } else {
            GradientButton(if (result is Pairing.Result.Ok) "Genial" else "Cerrar", Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}
