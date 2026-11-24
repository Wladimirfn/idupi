package com.idupi.app.data.inbox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Capture diagnostics transitions (inbox capture fix T1). The state intentionally
 * has no message/sender/conversation field: only app ids, timestamps, counts and
 * exception class names reach Ajustes.
 */
class InboxCaptureDiagnosticsTest {

    @Before
    fun setUp() {
        InboxCaptureDiagnostics.reset()
    }

    @After
    fun tearDown() {
        InboxCaptureDiagnostics.reset()
    }

    @Test
    fun `connect and disconnect flip the listening flag and since timestamp`() {
        InboxCaptureDiagnostics.onListenerConnected(nowMillis = 1_000)
        assertTrue(InboxCaptureDiagnostics.state.value.listenerConnected)
        assertEquals(1_000L, InboxCaptureDiagnostics.state.value.connectedSinceMillis)

        InboxCaptureDiagnostics.onListenerDisconnected()
        assertFalse(InboxCaptureDiagnostics.state.value.listenerConnected)
        assertNull(InboxCaptureDiagnostics.state.value.connectedSinceMillis)
    }

    @Test
    fun `capture records the time and app id only`() {
        InboxCaptureDiagnostics.onCaptured(app = "whatsapp", nowMillis = 2_000)

        val state = InboxCaptureDiagnostics.state.value
        assertEquals(2_000L, state.lastCaptureAtMillis)
        assertEquals("whatsapp", state.lastCaptureApp)
    }

    @Test
    fun `upload failure records the exception class and time`() {
        InboxCaptureDiagnostics.onUploadFailed("UnknownHostException", nowMillis = 3_000)

        val state = InboxCaptureDiagnostics.state.value
        assertEquals("UnknownHostException", state.lastUploadErrorClass)
        assertEquals(3_000L, state.lastUploadErrorAtMillis)
        assertNull(state.lastUploadOkAtMillis)
    }

    @Test
    fun `a later success clears the previous failure`() {
        InboxCaptureDiagnostics.onUploadFailed("IOException", nowMillis = 3_000)
        InboxCaptureDiagnostics.onUploadSucceeded(nowMillis = 4_000)

        val state = InboxCaptureDiagnostics.state.value
        assertEquals(4_000L, state.lastUploadOkAtMillis)
        assertNull(state.lastUploadErrorClass)
        assertNull(state.lastUploadErrorAtMillis)
    }

    @Test
    fun `scan and pending counts are recorded without negatives`() {
        InboxCaptureDiagnostics.onScanCompleted(4)
        InboxCaptureDiagnostics.onPendingCount(2)
        assertEquals(4, InboxCaptureDiagnostics.state.value.lastScanCount)
        assertEquals(2, InboxCaptureDiagnostics.state.value.pendingRetryCount)

        InboxCaptureDiagnostics.onPendingCount(-1)
        assertEquals(0, InboxCaptureDiagnostics.state.value.pendingRetryCount)
    }

    @Test
    fun `reset returns the state to its initial value`() {
        InboxCaptureDiagnostics.onListenerConnected(nowMillis = 1_000)
        InboxCaptureDiagnostics.onCaptured("gmail", nowMillis = 2_000)

        InboxCaptureDiagnostics.reset()

        assertEquals(InboxCaptureState(), InboxCaptureDiagnostics.state.value)
    }
}
