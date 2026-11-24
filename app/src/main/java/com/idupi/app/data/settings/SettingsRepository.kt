package com.idupi.app.data.settings

import com.idupi.app.domain.model.InboxSettings
import com.idupi.app.domain.model.ThemeColors
import com.idupi.app.domain.model.ThemeMode
import com.idupi.app.domain.model.ThemePreset
import com.idupi.app.domain.model.ThemeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Persisted app settings: the theme choices and the phone-side Alertas
 * configuration (phone-inbox T2).
 *
 * Production uses [DataStoreSettingsRepository] (crash-safe, survives app
 * restarts); tests and ViewModels without an injected repository use
 * [InMemorySettingsRepository].
 */
interface SettingsRepository {

    /** Observed theme choice; defaults to dark with the indigo accent. */
    val themeSettings: Flow<ThemeSettings>

    /** Observed Alertas capture/reminder/auto-reply configuration. */
    val inboxSettings: Flow<InboxSettings>

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setThemePreset(preset: ThemePreset)

    suspend fun setCustomColors(colors: ThemeColors)

    /** [apps] are [com.idupi.app.domain.model.InboxApp.id] values. */
    suspend fun setInboxEnabledApps(apps: Set<String>)

    suspend fun setInboxReminderIntervalMinutes(minutes: Int)

    /** `high` or `medium`; anything else reads back as `high`. */
    suspend fun setInboxRemindMinImportance(importance: String)

    suspend fun setInboxAutoReplyEnabled(enabled: Boolean)

    suspend fun setInboxAutoReplyDelayMinutes(minutes: Int)

    suspend fun setInboxAutoReplyTemplate(template: String)
}

/** In-memory [SettingsRepository] backed by [MutableStateFlow]s. */
class InMemorySettingsRepository(
    initial: ThemeSettings = ThemeSettings(),
    initialInbox: InboxSettings = InboxSettings(),
) : SettingsRepository {

    private val _themeSettings = MutableStateFlow(initial)
    private val _inboxSettings = MutableStateFlow(initialInbox)

    override val themeSettings: Flow<ThemeSettings> = _themeSettings.asStateFlow()

    override val inboxSettings: Flow<InboxSettings> = _inboxSettings.asStateFlow()

    override suspend fun setThemeMode(mode: ThemeMode) {
        _themeSettings.update { it.copy(mode = mode) }
    }

    override suspend fun setThemePreset(preset: ThemePreset) {
        _themeSettings.update { it.copy(preset = preset) }
    }

    override suspend fun setCustomColors(colors: ThemeColors) {
        _themeSettings.update { it.copy(custom = colors) }
    }

    override suspend fun setInboxEnabledApps(apps: Set<String>) {
        _inboxSettings.update { it.copy(enabledApps = apps.toSet()) }
    }

    override suspend fun setInboxReminderIntervalMinutes(minutes: Int) {
        _inboxSettings.update { it.copy(reminderIntervalMinutes = minutes.coerceAtLeast(1)) }
    }

    override suspend fun setInboxRemindMinImportance(importance: String) {
        _inboxSettings.update {
            it.copy(remindMinImportance = InboxSettings.normalizeImportance(importance))
        }
    }

    override suspend fun setInboxAutoReplyEnabled(enabled: Boolean) {
        _inboxSettings.update { it.copy(autoReplyEnabled = enabled) }
    }

    override suspend fun setInboxAutoReplyDelayMinutes(minutes: Int) {
        _inboxSettings.update { it.copy(autoReplyDelayMinutes = minutes.coerceAtLeast(0)) }
    }

    override suspend fun setInboxAutoReplyTemplate(template: String) {
        _inboxSettings.update { it.copy(autoReplyTemplate = template) }
    }
}
