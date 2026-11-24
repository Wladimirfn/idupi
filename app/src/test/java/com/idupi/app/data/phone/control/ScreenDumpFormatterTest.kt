package com.idupi.app.data.phone.control

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Screen dump contract (phone-agent T3): the PC parses this text, so the exact
 * shape, escaping, ordinals, masking and invisibility rules are pinned here.
 */
class ScreenDumpFormatterTest {

    private fun node(
        className: String = "android.widget.TextView",
        text: String = "",
        desc: String = "",
        viewId: String = "",
        clickable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
        visible: Boolean = true,
        bounds: NodeBounds = NodeBounds(0, 0, 100, 50),
        children: List<NodeSnapshot> = emptyList(),
    ) = NodeSnapshot(
        className = className,
        text = text,
        desc = desc,
        viewId = viewId,
        clickable = clickable,
        editable = editable,
        password = password,
        visible = visible,
        bounds = bounds,
        children = children,
    )

    @Test
    fun `prints the documented line shape`() {
        val root = node(
            className = "android.widget.FrameLayout",
            bounds = NodeBounds(0, 0, 1080, 2400),
            children = listOf(
                node(
                    text = "Enviar",
                    desc = "Enviar mensaje",
                    viewId = "com.whatsapp:id/send",
                    clickable = true,
                    bounds = NodeBounds(900, 2100, 160, 100),
                )
            ),
        )

        assertEquals(
            """
            [1] FrameLayout @0,0,1080,2400
            [2] TextView "Enviar" (Enviar mensaje) #com.whatsapp:id/send [clickable] @900,2100,160,100
            """.trimIndent(),
            ScreenDumpFormatter.format(root),
        )
    }

    @Test
    fun `prints editable flags and class short name`() {
        val root = node(
            className = "android.widget.EditText",
            text = "hola",
            editable = true,
            bounds = NodeBounds(40, 300, 1000, 120),
        )

        assertEquals(
            "[1] EditText \"hola\" [editable] @40,300,1000,120",
            ScreenDumpFormatter.format(root),
        )
    }

    @Test
    fun `ordinals are depth-first and continue across window roots`() {
        val first = node(className = "android.widget.FrameLayout", children = listOf(node(text = "A")))
        val second = node(className = "android.widget.FrameLayout", children = listOf(node(text = "B")))

        val dump = ScreenDumpFormatter.format(listOf(first, second))

        assertEquals(
            """
            [1] FrameLayout @0,0,100,50
            [2] TextView "A" @0,0,100,50
            [3] FrameLayout @0,0,100,50
            [4] TextView "B" @0,0,100,50
            """.trimIndent(),
            dump,
        )
    }

    @Test
    fun `escapes quotes and line breaks`() {
        val root = node(text = "Di \"hola\"\ny adiós", desc = "dos\nlíneas")

        assertEquals(
            "[1] TextView \"Di \\\"hola\\\"\\ny adiós\" (dos\\nlíneas) @0,0,100,50",
            ScreenDumpFormatter.format(root),
        )
    }

    @Test
    fun `masks password text`() {
        val root = node(
            className = "android.widget.EditText",
            text = "secreto",
            password = true,
            editable = true,
        )

        val dump = ScreenDumpFormatter.format(root)

        assertEquals(
            "[1] EditText \"${ScreenDumpFormatter.PASSWORD_MASK}\" [editable] @0,0,100,50",
            dump,
        )
        assertEquals(false, dump.contains("secreto"))
    }

    @Test
    fun `an empty password field does not fake content`() {
        val root = node(className = "android.widget.EditText", password = true, editable = true)

        assertEquals(
            "[1] EditText [editable] @0,0,100,50",
            ScreenDumpFormatter.format(root),
        )
    }

    @Test
    fun `skips invisible nodes but still visits their children`() {
        val root = node(
            className = "android.widget.FrameLayout",
            children = listOf(
                node(text = "oculto", visible = false, children = listOf(node(text = "visible"))),
            ),
        )

        assertEquals(
            """
            [1] FrameLayout @0,0,100,50
            [2] TextView "visible" @0,0,100,50
            """.trimIndent(),
            ScreenDumpFormatter.format(root),
        )
    }

    @Test
    fun `an empty tree dumps an empty string`() {
        assertEquals("", ScreenDumpFormatter.format(node(visible = false)))
        assertEquals("", ScreenDumpFormatter.format(emptyList()))
    }

    @Test
    fun `omits text, desc and id when empty`() {
        val root = node(className = "android.widget.ImageView", bounds = NodeBounds(1, 2, 3, 4))

        assertEquals("[1] ImageView @1,2,3,4", ScreenDumpFormatter.format(root))
    }
}
