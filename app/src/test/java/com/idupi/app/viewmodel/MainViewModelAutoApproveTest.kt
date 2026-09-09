package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.settings.InMemorySettingsRepository
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * PR 3 / Tasks 4.2 + 4.3 wire-up — RED-first coverage for the OpenCode
 * auto-approve toggle surface on [MainViewModel].
 *
 * The ViewModel is the single Android-side owner of the toggle value: the
 * Settings UI binds to `opencodeAutoApprove`, `setOpencodeAutoApprove(value)`
 * persists into the SettingsRepository, and the same value is pushed to
 * `RealIduPiClient.opencodeAutoApprove` so the next chat request carries
 * the header. The repo is the source of truth across process restarts; the
 * ViewModel is the source of truth within a single process.
 */
class MainViewModelAutoApproveTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient
    private lateinit var settings: InMemorySettingsRepository

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
        settings = InMemorySettingsRepository()
    }

    @Test
    fun `opencodeAutoApprove defaults to false on a fresh ViewModel`() = runTest {
        val viewModel = MainViewModel(FakeClientSource(fake), settings)
        advanceUntilIdle()

        assertFalse(
            "MainViewModel.opencodeAutoApprove MUST default to false " +
                "(toggle OFF, sidecar-first path) so a fresh install " +
                "surfaces permission cards instead of silently " +
                "self-approving with --auto",
            viewModel.opencodeAutoApprove.value,
        )
    }

    @Test
    fun `setOpencodeAutoApprove true updates the observed StateFlow to true`() = runTest {
        val viewModel = MainViewModel(FakeClientSource(fake), settings)
        advanceUntilIdle()

        viewModel.setOpencodeAutoApprove(true)
        advanceUntilIdle()

        assertEquals(
            "MainViewModel.opencodeAutoApprove MUST emit true after " +
                "setOpencodeAutoApprove(true); the SettingsScreen Switch " +
                "binds to this StateFlow and would visibly stay OFF otherwise",
            true,
            viewModel.opencodeAutoApprove.value,
        )
    }

    @Test
    fun `setOpencodeAutoApprove false after true round-trips back to false`() = runTest {
        val viewModel = MainViewModel(FakeClientSource(fake), settings)
        advanceUntilIdle()
        viewModel.setOpencodeAutoApprove(true)
        advanceUntilIdle()

        viewModel.setOpencodeAutoApprove(false)
        advanceUntilIdle()

        assertFalse(
            "toggle MUST round-trip back to false after the user flips it OFF",
            viewModel.opencodeAutoApprove.value,
        )
    }

    @Test
    fun `setOpencodeAutoApprove persists into the repository for crash survival`() = runTest {
        val viewModel = MainViewModel(FakeClientSource(fake), settings)
        advanceUntilIdle()
        viewModel.setOpencodeAutoApprove(true)
        advanceUntilIdle()

        // A NEW ViewModel reading the same repo must see true. This is the
        // post-restart / process-recreation invariant the DataStore backing
        // gives us — without it, every cold launch would reset the toggle.
        val secondViewModel = MainViewModel(FakeClientSource(fake), settings)
        advanceUntilIdle()

        assertTrue(
            "a second ViewModel reading the SAME repo MUST see the " +
                "persisted value (true); this is the crash-survival " +
                "invariant the DataStore-backed repository guarantees",
            secondViewModel.opencodeAutoApprove.value,
        )
    }
}
