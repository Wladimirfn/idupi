package com.idupi.app.data.phone.approval

import java.text.Normalizer

/**
 * Phone-side port of `idupi-server/lib/phone-risk.mjs` (phone-approvals T2).
 *
 * The same words must be risky on both sides: a tap is a tap regardless of
 * which process classified it. Matching is whole-word and ignores case and
 * accents, so "Enviar" is risky while "Reenviar" and "Pagaré mañana" are not.
 */
object PhoneRiskText {

    /** Verbs that turn a tap into an outbound or irreversible action. */
    val RISKY_VERBS: List<String> = listOf(
        "enviar",
        "send",
        "pagar",
        "pay",
        "eliminar",
        "borrar",
        "delete",
        // phone-dashboard T3: uninstalling is irreversible, same as the server.
        "desinstalar",
        "desinstala",
        "uninstall",
        "aprobar",
        "approve",
        "confirmar",
        "confirm",
        "comprar",
        "buy",
        "transferir",
        "transfer",
        "publicar",
        "post",
        "share",
        "compartir",
    )

    private val riskyPattern = Regex(
        "(^|[^\\p{L}\\p{N}])(${RISKY_VERBS.joinToString("|")})(?=$|[^\\p{L}\\p{N}])",
    )

    // Clearing app data or storage is destructive and needs the owner's
    // approval, exactly like any other delete; the phrase only decides which
    // sentence the bubble says (phone-dashboard T6). Up to two filler words
    // catch "borrar todos los datos" / "clear app data".
    private val dataWipePattern = Regex(
        "(^|[^\\p{L}\\p{N}])(borrar|eliminar|clear)(\\s+\\p{L}+){0,2}\\s+" +
            "(datos|almacenamiento|data|storage)(?=$|[^\\p{L}\\p{N}])",
    )

    /** Lowercase and strip combining marks so accents never hide a verb. */
    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase()

    /**
     * True when the text is, or contains as a whole word, a risky verb, or is
     * a data-wipe phrase. "Clear" alone is not a risky verb; only the full
     * phrase ("Clear data") is caught, so an English cache clear stays a free
     * tap.
     */
    fun isRisky(value: String): Boolean {
        val normalized = normalize(value)
        if (normalized.isBlank()) return false
        return riskyPattern.containsMatchIn(normalized) ||
            dataWipePattern.containsMatchIn(normalized)
    }

    /**
     * True when the text asks to clear app data or storage (phone-dashboard
     * T3 + T6). It is a normal destructive approval; "borrar caché" and
     * "clear cache" stay cache clears.
     */
    fun isDataWipe(value: String): Boolean {
        val normalized = normalize(value)
        if (normalized.isBlank()) return false
        return dataWipePattern.containsMatchIn(normalized)
    }

    private val MARKS = Regex("\\p{M}+")
}
