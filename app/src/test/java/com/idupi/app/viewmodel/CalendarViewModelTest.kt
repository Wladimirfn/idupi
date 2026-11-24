package com.idupi.app.viewmodel

import com.idupi.app.FakeCalendarStore
import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.calendar.CalendarViewMode
import com.idupi.app.data.calendar.EventPrefill
import com.idupi.app.data.remote.IduPiHttpException
import com.idupi.app.data.settings.InMemorySettingsRepository
import com.idupi.app.domain.model.PhoneCalendarEvent
import com.idupi.app.domain.model.PhoneCalendarProposal
import com.idupi.app.domain.model.PhoneCalendarProposalsResponse
import com.idupi.app.domain.model.PhoneCalendarRef
import com.idupi.app.domain.model.PhoneCalendarSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-11-24T12:00:00Z").toEpochMilli()

    private lateinit var fake: FakeIduPiClient
    private lateinit var repository: InMemorySettingsRepository

    private val calendarGoogle = PhoneCalendarRef(
        id = "1",
        name = "Personal",
        accountType = "com.google",
        accountName = "owner@gmail.com",
        writable = true,
    )
    private val calendarOutlook = PhoneCalendarRef(
        id = "2",
        name = "Trabajo",
        accountType = "com.microsoft.exchange",
        accountName = "owner@work.com",
        writable = true,
    )

    private val proposal = PhoneCalendarProposal(
        id = "phr-1",
        title = "Almuerzo",
        start = "2026-11-25T16:00:00Z",
        end = "2026-11-25T17:00:00Z",
        location = "Centro",
        calendarId = "1",
        source = "agent",
        status = "pending",
        createdAt = "2026-11-24T12:00:00Z",
        expiresAt = "2026-12-01T12:00:00Z",
    )

    private fun event(
        id: String,
        start: String,
        end: String,
        allDay: Boolean = false,
    ) = PhoneCalendarEvent(
        id = id,
        calendarId = "1",
        title = id,
        start = start,
        end = end,
        allDay = allDay,
    )

    private fun snapshot(vararg events: PhoneCalendarEvent) = PhoneCalendarSnapshot(
        capturedAt = "2026-11-24T12:00:00Z",
        rangeStart = "2026-11-24T12:00:00Z",
        rangeEnd = "2026-12-08T12:00:00Z",
        tzOffsetMinutes = 0,
        calendars = listOf(calendarGoogle),
        events = events.toList(),
    )

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
        repository = InMemorySettingsRepository()
    }

    private fun viewModel(
        store: FakeCalendarStore,
        zone: ZoneId = ZoneOffset.UTC,
        report: suspend () -> Unit = {},
        repository: InMemorySettingsRepository = this.repository,
    ) = CalendarViewModel(
        clientSource = FakeClientSource(fake),
        store = store,
        settingsRepository = repository,
        reportAfterSave = report,
        nowMillis = { now },
        zoneProvider = { zone },
    )

    @Test
    fun `refresh groups the agenda by day and flags conflicts`() = runTest {
        val store = FakeCalendarStore(
            snapshot = snapshot(
                event("a", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z"),
                event("b", "2026-11-24T13:30:00Z", "2026-11-24T14:30:00Z"),
                event("c", "2026-11-24T16:00:00Z", "2026-11-24T17:00:00Z"),
            )
        )
        val viewModel = viewModel(store)

        viewModel.refresh()
        advanceUntilIdle()

        val agenda = viewModel.agenda.value
        assertEquals(1, agenda.days.size)
        assertEquals(3, agenda.days.single().events.size)
        assertEquals(setOf("a", "b"), agenda.conflictIds)
        assertTrue(agenda.hasCalendarPermission)
        assertFalse(agenda.isLoading)
    }

    @Test
    fun `without read permission the agenda is empty and marked`() = runTest {
        val store = FakeCalendarStore(
            canRead = false,
            snapshot = snapshot(event("a", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z")),
        )
        val viewModel = viewModel(store)

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.agenda.value.days.isEmpty())
        assertFalse(viewModel.agenda.value.hasCalendarPermission)
    }

    @Test
    fun `startPolling loads immediately and proposals appear`() = runTest {
        fake.proposalsToReturn = PhoneCalendarProposalsResponse(proposals = listOf(proposal))
        val store = FakeCalendarStore(
            snapshot = snapshot(event("a", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z"))
        )
        val viewModel = viewModel(store)

        viewModel.startPolling()
        runCurrent()
        viewModel.stopPolling()
        advanceUntilIdle()

        assertEquals(1, viewModel.agenda.value.days.size)
        assertEquals(listOf("phr-1"), viewModel.agenda.value.proposals.map { it.id })
        assertEquals("pending", fake.lastProposalsStatus)
    }

    @Test
    fun `openProposal prefills the shared sheet`() = runTest {
        fake.proposalsToReturn = PhoneCalendarProposalsResponse(proposals = listOf(proposal))
        val store = FakeCalendarStore(writable = listOf(calendarGoogle, calendarOutlook))
        val viewModel = viewModel(store)

        viewModel.openProposal(proposal)
        advanceUntilIdle()

        val sheet = viewModel.addEvent.value
        assertTrue(sheet.isOpen)
        assertEquals("phr-1", sheet.proposalId)
        assertEquals("Almuerzo", sheet.draft.title)
        assertEquals("2026-11-25", sheet.draft.date)
        assertEquals("16:00", sheet.draft.startTime)
        assertEquals("17:00", sheet.draft.endTime)
        assertEquals("1", sheet.draft.calendarId)
        assertTrue(sheet.calendars.any { it.id == "1" })
    }

    @Test
    fun `saving a proposal inserts once, approves it and reports`() = runTest {
        var reports = 0
        var saved: SavedCalendarEvent? = null
        val store = FakeCalendarStore(writable = listOf(calendarGoogle))
        val viewModel = viewModel(store, report = { reports++ })
        fake.proposalsToReturn = PhoneCalendarProposalsResponse(proposals = listOf(proposal))
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.openProposal(proposal)
        advanceUntilIdle()
        viewModel.saveEvent { saved = it }
        advanceUntilIdle()

        assertEquals(1, store.insertCalls)
        assertEquals("Almuerzo", store.inserted.single().title)
        val decision = fake.proposalDecisions.single()
        assertEquals("phr-1", decision.proposalId)
        assertEquals("approved", decision.decision)
        assertEquals("ev-inserted", decision.eventId)
        assertTrue(viewModel.agenda.value.proposals.isEmpty())
        assertEquals("1", repository.calendarLastUsedId.first())
        assertEquals(1, reports)
        assertEquals("Personal", saved?.calendarName)
        assertFalse(viewModel.addEvent.value.isOpen)
    }

    @Test
    fun `rejecting a proposal posts the decision without writing`() = runTest {
        val store = FakeCalendarStore(writable = listOf(calendarGoogle))
        val viewModel = viewModel(store)
        fake.proposalsToReturn = PhoneCalendarProposalsResponse(proposals = listOf(proposal))
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.rejectProposal(proposal)
        advanceUntilIdle()

        assertEquals(0, store.insertCalls)
        val decision = fake.proposalDecisions.single()
        assertEquals("rejected", decision.decision)
        assertEquals(null, decision.eventId)
        assertTrue(viewModel.agenda.value.proposals.isEmpty())
    }

    @Test
    fun `a 409 removes the proposal and explains it`() = runTest {
        fake.failProposalDecisionWith = IduPiHttpException(409, "fake://test", "already decided")
        val store = FakeCalendarStore(writable = listOf(calendarGoogle))
        val viewModel = viewModel(store)
        fake.proposalsToReturn = PhoneCalendarProposalsResponse(proposals = listOf(proposal))
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.rejectProposal(proposal)
        advanceUntilIdle()

        assertTrue(viewModel.agenda.value.proposals.isEmpty())
        assertEquals(CalendarViewModel.MESSAGE_ALREADY_DECIDED, viewModel.agenda.value.errorMessage)
    }

    @Test
    fun `a blank title never reaches the provider`() = runTest {
        val store = FakeCalendarStore(writable = listOf(calendarGoogle))
        val viewModel = viewModel(store)

        viewModel.openAddEvent(
            EventPrefill("Reunión", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z")
        )
        advanceUntilIdle()
        viewModel.updateDraft(viewModel.addEvent.value.draft.copy(title = "  "))
        viewModel.saveEvent {}
        advanceUntilIdle()

        assertEquals(0, store.insertCalls)
        assertEquals("Escribe un título para el evento.", viewModel.addEvent.value.validationMessage)
    }

    @Test
    fun `an invalid date is rejected before writing`() = runTest {
        val store = FakeCalendarStore(writable = listOf(calendarGoogle))
        val viewModel = viewModel(store)

        viewModel.openAddEvent(
            EventPrefill("Reunión", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z")
        )
        advanceUntilIdle()
        viewModel.updateDraft(viewModel.addEvent.value.draft.copy(date = "24/11/2026"))
        viewModel.saveEvent {}
        advanceUntilIdle()

        assertEquals(0, store.insertCalls)
        assertEquals(
            "La fecha debe tener el formato AAAA-MM-DD.",
            viewModel.addEvent.value.validationMessage,
        )
    }

    @Test
    fun `an end before start is rejected`() = runTest {
        val store = FakeCalendarStore(writable = listOf(calendarGoogle))
        val viewModel = viewModel(store)

        viewModel.openAddEvent(
            EventPrefill("Reunión", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z")
        )
        advanceUntilIdle()
        viewModel.updateDraft(viewModel.addEvent.value.draft.copy(startTime = "14:00", endTime = "13:00"))
        viewModel.saveEvent {}
        advanceUntilIdle()

        assertEquals(0, store.insertCalls)
        assertEquals(
            "La hora de fin debe ser después de la hora de inicio.",
            viewModel.addEvent.value.validationMessage,
        )
    }

    @Test
    fun `without write permission the sheet blocks the save`() = runTest {
        val store = FakeCalendarStore(canWrite = false, writable = listOf(calendarGoogle))
        val viewModel = viewModel(store)

        viewModel.openAddEvent(
            EventPrefill("Reunión", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z")
        )
        advanceUntilIdle()

        assertFalse(viewModel.addEvent.value.hasWritePermission)
        assertNotNull(viewModel.addEvent.value.errorMessage)

        viewModel.saveEvent {}
        advanceUntilIdle()

        assertEquals(0, store.insertCalls)
        assertNotNull(viewModel.addEvent.value.errorMessage)
    }

    @Test
    fun `the last used calendar is the default`() = runTest {
        val repo = InMemorySettingsRepository(initialCalendarLastUsedId = "2")
        val store = FakeCalendarStore(writable = listOf(calendarGoogle, calendarOutlook))
        val viewModel = viewModel(store, repository = repo)

        viewModel.openAddEvent(
            EventPrefill("Reunión", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z")
        )
        advanceUntilIdle()

        assertEquals("2", viewModel.addEvent.value.draft.calendarId)
    }

    @Test
    fun `local conflicts are shown in the sheet`() = runTest {
        val store = FakeCalendarStore(
            writable = listOf(calendarGoogle),
            localEvents = listOf(event("otro", "2026-11-24T13:30:00Z", "2026-11-24T14:30:00Z")),
        )
        val viewModel = viewModel(store)

        viewModel.openAddEvent(
            EventPrefill("Reunión", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z")
        )
        advanceUntilIdle()

        assertEquals(listOf("otro"), viewModel.addEvent.value.conflicts.map { it.id })
    }

    @Test
    fun `a failed insert keeps the sheet open with an error`() = runTest {
        val store = FakeCalendarStore(
            writable = listOf(calendarGoogle),
            insertResult = null,
        )
        val viewModel = viewModel(store)

        viewModel.openAddEvent(
            EventPrefill("Reunión", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z")
        )
        advanceUntilIdle()
        viewModel.saveEvent {}
        advanceUntilIdle()

        assertTrue(viewModel.addEvent.value.isOpen)
        assertNotNull(viewModel.addEvent.value.errorMessage)
        assertFalse(viewModel.addEvent.value.isSaving)
    }

    @Test
    fun `closeAddEvent clears the sheet`() = runTest {
        val store = FakeCalendarStore(writable = listOf(calendarGoogle))
        val viewModel = viewModel(store)
        viewModel.openAddEvent(
            EventPrefill("Reunión", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z")
        )
        advanceUntilIdle()

        viewModel.closeAddEvent()

        assertFalse(viewModel.addEvent.value.isOpen)
        assertEquals("", viewModel.addEvent.value.draft.title)
    }

    // --- View modes and navigation (phone-calendar T5) ---

    @Test
    fun `agenda opens in month mode on today`() = runTest {
        val viewModel = viewModel(FakeCalendarStore())
        advanceUntilIdle()

        val state = viewModel.agenda.value
        assertEquals(CalendarViewMode.MONTH, state.viewMode)
        assertEquals(LocalDate.of(2026, 11, 24), state.selectedDate)
        assertEquals(YearMonth.of(2026, 11), state.visibleMonth)
        assertEquals(LocalDate.of(2026, 11, 23), state.weekStart)
        assertEquals(6, state.monthWeeks.size)
    }

    @Test
    fun `a stored view mode is restored`() = runTest {
        val repository = InMemorySettingsRepository(initialCalendarViewMode = "week")
        val viewModel = viewModel(FakeCalendarStore(), repository = repository)

        advanceUntilIdle()

        assertEquals(CalendarViewMode.WEEK, viewModel.agenda.value.viewMode)
    }

    @Test
    fun `switching the view mode persists it and reloads that range`() = runTest {
        val store = FakeCalendarStore()
        val viewModel = viewModel(store)
        advanceUntilIdle()

        viewModel.setViewMode(CalendarViewMode.WEEK)
        advanceUntilIdle()

        assertEquals(CalendarViewMode.WEEK, viewModel.agenda.value.viewMode)
        assertEquals("week", repository.calendarViewMode.first())
        val (start, end) = store.eventWindowQueries.last()
        assertEquals("2026-11-23T00:00:00Z", Instant.ofEpochMilli(start).toString())
        assertEquals("2026-11-30T00:00:00Z", Instant.ofEpochMilli(end).toString())
    }

    @Test
    fun `next month moves the grid and queries the new range`() = runTest {
        val store = FakeCalendarStore()
        val viewModel = viewModel(store)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.nextMonth()
        advanceUntilIdle()

        val state = viewModel.agenda.value
        assertEquals(YearMonth.of(2026, 12), state.visibleMonth)
        assertEquals(5, state.monthWeeks.size)
        assertEquals(LocalDate.of(2026, 12, 1), state.selectedDate)
        val (start, end) = store.eventWindowQueries.last()
        assertEquals("2026-11-30T00:00:00Z", Instant.ofEpochMilli(start).toString())
        assertEquals("2027-01-04T00:00:00Z", Instant.ofEpochMilli(end).toString())
    }

    @Test
    fun `previous month moves back`() = runTest {
        val viewModel = viewModel(FakeCalendarStore())
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.previousMonth()
        advanceUntilIdle()

        assertEquals(YearMonth.of(2026, 10), viewModel.agenda.value.visibleMonth)
    }

    @Test
    fun `week navigation moves the Monday-first anchor`() = runTest {
        val store = FakeCalendarStore()
        val viewModel = viewModel(store)
        viewModel.setViewMode(CalendarViewMode.WEEK)
        advanceUntilIdle()

        viewModel.nextWeek()
        advanceUntilIdle()

        val state = viewModel.agenda.value
        assertEquals(LocalDate.of(2026, 11, 30), state.weekStart)
        assertEquals(LocalDate.of(2026, 11, 30), state.selectedDate)
        assertEquals(7, state.weekDays.size)
        val (start, end) = store.eventWindowQueries.last()
        assertEquals("2026-11-30T00:00:00Z", Instant.ofEpochMilli(start).toString())
        assertEquals("2026-12-07T00:00:00Z", Instant.ofEpochMilli(end).toString())

        viewModel.previousWeek()
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 11, 23), viewModel.agenda.value.weekStart)
    }

    @Test
    fun `goToday returns the month and week to the current date`() = runTest {
        val viewModel = viewModel(FakeCalendarStore())
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.nextMonth()
        advanceUntilIdle()

        viewModel.goToday()
        advanceUntilIdle()

        val state = viewModel.agenda.value
        assertEquals(YearMonth.of(2026, 11), state.visibleMonth)
        assertEquals(LocalDate.of(2026, 11, 24), state.selectedDate)
        assertEquals(LocalDate.of(2026, 11, 23), state.weekStart)
    }

    @Test
    fun `selecting a month day exposes that day's events`() = runTest {
        val store = FakeCalendarStore(
            snapshot = snapshot(
                event("hoy", "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z"),
                event("otro", "2026-11-26T13:00:00Z", "2026-11-26T14:00:00Z"),
            )
        )
        val viewModel = viewModel(store)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.selectDate(LocalDate.of(2026, 11, 26))

        val state = viewModel.agenda.value
        assertEquals(LocalDate.of(2026, 11, 26), state.selectedDate)
        assertEquals(listOf("otro"), state.eventsByDay[LocalDate.of(2026, 11, 26)].orEmpty().map { it.id })
    }

    @Test
    fun `month mode flags conflict days and week mode lays timed events out`() = runTest {
        val store = FakeCalendarStore(
            snapshot = snapshot(
                event("a", "2026-11-25T13:00:00Z", "2026-11-25T14:00:00Z"),
                event("b", "2026-11-25T13:30:00Z", "2026-11-25T14:30:00Z"),
            )
        )
        val viewModel = viewModel(store)

        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(LocalDate.of(2026, 11, 25) in viewModel.agenda.value.conflictDates)

        viewModel.setViewMode(CalendarViewMode.WEEK)
        advanceUntilIdle()

        val blocks = viewModel.agenda.value.weekBlocks
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it.columnCount == 2 })
        assertEquals(6 * 60, blocks.minOf { it.topMinutes })
    }
}
