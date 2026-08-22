package com.example.idupi.domain.model

/**
 * The text a selection of chat messages copies to the clipboard.
 *
 * Two things it deliberately does NOT do: reorder, and relabel. The messages
 * come out in the order they appear on screen regardless of the order they were
 * tapped in, because that is the order they were read in; and they are copied
 * as their own text, with no "Pi:" / "Vos:" prefixes invented around them --
 * what is pasted is what was on screen.
 */
fun copyTextOf(messages: List<ChatMessage>, selectedIds: Set<String>): String =
    messages
        .filter { it.id in selectedIds }
        .map { it.text.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

/** Adds or removes [id], which is how a tap toggles one message in selection mode. */
fun Set<String>.toggled(id: String): Set<String> =
    if (id in this) this - id else this + id
