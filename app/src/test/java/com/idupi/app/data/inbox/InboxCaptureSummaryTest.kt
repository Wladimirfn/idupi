package com.idupi.app.data.inbox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * IDUPI's single silent summary (alerts-flow A1): the line the owner reads
 * instead of a pile of notifications, and the counter that feeds it.
 */
class InboxCaptureSummaryTest {

    @After
    fun tearDown() {
        InboxCaptureSummary.reset()
    }

    @Test
    fun `summary text pluralizes alerts and counts important ones`() {
        assertEquals("3 alertas nuevas · 1 importante", InboxCaptureSummary.text(3, 1))
    }

    @Test
    fun `summary text uses the singular for a single alert`() {
        assertEquals("1 alerta nueva · 2 importantes", InboxCaptureSummary.text(1, 2))
    }

    @Test
    fun `summary text omits the important part when none is known yet`() {
        // Importance arrives later from the PC: "· 0 importantes" would read as
        // "nothing matters" on every fresh capture.
        assertEquals("4 alertas nuevas", InboxCaptureSummary.text(4, 0))
    }

    @Test
    fun `recording cleared captures adds up until the owner opens Alertas`() {
        InboxCaptureSummary.reset()

        assertEquals(InboxCaptureSummary.Count(1, 0), InboxCaptureSummary.record())
        assertEquals(InboxCaptureSummary.Count(2, 1), InboxCaptureSummary.record(important = true))
        assertEquals(InboxCaptureSummary.Count(3, 1), InboxCaptureSummary.record())

        InboxCaptureSummary.reset()

        assertEquals(InboxCaptureSummary.Count(1, 0), InboxCaptureSummary.record())
    }
}
