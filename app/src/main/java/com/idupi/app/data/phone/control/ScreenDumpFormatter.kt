package com.idupi.app.data.phone.control

/**
 * Builds the screen dump the PC reads (phone-agent T3).
 *
 * The format is a contract in `idupi-server/docs/phone.md` ("Screen dump
 * format"), one line per visible node, depth-first:
 *
 * ```
 * [n] <class short> "text" (desc) #id [clickable] [editable] @x,y,w,h
 * ```
 *
 * Rules that matter for the PC side:
 * - Ordinals are 1-based within the whole dump (multiple window roots share
 *   one counter) and are only reading aids, not command targets.
 * - `"` becomes `\"` and line breaks become `\n`; everything else is verbatim.
 * - A password field never exposes its text: it prints `••••` when it has any
 *   text, and omits the quoted part when empty (nothing is typed there yet).
 * - Invisible nodes are not printed. Their descendants are still visited
 *   because Android occasionally keeps a visible child under an invisible
 *   container; only the node's own visibility decides whether it is listed.
 */
object ScreenDumpFormatter {

    /** Four bullets: the fixed mask the contract documents for password fields. */
    const val PASSWORD_MASK = "••••"

    fun format(root: NodeSnapshot): String = format(listOf(root))

    fun format(roots: List<NodeSnapshot>): String {
        val lines = mutableListOf<String>()
        var ordinal = 0

        fun visit(node: NodeSnapshot) {
            if (node.visible) {
                ordinal += 1
                lines += line(ordinal, node)
            }
            node.children.forEach(::visit)
        }

        roots.forEach(::visit)
        return lines.joinToString("\n")
    }

    private fun line(ordinal: Int, node: NodeSnapshot): String {
        val parts = mutableListOf<String>()
        parts += "[$ordinal]"

        val shortClass = node.className.substringAfterLast('.')
        if (shortClass.isNotBlank()) parts += shortClass

        val text = if (node.password && node.text.isNotEmpty()) PASSWORD_MASK else node.text
        if (text.isNotEmpty()) parts += "\"${escape(text)}\""

        if (node.desc.isNotEmpty()) parts += "(${escape(node.desc)})"
        if (node.viewId.isNotEmpty()) parts += "#${node.viewId}"
        if (node.clickable) parts += CLICKABLE
        if (node.editable) parts += EDITABLE

        parts += "@${node.bounds.x},${node.bounds.y},${node.bounds.width},${node.bounds.height}"
        return parts.joinToString(" ")
    }

    /**
     * Escapes exactly what the contract documents. Backslashes are left alone:
     * the dump is for reading, and adding an escape the PC parser does not know
     * would corrupt every Windows-path text.
     */
    private fun escape(value: String): String =
        value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

    private const val CLICKABLE = "[clickable]"
    private const val EDITABLE = "[editable]"
}
