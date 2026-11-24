package com.tecnomanu.pasala.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.tecnomanu.pasala.BuildConfig
import com.tecnomanu.pasala.core.Live
import com.tecnomanu.pasala.core.Outgoing
import com.tecnomanu.pasala.core.Pairing
import com.tecnomanu.pasala.data.QrPayload
import com.tecnomanu.pasala.net.Resolver
import com.tecnomanu.pasala.service.PasalaService
import com.tecnomanu.pasala.ui.home.HomeScreen
import com.tecnomanu.pasala.ui.pair.AddDeviceScreen
import com.tecnomanu.pasala.ui.pair.DeepLinkPairDialog
import com.tecnomanu.pasala.ui.send.SendScreen
import com.tecnomanu.pasala.ui.settings.SettingsScreen
import com.tecnomanu.pasala.ui.theme.PasalaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class AddDevice(val scan: Boolean = false) : Screen
    data object Settings : Screen
    data class Send(val peerId: String, val files: List<Outgoing>) : Screen
}

class MainActivity : ComponentActivity() {
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    private val deepLink = mutableStateOf<QrPayload?>(null)
    private var poller: Job? = null

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PasalaService.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handleIntent(intent)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                PasalaService.start(this@MainActivity)
                PasalaService.setBrowsing(true)
                poller = lifecycleScope.launch {
                    while (isActive) {
                        Resolver.refreshAll()
                        delay(20_000)
                    }
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                poller?.cancel()
                PasalaService.setBrowsing(false)
            }
        })

        setContent {
            PasalaTheme {
                App()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.data?.let { uri ->
            if (uri.scheme == "pasala") Pairing.parse(uri.toString())?.let { deepLink.value = it }
        }
        // Debug aid: `adb shell am start -n com.tecnomanu.pasala/.ui.MainActivity --es screen show_code`
        if (BuildConfig.DEBUG && intent.getStringExtra("screen") == "show_code") {
            stack.clear(); stack += Screen.Home; stack += Screen.AddDevice(scan = false)
        }
    }

    private fun push(s: Screen) { stack += s }
    private fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    @Composable
    private fun App() {
        val current = stack.last()
        BackHandler(enabled = stack.size > 1) { pop() }
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                val forward = targetState != Screen.Home
                (slideInHorizontally(tween(320)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(260)))
                    .togetherWith(slideOutHorizontally(tween(320)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(200)))
            },
            label = "nav",
        ) { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(
                    onAddDevice = { push(Screen.AddDevice()) },
                    onSettings = { push(Screen.Settings) },
                    onSend = { peerId, files -> push(Screen.Send(peerId, files)) },
                )
                is Screen.AddDevice -> AddDeviceScreen(startOnScan = screen.scan, onDone = { pop() })
                Screen.Settings -> SettingsScreen(onBack = { pop() })
                is Screen.Send -> SendScreen(
                    files = screen.files,
                    preselectedPeer = screen.peerId,
                    autoStart = true,
                    onClose = { pop() },
                )
            }
        }
        PendingOfferDialog()
        deepLink.value?.let { qr ->
            DeepLinkPairDialog(qr, onDismiss = { deepLink.value = null })
        }
    }
}
