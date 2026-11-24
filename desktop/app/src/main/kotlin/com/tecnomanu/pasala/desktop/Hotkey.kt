package com.tecnomanu.pasala.desktop

import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import com.tecnomanu.pasala.core.Log

/**
 * Ctrl+Alt+P anywhere: "send the copied text". Registers only that one combination with the system
 * (RegisterHotKey on Windows, XGrabKey on X11); no keyboard hook. On Wayland the desktop can bind
 * a shortcut to `pasala --send-text` instead (desktop/AGENTS.md).
 */
object Hotkey {
    private const val TAG = "Pasala"
    const val LABEL = "Ctrl+Alt+P"

    fun start(onPress: () -> Unit) {
        val t = Thread({
            runCatching { if (Paths.isWindows) windows(onPress) else if (Paths.isLinux) x11(onPress) }
                .onFailure { Log.w(TAG, "global shortcut unavailable: ${it.message}") }
        }, "pasala-hotkey")
        t.isDaemon = true
        t.start()
    }

    private fun windows(onPress: () -> Unit) {
        val modNoRepeat = 0x4000
        if (!User32.INSTANCE.RegisterHotKey(null, 1, WinUser.MOD_CONTROL or WinUser.MOD_ALT or modNoRepeat, 'P'.code)) {
            Log.w(TAG, "Ctrl+Alt+P is taken by another app"); return
        }
        val msg = WinUser.MSG()
        while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
            if (msg.message == WinUser.WM_HOTKEY) onPress()
        }
    }

    private fun x11(onPress: () -> Unit) {
        if (System.getenv("DISPLAY").isNullOrBlank()) return
        val x = X11.INSTANCE
        val display = x.XOpenDisplay(null) ?: return
        val root = x.XDefaultRootWindow(display)
        val code = x.XKeysymToKeycode(display, x.XStringToKeysym("p")).toInt()
        val base = X11.ControlMask or X11.Mod1Mask
        // Also grab with Caps Lock / Num Lock on, or the shortcut stops working when they are.
        for (extra in listOf(0, X11.LockMask, X11.Mod2Mask, X11.LockMask or X11.Mod2Mask)) {
            x.XGrabKey(display, code, base or extra, root, 1, X11.GrabModeAsync, X11.GrabModeAsync)
        }
        x.XSelectInput(display, root, NativeLong(X11.KeyPressMask.toLong()))
        val ev = X11.XEvent()
        while (true) {
            x.XNextEvent(display, ev)
            if (ev.type == X11.KeyPress) onPress()
        }
    }
}
