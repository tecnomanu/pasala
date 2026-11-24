package com.tecnomanu.pasala.core

import android.content.Context
import android.text.format.DateUtils
import com.tecnomanu.pasala.R
import java.util.Locale

object Format {
    fun size(bytes: Long): String {
        val l = Locale.getDefault()
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(l, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(l, if (mb < 10) "%.1f MB" else "%.0f MB", mb)
        return String.format(l, "%.1f GB", mb / 1024.0)
    }

    /** "5 min ago" / "hace 5 min", following the app's locale. */
    fun ago(context: Context, time: Long, now: Long = System.currentTimeMillis()): String {
        if (now - time < 60_000) return context.getString(R.string.just_now)
        // Shown mid-sentence ("Seen 5 min. ago", "De X · hace 5 min"), so never capitalized.
        return DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
            .toString().replaceFirstChar { it.lowercase(Locale.getDefault()) }
    }

    /** Peer subtitle when it isn't online. */
    fun seen(context: Context, time: Long): String =
        if (time <= 0) context.getString(R.string.never_seen) else context.getString(R.string.seen, ago(context, time))
}
