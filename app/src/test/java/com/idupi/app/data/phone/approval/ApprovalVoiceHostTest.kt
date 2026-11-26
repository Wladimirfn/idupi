package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Process-wide bridge between the approval poll and the bubble overlay
 * (phone-bubble T23). A detached bubble never receives an approval, and a
 * handler that refuses one lets the poll post the notification instead.
 */
class ApprovalVoiceHostTest {

    @After
    fun tearDown() {
        ApprovalVoiceHost.detach()
    }

    @Test
    fun `offers only reach an attached bubble`() {
        val offered = mutableListOf<String>()
        assertFalse(ApprovalVoiceHost.isAttached())
        assertFalse(ApprovalVoiceHost.offer(PhoneApproval(id = "apr-1")))

        ApprovalVoiceHost.attach { approval ->
            offered += approval.id
            true
        }
        assertTrue(ApprovalVoiceHost.isAttached())
        assertTrue(ApprovalVoiceHost.offer(PhoneApproval(id = "apr-2")))
        assertEquals(listOf("apr-2"), offered)

        ApprovalVoiceHost.detach()
        assertFalse(ApprovalVoiceHost.isAttached())
        assertFalse(ApprovalVoiceHost.offer(PhoneApproval(id = "apr-3")))
        assertEquals(listOf("apr-2"), offered)
    }

    @Test
    fun `a bubble that cannot take the approval refuses it`() {
        ApprovalVoiceHost.attach { false }

        assertFalse(ApprovalVoiceHost.offer(PhoneApproval(id = "apr-1")))
    }
}
