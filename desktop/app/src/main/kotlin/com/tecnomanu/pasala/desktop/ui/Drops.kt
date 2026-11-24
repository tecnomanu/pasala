package com.tecnomanu.pasala.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.net.URI

/**
 * Files dragged in from the file manager. Compose owns the window's drop target, so drops go through its
 * modifier, not an AWT DropTarget on the window. [onFiles] gets the files and where they fell (AWT units).
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.fileDrop(onHover: (Boolean) -> Unit = {}, onFiles: (List<File>, Point?) -> Unit): Modifier = composed {
    val hover by rememberUpdatedState(onHover)
    val files by rememberUpdatedState(onFiles)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val drop = event.nativeEvent as? DropTargetDropEvent ?: return false
                val list = filesOf(drop.transferable)
                if (list.isEmpty()) return false
                files(list, drop.location)
                return true
            }
        }
    }
    dragAndDropTarget(shouldStartDragAndDrop = { e -> e.nativeEvent.let { it is DropTargetDragEvent || it is DropTargetDropEvent } }, target = target)
}

@Suppress("UNCHECKED_CAST")
fun filesOf(t: Transferable): List<File> {
    runCatching {
        if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return (t.getTransferData(DataFlavor.javaFileListFlavor) as List<File>).filter { it.isFile }
    }
    // Some Linux file managers only offer text/uri-list.
    val uriList = runCatching { DataFlavor("text/uri-list;class=java.lang.String") }.getOrNull() ?: return emptyList()
    val text = runCatching { t.getTransferData(uriList) as String }.getOrNull() ?: return emptyList()
    return text.lines().map { it.trim() }.filter { it.startsWith("file:") }
        .mapNotNull { runCatching { File(URI(it)) }.getOrNull() }.filter { it.isFile }
}
