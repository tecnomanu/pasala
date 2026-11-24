package com.tecnomanu.pasala.data

import java.io.File

/** Full texts sent/received, one file each, so the history JSON stays small (texts can be up to 1 MB). */
object TextStore {
    private lateinit var base: File

    /** [dir] is where the texts live (`<app data>/texts`). */
    fun init(dir: File) { base = dir }

    private fun dir() = base.apply { mkdirs() }

    fun file(id: String): File = File(dir(), "$id.txt")

    fun save(id: String, text: String) {
        file(id).writeText(text)
    }

    fun load(id: String): String? = file(id).takeIf { it.exists() }?.readText()

    fun preview(text: String): String = text.trim().let { if (it.length > 280) it.take(280) + "…" else it }
}
