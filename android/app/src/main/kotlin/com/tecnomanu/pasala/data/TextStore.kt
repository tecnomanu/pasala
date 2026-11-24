package com.tecnomanu.pasala.data

import android.content.Context
import java.io.File

/** Full texts sent/received, one file each, so the history JSON stays small (texts can be up to 1 MB). */
object TextStore {
    private fun dir(context: Context) = File(context.filesDir, "texts").apply { mkdirs() }

    fun save(context: Context, id: String, text: String) {
        File(dir(context), "$id.txt").writeText(text)
    }

    fun load(context: Context, id: String): String? = File(dir(context), "$id.txt").takeIf { it.exists() }?.readText()

    fun preview(text: String): String = text.trim().let { if (it.length > 280) it.take(280) + "…" else it }
}
