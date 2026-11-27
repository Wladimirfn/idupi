package com.idupi.app.data.phone.shell

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One executed plan step, already reduced to the summary inputs. */
data class BatchStepOutcome(
    val step: ShellOperation,
    val ok: Boolean,
    val text: String,
)

/**
 * One Spanish summary for a T4b plan: per-step ok/fail and the helper's text
 * (trim-caches carries the freed space there). Pure, so the wording and the
 * JSON are unit-tested.
 */
object PhoneBatchSummary {

    fun render(outcomes: List<BatchStepOutcome>): String {
        if (outcomes.isEmpty()) return "El plan no tenía pasos."
        val okCount = outcomes.count { it.ok }
        val failed = outcomes.size - okCount
        val header = "Plan ejecutado: ${outcomes.size} pasos, $okCount bien, $failed con error."
        val lines = outcomes.mapIndexed { index, outcome ->
            val detail = outcome.text.trim().ifEmpty { "sin detalle" }
            val prefix = if (outcome.ok) outcome.step.summary() else "Error en ${outcome.step.summary()}"
            "${index + 1}. $prefix: $detail"
        }
        return (listOf(header) + lines).joinToString("\n")
    }

    fun json(outcomes: List<BatchStepOutcome>): JsonArray = buildJsonArray {
        outcomes.forEach { outcome ->
            add(buildJsonObject {
                put("step", outcome.step.summary())
                put("ok", outcome.ok)
                put("text", outcome.text)
            })
        }
    }
}
