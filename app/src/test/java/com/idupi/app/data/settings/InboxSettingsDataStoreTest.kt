package com.idupi.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.idupi.app.domain.model.InboxApp
import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Persistence contract for the phone-side Alertas settings (phone-inbox T2).
 *
 * These values drive what gets captured and when the phone interrupts the
 * owner, so they MUST survive an app restart. A second repository instance over
 * the same file is how that invariant is pinned without an Android Context,
 * exactly like [DataStoreSettingsRepositoryTest] does for the theme.
 */
class InboxSettingsDataStoreTest {

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
    fun `an empty DataStore follows the default apps and leaves auto-reply off`() = runTest {
        val settings = repo.inboxSettings.first()

        assertEquals(InboxApp.defaultFollowedIds, settings.followedApps)
        assertEquals(6, settings.followedApps.size)
        assertTrue(settings.followedApps.containsAll(setOf("whatsapp", "messenger", "telegram")))
        assertEquals(15, settings.reminderIntervalMinutes)
        assertEquals(InboxImportance.HIGH, settings.remindMinImportance)
        assertFalse(settings.autoReplyEnabled)
        assertEquals(30, settings.autoReplyDelayMinutes)
        assertEquals(InboxSettings.DEFAULT_AUTO_REPLY_TEMPLATE, settings.autoReplyTemplate)
    }

    @Test
    fun `every inbox setting persists across a new repository instance`() = runTest {
        repo.setInboxFollowedApps(setOf(InboxApp.WHATSAPP.id, InboxApp.OUTLOOK.id))
        repo.setInboxReminderIntervalMinutes(5)
        repo.setInboxRemindMinImportance(InboxImportance.MEDIUM)
        repo.setInboxAutoReplyEnabled(true)
        repo.setInboxAutoReplyDelayMinutes(10)
        repo.setInboxAutoReplyTemplate("Estoy en una reunion, te escribo luego.")

        val reopened = reopen().inboxSettings.first()

        assertEquals(setOf("whatsapp", "outlook"), reopened.followedApps)
        assertEquals(5, reopened.reminderIntervalMinutes)
        assertEquals(InboxImportance.MEDIUM, reopened.remindMinImportance)
        assertTrue(reopened.autoReplyEnabled)
        assertEquals(10, reopened.autoReplyDelayMinutes)
        assertEquals("Estoy en una reunion, te escribo luego.", reopened.autoReplyTemplate)
    }

    @Test
    fun `monitoring nothing is a real choice and survives a reopen`() = runTest {
        repo.setInboxFollowedApps(emptySet())

        assertTrue(reopen().inboxSettings.first().followedApps.isEmpty())
    }

    @Test
    fun `writing one inbox key keeps the other choices intact`() = runTest {
        repo.setInboxFollowedApps(setOf(InboxApp.GMAIL.id))
        repo.setInboxAutoReplyEnabled(true)
        repo.setInboxAutoReplyTemplate("Plantilla propia")

        repo.setInboxReminderIntervalMinutes(45)

        val settings = repo.inboxSettings.first()
        assertEquals(45, settings.reminderIntervalMinutes)
        assertEquals(setOf(InboxApp.GMAIL.id), settings.followedApps)
        assertTrue(settings.autoReplyEnabled)
        assertEquals("Plantilla propia", settings.autoReplyTemplate)
    }

    @Test
    fun `an unknown stored importance falls back to high`() = runTest {
        store.edit { prefs ->
            prefs[DataStoreSettingsRepository.INBOX_REMIND_MIN_IMPORTANCE_KEY] = "urgent"
        }

        assertEquals(InboxImportance.HIGH, repo.inboxSettings.first().remindMinImportance)
    }

    @Test
    fun `a zero reminder interval is clamped instead of persisted`() = runTest {
        repo.setInboxReminderIntervalMinutes(0)

        assertEquals(1, reopen().inboxSettings.first().reminderIntervalMinutes)
    }

    @Test
    fun `inbox settings do not disturb the theme and vice versa`() = runTest {
        repo.setInboxReminderIntervalMinutes(5)

        assertEquals(
            com.idupi.app.domain.model.ThemeSettings(),
            repo.themeSettings.first()
        )

        repo.setInboxReminderIntervalMinutes(20)
        assertEquals(20, repo.inboxSettings.first().reminderIntervalMinutes)
    }

    @Test
    fun `silenced conversations survive a restart and start empty`() = runTest {
        assertEquals(emptySet<String>(), repo.inboxSettings.first().silencedConversations)

        repo.setInboxSilencedConversations(setOf("whatsapp|Equipo", "telegram|Grupo"))

        assertEquals(
            setOf("whatsapp|Equipo", "telegram|Grupo"),
            reopen().inboxSettings.first().silencedConversations
        )
    }

    @Test
    fun `changing other settings keeps the silenced list`() = runTest {
        repo.setInboxSilencedConversations(setOf("whatsapp|Equipo"))

        repo.setInboxReminderIntervalMinutes(30)

        assertEquals(
            setOf("whatsapp|Equipo"),
            reopen().inboxSettings.first().silencedConversations
        )
    }
}
