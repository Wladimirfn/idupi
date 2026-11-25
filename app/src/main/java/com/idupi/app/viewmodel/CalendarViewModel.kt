package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.IduPiApp
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.calendar.AgendaDay
import com.idupi.app.data.calendar.AgendaGrouping
import com.idupi.app.data.calendar.AndroidCalendarStore
import com.idupi.app.data.calendar.CalendarBucketing
import com.idupi.app.data.calendar.CalendarDraftResult
import com.idupi.app.data.calendar.CalendarEventBuilder
import com.idupi.app.data.calendar.CalendarEventDraft
import com.idupi.app.data.calendar.CalendarOverlap
import com.idupi.app.data.calendar.CalendarRange
import com.idupi.app.data.calendar.CalendarReporting
import com.idupi.app.data.calendar.CalendarStore
import com.idupi.app.data.calendar.CalendarViewMode
import com.idupi.app.data.calendar.CalendarWeekBlock
import com.idupi.app.data.calendar.CalendarWeekLayout
import com.idupi.app.data.calendar.EventPrefill
import com.idupi.app.data.remote.IduPiHttpException
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.data.settings.SettingsRepository
import com.idupi.app.domain.model.PhoneCalendarEvent
import com.idupi.app.domain.model.PhoneCalendarProposal
import com.idupi.app.domain.model.PhoneCalendarProposalStatus
import com.idupi.app.domain.model.PhoneCalendarRef
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Agenda view state: the list, the visible month/week grids and PC proposals. */
data class CalendarAgendaState(
    val days: List<AgendaDay> = emptyList(),
    val proposals: List<PhoneCalendarProposal> = emptyList(),
    val conflictIds: Set<String> = emptySet(),
    val conflictDates: Set<LocalDate> = emptySet(),
    val viewMode: CalendarViewMode = CalendarViewMode.DEFAULT,
    val selectedDate: LocalDate? = null,
    val visibleMonth: YearMonth? = null,
    val weekStart: LocalDate? = null,
    val monthWeeks: List<List<LocalDate>> = emptyList(),
    val weekDays: List<LocalDate> = emptyList(),
    val weekBlocks: List<CalendarWeekBlock> = emptyList(),
    val eventsByDay: Map<LocalDate, List<PhoneCalendarEvent>> = emptyMap(),
    val calendars: List<PhoneCalendarRef> = emptyList(),
    val isLoading: Boolean = false,
    val hasCalendarPermission: Boolean = true,
    val decidingProposalIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
)

/** Add-event sheet state, shared by the Alertas sheet and the Agenda sheet. */
data class CalendarAddEventState(
    val isOpen: Boolean = false,
    val draft: CalendarEventDraft = CalendarEventDraft(),
    val calendars: List<PhoneCalendarRef> = emptyList(),
    val conflicts: List<PhoneCalendarEvent> = emptyList(),
    val validationMessage: String? = null,
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
    val hasWritePermission: Boolean = true,
    /** Set when the sheet was opened from a PC proposal. */
    val proposalId: String? = null,
)

/** Result of a successful insert, for the snackbar and the proposal decision. */
data class SavedCalendarEvent(
    val eventId: String?,
    val calendarName: String,
    val proposalId: String? = null,
)

/**
 * Agenda + add-event state (phone-calendar T2/T3).
 *
 * The Agenda list is read from the local `CalendarContract` reader (no network),
 * refreshed while the screen is visible; proposals are polled from the PC every
 * [PROPOSAL_POLL_MS]. Writes go through the same local reader only after the
 * owner taps "Guardar" in the confirm sheet; the PC never writes.
 */
class CalendarViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider,
    private val store: CalendarStore = AndroidCalendarStore(IduPiApp.instance),
    private val settingsRepository: SettingsRepository = SettingsProvider.repository,
    private val reportAfterSave: suspend () -> Unit = { CalendarReporting.reportNow() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {

    private val client get() = clientSource.client

    private val _agenda = MutableStateFlow(initialAgendaState())
    val agenda: StateFlow<CalendarAgendaState> = _agenda.asStateFlow()

    private val _addEvent = MutableStateFlow(CalendarAddEventState())
    val addEvent: StateFlow<CalendarAddEventState> = _addEvent.asStateFlow()

    private var pollingJob: Job? = null

    /** Set once the owner picks a mode, so the stored-mode restore cannot win a race. */
    private var viewModeChosen = false

    init {
        // Restore the owner's last Agenda view mode. The default (month) is
        // already in the initial state, so only a stored choice reloads.
        viewModelScope.launch {
            val stored = runCatching { settingsRepository.calendarViewMode.first() }
                .getOrDefault(CalendarViewMode.DEFAULT.storageValue)
            val mode = CalendarViewMode.fromStorage(stored)
            if (!viewModeChosen && mode != _agenda.value.viewMode) {
                _agenda.update { it.copy(viewMode = mode) }
                loadLocalAgenda()
            }
        }
    }

    private fun initialAgendaState(): CalendarAgendaState {
        val today = today()
        val month = YearMonth.from(today)
        return CalendarAgendaState(
            viewMode = CalendarViewMode.DEFAULT,
            selectedDate = today,
            visibleMonth = month,
            weekStart = CalendarRange.weekStart(today),
            monthWeeks = CalendarRange.monthWeeks(month),
            weekDays = CalendarRange.weekDates(today),
        )
    }

    private fun today(): LocalDate =
        Instant.ofEpochMilli(nowMillis()).atZone(zoneProvider()).toLocalDate()

    /**
     * Proposals whose event was already written on this process. A failed
     * decision report must not offer the same insert again: the next poll would
     * re-list the server's still-pending proposal and risk a duplicate event.
     */
    private val handledProposalIds = mutableSetOf<String>()

    fun clearError() {
        _agenda.update { it.copy(errorMessage = null) }
    }

    /**
     * Polling is tied to Agenda's composition: local agenda + proposals right
     * away, then every [PROPOSAL_POLL_MS]. Leaving the screen stops it.
     */
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            loadLocalAgenda()
            loadProposals(reportErrors = false)
            while (isActive) {
                delay(PROPOSAL_POLL_MS)
                loadLocalAgenda()
                loadProposals(reportErrors = false)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /** Manual refresh (top-bar icon); failures are shown this time. */
    fun refresh() {
        viewModelScope.launch {
            _agenda.update { it.copy(isLoading = true) }
            try {
                loadLocalAgenda()
                loadProposals(reportErrors = true)
            } finally {
                _agenda.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Reads the local provider for the range the current view needs: the next
     * seven days in Lista, the visible week in Semana and the whole month grid
     * in Mes. Navigating or switching modes reloads on demand; the 14-day PC
     * report is a separate path and stays unchanged.
     */
    private suspend fun loadLocalAgenda() {
        val hasPermission = runCatching { store.canRead() }.getOrDefault(false)
        val now = nowMillis()
        val zone = zoneProvider()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val current = _agenda.value
        val month = current.visibleMonth ?: YearMonth.from(today)
        val weekStart = current.weekStart ?: CalendarRange.weekStart(today)
        val weekDates = CalendarRange.weekDates(weekStart)
        val monthDates = CalendarRange.monthDates(month)
        val listDates = (0 until AgendaGrouping.DEFAULT_DAYS).map { today.plusDays(it.toLong()) }
        val visibleDates = when (current.viewMode) {
            CalendarViewMode.MONTH -> monthDates
            CalendarViewMode.WEEK -> weekDates
            CalendarViewMode.LIST -> listDates
        }
        val window = CalendarRange.query(visibleDates, zone)
        val events = if (hasPermission && window != null) {
            runCatching {
                store.eventsBetween(window.startMillis, window.endExclusiveMillis)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val calendars = if (hasPermission) {
            runCatching { store.visibleCalendars() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val eventsByDay = CalendarBucketing.bucket(events, visibleDates, zone)
        val weekBlocks = if (current.viewMode == CalendarViewMode.WEEK) {
            CalendarWeekLayout.blocks(eventsByDay, zone)
        } else {
            emptyList()
        }
        val selectedDate = current.selectedDate
            ?.takeIf { it in eventsByDay }
            ?: when (current.viewMode) {
                CalendarViewMode.MONTH -> month.atDay(1)
                CalendarViewMode.WEEK -> weekStart
                CalendarViewMode.LIST -> today
            }
        _agenda.update {
            it.copy(
                days = AgendaGrouping.days(events = events, nowMillis = now, zone = zone),
                conflictIds = CalendarOverlap.conflictingEventIds(events),
                conflictDates = CalendarBucketing.conflictingDates(events, zone),
                selectedDate = selectedDate,
                visibleMonth = month,
                weekStart = weekStart,
                monthWeeks = CalendarRange.monthWeeks(month),
                weekDays = weekDates,
                weekBlocks = weekBlocks,
                eventsByDay = eventsByDay,
                calendars = calendars,
                hasCalendarPermission = hasPermission,
            )
        }
    }

    // --- View modes and navigation (phone-calendar T5) --------------------

    /** Switches the visible mode, remembers it on the phone and reloads its range. */
    fun setViewMode(mode: CalendarViewMode) {
        viewModeChosen = true
        if (_agenda.value.viewMode == mode) return
        _agenda.update { it.copy(viewMode = mode) }
        viewModelScope.launch {
            runCatching { settingsRepository.setCalendarViewMode(mode.storageValue) }
            loadLocalAgenda()
        }
    }

    fun selectDate(date: LocalDate) {
        _agenda.update { it.copy(selectedDate = date) }
    }

    fun previousMonth() = shiftMonth(-1)

    fun nextMonth() = shiftMonth(1)

    fun previousWeek() = shiftWeek(-1)

    fun nextWeek() = shiftWeek(1)

    /** Returns every mode to the week/month that contains today. */
    fun goToday() {
        val today = today()
        _agenda.update {
            it.copy(
                selectedDate = today,
                visibleMonth = YearMonth.from(today),
                weekStart = CalendarRange.weekStart(today),
            )
        }
        reloadLocalAgenda()
    }

    private fun shiftMonth(delta: Long) {
        _agenda.update { state ->
            val month = (state.visibleMonth ?: YearMonth.from(today())).plusMonths(delta)
            val dates = CalendarRange.monthDates(month)
            state.copy(
                visibleMonth = month,
                // Keep the selection visible; a day of another month moves to
                // the first of the new one.
                selectedDate = state.selectedDate?.takeIf { it in dates } ?: month.atDay(1),
            )
        }
        reloadLocalAgenda()
    }

    private fun shiftWeek(delta: Long) {
        _agenda.update { state ->
            val start = (state.weekStart ?: CalendarRange.weekStart(today())).plusWeeks(delta)
            state.copy(
                weekStart = start,
                selectedDate = start,
            )
        }
        reloadLocalAgenda()
    }

    private fun reloadLocalAgenda() {
        viewModelScope.launch { loadLocalAgenda() }
    }

    private suspend fun loadProposals(reportErrors: Boolean) {
        try {
            val response = client.getPhoneCalendarProposals(
                status = PhoneCalendarProposalStatus.PENDING,
                limit = PROPOSAL_LIMIT,
            )
            _agenda.update { state ->
                state.copy(
                    proposals = response.proposals.filter {
                        it.status == PhoneCalendarProposalStatus.PENDING &&
                            it.id !in handledProposalIds
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The PC may simply be off; polling stays silent, a manual refresh
            // surfaces the real failure.
            Log.w(TAG, "proposal load failed: ${e.javaClass.simpleName}")
            if (reportErrors) {
                _agenda.update {
                    it.copy(
                        errorMessage = "No se pudieron cargar las propuestas: " +
                            e.localizedMessage.orEmpty()
                    )
                }
            }
        }
    }

    // --- Add-event confirm sheet ----------------------------------------

    /** Opens the sheet prefilled from an alert; [proposalId] ties it to a proposal. */
    fun openAddEvent(prefill: EventPrefill, proposalId: String? = null) {
        viewModelScope.launch {
            val calendars = runCatching { store.writableCalendars() }.getOrDefault(emptyList())
            val lastUsed = runCatching {
                settingsRepository.calendarLastUsedId.first()
            }.getOrDefault("")
            val hasWrite = runCatching { store.canWrite() }.getOrDefault(false)
            val draft = CalendarEventBuilder.toDraft(
                prefill = prefill,
                calendars = calendars,
                lastUsedId = lastUsed,
                zone = zoneProvider(),
            )
            val problem = when {
                !hasWrite -> "Falta el permiso para escribir en el calendario. Actívalo en Ajustes."
                calendars.isEmpty() ->
                    "No hay calendarios donde guardar. Revisa los calendarios del teléfono."
                else -> null
            }
            _addEvent.value = CalendarAddEventState(
                isOpen = true,
                draft = draft,
                calendars = calendars,
                hasWritePermission = hasWrite,
                proposalId = proposalId,
                errorMessage = problem,
            )
            if (problem == null) refreshConflicts()
        }
    }

    fun openProposal(proposal: PhoneCalendarProposal) {
        openAddEvent(CalendarEventBuilder.prefillFor(proposal), proposalId = proposal.id)
    }

    fun closeAddEvent() {
        _addEvent.value = CalendarAddEventState()
    }

    fun updateDraft(draft: CalendarEventDraft) {
        _addEvent.update { it.copy(draft = draft, validationMessage = null) }
        viewModelScope.launch { refreshConflicts() }
    }

    private suspend fun refreshConflicts() {
        val state = _addEvent.value
        if (!state.isOpen) return
        val values = (CalendarEventBuilder.build(state.draft, state.calendars, zoneProvider())
            as? CalendarDraftResult.Valid)?.values
        if (values == null) {
            _addEvent.update { it.copy(conflicts = emptyList()) }
            return
        }
        val margin = DAY_MS
        val events = runCatching {
            store.eventsBetween(values.startMillis - margin, values.endMillis + margin)
        }.getOrDefault(emptyList())
        val conflicts = CalendarOverlap.conflictsFor(
            startMillis = values.startMillis,
            endMillis = values.endMillis,
            allDay = values.allDay,
            events = events,
        )
        _addEvent.update { it.copy(conflicts = conflicts) }
    }

    /**
     * Validates and inserts on an explicit owner tap. On success it remembers
     * the calendar, triggers a calendar report, approves a linked proposal and
     * calls [onSaved]; nothing is written on validation failure.
     */
    fun saveEvent(onSaved: (SavedCalendarEvent) -> Unit) {
        val state = _addEvent.value
        if (!state.isOpen || state.isSaving) return
        val values = when (
            val result = CalendarEventBuilder.build(state.draft, state.calendars, zoneProvider())
        ) {
            is CalendarDraftResult.Invalid -> {
                _addEvent.update { it.copy(validationMessage = result.message) }
                return
            }
            is CalendarDraftResult.Valid -> result.values
        }
        if (!state.hasWritePermission) {
            _addEvent.update {
                it.copy(
                    errorMessage = "Falta el permiso para escribir en el calendario. " +
                        "Actívalo en Ajustes."
                )
            }
            return
        }
        _addEvent.update {
            it.copy(isSaving = true, validationMessage = null, errorMessage = null)
        }
        viewModelScope.launch {
            val eventId = runCatching { store.insertEvent(values) }.getOrNull()
            if (eventId == null) {
                _addEvent.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "No se pudo guardar el evento. " +
                            "Revisa el permiso del calendario.",
                    )
                }
                return@launch
            }
            runCatching { settingsRepository.setCalendarLastUsedId(values.calendarId) }
            val calendarName = state.calendars
                .firstOrNull { it.id == values.calendarId }
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: "tu calendario"
            val proposalId = state.proposalId
            _addEvent.value = CalendarAddEventState()
            if (proposalId != null) {
                handledProposalIds += proposalId
                postDecision(
                    proposalId = proposalId,
                    decision = PhoneCalendarProposalStatus.APPROVED,
                    eventId = eventId,
                    failureMessage = "El evento se guardó, pero no se pudo avisar a la PC.",
                )
            }
            runCatching { reportAfterSave() }
            onSaved(
                SavedCalendarEvent(
                    eventId = eventId,
                    calendarName = calendarName,
                    proposalId = proposalId,
                )
            )
        }
    }

    // --- Proposal decisions ---------------------------------------------

    fun rejectProposal(proposal: PhoneCalendarProposal) {
        val id = proposal.id
        if (id.isBlank() || id in _agenda.value.decidingProposalIds) return
        _agenda.update { it.copy(decidingProposalIds = it.decidingProposalIds + id) }
        viewModelScope.launch {
            try {
                postDecision(
                    proposalId = id,
                    decision = PhoneCalendarProposalStatus.REJECTED,
                    eventId = null,
                    failureMessage = "No se pudo rechazar la propuesta. Revisa la conexión con la PC.",
                )
            } finally {
                _agenda.update { it.copy(decidingProposalIds = it.decidingProposalIds - id) }
            }
        }
    }

    private suspend fun postDecision(
        proposalId: String,
        decision: String,
        eventId: String?,
        failureMessage: String,
    ) {
        try {
            client.decidePhoneCalendarProposal(proposalId, decision, eventId)
            removeProposal(proposalId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IduPiHttpException) {
            if (e.statusCode == 409 || e.statusCode == 404) {
                removeProposal(proposalId)
                _agenda.update { it.copy(errorMessage = MESSAGE_ALREADY_DECIDED) }
            } else {
                Log.w(TAG, "proposal decision failed: ${e.statusCode}")
                _agenda.update { it.copy(errorMessage = failureMessage) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "proposal decision failed: ${e.javaClass.simpleName}")
            _agenda.update { it.copy(errorMessage = failureMessage) }
        }
    }

    private fun removeProposal(proposalId: String) {
        _agenda.update { state ->
            state.copy(proposals = state.proposals.filterNot { it.id == proposalId })
        }
    }

    companion object {
        private const val TAG = "CalendarViewModel"
        const val PROPOSAL_POLL_MS = 30_000L
        const val PROPOSAL_LIMIT = 50
        const val MESSAGE_ALREADY_DECIDED = "La propuesta ya fue decidida o expiró."

        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
