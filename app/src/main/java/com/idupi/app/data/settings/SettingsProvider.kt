package com.idupi.app.data.settings

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.idupi.app.IduPiApp

/**
 * Single DataStore instance for app settings. It must be created once per
 * process (the `preferencesDataStore` delegate enforces that), so the provider
 * owns it and every consumer shares the same repository.
 *
 * [IduPiApp.instance] is set in `Application.onCreate`, before MainActivity and
 * its ViewModels exist, so touching the lazy repository from MainViewModel is
 * safe.
 */
private val Context.settingsDataStore by preferencesDataStore(name = "idupi_settings")

object SettingsProvider {
    val repository: SettingsRepository by lazy {
        DataStoreSettingsRepository(IduPiApp.instance.settingsDataStore)
    }
}
