package com.tecnomanu.pasala.ui.send

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.res.stringResource
import com.tecnomanu.pasala.R
import com.tecnomanu.pasala.core.AndroidPlatform
import com.tecnomanu.pasala.core.Outgoing
import com.tecnomanu.pasala.net.Resolver
import com.tecnomanu.pasala.service.PasalaService
import com.tecnomanu.pasala.ui.MainActivity
import com.tecnomanu.pasala.ui.components.Backdrop
import com.tecnomanu.pasala.ui.components.GradientButton
import com.tecnomanu.pasala.ui.theme.Orange
import com.tecnomanu.pasala.ui.theme.P
import com.tecnomanu.pasala.ui.theme.PasalaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Entry from the system share sheet. */
class ShareActivity : ComponentActivity() {
    private var files by mutableStateOf<List<Outgoing>?>(null)

    /** Files chosen in the picker (shortcut / widget), kept across recreation. */
    private var picked: List<Uri> = emptyList()

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) { finish(); return@registerForActivityResult }
        picked = uris
        describePicked()
    }

    private fun describePicked() {
        val uris = picked
        lifecycleScope.launch {
            files = withContext(Dispatchers.IO) { uris.mapNotNull { Sender.describe(this@ShareActivity, it) } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(STATE_PICKED, ArrayList(picked))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PasalaService.start(this)
        PasalaService.setBrowsing(true)
        if (intent.action == ACTION_PICK_FILES) {
            // Launcher shortcut / widget: pick first, then the same send sheet as a share.
            if (savedInstanceState == null) {
                pickFiles.launch(arrayOf("*/*"))
            } else {
                @Suppress("DEPRECATION")
                picked = savedInstanceState.getParcelableArrayList<Uri>(STATE_PICKED).orEmpty()
                if (picked.isNotEmpty()) describePicked()
            }
        } else {
            load(intent)
        }
        lifecycleScope.launch { Resolver.refreshAll() }

        setContent {
            PasalaTheme {
                val f = files
                when {
                    f == null -> Backdrop { CircularProgressIndicator(Modifier.align(Alignment.Center), color = Orange) }
                    f.isEmpty() -> Backdrop {
                        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.share_empty_title), style = MaterialTheme.typography.headlineSmall, color = P.c.text, textAlign = TextAlign.Center)
                            Text(
                                stringResource(R.string.share_empty_body),
                                style = MaterialTheme.typography.bodyMedium, color = P.c.muted, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                            )
                            GradientButton(stringResource(R.string.action_close), Modifier.size(width = 200.dp, height = 60.dp)) { finish() }
                        }
                    }
                    else -> SendScreen(
                        files = f,
                        preselectedPeer = null,
                        autoStart = false,
                        onClose = { finish() },
                        onAddDevice = {
                            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        files = null
        if (intent.action == ACTION_PICK_FILES) pickFiles.launch(arrayOf("*/*")) else load(intent)
    }

    private fun load(intent: Intent) {
        val uris = sharedUris(intent)
        // Plain text (no file attached) travels as text, not as a .txt file.
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (uris.isEmpty() && !sharedText.isNullOrBlank()) {
            startActivity(Intent(this, TextActivity::class.java).putExtra(Intent.EXTRA_TEXT, sharedText))
            finish()
            return
        }
        val type = intent.type
        lifecycleScope.launch {
            files = withContext(Dispatchers.IO) { uris.mapNotNull { AndroidPlatform.describe(this@ShareActivity, it, type) } }
        }
    }

    override fun onStop() {
        super.onStop()
        PasalaService.setBrowsing(false)
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> {
        val list = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val u = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                u?.let { list += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val us = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                us?.let { list += it }
            }
        }
        if (list.isEmpty()) intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNullTo(list) { clip.getItemAt(it).uri } }
        return list.distinct()
    }

    companion object {
        const val ACTION_PICK_FILES = "com.tecnomanu.pasala.action.PICK_FILES"
        private const val STATE_PICKED = "picked"
    }
}
