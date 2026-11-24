package com.tecnomanu.pasala.desktop

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/** The system clipboard. Pasala only writes it when the person presses "Copy". */
object Clipboard {
    fun text(): String? = runCatching {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) cb.getData(DataFlavor.stringFlavor) as? String else null
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }
}
