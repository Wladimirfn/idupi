package com.idupi.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.idupi.app.data.calendar.CalendarViewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Persistence contract for the Agenda view mode (phone-calendar T5): the
 * calendar-like month view is the default and the owner's last tab survives a
 * restart.
 */
class CalendarViewModeDataStoreTest {

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
    fun `the agenda defaults to month`() = runTest {
        assertEquals(CalendarViewMode.MONTH.storageValue, repo.calendarViewMode.first())
    }

    @Test
    fun `the chosen mode survives a new repository instance`() = runTest {
        repo.setCalendarViewMode(CalendarViewMode.WEEK.storageValue)

        assertEquals(CalendarViewMode.WEEK.storageValue, reopen().calendarViewMode.first())
    }

    @Test
    fun `changing the mode does not disturb the other calendar settings`() = runTest {
        repo.setCalendarLastUsedId("7")
        repo.setCalendarShareEnabled(false)
        repo.setCalendarViewMode(CalendarViewMode.LIST.storageValue)

        val reopened = reopen()
        assertEquals("7", reopened.calendarLastUsedId.first())
        assertEquals(false, reopened.calendarShareEnabled.first())
        assertEquals(CalendarViewMode.LIST.storageValue, reopened.calendarViewMode.first())
    }
}
