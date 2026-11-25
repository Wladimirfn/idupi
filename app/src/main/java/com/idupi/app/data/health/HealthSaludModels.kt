package com.idupi.app.data.health

import com.idupi.app.domain.model.HealthDaySummary
import com.idupi.app.domain.model.HealthDigest
import com.idupi.app.domain.model.HealthSummary
import java.time.LocalDate
import java.util.Locale

/**
 * Pure read models and mappers of the Salud screen (phone-health T3).
 *
 * The PC answers a merged [HealthSummary] where each day maps every metric to
 * the source that supplied it. The screen needs cards (one per metric with a
 * value, an optional goal/stage detail and a source label), four seven-day
 * trend series and neutral Spanish formatting. All of it is plain Kotlin so
 * node-free JVM tests cover the rules; the Compose screen only draws these
 * models.
 */
enum class HealthTrendStyle { SPARKLINE, BARS }

/** One "today" card. [progress] is the goal fraction (0..1) when a goal exists. */
data class HealthMetricCard(
    val key: String,
    val title: String,
    val value: String,
    val detail: String? = null,
    val sourceLabel: String? = null,
    val progress: Float? = null,
)

/** One trend series across the seven days; `points` keeps nulls for missing days. */
data class HealthTrend(
    val key: String,
    val title: String,
    val unit: String,
    val points: List<Float?>,
    val labels: List<String>,
    val style: HealthTrendStyle,
) {
    val hasData: Boolean get() = points.any { it != null }
    val maxValue: Float get() = points.filterNotNull().maxOrNull() ?: 1f
}

/** Everything the Salud screen renders for one summary + digest pair. */
data class HealthSaludView(
    val dateKey: String = "",
    val dateLabel: String = "",
    val cards: List<HealthMetricCard> = emptyList(),
    val trends: List<HealthTrend> = emptyList(),
    val hasData: Boolean = false,
)

/**
 * Source ids the PC knows; anything else is shown as the id itself so a new
 * connector is visible without an app update.
 */
object HealthSalud {

    const val TREND_DAYS = 7

    val SOURCE_LABELS: Map<String, String> = mapOf(
        "huawei_health" to "Huawei Health",
        "health_connect" to "Health Connect",
        "wellbeing" to "Bienestar digital",
        "huawei_health_kit" to "Huawei Health Kit",
        "samsung_health" to "Samsung Health",
        "honor_health" to "Honor Health",
        "mi_fitness" to "Mi Fitness",
    )

    private val WEEKDAYS = listOf(
        "lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo",
    )
    private val MONTHS = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )

    /** "lunes 24 de noviembre", deterministic and locale independent. */
    fun dateLabel(date: LocalDate): String =
        "${WEEKDAYS[date.dayOfWeek.value - 1]} ${date.dayOfMonth} de ${MONTHS[date.monthValue - 1]}"

    /** Human label of one source id; null when the day does not say. */
    fun sourceLabel(id: String?): String? {
        val key = id?.trim().orEmpty()
        if (key.isEmpty()) return null
        return SOURCE_LABELS[key] ?: key
    }

    /**
     * Builds the screen view. Only the day that matches [todayKey] feeds the
     * cards: showing yesterday's numbers as today would be a lie, so a day
     * without samples renders the empty state instead.
     */
    fun map(summary: HealthSummary, todayKey: String, label: String): HealthSaludView {
        val days = summary.days.sortedBy { it.date }
        val today = days.lastOrNull { it.date == todayKey }
        val cards = today?.let(::cardsFor).orEmpty()
        val trends = trendsFor(days)
        return HealthSaludView(
            dateKey = todayKey,
            dateLabel = label,
            cards = cards,
            trends = trends,
            hasData = cards.isNotEmpty() || trends.isNotEmpty(),
        )
    }

    private fun cardsFor(day: HealthDaySummary): List<HealthMetricCard> {
        val metrics = day.metrics
        val cards = mutableListOf<HealthMetricCard>()

        metrics.steps?.let { steps ->
            cards += HealthMetricCard(
                key = "steps",
                title = "Pasos",
                value = formatCount(steps),
                detail = metrics.stepsGoal?.let { "de ${formatCount(it)} pasos" },
                sourceLabel = sourceLabel(day.sources["steps"]),
                progress = progressOf(steps, metrics.stepsGoal),
            )
        }

        metrics.sleepMinutes?.let { sleep ->
            val details = mutableListOf<String>()
            metrics.sleepGoalMinutes?.let { details += "meta ${formatMinutes(it)}" }
            metrics.sleepStages?.let { stages ->
                val stageText = listOfNotNull(
                    stages.deep?.let { "Profundo ${formatMinutes(it)}" },
                    stages.light?.let { "Ligero ${formatMinutes(it)}" },
                    stages.rem?.let { "REM ${formatMinutes(it)}" },
                    stages.awake?.let { "Despierto ${formatMinutes(it)}" },
                ).joinToString(" · ")
                if (stageText.isNotEmpty()) details += stageText
            }
            cards += HealthMetricCard(
                key = "sleep",
                title = "Sueño",
                value = formatMinutes(sleep),
                detail = details.joinToString(" · ").ifEmpty { null },
                sourceLabel = sourceLabel(day.sources["sleepMinutes"]),
            )
        }

        if (metrics.heartRateBpm != null || metrics.restingHeartRateBpm != null) {
            val details = mutableListOf<String>()
            if (metrics.heartRateBpm != null && metrics.restingHeartRateBpm != null) {
                details += "Reposo ${metrics.restingHeartRateBpm} lpm"
            }
            if (metrics.heartRateMin != null && metrics.heartRateMax != null) {
                details += "Rango ${metrics.heartRateMin}-${metrics.heartRateMax} lpm"
            }
            cards += HealthMetricCard(
                key = "heartRate",
                title = "Frecuencia cardíaca",
                value = metrics.heartRateBpm?.let { "$it lpm" }
                    ?: "${metrics.restingHeartRateBpm} lpm en reposo",
                detail = details.joinToString(" · ").ifEmpty { null },
                sourceLabel = sourceLabel(
                    day.sources["heartRateBpm"] ?: day.sources["restingHeartRateBpm"],
                ),
            )
        }

        metrics.stress?.let { stress ->
            cards += HealthMetricCard(
                key = "stress",
                title = "Estrés",
                value = "$stress",
                detail = metrics.stressLabel?.takeIf { it.isNotBlank() },
                sourceLabel = sourceLabel(day.sources["stress"]),
            )
        }

        metrics.spo2Pct?.let { spo2 ->
            cards += HealthMetricCard(
                key = "spo2",
                title = "SpO₂",
                value = "${formatDecimal(spo2)}%",
                sourceLabel = sourceLabel(day.sources["spo2Pct"]),
            )
        }

        metrics.weightKg?.let { weight ->
            cards += HealthMetricCard(
                key = "weight",
                title = "Peso",
                value = "${formatDecimal(weight)} kg",
                detail = metrics.weightGoalKg?.let { "meta ${formatDecimal(it)} kg" },
                sourceLabel = sourceLabel(day.sources["weightKg"]),
            )
        }

        metrics.exerciseMinutes?.let { exercise ->
            cards += HealthMetricCard(
                key = "exercise",
                title = "Ejercicio",
                value = formatMinutes(exercise),
                detail = metrics.exerciseGoalMinutes?.let { "meta ${formatMinutes(it)}" },
                sourceLabel = sourceLabel(day.sources["exerciseMinutes"]),
                progress = progressOf(exercise, metrics.exerciseGoalMinutes),
            )
        }

        metrics.waterMl?.let { water ->
            cards += HealthMetricCard(
                key = "water",
                title = "Agua",
                value = "${formatCount(water)} ml",
                detail = metrics.waterGoalMl?.let { "meta ${formatCount(it)} ml" },
                sourceLabel = sourceLabel(day.sources["waterMl"]),
                progress = progressOf(water, metrics.waterGoalMl),
            )
        }

        metrics.screenTimeMinutes?.let { screen ->
            val details = mutableListOf<String>()
            metrics.unlocks?.let { details += "${formatCount(it)} desbloqueos" }
            metrics.nightScreenMinutes?.takeIf { it > 0 }?.let {
                details += "${formatMinutes(it)} de noche"
            }
            cards += HealthMetricCard(
                key = "screenTime",
                title = "Tiempo en pantalla",
                value = formatMinutes(screen),
                detail = details.joinToString(" · ").ifEmpty { null },
                sourceLabel = sourceLabel(day.sources["screenTimeMinutes"]),
            )
        }

        val topApps = metrics.topApps.orEmpty()
        if (topApps.isNotEmpty()) {
            val listed = topApps.take(3).joinToString(" · ") {
                "${it.label} ${formatMinutes(it.minutes)}"
            }
            cards += HealthMetricCard(
                key = "topApps",
                title = "Apps más usadas",
                value = listed,
                sourceLabel = sourceLabel(day.sources["topApps"]),
            )
        }

        return cards
    }

    private fun trendsFor(allDays: List<HealthDaySummary>): List<HealthTrend> {
        val days = allDays.takeLast(TREND_DAYS)
        if (days.isEmpty()) return emptyList()
        val labels = days.map { it.date }
        val candidates = listOf(
            HealthTrend(
                key = "steps",
                title = "Pasos",
                unit = "pasos",
                points = days.map { it.metrics.steps?.toFloat() },
                labels = labels,
                style = HealthTrendStyle.BARS,
            ),
            HealthTrend(
                key = "sleep",
                title = "Sueño",
                unit = "h",
                points = days.map { it.metrics.sleepMinutes?.let { minutes -> minutes / 60f } },
                labels = labels,
                style = HealthTrendStyle.SPARKLINE,
            ),
            HealthTrend(
                key = "restingHeartRate",
                title = "Pulso en reposo",
                unit = "lpm",
                points = days.map { it.metrics.restingHeartRateBpm?.toFloat() },
                labels = labels,
                style = HealthTrendStyle.SPARKLINE,
            ),
            HealthTrend(
                key = "screenTime",
                title = "Pantalla",
                unit = "h",
                points = days.map {
                    it.metrics.screenTimeMinutes?.let { minutes -> minutes / 60f }
                },
                labels = labels,
                style = HealthTrendStyle.BARS,
            ),
        )
        return candidates.filter { it.hasData }
    }

    // --- formatting --------------------------------------------------------

    /** "12,400": deterministic thousands grouping, like the PC CLI. */
    fun formatCount(value: Int): String = String.format(Locale.US, "%,d", value)

    /** One decimal only when it is not an integer ("72.5", "72"). */
    fun formatDecimal(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
        }

    /** "45 min" under an hour, "8 h 29 min" above, "8 h" when exact. */
    fun formatMinutes(totalMinutes: Int): String {
        val minutes = totalMinutes.coerceAtLeast(0)
        if (minutes < 60) return "$minutes min"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0) "$hours h" else "$hours h $rest min"
    }

    /** Compact hours for the trend axis label: "8.5 h". */
    fun formatTrendValue(value: Float, unit: String): String =
        if (unit == "h") String.format(Locale.US, "%.1f h", value) else "${value.toInt()} $unit"

    private fun progressOf(value: Int, goal: Int?): Float? {
        if (goal == null || goal <= 0) return null
        return (value.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
    }
}

/** Digest card labels (pure, so the screen and tests agree). */
object HealthDigestCopy {

    const val FOOTER = "No es consejo médico."

    fun actionLabel(digest: HealthDigest): String =
        if (digest.hasContent) "Actualizar resumen" else "Generar resumen"

    /** What the card says while the agent runs, fails or has no inputs. */
    fun stateText(digest: HealthDigest): String? = when {
        digest.isAnalyzing -> "Analizando…"
        digest.isFailed -> digest.error ?: "No se pudo escribir el resumen."
        !digest.available -> "Sin datos de salud todavía."
        else -> null
    }

    /** Empty state of the whole screen: how to connect the sources. */
    const val EMPTY_TITLE = "Aún no hay datos de salud"
    const val EMPTY_BODY =
        "Conecta tus apps en Ajustes > SALUD: Health Connect para Samsung Health, " +
            "Mi Fitness y similares, o el lector de Huawei Health desde la PC. " +
            "Después toca Actualizar ahora."
}
