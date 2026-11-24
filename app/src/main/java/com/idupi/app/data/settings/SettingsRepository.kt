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

    /**
     * Observed "send the phone status to the PC" switch (phone-agent T2).
     * Missing value means ON: status reporting is opt-out.
     */
    val phoneStatusEnabled: Flow<Boolean>

    /**
     * Observed "control from the PC" master switch (phone-agent T3). Missing
     * value means OFF: execution needs both this switch and the system
     * accessibility grant.
     */
    val phoneControlEnabled: Flow<Boolean>

    /**
     * Observed "share my agenda with the PC" switch (phone-calendar T2).
     * Missing value means ON: reporting starts as soon as the calendar
     * permission is granted, matching the status-reporting default.
     */
    val calendarShareEnabled: Flow<Boolean>

    /** Last calendar the owner saved an event in; empty when none. */
    val calendarLastUsedId: Flow<String>

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setThemePreset(preset: ThemePreset)

    suspend fun setCustomColors(colors: ThemeColors)

    /**
     * [apps] are followed app ids: a [com.idupi.app.domain.model.InboxApp.id]
     * or the package name of any other app.
     */
    suspend fun setInboxFollowedApps(apps: Set<String>)

    suspend fun setInboxReminderIntervalMinutes(minutes: Int)

    /** `high` or `medium`; anything else reads back as `high`. */
    suspend fun setInboxRemindMinImportance(importance: String)

    suspend fun setInboxAutoReplyEnabled(enabled: Boolean)

    suspend fun setInboxAutoReplyDelayMinutes(minutes: Int)

    suspend fun setInboxAutoReplyTemplate(template: String)

    /**
     * Conversations silenced inside IDUPI, keyed by
     * [com.idupi.app.data.inbox.inboxConversationKey].
     */
    suspend fun setInboxSilencedConversations(conversations: Set<String>)

    suspend fun setPhoneStatusEnabled(enabled: Boolean)

    suspend fun setPhoneControlEnabled(enabled: Boolean)

    suspend fun setCalendarShareEnabled(enabled: Boolean)

    suspend fun setCalendarLastUsedId(calendarId: String)
}

/** In-memory [SettingsRepository] backed by [MutableStateFlow]s. */
class InMemorySettingsRepository(
    initial: ThemeSettings = ThemeSettings(),
    initialInbox: InboxSettings = InboxSettings(),
    initialPhoneStatusEnabled: Boolean = true,
    initialPhoneControlEnabled: Boolean = false,
    initialCalendarShareEnabled: Boolean = true,
    initialCalendarLastUsedId: String = "",
) : SettingsRepository {

    private val _themeSettings = MutableStateFlow(initial)
    private val _inboxSettings = MutableStateFlow(initialInbox)
    private val _phoneStatusEnabled = MutableStateFlow(initialPhoneStatusEnabled)
    private val _phoneControlEnabled = MutableStateFlow(initialPhoneControlEnabled)
    private val _calendarShareEnabled = MutableStateFlow(initialCalendarShareEnabled)
    private val _calendarLastUsedId = MutableStateFlow(initialCalendarLastUsedId)

    override val themeSettings: Flow<ThemeSettings> = _themeSettings.asStateFlow()

    override val inboxSettings: Flow<InboxSettings> = _inboxSettings.asStateFlow()

    override val phoneStatusEnabled: Flow<Boolean> = _phoneStatusEnabled.asStateFlow()

    override val phoneControlEnabled: Flow<Boolean> = _phoneControlEnabled.asStateFlow()

    override val calendarShareEnabled: Flow<Boolean> = _calendarShareEnabled.asStateFlow()

    override val calendarLastUsedId: Flow<String> = _calendarLastUsedId.asStateFlow()

    override suspend fun setThemeMode(mode: ThemeMode) {
        _themeSettings.update { it.copy(mode = mode) }
    }

    override suspend fun setThemePreset(preset: ThemePreset) {
        _themeSettings.update { it.copy(preset = preset) }
    }

    override suspend fun setCustomColors(colors: ThemeColors) {
        _themeSettings.update { it.copy(custom = colors) }
    }

    override suspend fun setInboxFollowedApps(apps: Set<String>) {
        _inboxSettings.update { it.copy(followedApps = apps.toSet()) }
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

    override suspend fun setInboxSilencedConversations(conversations: Set<String>) {
        _inboxSettings.update { it.copy(silencedConversations = conversations.toSet()) }
    }

    override suspend fun setPhoneStatusEnabled(enabled: Boolean) {
        _phoneStatusEnabled.value = enabled
    }

    override suspend fun setPhoneControlEnabled(enabled: Boolean) {
        _phoneControlEnabled.value = enabled
    }

    override suspend fun setCalendarShareEnabled(enabled: Boolean) {
        _calendarShareEnabled.value = enabled
    }

    override suspend fun setCalendarLastUsedId(calendarId: String) {
        _calendarLastUsedId.value = calendarId
    }
}
