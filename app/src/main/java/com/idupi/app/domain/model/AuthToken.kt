package com.idupi.app.domain.model

/**
 * Characters that clipboards and terminal emulators inject around a copied
 * value. They are invisible, so a token carrying one looks byte-identical to
 * the real thing on screen while failing every comparison.
 *
 * Note that neither Kotlin's nor JavaScript's `trim()` removes these: `trim()`
 * only handles whitespace, and a zero-width space is not whitespace. They are
 * written as escapes on purpose -- an invisible literal cannot be reviewed.
 */
private val INVISIBLE_CHARS = setOf(
    '\u200B', // zero width space
    '\u200C', // zero width non-joiner
    '\u200D', // zero width joiner
    '\u2060', // word joiner
    '\uFEFF'  // zero width no-break space / BOM
)

/**
 * Cleans a hand-pasted auth token. Removes surrounding and embedded whitespace,
 * control characters and invisible separators, leaving the value the user
 * believes they copied.
 *
 * Intentionally does NOT validate the token's shape: the server decides what a
 * valid token is, and IDUPI_TOKEN can be set to any string.
 */
fun sanitizeAuthToken(raw: String): String =
    raw.filterNot { it.isWhitespace() || it.isISOControl() || it in INVISIBLE_CHARS }
