package com.idupi.app.data.calendar

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.domain.model.PhoneCalendarProposal
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Last calendar report, for Ajustes and the Agenda empty state. */
data class CalendarReportState(
    val lastAttemptAtMillis: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    val success: Boolean? = null,
    val eventCount: Int = 0,
)

/**
 * Posts the phone's agenda every [DEFAULT_TICK_MS] (the phone status loop
 * cadence) but at most every [DEFAULT_MIN_INTERVAL_MS], plus immediately when
 * the calendar changed (debounced by [CHANGE_DEBOUNCE_MS]) and on demand from
 * Ajustes.
 *
 * The loop also polls pending PC proposals and hands fresh ones to
 * [onNewProposals], which is what raises the local "new proposal" ping. A first
 * successful poll only records the baseline: existing pending proposals must
 * not notify on every app start.
 *
 * Failure logging never includes event fields: titles and locations are the
 * owner's personal data.
 */
class CalendarReporter(
    private val clientSource: IduPiClientSource,
    private val snapshotProvider: () -> com.idupi.app.domain.model.PhoneCalendarSnapshot?,
    private val isEnabled: suspend () -> Boolean,
    private val canReport: () -> Boolean,
    private val proposalsProvider: suspend () -> List<PhoneCalendarProposal> = { emptyList() },
    private val onNewProposals: (List<PhoneCalendarProposal>) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val tickMillis: Long = DEFAULT_TICK_MS,
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MS,
    private val changeDebounceMillis: Long = CHANGE_DEBOUNCE_MS,
    private val state: MutableStateFlow<CalendarReportState> = MutableStateFlow(CalendarReportState()),
) {

    val reportState: StateFlow<CalendarReportState> = state.asStateFlow()

    private val postMutex = Mutex()
    private var loopJob: Job? = null
    private var debounceJob: Job? = null
    private var loopScope: CoroutineScope? = null

    /** Pending proposal ids seen on the last successful poll; null until then. */
    private var knownProposalIds: Set<String>? = null

    /** Idempotent: a second call while the loop runs is a no-op. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        loopScope = scope
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { loop() }
    }

    @Synchronized
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        debounceJob?.cancel()
        debounceJob = null
        loopScope = null
    }

    /**
     * The provider changed. Debounced: a burst of writes (one insert touches
     * several rows) becomes one report [changeDebounceMillis] after the last
     * change.
     */
    @Synchronized
    fun onChanged() {
        val scope = loopScope ?: return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(changeDebounceMillis)
            deliver(requireEnabled = true)
        }
    }

    /**
     * Manual report from Ajustes. Like the phone status button, the explicit
     * tap wins over the switch; it still needs the permission and a profile.
     */
    suspend fun reportNow(): Boolean = deliver(requireEnabled = false)

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            delay(tickMillis)
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "calendar tick failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun tick() {
        if (!isEnabled()) return
        pollProposals()
        val now = nowMillis()
        val lastAttempt = state.value.lastAttemptAtMillis
        if (lastAttempt == null || now - lastAttempt >= minIntervalMillis) {
            deliver(requireEnabled = true)
        }
    }

    private suspend fun deliver(requireEnabled: Boolean): Boolean = postMutex.withLock {
        if (requireEnabled && !isEnabled()) return@withLock false
        if (!canReport()) return@withLock false
        val snapshot = snapshotProvider() ?: return@withLock false
        val ok = try {
            clientSource.client.postPhoneCalendar(snapshot)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "calendar post failed: ${e.javaClass.simpleName}")
            false
        }
        val now = nowMillis()
        state.value = state.value.copy(
            lastAttemptAtMillis = now,
            lastSuccessAtMillis = if (ok) now else state.value.lastSuccessAtMillis,
            success = ok,
            eventCount = snapshot.events.size,
        )
        ok
    }

    private suspend fun pollProposals() {
        if (!canReport()) return
        val proposals = try {
            proposalsProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "proposal poll failed: ${e.javaClass.simpleName}")
            return
        }
        val pending = proposals.filter { it.status == com.idupi.app.domain.model.PhoneCalendarProposalStatus.PENDING }
        val ids = pending.mapTo(mutableSetOf()) { it.id }
        val previous = knownProposalIds
        knownProposalIds = ids
        if (previous == null) return
        val fresh = pending.filter { it.id !in previous }
        if (fresh.isNotEmpty()) {
            onNewProposals(fresh.sortedBy { it.createdAt })
        }
    }

    companion object {
        private const val TAG = "CalendarReporter"

        /** Same one-minute cadence as the phone status loop. */
        const val DEFAULT_TICK_MS = 60_000L

        /** Never post the agenda more often than this unless it changed. */
        const val DEFAULT_MIN_INTERVAL_MS = 15L * 60L * 1000L

        /** ContentObserver debounce before a change-triggered report. */
        const val CHANGE_DEBOUNCE_MS = 30_000L
    }
}

/**
 * Process-wide host for the calendar reporter, mirroring [PhoneStatusReporting].
 *
 * Started by the inbox listener, the accessibility service and Ajustes; the
 * instance and loop are shared, so hosting from several components never
 * doubles the posts. The ContentObserver lives exactly as long as the loop.
 */
object CalendarReporting {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(CalendarReportState())

    @Volatile
    private var reporter: CalendarReporter? = null

    @Volatile
    private var observer: ContentObserver? = null

    @Volatile
    private var observerContext: Context? = null

    /** Observable last-report state; never null, even before the first start. */
    val reportState: StateFlow<CalendarReportState> = state.asStateFlow()

    /** Idempotent: builds the reporter once, (re)starts the loop and observer. */
    @Synchronized
    fun start(context: Context) {
        val appContext = context.applicationContext
        val store = AndroidCalendarStore(appContext)
        val notifier = CalendarNotifier(appContext)
        val current = reporter ?: CalendarReporter(
            clientSource = IduPiClientProvider,
            snapshotProvider = { store.readSnapshot(System.currentTimeMillis()) },
            isEnabled = { SettingsProvider.repository.calendarShareEnabled.first() },
            canReport = { IduPiClientProvider.configureFromStorage(appContext) },
            proposalsProvider = {
                IduPiClientProvider.client.getPhoneCalendarProposals(
                    status = com.idupi.app.domain.model.PhoneCalendarProposalStatus.PENDING,
                ).proposals
            },
            onNewProposals = { proposals -> proposals.forEach(notifier::showNewProposal) },
            state = state,
        ).also { reporter = it }
        current.start(scope)
        registerObserver(appContext, current)
    }

    @Synchronized
    fun stop() {
        reporter?.stop()
        val registered = observer
        val context = observerContext
        if (registered != null && context != null) {
            runCatching { context.contentResolver.unregisterContentObserver(registered) }
        }
        observer = null
        observerContext = null
    }

    /**
     * One manual report. False when the reporter never started, there is no
     * saved profile or the request failed.
     */
    suspend fun reportNow(): Boolean = reporter?.reportNow() ?: false

    private fun registerObserver(context: Context, current: CalendarReporter) {
        if (observer != null) return
        val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                current.onChanged()
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(
                CalendarContract.Events.CONTENT_URI,
                true,
                created,
            )
        }.isSuccess
        if (registered) {
            observer = created
            observerContext = context
        }
    }
}
