package com.example.idupi.viewmodel

import com.example.idupi.FakeClientSource
import com.example.idupi.FakeIduPiClient
import com.example.idupi.MainDispatcherRule
import com.example.idupi.domain.model.ChatEvent
import com.example.idupi.domain.model.MessageSender
import com.example.idupi.domain.repository.AiModelItem
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
}
