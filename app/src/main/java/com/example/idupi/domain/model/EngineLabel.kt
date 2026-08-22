package com.example.idupi.domain.model

/**
 * How the chat names the engine it is talking to.
 *
 * The header was the literal string "Chat con Pi", so a session running on
 * Claude or OpenCode still said Pi -- and with three engines behind one chat
 * that is the one thing the header has to get right.
 *
 * An id the app does not recognise is shown as itself rather than folded into
 * "Pi": the server can gain an engine without the app being rebuilt, and
 * labelling it with the wrong name is the defect being fixed here, not a
 * reasonable fallback. Only a genuinely absent engine falls back to Pi, which
 * is the server's own default (`currentStatus.activeEngine`).
 */
fun engineLabel(activeEngine: String?): String {
    val id = activeEngine?.trim().orEmpty()
    return when (id.lowercase()) {
        "", "pi-cli", "pi" -> "Pi"
        "claude" -> "Claude"
        "opencode" -> "OpenCode"
        else -> id
    }
}

/** Title for the chat header, e.g. "Chat con Claude". */
fun chatTitleFor(activeEngine: String?): String = "Chat con ${engineLabel(activeEngine)}"

/**
 * The message an empty chat opens with. It introduced itself as Pi regardless
 * of the engine, so a Claude or OpenCode session was greeted by the wrong one.
 */
fun greetingFor(activeEngine: String?): String =
    "¡Qué hacés! Soy ${engineLabel(activeEngine)}. Estoy listo para ayudarte con tu " +
        "proyecto a través de la terminal IDUPI. ¿De qué se trata lo que querés resolver hoy?"
