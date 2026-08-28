package com.idupi.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two display rules the chat got wrong in the first live run:
 *
 *  - every finished operation kept a row, so a fan-out produced a wall of
 *    identical "listo" lines that buried the one still running;
 *  - the subagent strip rendered in arrival order, so the newest delegation --
 *    the only one worth looking at -- sat at the far end of the row.
 */
class ActivityOrderingTest {

    private fun activity(id: String, status: ActivityStatus) =
        ActivityUiState(id = id, streamId = "s1", kind = "tool", name = id, status = status)

    @Test
    fun `only running operations stay on the live bar`() {
        val visible = liveActivities(
            listOf(
                activity("a", ActivityStatus.OK),
                activity("b", ActivityStatus.RUNNING),
                activity("c", ActivityStatus.FAILED),
                activity("d", ActivityStatus.TIMED_OUT),
            ),
        )
        assertEquals(listOf("b"), visible.map { it.id })
    }

    @Test
    fun `a bar with nothing running renders nothing at all`() {
        val visible = liveActivities(listOf(activity("a", ActivityStatus.OK), activity("b", ActivityStatus.OK)))
        assertTrue(visible.isEmpty())
    }

    @Test
    fun `running operations keep the order they started in`() {
        val visible = liveActivities(
            listOf(
                activity("first", ActivityStatus.RUNNING),
                activity("done", ActivityStatus.OK),
                activity("second", ActivityStatus.RUNNING),
            ),
        )
        assertEquals(listOf("first", "second"), visible.map { it.id })
    }

    private fun subagent(id: String, running: Boolean, startedAt: Long) =
        SubagentLiveState(id = id, name = id, isRunning = running, startTime = startedAt)

    @Test
    fun `running subagents come before finished ones`() {
        val ordered = orderedSubagents(
            listOf(
                subagent("old-done", running = false, startedAt = 100),
                subagent("running", running = true, startedAt = 50),
            ),
        )
        assertEquals(listOf("running", "old-done"), ordered.map { it.id })
    }

    @Test
    fun `the most recent comes first within each group`() {
        val ordered = orderedSubagents(
            listOf(
                subagent("run-old", running = true, startedAt = 10),
                subagent("done-old", running = false, startedAt = 20),
                subagent("run-new", running = true, startedAt = 30),
                subagent("done-new", running = false, startedAt = 40),
            ),
        )
        assertEquals(listOf("run-new", "run-old", "done-new", "done-old"), ordered.map { it.id })
    }

    @Test
    fun `ordering never drops or duplicates a subagent`() {
        val input = listOf(
            subagent("a", running = true, startedAt = 1),
            subagent("b", running = false, startedAt = 2),
            subagent("c", running = true, startedAt = 3),
        )
        val ordered = orderedSubagents(input)
        assertEquals(input.size, ordered.size)
        assertEquals(input.map { it.id }.toSet(), ordered.map { it.id }.toSet())
    }
}
