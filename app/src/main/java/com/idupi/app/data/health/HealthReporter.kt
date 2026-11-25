package com.idupi.app.data.health

import android.content.Context
import android.util.Log
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Last health report, for Ajustes > SALUD. */
data class HealthReportState(
    val lastAttemptAtMillis: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    val success: Boolean? = null,
    val sampleCount: Int = 0,
)

/**
 * Posts the health upload every [DEFAULT_INTERVAL_MS] while hosted (the same
 * process lifetime that hosts the phone status reporter), on app start/resume
 * at most every [DEFAULT_RESUME_INTERVAL_MS], and on demand from Ajustes.
 *
 * Every run reads the last seven days again: the PC merges per day, so
 * re-sending a day with fresh values is how the phone corrects today's steps
 * without keeping a local database. With no PC configured nothing is
 * collected at all, not even Health Connect permissions are queried.
 *
 * Health values never reach the log: failures log the exception class only.
 */
class HealthReporter(
    private val clientSource: IduPiClientSource,
    private val snapshotProvider: suspend () -> HealthSamplesUpload?,
    private val canReport: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val tickMillis: Long = DEFAULT_TICK_MS,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MS,
    private val resumeIntervalMillis: Long = DEFAULT_RESUME_INTERVAL_MS,
    private val state: MutableStateFlow<HealthReportState> = MutableStateFlow(HealthReportState()),
) {

    val reportState: StateFlow<HealthReportState> = state.asStateFlow()

    private val postMutex = Mutex()
    private var loopJob: Job? = null

    /** Idempotent: a second call while the loop runs is a no-op. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { loop() }
    }

    @Synchronized
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * App start/resume path: reports only when the previous attempt is older
     * than [resumeIntervalMillis], so switching apps does not spam the PC.
     * The check runs under the post lock so the loop's immediate first tick
     * and a resume in the same instant cannot both post.
     */
    suspend fun reportOnResume(): Boolean = postMutex.withLock {
        val last = state.value.lastAttemptAtMillis
        if (last != null && nowMillis() - last < resumeIntervalMillis) return@withLock false
        deliverLocked()
    }

    /** Manual "Enviar ahora": the explicit tap wins over the resume throttle. */
    suspend fun reportNow(): Boolean = postMutex.withLock { deliverLocked() }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            try {
                postMutex.withLock {
                    val last = state.value.lastAttemptAtMillis
                    if (last == null || nowMillis() - last >= intervalMillis) deliverLocked()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "health tick failed: ${e.javaClass.simpleName}")
            }
            delay(tickMillis)
        }
    }

    private suspend fun deliverLocked(): Boolean {
        if (!canReport()) return false
        val upload = try {
            snapshotProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "health collect failed: ${e.javaClass.simpleName}")
            null
        } ?: return false

        val ok = try {
            clientSource.client.postHealthSamples(upload)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "health post failed: ${e.javaClass.simpleName}")
            false
        }
        val now = nowMillis()
        state.value = state.value.copy(
            lastAttemptAtMillis = now,
            lastSuccessAtMillis = if (ok) now else state.value.lastSuccessAtMillis,
            success = ok,
            sampleCount = upload.samples.size,
        )
        return ok
    }

    companion object {
        private const val TAG = "HealthReporter"

        /** Checked once a minute; the actual post cadence is [DEFAULT_INTERVAL_MS]. */
        const val DEFAULT_TICK_MS = 60_000L

        /** Every 3 h, aligned with the PC's own hidden-display refresh. */
        const val DEFAULT_INTERVAL_MS = 3L * 60L * 60L * 1000L

        /** App start/resume posts at most this often. */
        const val DEFAULT_RESUME_INTERVAL_MS = 30L * 60L * 1000L
    }
}

/**
 * Process-wide host for the health reporter, mirroring [CalendarReporting] and
 * [com.idupi.app.data.phone.PhoneStatusReporting]: the inbox listener, the
 * accessibility service and Ajustes start it, and the shared instance keeps
 * one loop no matter how many components attach.
 */
object HealthReporting {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(HealthReportState())

    @Volatile
    private var reporter: HealthReporter? = null

    @Volatile
    private var appContext: Context? = null

    /** Observable last-report state; never null, even before the first start. */
    val reportState: StateFlow<HealthReportState> = state.asStateFlow()

    /** Idempotent: builds the reporter once and (re)starts its loop. */
    @Synchronized
    fun start(context: Context) {
        val application = context.applicationContext
        appContext = application
        val current = reporter ?: HealthReporter(
            clientSource = IduPiClientProvider,
            snapshotProvider = {
                HealthSnapshotProvider(
                    reader = HealthConnectReader(application),
                    wellbeing = AndroidWellbeingCollector(application),
                    sources = AndroidHealthSources(application),
                ).collect()
            },
            canReport = { IduPiClientProvider.configureFromStorage(application) },
            state = state,
        ).also { reporter = it }
        current.start(scope)
    }

    @Synchronized
    fun stop() {
        reporter?.stop()
    }

    /**
     * App start/resume hook, throttled to once every 30 min.
     *
     * @return false when the reporter was never started, there is no saved
     *   profile, it is throttled or the request failed.
     */
    suspend fun reportOnResume(): Boolean {
        val current = reporter ?: return false
        return current.reportOnResume()
    }

    /**
     * App start/resume hook, throttled to once every 30 min; safe to call
     * from a lifecycle callback because it never blocks the caller.
     */
    fun onAppResumed() {
        scope.launch {
            try {
                reporter?.reportOnResume()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("HealthReporting", "resume report failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Manual report from Ajustes; false when the reporter was never started. */
    suspend fun reportNow(): Boolean = reporter?.reportNow() ?: false

    /** Installed health apps and their route, for the Ajustes list. */
    suspend fun detectedSources(): List<HealthInstalledApp> {
        val application = appContext ?: return emptyList()
        val origins = runCatching { HealthConnectReader(application).readOriginPackages() }
            .getOrDefault(emptySet())
        return runCatching { AndroidHealthSources(application).installedApps(origins) }
            .getOrDefault(emptyList())
    }
}
