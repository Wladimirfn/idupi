package com.idupi.app.data.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 3 / Task 4.2 — RED-first coverage for [InMemorySettingsRepository].
 *
 * The repository is the single source of truth for the
 * `X-OpenCode-Auto-Approve` toggle shipped to the server. Spec requires the
 * toggle to default OFF (`opencodeAutoApprove = false`) so a fresh install
 * surfaces permission cards instead of silently self-approving with
 * `opencode run --auto`.
 *
 * TDD discipline:
 *   1. RED   — the production class does not exist yet; compilation fails.
 *   2. GREEN — implement the minimum to compile + pass these assertions.
 *   3. TRIANGULATE — cover the three observable behaviours (default, set,
 *                   round-trip) so a hardcoded `return false` cannot pass.
 *
 * The DataStore-backed implementation is covered separately; this file pins
 * the in-memory contract that the DataStore impl must also satisfy.
 */
class InMemorySettingsRepositoryTest {

    @Test
    fun `opencodeAutoApprove defaults to false on a fresh repository`() = runTest {
        val repo = InMemorySettingsRepository()

        assertFalse(
            "fresh repository MUST default to false (toggle OFF, sidecar path) " +
                "so a fresh install surfaces permission cards instead of " +
                "silently self-approving with --auto",
            repo.opencodeAutoApprove.first(),
        )
    }

    @Test
    fun `setOpencodeAutoApprove true updates the observed value to true`() = runTest {
        val repo = InMemorySettingsRepository()

        repo.setOpencodeAutoApprove(true)

        assertEquals(
            "after setOpencodeAutoApprove(true) the flow MUST emit true; " +
                "this is the round-trip the SettingsScreen toggle relies on " +
                "when the user flips it ON (legacy run --auto path)",
            true,
            repo.opencodeAutoApprove.first(),
        )
    }

    @Test
    fun `setOpencodeAutoApprove false after true round-trips back to false`() = runTest {
        val repo = InMemorySettingsRepository()
        repo.setOpencodeAutoApprove(true)

        repo.setOpencodeAutoApprove(false)

        assertFalse(
            "toggle must round-trip back to false; a stuck-true regression " +
                "would silently enable --auto even after the user toggled it OFF",
            repo.opencodeAutoApprove.first(),
        )
    }

    @Test
    fun `opencodeAutoApprove flow emits the new value after setOpencodeAutoApprove`() = runTest {
        // Pin the contract that the Flow is NOT a snapshot — observers must
        // see subsequent writes. This is what MainViewModel's StateFlow binds
        // to, so a stale-on-write regression would let the Settings toggle and
        // the chat-header drift apart.
        val repo = InMemorySettingsRepository()

        repo.setOpencodeAutoApprove(true)
        val observedAfterSet = repo.opencodeAutoApprove.first()

        assertTrue(
            "the flow MUST surface the just-written value, not the initial false",
            observedAfterSet,
        )
    }
}
