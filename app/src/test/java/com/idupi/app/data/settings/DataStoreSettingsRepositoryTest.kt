package com.idupi.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PR 3 / Task 4.2 — RED-first coverage for [DataStoreSettingsRepository].
 *
 * Persistence contract the spec demands: the OpenCode auto-approve toggle
 * MUST survive an app restart. An in-memory fake can pass
 * [InMemorySettingsRepositoryTest]; this file pins the DataStore path
 * that production uses.
 *
 * No Android Context here: the repository is constructed from a
 * `DataStore<Preferences>` so the test exercises the same code path the
 * production wiring does (the production wiring just delegates to the
 * `Context.preferencesDataStore` extension on Android, which yields an
 * equivalent `DataStore<Preferences>`).
 */
class DataStoreSettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: DataStoreSettingsRepository

    @Before
    fun setUp() {
        file = tempFolder.newFile("settings.preferences_pb")
        // Delete the file so DataStore creates a fresh store on first use.
        // (PreferenceDataStoreFactory will recreate it on the first write.)
        file.delete()
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        repo = DataStoreSettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `opencodeAutoApprove defaults to false when the DataStore is empty`() = runTest {
        assertFalse(
            "fresh DataStore MUST default to false (toggle OFF, sidecar path); " +
                "without the default-false invariant, the first launch could " +
                "silently ship X-OpenCode-Auto-Approve: 1 and self-approve " +
                "every prompt before the user has touched the toggle",
            repo.opencodeAutoApprove.first(),
        )
    }

    @Test
    fun `setOpencodeAutoApprove true persists across a new repository instance`() = runTest {
        // Write with the first instance.
        repo.setOpencodeAutoApprove(true)

        // Tear down the first DataStore and build a second one against the
        // SAME file. A new repository instance must see the persisted value.
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val reopenDataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        val reopenedRepo = DataStoreSettingsRepository(reopenDataStore)

        assertTrue(
            "the toggle MUST survive an app restart: writing true with one " +
                "instance and reading with another pointed at the same file " +
                "is the crash-safe persistence invariant the Settings UI relies on",
            reopenedRepo.opencodeAutoApprove.first(),
        )
    }

    @Test
    fun `setOpencodeAutoApprove false after true round-trips back to false on reopen`() = runTest {
        repo.setOpencodeAutoApprove(true)

        repo.setOpencodeAutoApprove(false)

        // Reopen to confirm the latest write (not just the in-memory state) wins.
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val reopenDataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        val reopenedRepo = DataStoreSettingsRepository(reopenDataStore)

        assertFalse(
            "round-trip back to false MUST persist; a stuck-true regression " +
                "would silently re-enable --auto on every restart",
            reopenedRepo.opencodeAutoApprove.first(),
        )
    }

    @Test
    fun `opencodeAutoApprove flow reflects the most recent write on the same instance`() = runTest {
        repo.setOpencodeAutoApprove(true)
        assertEquals(
            "after setOpencodeAutoApprove(true), opencodeAutoApprove MUST emit true",
            true, repo.opencodeAutoApprove.first(),
        )

        repo.setOpencodeAutoApprove(false)
        assertEquals(
            "after setOpencodeAutoApprove(false), opencodeAutoApprove MUST emit false",
            false, repo.opencodeAutoApprove.first(),
        )
    }
}
