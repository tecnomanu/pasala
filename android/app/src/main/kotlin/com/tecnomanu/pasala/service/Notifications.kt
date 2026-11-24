package com.tecnomanu.pasala.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tecnomanu.pasala.R
import com.tecnomanu.pasala.ui.MainActivity

object Notifications {
    const val CH_READY = "ready"
    const val CH_TRANSFER = "transfer"
    const val CH_REQUEST = "request"
    const val CH_DONE = "done"
    const val CH_TEXT = "text"

    const val ID_SERVICE = 1
    const val ID_RECEIVING = 2
    const val ID_DONE = 3
    private const val ID_OFFER_BASE = 1000

    private const val ACCENT = 0xFFFF6B3D.toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_READY, context.getString(R.string.ch_ready_name), NotificationManager.IMPORTANCE_MIN).apply {
                    description = context.getString(R.string.ch_ready_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CH_TRANSFER, context.getString(R.string.ch_transfer_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.ch_transfer_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CH_REQUEST, context.getString(R.string.ch_request_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.ch_request_desc)
                },
                NotificationChannel(CH_DONE, context.getString(R.string.ch_done_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.ch_done_desc)
                },
                NotificationChannel(CH_TEXT, context.getString(R.string.ch_text_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.ch_text_desc)
                },
            )
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ready(context: Context): Notification =
        NotificationCompat.Builder(context, CH_READY)
            .setSmallIcon(R.drawable.ic_stat_mark)
            .setColor(ACCENT)
            .setContentTitle(context.getString(R.string.notif_ready_title))
            .setContentText(context.getString(R.string.notif_ready_text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .build()

    private fun canPost(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, n: Notification) {
        if (canPost(context)) NotificationManagerCompat.from(context).notify(id, n)
    }

    fun receiving(context: Context, from: String, name: String, index: Int, count: Int, done: Long, total: Long) {
        val pct = if (total > 0) ((done * 100) / total).toInt() else 0
        val title = if (count > 1) context.getString(R.string.notif_receiving_many, index + 1, count, from)
        else context.getString(R.string.notif_receiving_one, from)
        post(
            context, ID_RECEIVING,
            NotificationCompat.Builder(context, CH_TRANSFER)
                .setSmallIcon(R.drawable.ic_stat_mark)
                .setColor(ACCENT)
                .setContentTitle(title)
                .setContentText(name)
                .setProgress(100, pct, total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    fun cancelReceiving(context: Context) = NotificationManagerCompat.from(context).cancel(ID_RECEIVING)

    fun received(context: Context, from: String, count: Int, lastName: String) {
        val text = if (count == 1) lastName else context.resources.getQuantityString(R.plurals.files_count, count, count)
        post(
            context, ID_DONE,
            NotificationCompat.Builder(context, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_mark)
                .setColor(ACCENT)
                .setContentTitle(context.getString(R.string.notif_received_title, from))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    // Above the offer range (ID_OFFER_BASE + 0..0x7fff) so a text never replaces a pending request.
    fun textId(itemId: String) = 40_000 + (itemId.hashCode() and 0x7fff)

    fun textReady(context: Context, itemId: String, from: String, preview: String) {
        val copy = PendingIntent.getBroadcast(
            context, textId(itemId),
            Intent(context, CopyTextReceiver::class.java).putExtra(CopyTextReceiver.EXTRA_ITEM, itemId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            context, textId(itemId),
            NotificationCompat.Builder(context, CH_TEXT)
                .setSmallIcon(R.drawable.ic_stat_mark)
                .setColor(ACCENT)
                .setContentTitle(context.getString(R.string.text_ready_title))
                .setContentText(preview)
                .setSubText(context.getString(R.string.history_from, from))
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .addAction(0, context.getString(R.string.action_copy), copy)
                .build(),
        )
    }

    fun cancelText(context: Context, itemId: String) = NotificationManagerCompat.from(context).cancel(textId(itemId))

    fun offerId(batch: String) = ID_OFFER_BASE + (batch.hashCode() and 0x7fff)

    fun offer(context: Context, batch: String, from: String, count: Int, sizeText: String) {
        fun action(accept: Boolean) = PendingIntent.getBroadcast(
            context, offerId(batch) * 2 + if (accept) 1 else 0,
            Intent(context, OfferActionReceiver::class.java)
                .putExtra(OfferActionReceiver.EXTRA_BATCH, batch)
                .putExtra(OfferActionReceiver.EXTRA_ACCEPT, accept),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            context, offerId(batch),
            NotificationCompat.Builder(context, CH_REQUEST)
                .setSmallIcon(R.drawable.ic_stat_mark)
                .setColor(ACCENT)
                .setContentTitle(context.resources.getQuantityString(R.plurals.offer_title, count, from, count))
                .setContentText(context.getString(R.string.notif_offer_text, sizeText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setTimeoutAfter(60_000)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .addAction(0, context.getString(R.string.action_decline), action(false))
                .addAction(0, context.getString(R.string.action_accept), action(true))
                .build(),
        )
    }

    fun cancelOffer(context: Context, batch: String) = NotificationManagerCompat.from(context).cancel(offerId(batch))
}
