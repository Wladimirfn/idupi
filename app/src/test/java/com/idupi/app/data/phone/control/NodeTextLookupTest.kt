package com.idupi.app.data.phone.control

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Text extraction used by the phone-side risky-node check (phone-approvals T2).
 * A button label often lives in a child TextView, and a coordinate tap has no
 * node id, so both paths need the visible text around the resolved node.
 */
class NodeTextLookupTest {

    private val sendButton = NodeSnapshot(
        className = "android.widget.Button",
        text = "Enviar",
        desc = "Enviar mensaje",
        clickable = true,
        bounds = NodeBounds(900, 2000, 160, 100),
    )

    private val row = NodeSnapshot(
        className = "android.widget.LinearLayout",
        clickable = true,
        bounds = NodeBounds(0, 1900, 1080, 300),
        children = listOf(sendButton),
    )

    private val root = NodeSnapshot(
        className = "android.widget.FrameLayout",
        bounds = NodeBounds(0, 0, 1080, 2400),
        children = listOf(row),
    )

    @Test
    fun `text candidates include the node and its descendants`() {
        assertEquals(
            listOf("Enviar", "Enviar mensaje"),
            NodeMatcher.textCandidates(row),
        )
    }

    @Test
    fun `text candidates skip empty text and descriptions`() {
        assertEquals(
            emptyList<String>(),
            NodeMatcher.textCandidates(NodeSnapshot(className = "android.view.View")),
        )
    }

    @Test
    fun `a coordinate on the button resolves to the button text`() {
        assertEquals(
            listOf("Enviar", "Enviar mensaje"),
            NodeMatcher.textsAtPoint(listOf(root), 950, 2050),
        )
    }

    @Test
    fun `a coordinate outside every node resolves to nothing`() {
        assertEquals(
            emptyList<String>(),
            NodeMatcher.textsAtPoint(listOf(root), 5000, 5000),
        )
    }

    @Test
    fun `an untagged container falls back to the nearest annotated ancestor`() {
        val bare = NodeSnapshot(
            className = "android.view.View",
            bounds = NodeBounds(10, 10, 40, 40),
        )
        val annotated = NodeSnapshot(
            className = "android.widget.LinearLayout",
            text = "Transferir",
            bounds = NodeBounds(0, 0, 200, 200),
            children = listOf(bare),
        )

        assertEquals(listOf("Transferir"), NodeMatcher.textsAtPoint(listOf(annotated), 20, 20))
    }
}
