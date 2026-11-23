package com.idupi.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.idupi.app.domain.model.ThemeColors
import com.idupi.app.domain.model.ThemeDefaults
import com.idupi.app.domain.model.ThemeMode
import com.idupi.app.domain.model.ThemePreset
import com.idupi.app.domain.model.ThemeSettings
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
 * Persistence contract for the T9 theme settings.
 *
 * The theme MUST survive an app restart: applying a preset and closing the app
 * cannot silently send the owner back to the default dark/Índigo palette. A
 * second repository instance over the same file is how that invariant is
 * pinned without an Android Context.
 */
class DataStoreSettingsRepositoryTest {

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

    private suspend fun reopen(): DataStoreSettingsRepository {
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        store = createStore()
        repo = DataStoreSettingsRepository(store)
        return repo
    }

    @Test
    fun `an empty DataStore defaults to dark indigo with the dark custom palette`() = runTest {
        assertEquals(ThemeSettings(), repo.themeSettings.first())
        val defaults = repo.themeSettings.first().custom
        assertEquals(ThemeDefaults.accentArgb, defaults.accentArgb)
        assertEquals(ThemeDefaults.backgroundArgb, defaults.backgroundArgb)
        assertEquals(ThemeDefaults.cardArgb, defaults.cardArgb)
        assertEquals(ThemeDefaults.textArgb, defaults.textArgb)
    }

    @Test
    fun `mode and preset persist across a new repository instance`() = runTest {
        repo.setThemeMode(ThemeMode.LIGHT)
        repo.setThemePreset(ThemePreset.EMERALD)

        val reopened = reopen().themeSettings.first()

        assertEquals(ThemeMode.LIGHT, reopened.mode)
        assertEquals(ThemePreset.EMERALD, reopened.preset)
    }

    @Test
    fun `custom colours persist across a new repository instance`() = runTest {
        val custom = ThemeColors(
            accentArgb = 0xFF10B981.toInt(),
            backgroundArgb = 0xFFF8FAFC.toInt(),
            cardArgb = 0xFFFFFFFF.toInt(),
            textArgb = 0xFF0F172A.toInt(),
        )
        repo.setThemeMode(ThemeMode.CUSTOM)
        repo.setCustomColors(custom)

        val reopened = reopen().themeSettings.first()

        assertEquals(ThemeMode.CUSTOM, reopened.mode)
        assertEquals(custom, reopened.custom)
    }

    @Test
    fun `writing one key keeps the other choices intact`() = runTest {
        repo.setThemeMode(ThemeMode.CUSTOM)
        repo.setThemePreset(ThemePreset.AMBER)
        repo.setCustomColors(ThemeColors(accentArgb = 0xFFEF4444.toInt()))

        repo.setThemeMode(ThemeMode.LIGHT)
        val afterModeChange = repo.themeSettings.first()

        assertEquals(ThemeMode.LIGHT, afterModeChange.mode)
        assertEquals(ThemePreset.AMBER, afterModeChange.preset)
        assertEquals(0xFFEF4444.toInt(), afterModeChange.custom.accentArgb)
    }

    @Test
    fun `an unknown stored mode falls back to the default instead of crashing`() = runTest {
        // A value written by a future/older build must not break startup.
        store.edit { prefs ->
            prefs[DataStoreSettingsRepository.THEME_MODE_KEY] = "SEPIA"
        }

        assertEquals(ThemeMode.DARK, repo.themeSettings.first().mode)
    }
}
