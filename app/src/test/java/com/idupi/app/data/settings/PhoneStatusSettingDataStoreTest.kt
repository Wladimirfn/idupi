package com.idupi.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.idupi.app.domain.model.InboxSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * Persistence contract for the phone status switch (phone-agent T2). The switch
 * decides whether the 60 s reporter talks to the PC at all, so the default and
 * the stored choice must survive a restart.
 */
class PhoneStatusSettingDataStoreTest {

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
    fun `status reporting defaults to on`() = runTest {
        assertTrue(repo.phoneStatusEnabled.first())
    }

    @Test
    fun `the off choice survives a new repository instance`() = runTest {
        repo.setPhoneStatusEnabled(false)

        assertFalse(reopen().phoneStatusEnabled.first())
    }

    @Test
    fun `turning it back on also survives`() = runTest {
        repo.setPhoneStatusEnabled(false)
        repo.setPhoneStatusEnabled(true)

        assertTrue(reopen().phoneStatusEnabled.first())
    }

    @Test
    fun `the status switch does not disturb the inbox settings`() = runTest {
        repo.setPhoneStatusEnabled(false)

        assertEquals(InboxSettings(), repo.inboxSettings.first())
    }
}
