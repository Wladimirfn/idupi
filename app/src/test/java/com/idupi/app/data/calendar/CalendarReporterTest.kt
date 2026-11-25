package com.idupi.app.data.calendar

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.domain.model.PhoneCalendarEvent
import com.idupi.app.domain.model.PhoneCalendarProposal
import com.idupi.app.domain.model.PhoneCalendarSnapshot
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CalendarReporterTest {

    private val snapshot = PhoneCalendarSnapshot(
        capturedAt = "2026-11-24T12:00:00Z",
        rangeStart = "2026-11-24T12:00:00Z",
        rangeEnd = "2026-12-08T12:00:00Z",
        tzOffsetMinutes = -180,
        events = listOf(
            PhoneCalendarEvent(
                id = "e1",
                calendarId = "1",
                title = "Reunión",
                start = "2026-11-24T13:00:00Z",
                end = "2026-11-24T14:00:00Z",
            )
        ),
    )

    private fun reporter(
        fake: FakeIduPiClient,
        enabled: suspend () -> Boolean = { true },
        canReport: () -> Boolean = { true },
        proposals: suspend () -> List<PhoneCalendarProposal> = { emptyList() },
        onNewProposals: (List<PhoneCalendarProposal>) -> Unit = {},
        clock: () -> Long,
    ) = CalendarReporter(
        clientSource = FakeClientSource(fake),
        snapshotProvider = { snapshot },
        isEnabled = enabled,
        canReport = canReport,
        proposalsProvider = proposals,
        onNewProposals = onNewProposals,
        nowMillis = clock,
        tickMillis = 60_000L,
        minIntervalMillis = 15L * 60_000L,
        changeDebounceMillis = 30_000L,
    )

    @Test
    fun `reports on the loop cadence and at most every 15 minutes`() = runTest {
        val fake = FakeIduPiClient()
        val reporter = reporter(fake) { testScheduler.currentTime }
        reporter.start(backgroundScope)

        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(1, fake.postedPhoneCalendars.size)
        assertEquals(snapshot, fake.postedPhoneCalendars.single())
        assertEquals(1, reporter.reportState.value.eventCount)
        assertEquals(true, reporter.reportState.value.success)

        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertEquals(1, fake.postedPhoneCalendars.size)

        advanceTimeBy(15L * 60_000L)
        runCurrent()
        assertEquals(2, fake.postedPhoneCalendars.size)
    }

    @Test
    fun `a calendar change reports after the debounce even inside the interval`() = runTest {
        val fake = FakeIduPiClient()
        val reporter = reporter(fake) { testScheduler.currentTime }
        reporter.start(backgroundScope)

        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(1, fake.postedPhoneCalendars.size)

        reporter.onChanged()
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(2, fake.postedPhoneCalendars.size)
    }

    @Test
    fun `a burst of changes is debounced into one report`() = runTest {
        val fake = FakeIduPiClient()
        val reporter = reporter(fake) { testScheduler.currentTime }
        reporter.start(backgroundScope)

        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(1, fake.postedPhoneCalendars.size)

        reporter.onChanged()
        advanceTimeBy(10_000)
        reporter.onChanged()
        advanceTimeBy(10_000)
        reporter.onChanged()
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(2, fake.postedPhoneCalendars.size)
    }

    @Test
    fun `the switch off stops the loop but not the manual send`() = runTest {
        val fake = FakeIduPiClient()
        val reporter = reporter(fake, enabled = { false }) { testScheduler.currentTime }
        reporter.start(backgroundScope)

        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertTrue(fake.postedPhoneCalendars.isEmpty())

        assertTrue(reporter.reportNow())
        assertEquals(1, fake.postedPhoneCalendars.size)
    }

    @Test
    fun `a failure is reported without throwing`() = runTest {
        val fake = FakeIduPiClient()
        fake.failPhoneCalendarPostWith = IOException("pc off")
        val reporter = reporter(fake) { testScheduler.currentTime }
        reporter.start(backgroundScope)

        advanceTimeBy(60_001)
        runCurrent()

        assertEquals(0, fake.postedPhoneCalendars.size)
        assertEquals(false, reporter.reportState.value.success)
        assertEquals(1, reporter.reportState.value.eventCount)
    }

    @Test
    fun `first proposal poll seeds the baseline and only new proposals notify`() = runTest {
        val fake = FakeIduPiClient()
        val proposals = mutableListOf(PhoneCalendarProposal(id = "phr-1", title = "Uno"))
        val notified = mutableListOf<List<PhoneCalendarProposal>>()
        val reporter = reporter(
            fake,
            proposals = { proposals.toList() },
            onNewProposals = { notified += it },
        ) { testScheduler.currentTime }
        reporter.start(backgroundScope)

        advanceTimeBy(60_001)
        runCurrent()
        assertTrue(notified.isEmpty())

        proposals += PhoneCalendarProposal(id = "phr-2", title = "Dos")
        advanceTimeBy(60_001)
        runCurrent()

        assertEquals(listOf("phr-2"), notified.single().map { it.id })
    }

    @Test
    fun `no new proposal means no notification`() = runTest {
        val fake = FakeIduPiClient()
        val proposals = listOf(PhoneCalendarProposal(id = "phr-1", title = "Uno"))
        val notified = mutableListOf<List<PhoneCalendarProposal>>()
        val reporter = reporter(
            fake,
            proposals = { proposals },
            onNewProposals = { notified += it },
        ) { testScheduler.currentTime }
        reporter.start(backgroundScope)

        advanceTimeBy(3 * 60_001)
        runCurrent()

        assertTrue(notified.isEmpty())
    }

    @Test
    fun `a disabled reporter never polls proposals`() = runTest {
        val fake = FakeIduPiClient()
        var polls = 0
        val reporter = reporter(
            fake,
            enabled = { false },
            proposals = { polls++; emptyList() },
        ) { testScheduler.currentTime }
        reporter.start(backgroundScope)

        advanceTimeBy(3 * 60_001)
        runCurrent()

        assertEquals(0, polls)
    }

    @Test
    fun `reportNow without a profile or snapshot reports false`() = runTest {
        val fake = FakeIduPiClient()
        val noProfile = CalendarReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { snapshot },
            isEnabled = { true },
            canReport = { false },
            nowMillis = { testScheduler.currentTime },
        )
        val noSnapshot = CalendarReporter(
            clientSource = FakeClientSource(fake),
            snapshotProvider = { null },
            isEnabled = { true },
            canReport = { true },
            nowMillis = { testScheduler.currentTime },
        )

        assertFalse(noProfile.reportNow())
        assertFalse(noSnapshot.reportNow())
        assertTrue(fake.postedPhoneCalendars.isEmpty())
    }
}
