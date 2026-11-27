package com.idupi.app.data.phone.approval

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Spanish copy for a typed `shell` approval (phone-shell T4/T4b).
 *
 * A single action reads as one sentence. A plan (`action: "batch"`) groups its
 * steps: "Borrar la caché de todas las apps; detener 2 apps: A, B;
 * desinstalar 3 apps: C, D, E". Package labels are used when the caller can
 * resolve them; otherwise the package id is shown. Android-free so the copy is
 * unit-tested on the JVM.
 */
object ShellPlanText {

    private const val GENERIC = "Acción en el teléfono"
    private const val DESTRUCTIVE = "destructive"

    private val GROUP_ORDER = listOf(
        "trim-caches",
        "force-stop",
        "background",
        "disable",
        "enable",
        "uninstall",
    )

    fun actionLine(
        args: JsonObject,
        labelFor: (String) -> String? = { null },
    ): String {
        val action = args.stringValue("action") ?: return GENERIC
        if (action == "batch") return batchLine(args, labelFor)
        return singleLine(action, args, labelFor)
    }

    fun isBatch(args: JsonObject): Boolean = args.stringValue("action") == "batch"

    /** True when the plan (or the single action) contains an uninstall/disable. */
    fun containsDestructive(args: JsonObject): Boolean {
        if (isBatch(args)) {
            return steps(args).any { it.stringValue("action") in DESTRUCTIVE_ACTIONS }
        }
        return args.stringValue("action") in DESTRUCTIVE_ACTIONS
    }

    /** Numbered per-step lines for the approval details; empty for a single action. */
    fun stepLines(
        args: JsonObject,
        labelFor: (String) -> String? = { null },
    ): List<String> {
        if (!isBatch(args)) return emptyList()
        return steps(args).mapIndexed { index, step ->
            val action = step.stringValue("action") ?: "?"
            val pkg = step.stringValue("package")
            val display = pkg?.let { label(it, labelFor) }
            val detail = when (action) {
                "trim-caches" -> "Borrar la caché de todas las apps"
                "force-stop" -> "Detener $display"
                "background" -> when (step.stringValue("mode")) {
                    "allow" -> "Permitir el segundo plano de $display"
                    "ignore" -> "Limitar el segundo plano de $display"
                    else -> "Cambiar el segundo plano de $display"
                }
                "disable" -> "Desactivar $display"
                "enable" -> "Activar $display"
                "uninstall" -> "Desinstalar $display"
                else -> action
            }
            "${index + 1}. $detail"
        }
    }

    fun isDestructive(action: String?): Boolean = action in DESTRUCTIVE_ACTIONS

    /** Reason label derived from the typed action (older approvals lacked shell_*). */
    fun reasonLabel(args: JsonObject): String {
        val action = args.stringValue("action")
        return when {
            action == "batch" -> "Plan de optimización"
            action in DESTRUCTIVE_ACTIONS -> "Acción destructiva"
            else -> "Optimización del teléfono"
        }
    }

    private fun singleLine(
        action: String,
        args: JsonObject,
        labelFor: (String) -> String?,
    ): String = when (action) {
        "trim-caches" -> "Borrar la caché de todas las apps"
        "force-stop" -> "Detener la app «${display(args, labelFor)}»"
        "background" -> {
            val verb = if (args.stringValue("mode") == "allow") "Permitir" else "Limitar"
            "$verb el segundo plano de «${display(args, labelFor)}»"
        }
        "disable" -> "Desactivar la app «${display(args, labelFor)}»"
        "enable" -> "Activar la app «${display(args, labelFor)}»"
        "uninstall" -> "Desinstalar la app «${display(args, labelFor)}»"
        "info" -> "Consultar información del teléfono"
        "scan" -> "Analizar el teléfono"
        else -> GENERIC
    }

    private fun batchLine(args: JsonObject, labelFor: (String) -> String?): String {
        val groups = mutableListOf<String>()
        val byAction = steps(args).groupBy { it.stringValue("action") ?: "" }
        GROUP_ORDER.forEach { action ->
            val group = byAction[action].orEmpty()
            if (group.isEmpty()) return@forEach
            val packages = group.mapNotNull { it.stringValue("package") }
                .map { label(it, labelFor) }
                .distinct()
            when (action) {
                "trim-caches" -> groups += "borrar la caché de todas las apps"
                "force-stop" -> groups += countLine("detener", "app", "apps", packages)
                "background" -> groups += countLine("cambiar el segundo plano de", "app", "apps", packages)
                "disable" -> groups += countLine("desactivar", "app", "apps", packages)
                "enable" -> groups += countLine("activar", "app", "apps", packages)
                "uninstall" -> groups += countLine("desinstalar", "app", "apps", packages)
            }
        }
        if (groups.isEmpty()) return GENERIC
        val first = groups.first().replaceFirstChar { it.uppercase() }
        return (listOf(first) + groups.drop(1)).joinToString("; ")
    }

    private fun countLine(verb: String, singular: String, plural: String, items: List<String>): String {
        val count = items.size
        val noun = if (count == 1) singular else plural
        return "$verb $count $noun: ${items.joinToString(", ")}"
    }

    private fun display(args: JsonObject, labelFor: (String) -> String?): String =
        args.stringValue("package")?.let { label(it, labelFor) } ?: "?"

    private fun label(packageName: String, labelFor: (String) -> String?): String =
        labelFor(packageName)?.takeIf { it.isNotBlank() } ?: packageName

    private fun steps(args: JsonObject): List<JsonObject> =
        (args["steps"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private val DESTRUCTIVE_ACTIONS = setOf("disable", "uninstall")
}
