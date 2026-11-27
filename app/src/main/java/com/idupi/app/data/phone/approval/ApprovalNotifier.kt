package com.idupi.app.data.phone.approval

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.idupi.app.MainActivity
import com.idupi.app.domain.model.PhoneApproval
import kotlinx.serialization.json.Json

/**
 * Local notification for a pending phone approval (phone-approvals T2).
 *
 * Both actions need the device unlocked on API 31+ (`setAuthenticationRequired`):
 * approving a send or a payment from the lock screen is exactly what an
 * attacker holding the unlocked phone would want. Tapping the body opens
 * Ajustes, where the pending-approvals card lives.
 */
class ApprovalNotifier(private val context: Context) {

    private val notifications = NotificationManagerCompat.from(context)
    private val json = Json { encodeDefaults = true }
    private val appLabels by lazy { AppLabels.resolver(context) }

    fun show(approval: PhoneApproval) {
        if (approval.id.isBlank()) return
        createChannel()
        val description = ApprovalNotificationText.describe(approval, appLabels)
        val payload = json.encodeToString(PhoneApproval.serializer(), approval)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(ApprovalNotificationText.TITLE)
            .setContentText(description)
            .setStyle(NotificationCompat.BigTextStyle().bigText(description))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApprovalsIntent())
            .addAction(action(approval.id, payload, approve = false))
            .addAction(action(approval.id, payload, approve = true))
        post(approval.id, builder)
    }

    /** The decision raced with expiry or another decision: no action left. */
    fun markNoLongerValid(approval: PhoneApproval) {
        createChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(ApprovalNotificationText.TITLE)
            .setContentText(NO_LONGER_VALID)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApprovalsIntent())
        post(approval.id, builder)
    }

    fun cancel(approvalId: String) {
        runCatching { notifications.cancel(notificationId(approvalId)) }
    }

    private fun post(approvalId: String, builder: NotificationCompat.Builder) {
        // Without POST_NOTIFICATIONS the system ignores the post; a
        // notification is never a reason to stop the poll loop.
        runCatching { notifications.notify(notificationId(approvalId), builder.build()) }
    }

    private fun action(
        approvalId: String,
        payload: String,
        approve: Boolean,
    ): NotificationCompat.Action {
        val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
            action = if (approve) {
                ApprovalActionReceiver.ACTION_APPROVE
            } else {
                ApprovalActionReceiver.ACTION_REJECT
            }
            putExtra(ApprovalActionReceiver.EXTRA_APPROVAL, payload)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            actionRequestCode(approvalId, approve),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Action.Builder(
            if (approve) android.R.drawable.ic_menu_send
            else android.R.drawable.ic_menu_close_clear_cancel,
            if (approve) LABEL_APPROVE else LABEL_REJECT,
            pending,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAuthenticationRequired(true)
        }
        return builder.build()
    }

    private fun openApprovalsIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_APPROVALS, true)
        }
        return PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
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
                description = "Acciones riesgosas que esperan tu aprobación"
            }
        )
    }

    private fun notificationId(approvalId: String): Int =
        ("phone_approval:$approvalId").hashCode() and 0x7FFFFFFF

    private fun actionRequestCode(approvalId: String, approve: Boolean): Int =
        ("phone_approval:$approvalId:${if (approve) "approve" else "reject"}")
            .hashCode() and 0x7FFFFFFF

    companion object {
        const val CHANNEL_ID = "idupi_approvals"
        const val CHANNEL_NAME = "Aprobaciones"

        /** Extra that makes [MainActivity] open Ajustes on a notification tap. */
        const val EXTRA_OPEN_APPROVALS = "com.idupi.app.extra.OPEN_APPROVALS"

        const val NO_LONGER_VALID = "Ya no está vigente"

        private const val LABEL_APPROVE = "Aprobar"
        private const val LABEL_REJECT = "Rechazar"
        private const val OPEN_REQUEST_CODE = 7301
    }
}
