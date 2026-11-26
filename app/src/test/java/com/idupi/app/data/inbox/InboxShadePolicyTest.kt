package com.idupi.app.data.inbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a captured notification leaves the phone shade (alerts-flow A1).
 *
 * The rule is deliberately asymmetric: the new "clear captured" setting only
 * acts on an ACCEPTED upload (an offline or rejected capture must stay
 * visible), while a silenced conversation keeps its older rule of leaving the
 * shade as soon as IDUPI captured it.
 */
class InboxShadePolicyTest {

    @Test
    fun `an accepted capture leaves the shade when the setting is on`() {
        assertTrue(
            InboxShadePolicy.shouldCancel(
                InboxUploadOutcome.ACCEPTED,
                silenced = false,
                clearCaptured = true
            )
        )
    }

    @Test
    fun `an accepted capture stays visible when the setting is off`() {
        assertFalse(
            InboxShadePolicy.shouldCancel(
                InboxUploadOutcome.ACCEPTED,
                silenced = false,
                clearCaptured = false
            )
        )
    }

    @Test
    fun `a queued capture stays visible because the PC did not accept it`() {
        assertFalse(
            InboxShadePolicy.shouldCancel(
                InboxUploadOutcome.QUEUED,
                silenced = false,
                clearCaptured = true
            )
        )
    }

    @Test
    fun `a discarded capture stays visible because the PC refused it`() {
        assertFalse(
            InboxShadePolicy.shouldCancel(
                InboxUploadOutcome.DISCARDED,
                silenced = false,
                clearCaptured = true
            )
        )
    }

    @Test
    fun `a silenced conversation leaves the shade regardless of upload and setting`() {
        for (outcome in InboxUploadOutcome.values()) {
            for (clearCaptured in listOf(true, false)) {
                assertTrue(
                    "outcome=$outcome clearCaptured=$clearCaptured",
                    InboxShadePolicy.shouldCancel(outcome, silenced = true, clearCaptured)
                )
            }
        }
    }
}
