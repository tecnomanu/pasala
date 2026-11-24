package com.tecnomanu.pasala.core

import java.util.Locale

object Format {
    private val es = Locale.forLanguageTag("es-AR")

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(es, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(es, if (mb < 10) "%.1f MB" else "%.0f MB", mb)
        return String.format(es, "%.1f GB", mb / 1024.0)
    }

    fun ago(time: Long, now: Long = System.currentTimeMillis()): String {
        if (time <= 0) return "todavía no"
        val s = (now - time) / 1000
        return when {
            s < 45 -> "recién"
            s < 3600 -> "hace ${(s / 60).coerceAtLeast(1)} min"
            s < 86400 -> "hace ${s / 3600} h"
            s < 2 * 86400 -> "ayer"
            else -> "hace ${s / 86400} días"
        }
    }
}
