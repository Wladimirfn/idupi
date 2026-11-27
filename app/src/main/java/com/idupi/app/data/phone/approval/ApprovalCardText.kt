package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalReason
import java.time.Instant
import kotlin.math.ceil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Copy for the "Aprobaciones pendientes" card (phone-approvals T4).
 *
 * Every line is pure so the Alertas section and the Ajustes card show exactly
 * the same description of what the phone would do. Neutral Latin American
 * Spanish; a missing app, target or deadline never renders "null".
 */
object ApprovalCardText {

    const val SECTION_TITLE = "Aprobaciones pendientes"
    const val SECTION_SUBTITLE =
        "La PC espera tu confirmación antes de hacer algo que no se puede deshacer."

    const val MESSAGE_APPROVED = "Aprobado."
    const val MESSAGE_REJECTED = "Rechazado."
    const val MESSAGE_NO_LONGER_VALID = "Ya se decidió o venció."
    const val MESSAGE_DECISION_FAILED =
        "No se pudo enviar la decisión. Revisa la conexión con la PC."

    const val LABEL_APPROVE = "Aprobar"
    const val LABEL_REJECT = "Rechazar"
    const val LABEL_SHOW_DETAIL = "Ver detalle"
    const val LABEL_HIDE_DETAIL = "Ocultar detalle"

    /** One line describing the action: tap, long press or write-and-send. */
    fun actionLine(approval: PhoneApproval): String {
        val args = approval.command.args
        return when (approval.command.type) {
            "type" -> {
                val text = args.textValue("text")
                val base = if (text == null) "Escribir en el teléfono" else "Escribir «$text»"
                if (args.booleanValue("submit")) "$base y enviar" else base
            }
            "long_press" -> tapLine("Mantener presionado", targetOf(approval))
            "tap" -> tapLine("Tocar", targetOf(approval))
            "shell" -> shellLine(args)
            else -> GENERIC_ACTION
        }
    }

    /** "WhatsApp · com.whatsapp", or null when the server sent no app. */
    fun appLine(approval: PhoneApproval): String? {
        val app = approval.foregroundApp ?: return null
        val label = app.label.trim().takeIf { it.isNotEmpty() }
        val packageName = app.packageName.trim().takeIf { it.isNotEmpty() }
        return when {
            label != null && packageName != null -> "$label · $packageName"
            label != null -> label
            packageName != null -> packageName
            else -> null
        }
    }

    /** Why the server gated the command. */
    fun reasonLabel(approval: PhoneApproval): String = when (approval.reason) {
        PhoneApprovalReason.RISKY_TEXT -> "Acción riesgosa"
        PhoneApprovalReason.TYPE_SUBMIT -> "Envío de un mensaje"
        PhoneApprovalReason.SENSITIVE_APP -> "App sensible en primer plano"
        PhoneApprovalReason.PHONE_DETECTED -> "Riesgo detectado en el teléfono"
        PhoneApprovalReason.OPTIMIZATION -> "Optimización del teléfono"
        PhoneApprovalReason.DESTRUCTIVE -> "Acción destructiva"
        else -> "Pendiente de tu aprobación"
    }

    /** "La pidió: cli", or null when the server did not say. */
    fun requestedByLine(approval: PhoneApproval): String? {
        val requestedBy = approval.requestedBy.trim()
        return if (requestedBy.isEmpty()) null else "La pidió: $requestedBy"
    }

    /** "vence en 8 min", "vence en menos de 1 min" or "vencida". */
    fun timeLeftLine(expiresAt: String, nowMillis: Long): String? {
        val expiry = runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrNull()
            ?: return null
        val remaining = expiry - nowMillis
        return when {
            remaining <= 0L -> "vencida"
            remaining < 60_000L -> "vence en menos de 1 min"
            else -> "vence en ${ceil(remaining / 60_000.0).toLong()} min"
        }
    }

    /**
     * Exact command arguments plus the approval id for "Ver detalle": the owner
     * can check the target text, the typed text, coordinates / view id and the
     * submit flag before approving.
     */
    fun detailLines(approval: PhoneApproval): List<String> {
        val args = approval.command.args
        val type = approval.command.type
        val lines = mutableListOf<String>()
        lines += "Comando: ${type.ifBlank { "desconocido" }}"

        args.textValue("text")?.let { text ->
            lines += if (type == "type") {
                "Texto a escribir: «$text»"
            } else {
                "Texto del objetivo: «$text»"
            }
        }
        if (type == "type") {
            lines += "Presionar enviar: ${if (args.booleanValue("submit")) "sí" else "no"}"
        }
        if (type == "shell") {
            args.textValue("action")?.let { lines += "Acción: $it" }
            args.textValue("package")?.let { lines += "App: $it" }
            args.textValue("mode")?.let {
                lines += "Modo: ${if (it == "allow") "permitir" else "ignorar"}"
            }
            args.textValue("topic")?.let { lines += "Tema: $it" }
        }
        args.textValue("id")?.let { viewId -> lines += "Id de vista: $viewId" }
        val x = args.intValue("x")
        val y = args.intValue("y")
        if (x != null && y != null) lines += "Coordenadas: ($x, $y)"

        lines += "Aprobación: ${approval.id}"
        return lines
    }

    private fun tapLine(verb: String, target: TargetArg?): String = when (target) {
        null -> GENERIC_ACTION
        is TargetArg.TextArg -> "$verb «${target.value}»"
        is TargetArg.IdArg -> "$verb el control ${target.value}"
        is TargetArg.PointArg -> "$verb en (${target.x}, ${target.y})"
    }

    /** One line for a typed `shell` approval (phone-shell T4). */
    private fun shellLine(args: JsonObject): String {
        val pkg = args.textValue("package")
        return when (args.textValue("action")) {
            "trim-caches" -> "Liberar la caché de todas las apps"
            "force-stop" -> pkg?.let { "Detener la app «$it»" } ?: GENERIC_ACTION
            "background" -> when {
                pkg == null -> GENERIC_ACTION
                args.textValue("mode") == "allow" -> "Permitir el segundo plano de «$pkg»"
                else -> "Limitar el segundo plano de «$pkg»"
            }
            "disable" -> pkg?.let { "Desactivar la app «$it»" } ?: GENERIC_ACTION
            "enable" -> pkg?.let { "Activar la app «$it»" } ?: GENERIC_ACTION
            "info" -> "Consultar información del teléfono"
            else -> GENERIC_ACTION
        }
    }

    /**
     * The target the command will touch. The normalized args are authoritative;
     * when the server sent none (older approval) the target it computed is used
     * instead.
     */
    private fun targetOf(approval: PhoneApproval): TargetArg? {
        val args = approval.command.args
        args.textValue("text")?.let { return TargetArg.TextArg(it) }
        args.textValue("id")?.let { return TargetArg.IdArg(it) }
        val x = args.intValue("x")
        val y = args.intValue("y")
        if (x != null && y != null) return TargetArg.PointArg(x, y)

        val fallback = approval.target?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            COORDINATES.matches(fallback) -> {
                val (x, y) = fallback.split(",")
                TargetArg.PointArg(x.trim().toInt(), y.trim().toInt())
            }
            fallback.contains(":id/") || fallback.contains('/') -> TargetArg.IdArg(fallback)
            else -> TargetArg.TextArg(fallback)
        }
    }

    private fun JsonObject.textValue(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.intValue(key: String): Int? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.content
            ?.toIntOrNull()

    private fun JsonObject.booleanValue(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"

    private sealed interface TargetArg {
        data class TextArg(val value: String) : TargetArg
        data class IdArg(val value: String) : TargetArg
        data class PointArg(val x: Int, val y: Int) : TargetArg
    }

    private const val GENERIC_ACTION = "Acción en el teléfono"
    private val COORDINATES = Regex("^\\s*\\d+\\s*,\\s*\\d+\\s*$")
}
