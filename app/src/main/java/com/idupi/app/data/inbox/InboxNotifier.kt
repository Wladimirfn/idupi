package com.idupi.app.data.inbox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.idupi.app.MainActivity
import com.idupi.app.data.notification.SupervisorNotificationManager
import com.idupi.app.domain.model.InboxItem

/**
 * Local notifications for the inbox (phone-inbox T4): a reminder while an
 * important message is unanswered and a one-shot ping when a freshly analyzed
 * message turns out to be important.
 *
 * Both tap through to [MainActivity] with [EXTRA_OPEN_ALERTS], so the app
 * lands on Alertas instead of the terminals (the same one-shot mechanism
 * deliveries use). Message text is shown to the owner, never logged here.
 */
class InboxNotifier(private val context: Context) {

    private val notifications = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    /** Re-posts the same notification id, so a reminder updates instead of stacking. */
    fun showReminder(item: InboxItem) {
        post(
            notificationId = notificationId(REMINDER_TAG, item.id),
            channelId = REMINDER_CHANNEL_ID,
            title = item.sender.ifBlank { "Mensaje sin remitente" },
            text = summaryOf(item)
        )
    }

    fun showImportantReady(item: InboxItem) {
        post(
            notificationId = notificationId(READY_TAG, item.id),
            channelId = SupervisorNotificationManager.CHANNEL_ID,
            title = "Mensaje importante de ${item.sender.ifBlank { "un contacto" }}",
            text = summaryOf(item)
        )
    }

    private fun summaryOf(item: InboxItem): String =
        item.summary.ifBlank { item.text }.ifBlank { "Sin contenido para mostrar" }

    private fun post(
        notificationId: Int,
        channelId: String,
        title: String,
        text: String
    ) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAlertsIntent(notificationId))
            .build()

        // A notification is a side effect, never a reason to stop the loop:
        // without POST_NOTIFICATIONS the system ignores the post.
        runCatching { notifications.notify(notificationId, notification) }
    }

    private fun openAlertsIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ALERTS, true)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.createNotificationChannel(
            NotificationChannel(
                REMINDER_CHANNEL_ID,
                REMINDER_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Recordatorios de mensajes importantes sin responder"
                enableVibration(true)
            }
        )
        // The "Alertas IDUPI" channel may already exist from
        // SupervisorNotificationManager; creating it again is a no-op for an
        // existing channel and keeps this service self-contained when it is
        // the first component the system starts in a fresh process.
        manager.createNotificationChannel(
            NotificationChannel(
                SupervisorNotificationManager.CHANNEL_ID,
                SupervisorNotificationManager.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas en tiempo real de la app IDUPI"
            }
        )
    }

    private fun notificationId(tag: String, itemId: String): Int =
        ("$tag:$itemId").hashCode() and 0x7FFFFFFF

    companion object {
        /** Channel id agreed for reminders; "Recordatorios IDUPI" in the system UI. */
        const val REMINDER_CHANNEL_ID = "idupi_inbox_reminders"
        const val REMINDER_CHANNEL_NAME = "Recordatorios IDUPI"

        /** Extra that makes [MainActivity] open Alertas on a notification tap. */
        const val EXTRA_OPEN_ALERTS = "com.idupi.app.extra.OPEN_ALERTS"

        private const val REMINDER_TAG = "inbox_reminder"
        private const val READY_TAG = "inbox_ready"
    }
}
