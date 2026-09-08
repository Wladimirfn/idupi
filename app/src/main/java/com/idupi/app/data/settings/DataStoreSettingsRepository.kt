package com.idupi.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [SettingsRepository].
 *
 * Production wiring constructs this from a `Context.preferencesDataStore`
 * (or equivalent `DataStore<Preferences>`); tests construct it directly
 * with `PreferenceDataStoreFactory.create(produceFile = { ... })` so the
 * file-based persistence path runs on the plain JVM without Robolectric.
 *
 * The `opencodeAutoApprove` toggle is persisted under the
 * [OPENCODE_AUTO_APPROVE_KEY] preference key. The default is `false` (no
 * preference present → `map { it[KEY] ?: false }`), matching the spec's
 * "Settings toggle MUST default to OFF" requirement: a fresh install
 * surfaces permission cards instead of silently self-approving with
 * `opencode run --auto`.
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val opencodeAutoApprove: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[OPENCODE_AUTO_APPROVE_KEY] ?: false }

    override suspend fun setOpencodeAutoApprove(value: Boolean) {
        dataStore.edit { prefs -> prefs[OPENCODE_AUTO_APPROVE_KEY] = value }
    }

    companion object {
        /**
         * Preference key for the OpenCode auto-approve toggle. Lives on the
         * Preferences DataStore, scoped to the singleton instance the
         * production wiring builds at app start.
         */
        val OPENCODE_AUTO_APPROVE_KEY: Preferences.Key<Boolean> =
            booleanPreferencesKey("opencode_auto_approve")
    }
}
