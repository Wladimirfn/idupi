package com.idupi.app.data.settings

import com.idupi.app.domain.model.InboxSettings
import com.idupi.app.domain.model.ThemeColors
import com.idupi.app.domain.model.ThemeMode
import com.idupi.app.domain.model.ThemePreset
import com.idupi.app.domain.model.ThemeSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * In-memory settings contract. The DataStore implementation must satisfy the
 * same observable behaviour; this one is what ViewModel tests use.
 */
class InMemorySettingsRepositoryTest {

    @Test
    fun `fresh repository defaults to dark indigo`() = runTest {
        val repo = InMemorySettingsRepository()

        assertEquals(ThemeSettings(), repo.themeSettings.first())
    }

    @Test
    fun `setThemeMode updates the observed value`() = runTest {
        val repo = InMemorySettingsRepository()

        repo.setThemeMode(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, repo.themeSettings.first().mode)
    }

    @Test
    fun `setThemePreset keeps the mode and updates only the accent`() = runTest {
        val repo = InMemorySettingsRepository()

        repo.setThemeMode(ThemeMode.LIGHT)
        repo.setThemePreset(ThemePreset.AMBER)

        val settings = repo.themeSettings.first()
        assertEquals(ThemeMode.LIGHT, settings.mode)
        assertEquals(ThemePreset.AMBER, settings.preset)
    }

    @Test
    fun `setCustomColors round-trips every argb value`() = runTest {
        val repo = InMemorySettingsRepository()
        val custom = ThemeColors(
            accentArgb = 0xFF10B981.toInt(),
            backgroundArgb = 0xFFF8FAFC.toInt(),
            cardArgb = 0xFFFFFFFF.toInt(),
            textArgb = 0xFF0F172A.toInt(),
        )

        repo.setCustomColors(custom)

        assertEquals(custom, repo.themeSettings.first().custom)
    }

    @Test
    fun `fresh repository follows the default apps and leaves auto-reply off`() = runTest {
        val settings = InMemorySettingsRepository().inboxSettings.first()

        assertEquals(InboxSettings(), settings)
        assertEquals(6, settings.followedApps.size)
        assertEquals(false, settings.autoReplyEnabled)
    }

    @Test
    fun `inbox setters update only their own field`() = runTest {
        val repo = InMemorySettingsRepository()

        repo.setInboxFollowedApps(setOf("gmail"))
        repo.setInboxReminderIntervalMinutes(5)
        repo.setInboxRemindMinImportance("medium")
        repo.setInboxAutoReplyEnabled(true)
        repo.setInboxAutoReplyDelayMinutes(10)
        repo.setInboxAutoReplyTemplate("Plantilla")

        val settings = repo.inboxSettings.first()
        assertEquals(setOf("gmail"), settings.followedApps)
        assertEquals(5, settings.reminderIntervalMinutes)
        assertEquals("medium", settings.remindMinImportance)
        assertEquals(true, settings.autoReplyEnabled)
        assertEquals(10, settings.autoReplyDelayMinutes)
        assertEquals("Plantilla", settings.autoReplyTemplate)
    }

    @Test
    fun `an unknown reminder importance reads back as high`() = runTest {
        val repo = InMemorySettingsRepository()

        repo.setInboxRemindMinImportance("urgent")

        assertEquals("high", repo.inboxSettings.first().remindMinImportance)
    }

    @Test
    fun `phone control is off by default and toggles independently`() = runTest {
        val repo = InMemorySettingsRepository()

        assertEquals(false, repo.phoneControlEnabled.first())

        repo.setPhoneControlEnabled(true)

        assertEquals(true, repo.phoneControlEnabled.first())
        assertEquals(true, repo.phoneStatusEnabled.first())
    }

    @Test
    fun `silenced conversations default to empty and toggle one key`() = runTest {
        val repo = InMemorySettingsRepository()

        assertEquals(emptySet<String>(), repo.inboxSettings.first().silencedConversations)

        repo.setInboxSilencedConversations(setOf("whatsapp|Equipo", "telegram|Grupo"))
        assertEquals(
            setOf("whatsapp|Equipo", "telegram|Grupo"),
            repo.inboxSettings.first().silencedConversations
        )

        repo.setInboxSilencedConversations(setOf("telegram|Grupo"))
        assertEquals(
            setOf("telegram|Grupo"),
            repo.inboxSettings.first().silencedConversations
        )
    }
}
