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

    const val ID_SERVICE = 1
    const val ID_RECEIVING = 2
    const val ID_DONE = 3
    private const val ID_OFFER_BASE = 1000

    private const val ACCENT = 0xFFFF6B3D.toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_READY, "Lista para recibir", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Aviso fijo mientras Pasala puede recibir archivos"
                    setShowBadge(false)
                },
                NotificationChannel(CH_TRANSFER, "Archivos en camino", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progreso de lo que estás recibiendo"
                    setShowBadge(false)
                },
                NotificationChannel(CH_REQUEST, "Pedidos para recibir", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Cuando otro dispositivo te quiere pasar archivos y pediste confirmar"
                },
                NotificationChannel(CH_DONE, "Archivos recibidos", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Aviso cuando terminó de llegar algo"
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
            .setContentTitle("Lista para recibir")
            .setContentText("Tus dispositivos te pueden pasar archivos")
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
        val title = if (count > 1) "Recibiendo ${index + 1} de $count de $from" else "Recibiendo de $from"
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
        val text = if (count == 1) lastName else "$count archivos"
        post(
            context, ID_DONE,
            NotificationCompat.Builder(context, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_mark)
                .setColor(ACCENT)
                .setContentTitle("Llegó de $from")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    fun offerId(batch: String) = ID_OFFER_BASE + (batch.hashCode() and 0x7fff)

    fun offer(context: Context, batch: String, from: String, count: Int, sizeText: String) {
        fun action(accept: Boolean) = PendingIntent.getBroadcast(
            context, offerId(batch) * 2 + if (accept) 1 else 0,
            Intent(context, OfferActionReceiver::class.java)
                .putExtra(OfferActionReceiver.EXTRA_BATCH, batch)
                .putExtra(OfferActionReceiver.EXTRA_ACCEPT, accept),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val what = if (count == 1) "1 archivo" else "$count archivos"
        post(
            context, offerId(batch),
            NotificationCompat.Builder(context, CH_REQUEST)
                .setSmallIcon(R.drawable.ic_stat_mark)
                .setColor(ACCENT)
                .setContentTitle("$from te quiere pasar $what")
                .setContentText("$sizeText · Tenés un minuto para decidir")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setTimeoutAfter(60_000)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .addAction(0, "Rechazar", action(false))
                .addAction(0, "Aceptar", action(true))
                .build(),
        )
    }

    fun cancelOffer(context: Context, batch: String) = NotificationManagerCompat.from(context).cancel(offerId(batch))
}
