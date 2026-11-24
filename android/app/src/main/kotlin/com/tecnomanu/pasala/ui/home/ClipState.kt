package com.tecnomanu.pasala.ui.home

import android.content.ClipboardManager
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tecnomanu.pasala.core.Clip

/**
 * Whether the clipboard holds text, without reading it. Re-checked on resume, when the window gets focus
 * (Android only answers to the focused app) and whenever the clip changes while Home is in front.
 */
@Composable
fun rememberClipHasText(): Boolean {
    val context = LocalContext.current
    val view = LocalView.current
    var has by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        has = Clip.hasText(context)
        val onClip = ClipboardManager.OnPrimaryClipChangedListener { has = Clip.hasText(context) }
        val onFocus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) has = Clip.hasText(context) }
        cm.addPrimaryClipChangedListener(onClip)
        view.viewTreeObserver.addOnWindowFocusChangeListener(onFocus)
        onPauseOrDispose {
            cm.removePrimaryClipChangedListener(onClip)
            view.viewTreeObserver.removeOnWindowFocusChangeListener(onFocus)
        }
    }
    return has
}
