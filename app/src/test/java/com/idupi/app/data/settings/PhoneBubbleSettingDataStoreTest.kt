package com.idupi.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
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

/**
 * Persistence contract for the "Burbuja de IDUPI" switch (phone-bubble T4).
 * It defaults to OFF: the bubble draws over other apps, so the owner opts in,
 * and the choice must survive a restart so the service re-creates it.
 */
class PhoneBubbleSettingDataStoreTest {

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
    fun `the bubble is off by default`() = runTest {
        assertFalse(repo.phoneBubbleEnabled.first())
    }

    @Test
    fun `the on choice survives a new repository instance`() = runTest {
        repo.setPhoneBubbleEnabled(true)

        assertTrue(reopen().phoneBubbleEnabled.first())
    }

    @Test
    fun `turning it back off also survives`() = runTest {
        repo.setPhoneBubbleEnabled(true)
        repo.setPhoneBubbleEnabled(false)

        assertFalse(reopen().phoneBubbleEnabled.first())
    }

    @Test
    fun `the bubble switch does not disturb phone control`() = runTest {
        repo.setPhoneControlEnabled(true)
        repo.setPhoneBubbleEnabled(true)

        assertTrue(reopen().phoneControlEnabled.first())
    }
}
