package com.idupi.app.data.phone.approval

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-wide record of bubble turns that were started ON THIS phone
 * (phone-bubble T18). The approval gate trusts it to bind an owner-request
 * grant to the turn the owner actually asked for here.
 */
class OwnerTurnRegistryTest {

    @After
    fun tearDown() {
        OwnerTurnRegistry.clear()
    }

    @Test
    fun `a registered turn stays active until it is removed`() {
        OwnerTurnRegistry.register("pat-0123456789ab")

        assertTrue(OwnerTurnRegistry.isActive("pat-0123456789ab"))

        OwnerTurnRegistry.remove("pat-0123456789ab")
        assertFalse(OwnerTurnRegistry.isActive("pat-0123456789ab"))
    }

    @Test
    fun `unknown and blank ids are never active`() {
        assertFalse(OwnerTurnRegistry.isActive("pat-unknown"))
        assertFalse(OwnerTurnRegistry.isActive(""))

        OwnerTurnRegistry.register("   ")
        assertFalse(OwnerTurnRegistry.isActive("   "))

        OwnerTurnRegistry.register("pat-0123456789ab")
        OwnerTurnRegistry.clear()
        assertFalse(OwnerTurnRegistry.isActive("pat-0123456789ab"))
    }

    @Test
    fun `the registry reports whether any phone-started turn is active`() {
        assertFalse(OwnerTurnRegistry.hasActiveTurn())

        OwnerTurnRegistry.register("pat-0123456789ab")
        assertTrue(OwnerTurnRegistry.hasActiveTurn())

        OwnerTurnRegistry.register("pat-fedcba987654")
        OwnerTurnRegistry.remove("pat-0123456789ab")
        assertTrue(OwnerTurnRegistry.hasActiveTurn())

        OwnerTurnRegistry.remove("pat-fedcba987654")
        assertFalse(OwnerTurnRegistry.hasActiveTurn())
    }

    @Test
    fun `an owner approval is materialized at most once`() {
        assertTrue(OwnerTurnRegistry.claimOwnerApproval("apr-0123456789ab"))
        assertFalse(OwnerTurnRegistry.claimOwnerApproval("apr-0123456789ab"))
        assertFalse(OwnerTurnRegistry.claimOwnerApproval(""))

        OwnerTurnRegistry.clear()
        assertTrue(OwnerTurnRegistry.claimOwnerApproval("apr-0123456789ab"))
    }
}
