package com.tecnomanu.pasala.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.tecnomanu.pasala.R
import com.tecnomanu.pasala.ui.send.ShareActivity
import com.tecnomanu.pasala.ui.send.TextActivity

/** Home-screen widget: "Send copied text" and "Send file" in one tap. */
class QuickSendWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val clip = PendingIntent.getActivity(context, 11, TextActivity.fromClipboard(context), flags)
        val file = PendingIntent.getActivity(
            context, 12,
            Intent(context, ShareActivity::class.java).setAction(ShareActivity.ACTION_PICK_FILES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            flags,
        )
        val views = RemoteViews(context.packageName, R.layout.widget_quick).apply {
            setOnClickPendingIntent(R.id.widget_clip, clip)
            setOnClickPendingIntent(R.id.widget_file, file)
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
