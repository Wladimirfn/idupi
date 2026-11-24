package com.idupi.app.data.calendar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.idupi.app.MainActivity
import com.idupi.app.domain.model.PhoneCalendarProposal

/**
 * Local notification for a fresh PC proposal (phone-calendar T3).
 *
 * The proposal only becomes an event after the owner approves it on the phone,
 * so the ping is a nudge, not a result. Tapping opens Agenda.
 */
class CalendarNotifier(private val context: Context) {

    private val notifications = NotificationManagerCompat.from(context)

    fun showNewProposal(proposal: PhoneCalendarProposal) {
        createChannel()
        val title = proposal.title.ifBlank { "Propuesta de evento" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Nueva propuesta de evento desde el PC")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAgendaIntent(proposal.id))
            .build()
        // Without POST_NOTIFICATIONS the system ignores the post; a
        // notification is never a reason to stop the reporter loop.
        runCatching {
            notifications.notify(notificationId(proposal.id), notification)
        }
    }

    private fun openAgendaIntent(proposalId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_AGENDA, true)
        }
        return PendingIntent.getActivity(
            context,
            notificationId(proposalId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Propuestas de eventos que llegan desde la PC"
            }
        )
    }

    private fun notificationId(proposalId: String): Int =
        ("calendar_proposal:$proposalId").hashCode() and 0x7FFFFFFF

    companion object {
        const val CHANNEL_ID = "idupi_calendar"
        const val CHANNEL_NAME = "Calendario IDUPI"

        /** Extra that makes [MainActivity] open Agenda on a notification tap. */
        const val EXTRA_OPEN_AGENDA = "com.idupi.app.extra.OPEN_AGENDA"
    }
}
