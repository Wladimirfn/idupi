package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.domain.model.ActivityStatus
import com.idupi.app.domain.model.ChatEvent
import com.idupi.app.domain.model.MessageSender
import com.idupi.app.domain.model.ServerStatus
import com.idupi.app.domain.repository.AiModelItem
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    @Test
    fun `init loads available models from client`() = runTest {
        fake.availableModelsToReturn = listOf(AiModelItem(id = "m1", name = "Model One", provider = "acme"))

        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(fake.availableModelsToReturn, viewModel.availableModels.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `refreshModels sets errorMessage when client throws`() = runTest {
        fake.failWith = RuntimeException("models unavailable")

        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertEquals(true, viewModel.errorMessage.value!!.contains("models unavailable"))
    }

    @Test
    fun `sendMessage failure appends ERROR chat message instead of setting errorMessage`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.failWith = RuntimeException("send failed")
        viewModel.updateInputText("hello")
        viewModel.sendMessage()
        advanceUntilIdle()

        val lastMessage = viewModel.messages.value.last()
        assertEquals(MessageSender.ERROR, lastMessage.sender)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `clearError resets errorMessage to null`() = runTest {
        fake.failWith = RuntimeException("boom")
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertNotNull(viewModel.errorMessage.value)

        viewModel.clearError()

        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `cancelTask appends ERROR chat message instead of setting errorMessage`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.failWith = RuntimeException("cancel failed")
        viewModel.cancelTask()
        advanceUntilIdle()

        val lastMessage = viewModel.messages.value.last()
        assertEquals(MessageSender.ERROR, lastMessage.sender)
        assertTrue(lastMessage.text.contains("cancel failed"))
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `Thinking active true sets isThinking and message_end clears it`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertEquals(false, viewModel.isThinking.value)

        fake.emitChatEvent(ChatEvent.Thinking(active = true))
        advanceUntilIdle()
        assertEquals(true, viewModel.isThinking.value)

        fake.emitChatEvent(ChatEvent.MessageEnded("done"))
        advanceUntilIdle()
        assertEquals(false, viewModel.isThinking.value)
    }

    @Test
    fun `streaming deltas accumulate instead of replacing the bubble`() = runTest {
        // Each delta is the next fragment, not the whole message. Replacing on
        // every delta left a message being written live showing only its last
        // chunk: for a sentence ending in a period, literally a single ".".
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        for (fragment in listOf("Primero verifico", " si hay sesiones", " Pi disponibles", ".")) {
            fake.emitChatEvent(ChatEvent.AssistantDelta(fragment))
        }
        advanceUntilIdle()

        val streaming = viewModel.messages.value.last { it.sender == MessageSender.PI }
        assertEquals("Primero verifico si hay sesiones Pi disponibles.", streaming.text)
        assertTrue(streaming.isStreaming)
    }

    @Test
    fun `a second assistant message opens its own bubble instead of overwriting the first`() = runTest {
        // One Pi turn holds several assistant messages: the preamble before the
        // tools, then the answer. Both have to survive as separate messages.
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        // The chat opens with Pi's greeting, which is not part of this turn.
        val before = viewModel.messages.value.count { it.sender == MessageSender.PI }

        fake.emitChatEvent(ChatEvent.AssistantDelta("Primero verifico las sesiones."))
        fake.emitChatEvent(ChatEvent.MessageEnded("Primero verifico las sesiones."))
        advanceUntilIdle()

        fake.emitChatEvent(ChatEvent.AssistantDelta("Aviso: hice ambos trabajos inline."))
        fake.emitChatEvent(ChatEvent.MessageEnded("Aviso: hice ambos trabajos inline."))
        advanceUntilIdle()

        val turn = viewModel.messages.value.filter { it.sender == MessageSender.PI }.drop(before)
        assertEquals(2, turn.size)
        assertEquals("Primero verifico las sesiones.", turn[0].text)
        assertEquals("Aviso: hice ambos trabajos inline.", turn[1].text)
        assertTrue(turn.none { it.isStreaming })
    }

    @Test
    fun `ToolEnded correlates by id and marks the matching ToolStarted message done`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.emitChatEvent(ChatEvent.ToolStarted(toolName = "bash", message = "ls -la", id = "tool-1"))
        advanceUntilIdle()
        val running = viewModel.messages.value.last()
        assertEquals(MessageSender.TOOL, running.sender)
        assertEquals("tool-1", running.toolId)
        assertNull(running.toolOk)

        fake.emitChatEvent(ChatEvent.ToolEnded(id = "tool-1", toolName = "bash", ok = true))
        advanceUntilIdle()
        val finished = viewModel.messages.value.last()
        assertEquals("tool-1", finished.toolId)
        assertEquals(true, finished.toolOk)
    }

    @Test
    fun `ToolEnded with ok false marks the message as failed rather than done`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.emitChatEvent(ChatEvent.ToolStarted(toolName = "grep", message = "searching", id = "tool-2"))
        fake.emitChatEvent(ChatEvent.ToolEnded(id = "tool-2", toolName = "grep", ok = false))
        advanceUntilIdle()

        val finished = viewModel.messages.value.last()
        assertEquals("tool-2", finished.toolId)
        assertEquals(false, finished.toolOk)
    }

    @Test
    fun `SubagentStarted then SubagentEnded produces a SUBAGENT message marked done with the summary`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.emitChatEvent(ChatEvent.SubagentStarted(id = "sub-1", name = "reviewer", task = "review the PR"))
        advanceUntilIdle()
        val started = viewModel.messages.value.last()
        assertEquals(MessageSender.SUBAGENT, started.sender)
        assertEquals("review the PR", started.text)
        assertNull(started.toolOk)

        fake.emitChatEvent(ChatEvent.SubagentEnded(id = "sub-1", name = "reviewer", summary = "no issues found"))
        advanceUntilIdle()
        val ended = viewModel.messages.value.last()
        assertEquals(MessageSender.SUBAGENT, ended.sender)
        assertEquals("no issues found", ended.text)
        assertEquals(true, ended.toolOk)
    }

    @Test
    fun `Subagent updates and selections update live output state properly`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.emitChatEvent(ChatEvent.SubagentStarted(id = "sub-2", name = "sdd-explore", task = "explore codebase"))
        advanceUntilIdle()
        assertEquals(1, viewModel.subagents.value.size)
        assertEquals("sdd-explore", viewModel.subagents.value.first().name)
        assertEquals(true, viewModel.subagents.value.first().isRunning)

        fake.emitChatEvent(ChatEvent.SubagentUpdated(id = "sub-2", name = "sdd-explore", delta = "Scanning files..."))
        advanceUntilIdle()
        assertEquals("Scanning files...", viewModel.subagents.value.first().output)
        assertEquals("Scanning files...", viewModel.selectedSubagent.value?.output)

        fake.emitChatEvent(ChatEvent.SubagentEnded(id = "sub-2", name = "sdd-explore", summary = "Finished scan"))
        advanceUntilIdle()
        assertEquals(false, viewModel.subagents.value.first().isRunning)

        viewModel.dismissSubagentConsole()
        assertNull(viewModel.selectedSubagent.value)
    }

    // -- Live CLI activity (Change A, task 5.1) ------------------------------

    @Test
    fun `ActivityStarted opens a running entry keyed by its stable id`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.emitChatEvent(
            ChatEvent.ActivityStarted(
                id = "act-1", streamId = "s1", kind = "mcp",
                name = "playwright_browser_navigate", detail = "https://example.com",
            ),
        )
        advanceUntilIdle()

        val activity = viewModel.activities.value.single()
        assertEquals("act-1", activity.id)
        assertEquals("mcp", activity.kind)
        assertEquals("playwright_browser_navigate", activity.name)
        assertEquals(ActivityStatus.RUNNING, activity.status)
        assertEquals(false, activity.isStale)
    }

    @Test
    fun `a duplicate ActivityStarted for the same id does not open a second entry`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        val start = ChatEvent.ActivityStarted(id = "act-1", streamId = "s1", kind = "tool", name = "bash")
        fake.emitChatEvent(start)
        fake.emitChatEvent(start)
        advanceUntilIdle()

        assertEquals(1, viewModel.activities.value.size)
    }

    @Test
    fun `ActivityUpdated adds the server without replacing the original name or id`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.emitChatEvent(ChatEvent.ActivityStarted(id = "act-1", streamId = "s1", kind = "tool", name = "browser_navigate"))
        fake.emitChatEvent(ChatEvent.ActivityUpdated(id = "act-1", streamId = "s1", server = "playwright"))
        advanceUntilIdle()

        val activity = viewModel.activities.value.single()
        assertEquals("browser_navigate", activity.name)
        assertEquals("act-1", activity.id)
        assertEquals("playwright", activity.server)
    }

    @Test
    fun `a heartbeat under 20s keeps the operation open and recent`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.emitChatEvent(ChatEvent.ActivityStarted(id = "act-1", streamId = "s1", kind = "tool", name = "bash"))
        fake.emitChatEvent(
            ChatEvent.ActivityHeartbeat(
                id = "act-1", streamId = "s1",
                elapsedMs = 15_000L, sinceLastUpdateMs = 15_000L, inflight = true,
            ),
        )
        advanceUntilIdle()

        val activity = viewModel.activities.value.single()
        assertEquals(ActivityStatus.RUNNING, activity.status)
        assertEquals(15_000L, activity.elapsedMs)
        assertEquals(false, activity.isStale)
    }

    @Test
    fun `a heartbeat at or past 20s without a provider update reads as stale`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.emitChatEvent(ChatEvent.ActivityStarted(id = "act-1", streamId = "s1", kind = "tool", name = "bash"))
        fake.emitChatEvent(
            ChatEvent.ActivityHeartbeat(
                id = "act-1", streamId = "s1",
                elapsedMs = 20_000L, sinceLastUpdateMs = 20_000L, inflight = true,
            ),
        )
        advanceUntilIdle()

        val activity = viewModel.activities.value.single()
        assertEquals(ActivityStatus.RUNNING, activity.status)
        assertEquals(true, activity.isStale)
    }

    @Test
    fun `ActivityEnded terminalizes with its ok outcome`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.emitChatEvent(ChatEvent.ActivityStarted(id = "act-1", streamId = "s1", kind = "tool", name = "bash"))
        fake.emitChatEvent(ChatEvent.ActivityEnded(id = "act-1", streamId = "s1", ok = true))
        advanceUntilIdle()
        assertEquals(ActivityStatus.OK, viewModel.activities.value.single().status)

        fake.emitChatEvent(ChatEvent.ActivityStarted(id = "act-2", streamId = "s1", kind = "tool", name = "grep"))
        fake.emitChatEvent(ChatEvent.ActivityEnded(id = "act-2", streamId = "s1", ok = false))
        advanceUntilIdle()
        assertEquals(ActivityStatus.FAILED, viewModel.activities.value.last().status)
    }

    @Test
    fun `ActivityFailed and ActivityTimedOut are distinct terminal states`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.emitChatEvent(ChatEvent.ActivityStarted(id = "f1", streamId = "s1", kind = "tool", name = "bash"))
        fake.emitChatEvent(ChatEvent.ActivityFailed(id = "f1", streamId = "s1", errorClass = "tool"))
        fake.emitChatEvent(ChatEvent.ActivityStarted(id = "t1", streamId = "s1", kind = "mcp", name = "slow_mcp"))
        fake.emitChatEvent(ChatEvent.ActivityTimedOut(id = "t1", streamId = "s1"))
        advanceUntilIdle()

        val byId = viewModel.activities.value.associateBy { it.id }
        assertEquals(ActivityStatus.FAILED, byId.getValue("f1").status)
        assertEquals(ActivityStatus.TIMED_OUT, byId.getValue("t1").status)
    }

    @Test
    fun `a heartbeat arriving after a terminal frame never reopens the operation`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.emitChatEvent(ChatEvent.ActivityStarted(id = "act-1", streamId = "s1", kind = "tool", name = "bash"))
        fake.emitChatEvent(ChatEvent.ActivityEnded(id = "act-1", streamId = "s1", ok = true))
        fake.emitChatEvent(
            ChatEvent.ActivityHeartbeat(
                id = "act-1", streamId = "s1",
                elapsedMs = 90_000L, sinceLastUpdateMs = 90_000L, inflight = true,
            ),
        )
        advanceUntilIdle()

        val activity = viewModel.activities.value.single()
        assertEquals(ActivityStatus.OK, activity.status)
        assertEquals(false, activity.isStale)
    }

    @Test
    fun `frames for an unknown id are dropped instead of inventing an entry`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.emitChatEvent(ChatEvent.ActivityUpdated(id = "ghost", streamId = "s1", server = "nowhere"))
        fake.emitChatEvent(ChatEvent.ActivityEnded(id = "ghost", streamId = "s1", ok = true))
        advanceUntilIdle()

        assertTrue(viewModel.activities.value.isEmpty())
    }

    // -- loadSessionHistory: session-resume sync hook -------------------------
    //
    // The caller (AppNavigation) refreshes the shared selector state + chat
    // model list AFTER the resume completes, because POST /sessions/resume
    // switches the server's activeEngine to the resumed session's engine.
    // `onResumed` is the sequencing hook: it must fire only once the resume +
    // post-resume status sync have run, and never on a failed resume.

    @Test
    fun `loadSessionHistory invokes onResumed after a successful resume`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        var resumed = false
        viewModel.loadSessionHistory("sess-1") { resumed = true }
        advanceUntilIdle()

        assertTrue("onResumed must run once the session is resumed", resumed)
    }

    @Test
    fun `loadSessionHistory does NOT invoke onResumed when the resume fails`() = runTest {
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.failWith = RuntimeException("pi is busy")
        var resumed = false
        viewModel.loadSessionHistory("sess-1") { resumed = true }
        advanceUntilIdle()

        assertEquals("a failed resume must not fire the sync hook", false, resumed)
        val last = viewModel.messages.value.last()
        assertEquals(MessageSender.ERROR, last.sender)
        assertTrue(last.text.contains("pi is busy"))
    }

    @Test
    fun `onResumed fires only AFTER the post-resume status sync`() = runTest {
        // The hook must not run before the server state is read: the caller
        // refreshes the shared status from it. Prove ordering by observing the
        // status-derived `isThinking` inside the callback.
        val viewModel = ChatViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.statusToReturn = ServerStatus(
            connected = true,
            pcName = "fake-pc",
            project = "fake-project",
            agent = "fake-agent",
            busy = true,
            queueSize = 0,
        )
        var thinkingAtCallback: Boolean? = null
        viewModel.loadSessionHistory("sess-1") {
            thinkingAtCallback = viewModel.isThinking.value
        }
        advanceUntilIdle()

        assertEquals(
            "the status sync (busy=true) must already be applied when onResumed runs",
            true, thinkingAtCallback,
        )
    }
}
