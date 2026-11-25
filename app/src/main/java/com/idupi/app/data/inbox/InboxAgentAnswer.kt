package com.idupi.app.data.inbox

/**
 * Copy for the "Respuesta del agente lista" ping (alerts-flow A3).
 *
 * The notification never carries the alert's own text or the agent's answer
 * back into the shade: the title is fixed and the body is the first line of
 * the owner's instruction, which the owner already knows because they wrote
 * it. Keeping this here (instead of inline in the ViewModel) makes both rules
 * testable without Android.
 */
object InboxAgentAnswer {

    /** Fixed owner-facing title; the tap opens Alertas. */
    const val TITLE = "Respuesta del agente lista"

    /** Body used when the instruction has no line with visible text. */
    const val FALLBACK_TEXT = "La investigación terminó"

    /** Longest body kept; a longer first line is cut and marked with an ellipsis. */
    const val MAX_TEXT_CHARS = 140

    /**
     * First line of [instruction] that has non-blank text, trimmed and capped
     * at [MAX_TEXT_CHARS]. Falls back to [FALLBACK_TEXT] when there is none.
     */
    fun notificationText(instruction: String): String {
        val firstLine = instruction
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (firstLine.isEmpty()) return FALLBACK_TEXT
        if (firstLine.length <= MAX_TEXT_CHARS) return firstLine
        // The ellipsis counts toward the cap so the shade never overflows.
        return firstLine.take(MAX_TEXT_CHARS - 1).trimEnd() + "…"
    }
}
