package com.idupi.app.data.phone

import android.content.Context
import android.util.Log
import com.idupi.app.BuildConfig
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.phone.tailscale.TailscaleTimeouts
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Posts the phone's status every [intervalMillis] and on demand (phone-agent T2).
 *
 * The loop skips a tick when the owner turned the switch off or when no
 * connection profile is saved, so an unconfigured phone makes no requests. The
 * on-demand path (Ajustes "Enviar ahora") deliberately ignores the switch: the
 * owner is asking explicitly.
 *
 * Personal values never reach the log: failures log only the exception class.
 */
class PhoneStatusReporter(
    private val clientSource: IduPiClientSource,
    private val snapshotProvider: () -> PhoneStatus?,
    private val isEnabled: suspend () -> Boolean,
    private val canReport: () -> Boolean,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MS,
    private val onTransportFailure: (Throwable) -> Unit = {},
    private val onTransportSuccess: () -> Unit = {},
) {

    private val postMutex = Mutex()
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

    /**
     * Collects and posts one snapshot now.
     *
     * @return true when the server accepted it; false when there is no profile,
     *   no snapshot or the request failed. Never throws (except cancellation).
     */
    suspend fun reportNow(): Boolean = postMutex.withLock { deliver() }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            try {
                if (isEnabled()) reportNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "status tick failed: ${e.javaClass.simpleName}")
            }
            delay(intervalMillis)
        }
    }

    private suspend fun deliver(): Boolean {
        if (!canReport()) return false
        val snapshot = snapshotProvider() ?: return false
        return try {
            clientSource.client.postPhoneStatus(snapshot)
            onTransportSuccess()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The snapshot carries the owner's personal state: log the failure
            // kind only, never the body.
            Log.w(TAG, "status post failed: ${e.javaClass.simpleName}")
            onTransportFailure(e)
            false
        }
    }

    companion object {
        private const val TAG = "PhoneStatusReporter"

        /** One snapshot per minute, as specified for the phone-agent feature. */
        const val DEFAULT_INTERVAL_MS = 60_000L
    }
}

/**
 * Process-wide host for the single status reporter (phone-agent T2).
 *
 * [com.idupi.app.data.inbox.InboxNotificationListener] starts it while the
 * listener is connected; T3's accessibility service calls the same
 * [start]/[stop]. The instance and its loop are shared, so hosting it from two
 * places never doubles the posts. Ajustes also calls [start] so the "Enviar
 * ahora" button works before any service attaches to this process.
 */
object PhoneStatusReporting {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var reporter: PhoneStatusReporter? = null

    /** Idempotent: builds the reporter once and (re)starts its loop. */
    @Synchronized
    fun start(context: Context) {
        val appContext = context.applicationContext
        val collectors = PhoneStatusCollectors(appContext)
        val current = reporter ?: run {
            PhoneStatusReporter(
                clientSource = IduPiClientProvider,
                snapshotProvider = {
                    collectors.collect(BuildConfig.VERSION_NAME).copy(
                        // T3: the same per-app reader and 60 s cache the
                        // Dashboard uses; null without usage access.
                        largestApps = PhoneAppStorageMapping.toLargestApps(
                            AndroidPhoneDashboardSource.appStorageSummary(),
                        ),
                    )
                },
                isEnabled = { SettingsProvider.repository.phoneStatusEnabled.first() },
                // configureFromStorage doubles as "is a PC configured?": it
                // reads the saved profile and applies it to the shared client,
                // so the very first report cannot race the configuration.
                canReport = { IduPiClientProvider.configureFromStorage(appContext) },
                onTransportFailure = { error -> TailscaleTimeouts.onFailure(appContext, error) },
                onTransportSuccess = TailscaleTimeouts::onSuccess,
            ).also { reporter = it }
        }
        current.start(scope)
    }

    @Synchronized
    fun stop() {
        reporter?.stop()
    }

    /**
     * One manual report from Ajustes.
     *
     * @return false when the reporter was never started, there is no saved
     *   profile or the request failed.
     */
    suspend fun reportNow(): Boolean = reporter?.reportNow() ?: false
}
