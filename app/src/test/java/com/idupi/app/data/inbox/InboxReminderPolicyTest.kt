package com.idupi.app.data.inbox

import com.idupi.app.domain.model.InboxApp
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxItemStatus
import com.idupi.app.domain.model.InboxSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Exhaustive JVM coverage of the reminder/auto-reply rules (phone-inbox T4):
 * time math, importance gates, app/state gates, the template-only decision and
 * idempotence across ticks.
 */
class InboxReminderPolicyTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun minutesAgo(minutes: Long): String =
        Instant.ofEpochMilli(now - minutes * minute).toString()

    private fun item(
        id: String = "i1",
        app: String = InboxApp.WHATSAPP.id,
        state: String = InboxItemState.OPEN,
        status: String = InboxItemStatus.READY,
        importance: String? = "high",
        receivedAt: String = minutesAgo(60),
        postedAt: String = minutesAgo(60),
        conversation: String = "Ana",
        suggestedReply: String = "AI generated reply",
        updatedAt: String = "",
    ) = InboxItem(
        id = id,
        app = app,
        sender = "Ana",
        conversation = conversation,
        text = "Hola",
        postedAt = postedAt,
        receivedAt = receivedAt,
        status = status,
        importance = importance,
        suggestedReply = suggestedReply,
        state = state,
        updatedAt = updatedAt
    )

    private fun settings(
        intervalMinutes: Int = 15,
        minImportance: String = "high",
        autoReply: Boolean = false,
        autoReplyDelayMinutes: Int = 30,
        template: String = "Hola, te contesto apenas pueda.",
        apps: Set<String> = InboxApp.defaultFollowedIds,
    ) = InboxSettings(
        followedApps = apps,
        reminderIntervalMinutes = intervalMinutes,
        remindMinImportance = minImportance,
        autoReplyEnabled = autoReply,
        autoReplyDelayMinutes = autoReplyDelayMinutes,
        autoReplyTemplate = template
    )

    private fun decide(
        items: List<InboxItem>,
        settings: InboxSettings = settings(),
        states: Map<String, InboxReminderPolicy.ItemState> = emptyMap(),
        liveActions: Set<String> = emptySet(),
        silenced: Set<String> = emptySet(),
    ): List<InboxReminderPolicy.Action> = InboxReminderPolicy.decide(
        items = items,
        settings = settings,
        nowMillis = now,
        stateFor = { states[it] ?: InboxReminderPolicy.ItemState() },
        hasLiveReplyAction = { it in liveActions },
        isSilencedConversation = { it in silenced }
    )

    private fun conversationKey(app: String = InboxApp.WHATSAPP.id, conversation: String = "Ana") =
        inboxConversationKey(app, conversation)

    // --- Reminder timing ---

    @Test
    fun `reminds an open high item once the interval elapsed`() {
        val actions = decide(listOf(item(receivedAt = minutesAgo(60))))

        assertEquals(listOf<InboxReminderPolicy.Action>(InboxReminderPolicy.Action.Remind("i1")), actions)
    }

    @Test
    fun `does not remind before the interval`() {
        val actions = decide(
            items = listOf(item(receivedAt = minutesAgo(10))),
            settings = settings(intervalMinutes = 15)
        )

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `reminds exactly at the interval boundary`() {
        val actions = decide(
            items = listOf(item(receivedAt = minutesAgo(15))),
            settings = settings(intervalMinutes = 15)
        )

        assertEquals(1, actions.size)
    }

    @Test
    fun `a previous reminder delays the next one`() {
        val alreadyReminded = mapOf(
            "i1" to InboxReminderPolicy.ItemState(lastRemindedAtMillis = now - 5 * minute)
        )

        assertTrue(
            decide(
                items = listOf(item(receivedAt = minutesAgo(120))),
                settings = settings(intervalMinutes = 15),
                states = alreadyReminded
            ).isEmpty()
        )
    }

    @Test
    fun `the next reminder fires interval after the last one`() {
        val actions = decide(
            items = listOf(item(receivedAt = minutesAgo(120))),
            settings = settings(intervalMinutes = 15),
            states = mapOf(
                "i1" to InboxReminderPolicy.ItemState(lastRemindedAtMillis = now - 20 * minute)
            )
        )

        assertEquals(listOf<InboxReminderPolicy.Action>(InboxReminderPolicy.Action.Remind("i1")), actions)
    }

    @Test
    fun `a stale last reminder does not skip the first remind`() {
        // max(receivedAt, lastRemindedAt) must be receivedAt here: the item is
        // newer than any state left over from the same id.
        val actions = decide(
            items = listOf(item(receivedAt = minutesAgo(10))),
            settings = settings(intervalMinutes = 15),
            states = mapOf(
                "i1" to InboxReminderPolicy.ItemState(lastRemindedAtMillis = now - 100 * minute)
            )
        )

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `receivedAt wins over postedAt and postedAt is the fallback`() {
        assertTrue(
            decide(listOf(item(receivedAt = minutesAgo(5), postedAt = minutesAgo(500)))).isEmpty()
        )
        assertEquals(
            1,
            decide(listOf(item(receivedAt = "", postedAt = minutesAgo(60)))).size
        )
    }

    @Test
    fun `unparseable timestamps produce no actions`() {
        assertTrue(decide(listOf(item(receivedAt = "ayer", postedAt = ""))).isEmpty())
        assertTrue(decide(listOf(item(receivedAt = "", postedAt = "not-a-date"))).isEmpty())
    }

    // --- Eligibility gates ---

    @Test
    fun `only open items produce actions`() {
        val closed = listOf(
            InboxItemState.HANDLED,
            InboxItemState.REPLIED,
            InboxItemState.AUTO_REPLIED,
            InboxItemState.DISMISSED
        ).map { state -> item(id = state, state = state) }

        assertTrue(decide(closed).isEmpty())
    }

    @Test
    fun `an app the owner stopped following produces no actions`() {
        val actions = decide(
            items = listOf(item(app = InboxApp.GMAIL.id)),
            settings = settings(apps = setOf(InboxApp.WHATSAPP.id))
        )

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `an unfollowed app is never processed`() {
        assertTrue(decide(listOf(item(app = "com.spotify.music"))).isEmpty())
    }

    // --- Importance gate ---

    @Test
    fun `high gate lets only high through`() {
        assertTrue(decide(listOf(item(importance = "medium"))).isEmpty())
        assertTrue(decide(listOf(item(importance = "low"))).isEmpty())
        assertEquals(1, decide(listOf(item(importance = "high"))).size)
    }

    @Test
    fun `medium gate lets medium and high through but not low`() {
        val mediumGate = settings(minImportance = "medium")
        assertEquals(1, decide(listOf(item(importance = "medium")), mediumGate).size)
        assertEquals(1, decide(listOf(item(importance = "high")), mediumGate).size)
        assertTrue(decide(listOf(item(importance = "low")), mediumGate).isEmpty())
    }

    @Test
    fun `analyzing and failed items count as medium`() {
        val analyzing = item(importance = null, status = InboxItemStatus.ANALYZING)
        val failed = item(importance = null, status = InboxItemStatus.FAILED)

        assertTrue(decide(listOf(analyzing)).isEmpty())
        assertTrue(decide(listOf(failed)).isEmpty())
        assertEquals(
            1,
            decide(listOf(analyzing), settings(minImportance = "medium")).size
        )
        assertEquals(
            1,
            decide(listOf(failed), settings(minImportance = "medium")).size
        )
    }

    @Test
    fun `a ready item without importance is low and never reminds`() {
        assertTrue(
            decide(
                listOf(item(importance = null, status = InboxItemStatus.READY)),
                settings(minImportance = "medium")
            ).isEmpty()
        )
    }

    @Test
    fun `an unknown stored gate level falls back to high`() {
        // InboxSettings.normalizeImportance maps anything else to high, matching
        // the DataStore reader, so a corrupt setting stays strict.
        val corrupt = settings(minImportance = "urgent")
        assertTrue(decide(listOf(item(importance = "medium")), corrupt).isEmpty())
        assertEquals(1, decide(listOf(item(importance = "high")), corrupt).size)
    }

    // --- Auto-reply ---

    @Test
    fun `auto-reply sends the fixed template and never the AI suggestion`() {
        val actions = decide(
            items = listOf(item(receivedAt = minutesAgo(60), suggestedReply = "Texto de la IA")),
            settings = settings(autoReply = true, template = "Plantilla del dueño"),
            liveActions = setOf(conversationKey())
        )

        assertEquals(
            listOf<InboxReminderPolicy.Action>(
                InboxReminderPolicy.Action.AutoReply("i1", "Plantilla del dueño")
            ),
            actions
        )
        assertFalse(actions.any { it is InboxReminderPolicy.Action.AutoReply && it.text.contains("IA") })
    }

    @Test
    fun `auto-reply waits for the configured delay`() {
        val actions = decide(
            items = listOf(item(receivedAt = minutesAgo(10))),
            settings = settings(intervalMinutes = 5, autoReply = true, autoReplyDelayMinutes = 30),
            liveActions = setOf(conversationKey())
        )

        assertEquals(listOf<InboxReminderPolicy.Action>(InboxReminderPolicy.Action.Remind("i1")), actions)
    }

    @Test
    fun `auto-reply fires exactly at the delay boundary`() {
        val actions = decide(
            items = listOf(item(receivedAt = minutesAgo(30))),
            settings = settings(autoReply = true, autoReplyDelayMinutes = 30),
            liveActions = setOf(conversationKey())
        )

        assertEquals(
            listOf<InboxReminderPolicy.Action>(InboxReminderPolicy.Action.AutoReply("i1", "Hola, te contesto apenas pueda.")),
            actions
        )
    }

    @Test
    fun `auto-reply is disabled by default`() {
        val actions = decide(items = listOf(item(receivedAt = minutesAgo(60))), liveActions = setOf(conversationKey()))

        assertFalse(actions.any { it is InboxReminderPolicy.Action.AutoReply })
    }

    @Test
    fun `mail is never auto-answered`() {
        val actions = decide(
            items = listOf(item(app = InboxApp.GMAIL.id, receivedAt = minutesAgo(60))),
            settings = settings(autoReply = true),
            liveActions = setOf(conversationKey(app = InboxApp.GMAIL.id))
        )

        assertFalse(actions.any { it is InboxReminderPolicy.Action.AutoReply })
        assertEquals(listOf<InboxReminderPolicy.Action>(InboxReminderPolicy.Action.Remind("i1")), actions)
    }

    @Test
    fun `auto-reply respects the importance gate`() {
        val actions = decide(
            items = listOf(item(importance = "medium", receivedAt = minutesAgo(60))),
            settings = settings(autoReply = true),
            liveActions = setOf(conversationKey())
        )

        assertFalse(actions.any { it is InboxReminderPolicy.Action.AutoReply })
    }

    @Test
    fun `auto-reply requires a live reply action and keeps reminding without one`() {
        val actions = decide(
            items = listOf(item(receivedAt = minutesAgo(60))),
            settings = settings(autoReply = true)
        )

        assertEquals(listOf<InboxReminderPolicy.Action>(InboxReminderPolicy.Action.Remind("i1")), actions)
    }

    @Test
    fun `auto-reply does not repeat once done`() {
        val actions = decide(
            items = listOf(item(receivedAt = minutesAgo(120))),
            settings = settings(autoReply = true),
            states = mapOf("i1" to InboxReminderPolicy.ItemState(autoReplyDone = true)),
            liveActions = setOf(conversationKey())
        )

        assertFalse(actions.any { it is InboxReminderPolicy.Action.AutoReply })
    }

    @Test
    fun `a blank template never auto-replies`() {
        val actions = decide(
            items = listOf(item(receivedAt = minutesAgo(60))),
            settings = settings(autoReply = true, template = "   "),
            liveActions = setOf(conversationKey())
        )

        assertFalse(actions.any { it is InboxReminderPolicy.Action.AutoReply })
    }

    @Test
    fun `at most one auto-reply per conversation per tick`() {
        val actions = decide(
            items = listOf(
                item(id = "newer", receivedAt = minutesAgo(45)),
                item(id = "older", receivedAt = minutesAgo(90))
            ),
            settings = settings(autoReply = true),
            liveActions = setOf(conversationKey())
        )

        val replies = actions.filterIsInstance<InboxReminderPolicy.Action.AutoReply>()
        assertEquals(1, replies.size)
        assertEquals("newer", replies.single().itemId)
    }

    @Test
    fun `a recent auto-reply in the conversation blocks another one`() {
        val actions = decide(
            items = listOf(
                item(id = "answered", state = InboxItemState.AUTO_REPLIED,
                    receivedAt = minutesAgo(90), updatedAt = minutesAgo(50)),
                item(id = "burst", receivedAt = minutesAgo(45))
            ),
            settings = settings(autoReply = true),
            liveActions = setOf(conversationKey())
        )

        assertTrue(actions.none { it is InboxReminderPolicy.Action.AutoReply })
    }

    @Test
    fun `an auto-reply older than the cooldown allows a new one`() {
        val cooldownMinutes = InboxReminderPolicy.AUTO_REPLY_COOLDOWN_MILLIS / minute
        val actions = decide(
            items = listOf(
                item(id = "answered", state = InboxItemState.AUTO_REPLIED,
                    receivedAt = minutesAgo(cooldownMinutes + 60),
                    updatedAt = minutesAgo(cooldownMinutes + 1)),
                item(id = "new", receivedAt = minutesAgo(45))
            ),
            settings = settings(autoReply = true),
            liveActions = setOf(conversationKey())
        )

        val replies = actions.filterIsInstance<InboxReminderPolicy.Action.AutoReply>()
        assertEquals(listOf("new"), replies.map { it.itemId })
    }

    @Test
    fun `a recent auto-reply only blocks its own conversation`() {
        val actions = decide(
            items = listOf(
                item(id = "answered", conversation = "Ana", state = InboxItemState.AUTO_REPLIED,
                    receivedAt = minutesAgo(90), updatedAt = minutesAgo(50)),
                item(id = "beto", conversation = "Beto", receivedAt = minutesAgo(45))
            ),
            settings = settings(autoReply = true),
            liveActions = setOf(conversationKey(conversation = "Ana"), conversationKey(conversation = "Beto"))
        )

        val replies = actions.filterIsInstance<InboxReminderPolicy.Action.AutoReply>()
        assertEquals(listOf("beto"), replies.map { it.itemId })
    }

    @Test
    fun `different conversations each get their own auto-reply`() {
        val actions = decide(
            items = listOf(
                item(id = "a", conversation = "Ana", receivedAt = minutesAgo(60)),
                item(id = "b", conversation = "Beto", receivedAt = minutesAgo(60))
            ),
            settings = settings(autoReply = true),
            liveActions = setOf(conversationKey(conversation = "Ana"), conversationKey(conversation = "Beto"))
        )

        assertEquals(
            setOf("a", "b"),
            actions.filterIsInstance<InboxReminderPolicy.Action.AutoReply>().map { it.itemId }.toSet()
        )
    }

    @Test
    fun `an auto-replied item is not reminded in the same tick`() {
        val actions = decide(
            items = listOf(item(receivedAt = minutesAgo(60))),
            settings = settings(autoReply = true),
            liveActions = setOf(conversationKey())
        )

        assertFalse(actions.any { it is InboxReminderPolicy.Action.Remind })
    }

    // --- Idempotence ---

    @Test
    fun `recording the last reminder makes the next tick silent`() {
        val first = decide(listOf(item(receivedAt = minutesAgo(20))))
        assertEquals(listOf<InboxReminderPolicy.Action>(InboxReminderPolicy.Action.Remind("i1")), first)

        val second = decide(
            items = listOf(item(receivedAt = minutesAgo(20))),
            states = mapOf("i1" to InboxReminderPolicy.ItemState(lastRemindedAtMillis = now))
        )
        assertTrue(second.isEmpty())
    }

    @Test
    fun `auto-reply state and item state both silence later ticks`() {
        val done = mapOf("i1" to InboxReminderPolicy.ItemState(autoReplyDone = true))

        assertFalse(decide(listOf(item(receivedAt = minutesAgo(60))), settings(autoReply = true), done, setOf(conversationKey())).any {
            it is InboxReminderPolicy.Action.AutoReply
        })
        assertTrue(decide(listOf(item(state = InboxItemState.AUTO_REPLIED))).isEmpty())
    }

    // --- silence but read (inbox-management T4) ----------------------------

    @Test
    fun `a silenced conversation gets no reminder below high importance`() {
        val mediumGate = settings(minImportance = "medium")
        val medium = item(importance = "medium")

        assertTrue(decide(listOf(medium), mediumGate).any {
            it is InboxReminderPolicy.Action.Remind
        })
        assertTrue(decide(
            items = listOf(medium),
            settings = mediumGate,
            silenced = setOf(conversationKey())
        ).isEmpty())
    }

    @Test
    fun `a silenced conversation still reminds at high importance`() {
        val actions = decide(
            items = listOf(item(importance = "high")),
            silenced = setOf(conversationKey())
        )

        assertEquals(
            listOf(InboxReminderPolicy.Action.Remind("i1")),
            actions
        )
    }

    @Test
    fun `silencing one conversation does not silence another`() {
        val other = item(id = "i2", conversation = "Beto", importance = "medium")

        val actions = decide(
            items = listOf(item(importance = "medium"), other),
            settings = settings(minImportance = "medium"),
            silenced = setOf(conversationKey())
        )

        assertEquals(listOf(InboxReminderPolicy.Action.Remind("i2")), actions)
    }

    @Test
    fun `silencing does not block the template auto-reply`() {
        val actions = decide(
            items = listOf(item(receivedAt = minutesAgo(60))),
            settings = settings(autoReply = true),
            liveActions = setOf(conversationKey()),
            silenced = setOf(conversationKey())
        )

        assertEquals(1, actions.count { it is InboxReminderPolicy.Action.AutoReply })
    }
}
