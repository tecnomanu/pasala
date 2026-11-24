package com.tecnomanu.pasala.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tecnomanu.pasala.core.Receiver
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Starts listening again after the phone restarts or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> PasalaService.start(context)
        }
    }
}

/** "Copy" on the "text ready to copy" notification. */
class CopyTextReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val item = intent.getStringExtra(EXTRA_ITEM) ?: return
        val pending = goAsync()
        com.tecnomanu.pasala.core.Live.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val text = com.tecnomanu.pasala.data.TextStore.load(context, item) ?: return@launch
                withContext(kotlinx.coroutines.Dispatchers.Main) { com.tecnomanu.pasala.core.Clip.copy(context, text) }
                Notifications.cancelText(context, item)
            } finally {
                pending.finish()
            }
        }
    }

    companion object { const val EXTRA_ITEM = "item" }
}

/** Aceptar / Rechazar buttons on the "te quiere pasar" notification. */
class OfferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val batch = intent.getStringExtra(EXTRA_BATCH) ?: return
        Receiver.decide(batch, intent.getBooleanExtra(EXTRA_ACCEPT, false))
        Notifications.cancelOffer(context, batch)
    }

    companion object {
        const val EXTRA_BATCH = "batch"
        const val EXTRA_ACCEPT = "accept"
    }
}
