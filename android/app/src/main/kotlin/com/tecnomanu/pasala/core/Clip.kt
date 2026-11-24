package com.tecnomanu.pasala.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import com.tecnomanu.pasala.R

object Clip {
    /** Copies on the person's request. Android 13+ shows its own confirmation, so we only toast before that. */
    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        try {
            cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
        } catch (e: RuntimeException) {
            // Texts can be up to 1 MB; the clipboard travels through binder and can refuse that much.
            Toast.makeText(context, R.string.text_too_long_action, Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /** Opens the system share sheet with [text]; same size caveat as [copy]. */
    fun share(context: Context, text: String) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
        try {
            context.startActivity(android.content.Intent.createChooser(send, null))
        } catch (e: RuntimeException) {
            Toast.makeText(context, R.string.text_too_long_action, Toast.LENGTH_LONG).show()
        }
    }

    /** Only works while one of our windows has focus (Android 10+). */
    fun read(context: Context): String? {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    }
}
