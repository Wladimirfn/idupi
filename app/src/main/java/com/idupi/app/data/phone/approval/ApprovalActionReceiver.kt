package com.idupi.app.data.phone.approval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalDecisionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Handles the Aprobar / Rechazar actions of an approval notification
 * (phone-approvals T2).
 *
 * The approval travels in the immutable [PendingIntent] payload, so approving
 * records the same data the owner saw, then posts the decision. A 409/404
 * leaves a "Ya no está vigente" notice; a transport failure keeps the original
 * notification so the owner can retry.
 */
class ApprovalActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val approve = when (intent.action) {
            ACTION_APPROVE -> true
            ACTION_REJECT -> false
            else -> return
        }
        val payload = intent.getStringExtra(EXTRA_APPROVAL) ?: return
        val approval = runCatching {
            json.decodeFromString(PhoneApproval.serializer(), payload)
        }.getOrNull() ?: return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val notifier = ApprovalNotifier(appContext)
                val decider = PhoneApprovals.decider(appContext)
                val result = if (approve) decider.approve(approval) else decider.reject(approval)
                when (result) {
                    is PhoneApprovalDecisionResult.Decided -> notifier.cancel(approval.id)
                    is PhoneApprovalDecisionResult.Conflict,
                    PhoneApprovalDecisionResult.NotFound,
                    -> notifier.markNoLongerValid(approval)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The PC is unreachable: keep the notification for a retry.
                Log.w(TAG, "approval action failed: ${error.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ApprovalActionReceiver"

        private val json = Json { ignoreUnknownKeys = true }

        const val ACTION_APPROVE = "com.idupi.app.action.APPROVE_PHONE_COMMAND"
        const val ACTION_REJECT = "com.idupi.app.action.REJECT_PHONE_COMMAND"
        const val EXTRA_APPROVAL = "com.idupi.app.extra.PHONE_APPROVAL"
    }
}
