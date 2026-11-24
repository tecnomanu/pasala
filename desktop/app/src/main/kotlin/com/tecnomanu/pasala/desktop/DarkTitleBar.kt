package com.tecnomanu.pasala.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference

/** Windows 10/11 paint the title bar light unless asked; Pasala's windows are dark, so ask for the dark one. */
object DarkTitleBar {
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy { if (Paths.isWindows) runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() else null }

    fun apply(window: java.awt.Window) {
        val api = dwm ?: return
        runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            // DWMWA_USE_IMMERSIVE_DARK_MODE: 20 on current builds, 19 on early Windows 10 ones.
            if (api.DwmSetWindowAttribute(hwnd, 20, IntByReference(1), 4) != 0) api.DwmSetWindowAttribute(hwnd, 19, IntByReference(1), 4)
        }
    }
}
