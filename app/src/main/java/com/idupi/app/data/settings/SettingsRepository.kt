package com.idupi.app.data.settings

import com.idupi.app.domain.model.ThemeColors
import com.idupi.app.domain.model.ThemeMode
import com.idupi.app.domain.model.ThemePreset
import com.idupi.app.domain.model.ThemeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Persisted app settings. Today the only settings the app keeps are the theme
 * choices submitted from Ajustes.
 *
 * Production uses [DataStoreSettingsRepository] (crash-safe, survives app
 * restarts); tests and ViewModels without an injected repository use
 * [InMemorySettingsRepository].
 */
interface SettingsRepository {

    /** Observed theme choice; defaults to dark with the indigo accent. */
    val themeSettings: Flow<ThemeSettings>

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setThemePreset(preset: ThemePreset)

    suspend fun setCustomColors(colors: ThemeColors)
}

/** In-memory [SettingsRepository] backed by a [MutableStateFlow]. */
class InMemorySettingsRepository(
    initial: ThemeSettings = ThemeSettings(),
) : SettingsRepository {

    private val _themeSettings = MutableStateFlow(initial)

    override val themeSettings: Flow<ThemeSettings> = _themeSettings.asStateFlow()

    override suspend fun setThemeMode(mode: ThemeMode) {
        _themeSettings.update { it.copy(mode = mode) }
    }

    override suspend fun setThemePreset(preset: ThemePreset) {
        _themeSettings.update { it.copy(preset = preset) }
    }

    override suspend fun setCustomColors(colors: ThemeColors) {
        _themeSettings.update { it.copy(custom = colors) }
    }
}
