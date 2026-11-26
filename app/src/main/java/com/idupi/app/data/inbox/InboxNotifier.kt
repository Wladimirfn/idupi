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
import com.idupi.app.domain.model.InboxApp
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
            text = summaryOf(item),
            appLabel = InboxApp.displayLabel(item.appLabel, item.app)
        )
    }

    fun showImportantReady(item: InboxItem) {
        post(
            notificationId = notificationId(READY_TAG, item.id),
            channelId = SupervisorNotificationManager.CHANNEL_ID,
            title = "Mensaje importante de ${item.sender.ifBlank { "un contacto" }}",
            text = summaryOf(item),
            appLabel = InboxApp.displayLabel(item.appLabel, item.app)
        )
    }

    /**
     * alerts-flow A3: the Interactuar investigation finished. The body is the
     * owner's own instruction, never the alert text or the answer, and the tap
     * opens Alertas where the answer is shown in full.
     */
    fun showAgentAnswer(item: InboxItem) {
        val instruction = item.interaction?.instruction.orEmpty()
        post(
            notificationId = notificationId(ANSWER_TAG, item.id),
            channelId = SupervisorNotificationManager.CHANNEL_ID,
            title = InboxAgentAnswer.TITLE,
            text = InboxAgentAnswer.notificationText(instruction),
            appLabel = InboxApp.displayLabel(item.appLabel, item.app)
        )
    }

    /**
     * alerts-flow A4: a high-importance message already has a draft waiting.
     * The body is the card's summary, never the draft itself, and the tap
     * opens Alertas where the owner reviews and sends.
     */
    fun showReadyReply(item: InboxItem) {
        post(
            notificationId = notificationId(READY_REPLY_TAG, item.id),
            channelId = SupervisorNotificationManager.CHANNEL_ID,
            title = InboxReadyReply.TITLE,
            text = InboxReadyReply.notificationText(item),
            appLabel = InboxApp.displayLabel(item.appLabel, item.app)
        )
    }

    private fun summaryOf(item: InboxItem): String =
        item.summary.ifBlank { item.text }.ifBlank { "Sin contenido para mostrar" }

    private fun post(
        notificationId: Int,
        channelId: String,
        title: String,
        text: String,
        appLabel: String
    ) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(appLabel)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAlertsIntent(notificationId))
            .build()

        // A notification is a side effect, never a reason to stop the loop:
        // without POST_NOTIFICATIONS the system ignores the post.
        runCatching { notifications.notify(notificationId, notification) }
    }

    private fun openAlertsIntent(requestCode: Int): PendingIntent =
        alertsIntent(context, requestCode)

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

        /**
         * alerts-flow A1: the single silent summary that replaces the pile of
         * captured notifications the owner no longer needs in the shade.
         */
        const val SUMMARY_CHANNEL_ID = "idupi_inbox_capture_summary"
        const val SUMMARY_CHANNEL_NAME = "Resumen de Alertas"

        /** Extra that makes [MainActivity] open Alertas on a notification tap. */
        const val EXTRA_OPEN_ALERTS = "com.idupi.app.extra.OPEN_ALERTS"

        private const val REMINDER_TAG = "inbox_reminder"
        private const val READY_TAG = "inbox_ready"
        private const val ANSWER_TAG = "inbox_answer"
        private const val READY_REPLY_TAG = "inbox_ready_reply"

        /** Fixed id: the summary updates in place instead of stacking. */
        private const val SUMMARY_NOTIFICATION_ID = 24_001

        /**
         * Posts/updates IDUPI's one-line summary of what it captured and
         * cleared from the shade since the owner last opened Alertas. Silent
         * by construction: low-importance channel, no sound or vibration, and
         * [NotificationCompat.Builder.setOnlyAlertOnce] so an update never
         * re-alerts.
         */
        fun showCaptureSummary(context: Context, total: Int, important: Int) {
            ensureSummaryChannel(context)
            val notification = NotificationCompat.Builder(context, SUMMARY_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(SUMMARY_CHANNEL_NAME)
                .setContentText(InboxCaptureSummary.text(total, important))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(true)
                .setContentIntent(alertsIntent(context, SUMMARY_NOTIFICATION_ID))
                .build()

            // Same rule as the other posts: without POST_NOTIFICATIONS the
            // system ignores it and that is not a reason to stop capturing.
            runCatching {
                NotificationManagerCompat.from(context)
                    .notify(SUMMARY_NOTIFICATION_ID, notification)
            }
        }

        /** Removes the summary once the owner is looking at Alertas. */
        fun clearCaptureSummary(context: Context) {
            runCatching {
                NotificationManagerCompat.from(context).cancel(SUMMARY_NOTIFICATION_ID)
            }
        }

        private fun ensureSummaryChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    SUMMARY_CHANNEL_ID,
                    SUMMARY_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description =
                        "Una sola notificación con lo que IDUPI capturó y ya está en Alertas"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }

        private fun alertsIntent(context: Context, requestCode: Int): PendingIntent {
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
    }
}
