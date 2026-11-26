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

    /** Lowercase and strip combining marks so accents never hide a verb. */
    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase()

    /** True when the text is, or contains as a whole word, a risky verb. */
    fun isRisky(value: String): Boolean {
        val normalized = normalize(value)
        if (normalized.isBlank()) return false
        return riskyPattern.containsMatchIn(normalized)
    }

    private val MARKS = Regex("\\p{M}+")
}
