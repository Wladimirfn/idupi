package com.idupi.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.idupi.app.domain.model.ThemeColors
import com.idupi.app.domain.model.ThemeDefaults
import com.idupi.app.domain.model.ThemeMode
import com.idupi.app.domain.model.ThemePreset
import com.idupi.app.domain.model.ThemeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [SettingsRepository] for the app theme (T9).
 *
 * Every value has a safe default, so a fresh install and an install upgrading
 * from a build without these keys both land on dark + Índigo instead of
 * crashing or rendering an undefined palette. Each setter writes one key, which
 * keeps unrelated choices intact (changing the mode must not reset the custom
 * swatches).
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val themeSettings: Flow<ThemeSettings> =
        dataStore.data.map { prefs ->
            ThemeSettings(
                mode = enumValue(prefs[THEME_MODE_KEY], ThemeMode.DARK),
                preset = enumValue(prefs[THEME_PRESET_KEY], ThemePreset.INDIGO),
                custom = ThemeColors(
                    accentArgb = prefs[THEME_CUSTOM_ACCENT_KEY] ?: ThemeDefaults.accentArgb,
                    backgroundArgb = prefs[THEME_CUSTOM_BACKGROUND_KEY] ?: ThemeDefaults.backgroundArgb,
                    cardArgb = prefs[THEME_CUSTOM_CARD_KEY] ?: ThemeDefaults.cardArgb,
                    textArgb = prefs[THEME_CUSTOM_TEXT_KEY] ?: ThemeDefaults.textArgb,
                ),
            )
        }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[THEME_MODE_KEY] = mode.name }
    }

    override suspend fun setThemePreset(preset: ThemePreset) {
        dataStore.edit { prefs -> prefs[THEME_PRESET_KEY] = preset.name }
    }

    override suspend fun setCustomColors(colors: ThemeColors) {
        dataStore.edit { prefs ->
            prefs[THEME_CUSTOM_ACCENT_KEY] = colors.accentArgb
            prefs[THEME_CUSTOM_BACKGROUND_KEY] = colors.backgroundArgb
            prefs[THEME_CUSTOM_CARD_KEY] = colors.cardArgb
            prefs[THEME_CUSTOM_TEXT_KEY] = colors.textArgb
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T =
        raw?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: fallback

    companion object {
        val THEME_MODE_KEY: Preferences.Key<String> = stringPreferencesKey("theme_mode")
        val THEME_PRESET_KEY: Preferences.Key<String> = stringPreferencesKey("theme_preset")
        val THEME_CUSTOM_ACCENT_KEY: Preferences.Key<Int> = intPreferencesKey("theme_custom_accent")
        val THEME_CUSTOM_BACKGROUND_KEY: Preferences.Key<Int> =
            intPreferencesKey("theme_custom_background")
        val THEME_CUSTOM_CARD_KEY: Preferences.Key<Int> = intPreferencesKey("theme_custom_card")
        val THEME_CUSTOM_TEXT_KEY: Preferences.Key<Int> = intPreferencesKey("theme_custom_text")
    }
}
