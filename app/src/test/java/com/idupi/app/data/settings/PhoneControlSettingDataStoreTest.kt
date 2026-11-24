package com.idupi.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Persistence contract for the "Control desde la PC" switch (phone-agent T3).
 * It defaults to OFF because execution needs the owner's explicit opt-in twice
 * (switch plus accessibility grant), and the stored on choice must survive a
 * restart so the service re-arms itself.
 */
class PhoneControlSettingDataStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: DataStoreSettingsRepository

    @Before
    fun setUp() {
        file = tempFolder.newFile("settings.preferences_pb")
        file.delete()
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        store = createStore()
        repo = DataStoreSettingsRepository(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun createStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { file }

    private fun reopen(): DataStoreSettingsRepository {
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        store = createStore()
        repo = DataStoreSettingsRepository(store)
        return repo
    }

    @Test
    fun `control is off by default`() = runTest {
        assertFalse(repo.phoneControlEnabled.first())
    }

    @Test
    fun `the on choice survives a new repository instance`() = runTest {
        repo.setPhoneControlEnabled(true)

        assertTrue(reopen().phoneControlEnabled.first())
    }

    @Test
    fun `turning it back off also survives`() = runTest {
        repo.setPhoneControlEnabled(true)
        repo.setPhoneControlEnabled(false)

        assertFalse(reopen().phoneControlEnabled.first())
    }

    @Test
    fun `the control switch does not disturb status reporting`() = runTest {
        repo.setPhoneControlEnabled(true)

        assertTrue(reopen().phoneStatusEnabled.first())
    }
}
