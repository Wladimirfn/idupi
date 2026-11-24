package com.idupi.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.idupi.app.domain.model.InboxApp
import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxSettings
import com.idupi.app.domain.model.ThemeColors
import com.idupi.app.domain.model.ThemeDefaults
import com.idupi.app.domain.model.ThemeMode
import com.idupi.app.domain.model.ThemePreset
import com.idupi.app.domain.model.ThemeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [SettingsRepository] for the app theme (T9), the phone-side
 * Alertas settings (phone-inbox T2) and the phone status reporting switch
 * (phone-agent T2).
 *
 * Every value has a safe default, so a fresh install and an install upgrading
 * from a build without these keys both land on dark + Índigo instead of
 * crashing or rendering an undefined palette. Each setter writes one key, which
 * keeps unrelated choices intact (changing the mode must not reset the custom
 * swatches, and changing the reminder interval must not reset the followed
 * apps).
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

    override val inboxSettings: Flow<InboxSettings> =
        dataStore.data.map { prefs ->
            InboxSettings(
                // An empty stored set is a real choice ("follow nothing"), so
                // only a missing key falls back to the default follow list.
                followedApps = (prefs[INBOX_FOLLOWED_APPS_KEY] ?: InboxApp.defaultFollowedIds)
                    .toSet(),
                reminderIntervalMinutes = prefs[INBOX_REMINDER_INTERVAL_KEY]
                    ?: InboxSettings.DEFAULT_REMINDER_INTERVAL_MINUTES,
                remindMinImportance = InboxSettings.normalizeImportance(
                    prefs[INBOX_REMIND_MIN_IMPORTANCE_KEY] ?: InboxImportance.HIGH
                ),
                autoReplyEnabled = prefs[INBOX_AUTO_REPLY_ENABLED_KEY] ?: false,
                autoReplyDelayMinutes = prefs[INBOX_AUTO_REPLY_DELAY_KEY]
                    ?: InboxSettings.DEFAULT_AUTO_REPLY_DELAY_MINUTES,
                autoReplyTemplate = prefs[INBOX_AUTO_REPLY_TEMPLATE_KEY]
                    ?: InboxSettings.DEFAULT_AUTO_REPLY_TEMPLATE,
                silencedConversations = (prefs[INBOX_SILENCED_CONVERSATIONS_KEY] ?: emptySet())
                    .toSet(),
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

    override suspend fun setInboxFollowedApps(apps: Set<String>) {
        dataStore.edit { prefs -> prefs[INBOX_FOLLOWED_APPS_KEY] = apps }
    }

    override suspend fun setInboxReminderIntervalMinutes(minutes: Int) {
        // A zero-minute interval would mean "remind in a loop"; the reader
        // would have no sane fallback, so clamp at the writer.
        dataStore.edit { prefs -> prefs[INBOX_REMINDER_INTERVAL_KEY] = minutes.coerceAtLeast(1) }
    }

    override suspend fun setInboxRemindMinImportance(importance: String) {
        dataStore.edit { prefs ->
            prefs[INBOX_REMIND_MIN_IMPORTANCE_KEY] = InboxSettings.normalizeImportance(importance)
        }
    }

    override suspend fun setInboxAutoReplyEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[INBOX_AUTO_REPLY_ENABLED_KEY] = enabled }
    }

    override suspend fun setInboxAutoReplyDelayMinutes(minutes: Int) {
        dataStore.edit { prefs -> prefs[INBOX_AUTO_REPLY_DELAY_KEY] = minutes.coerceAtLeast(0) }
    }

    override suspend fun setInboxAutoReplyTemplate(template: String) {
        dataStore.edit { prefs -> prefs[INBOX_AUTO_REPLY_TEMPLATE_KEY] = template }
    }

    override suspend fun setInboxSilencedConversations(conversations: Set<String>) {
        dataStore.edit { prefs -> prefs[INBOX_SILENCED_CONVERSATIONS_KEY] = conversations }
    }

    // Missing key means ON: status reporting is opt-out, so an upgraded install
    // starts reporting without the owner having to touch anything.
    override val phoneStatusEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[PHONE_STATUS_ENABLED_KEY] ?: true }

    override suspend fun setPhoneStatusEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PHONE_STATUS_ENABLED_KEY] = enabled }
    }

    // Missing key means OFF: command execution is opt-in twice (system grant
    // plus this switch), so a fresh or upgraded install never executes a tap.
    override val phoneControlEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[PHONE_CONTROL_ENABLED_KEY] ?: false }

    override suspend fun setPhoneControlEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PHONE_CONTROL_ENABLED_KEY] = enabled }
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

        /**
         * Followed app ids: a preset id or any package name. The preference
         * name keeps its original "enabled apps" spelling so a set already
         * stored by an older build is read without a migration.
         */
        val INBOX_FOLLOWED_APPS_KEY: Preferences.Key<Set<String>> =
            stringSetPreferencesKey("inbox_enabled_apps")
        val INBOX_REMINDER_INTERVAL_KEY: Preferences.Key<Int> =
            intPreferencesKey("inbox_reminder_interval_minutes")
        val INBOX_REMIND_MIN_IMPORTANCE_KEY: Preferences.Key<String> =
            stringPreferencesKey("inbox_remind_min_importance")
        val INBOX_AUTO_REPLY_ENABLED_KEY: Preferences.Key<Boolean> =
            booleanPreferencesKey("inbox_auto_reply_enabled")
        val INBOX_AUTO_REPLY_DELAY_KEY: Preferences.Key<Int> =
            intPreferencesKey("inbox_auto_reply_delay_minutes")
        val INBOX_AUTO_REPLY_TEMPLATE_KEY: Preferences.Key<String> =
            stringPreferencesKey("inbox_auto_reply_template")

        /**
         * inbox-management T4: conversations silenced inside IDUPI, stored as
         * `app|conversation` keys. Missing means none, so an upgraded install
         * starts with nothing silenced.
         */
        val INBOX_SILENCED_CONVERSATIONS_KEY: Preferences.Key<Set<String>> =
            stringSetPreferencesKey("inbox_silenced_conversations")

        /** phone-agent T2: periodic status reporting to the PC; default ON. */
        val PHONE_STATUS_ENABLED_KEY: Preferences.Key<Boolean> =
            booleanPreferencesKey("phone_status_enabled")

        /** phone-agent T3: execute PC commands through accessibility; default OFF. */
        val PHONE_CONTROL_ENABLED_KEY: Preferences.Key<Boolean> =
            booleanPreferencesKey("phone_control_enabled")
    }
}
