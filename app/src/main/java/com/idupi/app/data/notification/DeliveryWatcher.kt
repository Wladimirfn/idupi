package com.idupi.app.data.notification

import android.content.Context
import android.util.Log
import com.idupi.app.domain.model.DeliveryInfo
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls `/api/v1/deliveries` while this process is alive and a server is
 * configured, and posts one notification per delivery that appeared since the
 * previous poll.
 *
 * SCOPE: this is the in-process discovery path of roadmap step 1. Delivery
 * while the app process is dead -- a push that arrives with the app force-
 * stopped, or catching up after boot -- is roadmap step 3 and is out of scope
 * here: there is no WorkManager job, no foreground service and no exact-alarm
 * scheduling behind it. The poll stops when the process dies by design.
 *
 * Poll cadence is [pollIntervalMillis]; any failure (server down, token
 * rotated, phone offline) backs off to [errorBackoffMillis] instead of
 * hammering a server that is not there.
 */
class DeliveryWatcher(
    context: Context,
    private val clientSource: IduPiClientSource,
    private val isServerConfigured: () -> Boolean,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MS,
    private val errorBackoffMillis: Long = DEFAULT_ERROR_BACKOFF_MS,
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val notifications = DeliveryNotifications(appContext)

    private var loopJob: Job? = null

    /** Idempotent: a second call while the loop runs is a no-op. */
    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { loop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val delayMillis = if (!isServerConfigured()) {
                // Not an error: the owner has not configured a PC yet.
                pollIntervalMillis
            } else {
                try {
                    val deliveries = clientSource.client.listDeliveries()
                    publishNewDeliveries(deliveries)
                    pollIntervalMillis
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "delivery poll failed", e)
                    errorBackoffMillis
                }
            }
            delay(delayMillis)
        }
    }

    private fun publishNewDeliveries(deliveries: List<DeliveryInfo>) {
        val seenIds = prefs.getStringSet(KEY_SEEN_IDS, emptySet()) ?: emptySet()
        val newestSeenCreatedAt = prefs.getString(KEY_NEWEST_CREATED_AT, null)

        val decision = deliveryNotificationDecision(deliveries, seenIds, newestSeenCreatedAt)

        if (decision.seenIds != seenIds || decision.newestCreatedAt != newestSeenCreatedAt) {
            prefs.edit()
                .putStringSet(KEY_SEEN_IDS, decision.seenIds)
                .putString(KEY_NEWEST_CREATED_AT, decision.newestCreatedAt)
                .apply()
        }

        decision.toNotify.forEach { delivery ->
            try {
                notifications.show(delivery)
            } catch (e: Exception) {
                // A failed notification must not break the poll loop.
                Log.w(TAG, "could not post delivery notification", e)
            }
        }
    }

    companion object {
        private const val TAG = "DeliveryWatcher"
        private const val PREFS_NAME = "idupi_delivery_prefs"
        private const val KEY_SEEN_IDS = "seen_delivery_ids"
        private const val KEY_NEWEST_CREATED_AT = "newest_seen_created_at"
        private const val DEFAULT_POLL_INTERVAL_MS = 8_000L
        private const val DEFAULT_ERROR_BACKOFF_MS = 30_000L
    }
}
