package com.evolia.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.evolia.app.R

/**
 * System notifications for BTC-e transfers, shared by both sides of a transfer:
 *  - the SENDER posts a "sent" receipt (accusé d'envoi) the moment a transfer is
 *    dispatched (on-chain settled, or an offline promise queued to the mesh);
 *  - the RECEIVER posts a "received" receipt (accusé de réception) when an
 *    incoming transfer is detected (an on-chain balance increase, or an offline
 *    promise arriving over the sealed Bluetooth/UDP mesh).
 *
 * One DEFAULT-priority channel, distinct from the silent supervisor channel and
 * the chat-message channel, so a value movement pops/vibrates and is never lost
 * in the always-on foreground notification. Best-effort: a withheld
 * POST_NOTIFICATIONS permission (API 33+) is swallowed, never crashing a transfer.
 */
object TransferNotify {

    const val CHANNEL_ID = "evolia_transfer"
    private const val NOTIF_BASE = 1000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.transfer_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /** Sender-side "accusé d'envoi". [settled] picks the on-chain vs offline wording. */
    fun notifySent(context: Context, amountBtce: Double, to: String, settled: Boolean) {
        val title = context.getString(R.string.transfer_notif_sent_title)
        val text = if (settled) {
            context.getString(R.string.transfer_notif_sent_onchain).format(amountBtce, to)
        } else {
            context.getString(R.string.transfer_notif_sent_offline).format(amountBtce, to)
        }
        post(context, title, text)
    }

    /** Receiver-side "accusé de réception". [settled] picks on-chain vs offline. */
    fun notifyReceived(context: Context, amountBtce: Double, from: String, settled: Boolean) {
        val title = context.getString(R.string.transfer_notif_recv_title)
        val text = if (settled) {
            context.getString(R.string.transfer_notif_recv_onchain).format(amountBtce, from)
        } else {
            context.getString(R.string.transfer_notif_recv_offline).format(amountBtce, from)
        }
        post(context, title, text)
    }

    private fun post(context: Context, title: String, text: String) {
        ensureChannel(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            // A distinct id per post so a "sent" and a "received" never overwrite
            // each other, while staying bounded to a small rotating window.
            NotificationManagerCompat.from(context).notify(NOTIF_BASE + (counter++ % 32), notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted on API 33+ — silently skip.
        }
    }

    private var counter = 0
}
