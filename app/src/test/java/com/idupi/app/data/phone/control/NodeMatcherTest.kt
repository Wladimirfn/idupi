package com.idupi.app.data.phone.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tap target resolution (phone-agent T3): exact-before-contains, view ids,
 * nearest clickable ancestor and the ambiguity rule that refuses a wrong tap.
 */
class NodeMatcherTest {

    private fun node(
        text: String = "",
        desc: String = "",
        viewId: String = "",
        clickable: Boolean = false,
        visible: Boolean = true,
        bounds: NodeBounds = NodeBounds(0, 0, 100, 50),
        children: List<NodeSnapshot> = emptyList(),
    ) = NodeSnapshot(
        className = "android.widget.TextView",
        text = text,
        desc = desc,
        viewId = viewId,
        clickable = clickable,
        visible = visible,
        bounds = bounds,
        children = children,
    )

    @Test
    fun `finds a node by exact text`() {
        val target = node(text = "Enviar", clickable = true)
        val root = node(children = listOf(node(text = "Chats"), target))

        val match = NodeMatcher.find(root, NodeTarget.Text("Enviar"))

        assertEquals(NodeMatch.Found(target), match)
    }

    @Test
    fun `exact text wins over a contains match`() {
        val exact = node(text = "Enviar", clickable = true, bounds = NodeBounds(0, 0, 2, 2))
        val longer = node(text = "Enviar ahora", clickable = true, bounds = NodeBounds(5, 5, 2, 2))
        val root = node(children = listOf(longer, exact))

        val match = NodeMatcher.find(root, NodeTarget.Text("Enviar"))

        assertEquals(NodeMatch.Found(exact), match)
    }

    @Test
    fun `falls back to case-insensitive contains on text`() {
        val target = node(text = "Enviar mensaje", clickable = true)
        val root = node(children = listOf(target))

        val match = NodeMatcher.find(root, NodeTarget.Text("enviar"))

        assertEquals(NodeMatch.Found(target), match)
    }

    @Test
    fun `falls back to case-insensitive contains on the description`() {
        val target = node(desc = "Botón ENVIAR", clickable = true)
        val root = node(children = listOf(target))

        val match = NodeMatcher.find(root, NodeTarget.Text("enviar"))

        assertEquals(NodeMatch.Found(target), match)
    }

    @Test
    fun `finds a node by its view id`() {
        val target = node(viewId = "com.whatsapp:id/send", clickable = true)
        val root = node(children = listOf(node(viewId = "com.whatsapp:id/title"), target))

        val match = NodeMatcher.find(root, NodeTarget.Id("com.whatsapp:id/send"))

        assertEquals(NodeMatch.Found(target), match)
    }

    @Test
    fun `prefers the nearest clickable ancestor`() {
        val container = NodeSnapshot(
            className = "android.widget.FrameLayout",
            clickable = true,
            bounds = NodeBounds(0, 100, 1080, 200),
            children = listOf(node(text = "Mensaje", bounds = NodeBounds(24, 120, 400, 60))),
        )
        val root = node(children = listOf(container))

        val match = NodeMatcher.find(root, NodeTarget.Text("Mensaje"))

        assertEquals(NodeMatch.Found(container), match)
    }

    @Test
    fun `several matches under one clickable row are one target`() {
        val row = NodeSnapshot(
            className = "android.widget.FrameLayout",
            clickable = true,
            bounds = NodeBounds(0, 100, 1080, 200),
            children = listOf(
                node(text = "Enviar", bounds = NodeBounds(24, 120, 100, 60)),
                node(text = "Enviar", bounds = NodeBounds(140, 120, 100, 60)),
            ),
        )
        val root = node(children = listOf(row))

        assertEquals(NodeMatch.Found(row), NodeMatcher.find(root, NodeTarget.Text("Enviar")))
    }

    @Test
    fun `refuses an ambiguous target`() {
        val first = node(text = "Enviar", clickable = true, bounds = NodeBounds(0, 0, 100, 100))
        val second = node(text = "Enviar", clickable = true, bounds = NodeBounds(0, 200, 100, 100))
        val root = node(children = listOf(first, second))

        assertEquals(
            NodeMatch.NotFound("varios elementos coinciden"),
            NodeMatcher.find(root, NodeTarget.Text("Enviar")),
        )
    }

    @Test
    fun `reports nothing found`() {
        val root = node(children = listOf(node(text = "Chats")))

        assertEquals(
            NodeMatch.NotFound("no se encontró el texto"),
            NodeMatcher.find(root, NodeTarget.Text("Enviar")),
        )
        assertEquals(
            NodeMatch.NotFound("no se encontró el id"),
            NodeMatcher.find(root, NodeTarget.Id("com.whatsapp:id/send")),
        )
    }

    @Test
    fun `ignores invisible nodes`() {
        val root = node(children = listOf(node(text = "Enviar", clickable = true, visible = false)))

        assertEquals(
            NodeMatch.NotFound("no se encontró el texto"),
            NodeMatcher.find(root, NodeTarget.Text("Enviar")),
        )
    }

    @Test
    fun `coordinates are not a node lookup`() {
        val match = NodeMatcher.find(node(), NodeTarget.Point(10, 20))

        assertTrue(match is NodeMatch.NotFound)
    }
}
