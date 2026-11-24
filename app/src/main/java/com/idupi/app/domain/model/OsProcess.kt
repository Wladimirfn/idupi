package com.idupi.app.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * One process from `GET /api/v1/os/processes` (T10).
 *
 * [category] is the server's classification (`apps`, `background`, `windows`,
 * `agents`, `idupi`); [isProtected] means the server refuses to end it and the
 * UI must hide the action. [cpuPercent] is null on the server's first sample
 * because a CPU delta needs two reads -- the UI renders "—", never 0 %.
 */
@Serializable
data class OsProcess(
    val pid: Int = 0,
    val parentPid: Int? = null,
    val name: String = "",
    val title: String? = null,
    val path: String? = null,
    val memoryBytes: Long = 0L,
    val cpuPercent: Double? = null,
    val category: String = OS_CATEGORY_BACKGROUND,
    @SerialName("protected") val isProtected: Boolean = false,
) {
    /**
     * Explorer shows a confirmation warning; the server deliberately keeps it
     * killable because ending it only restarts the Windows shell.
     */
    val isExplorer: Boolean
        get() {
            val base = if (name.endsWith(".exe", ignoreCase = true)) name.dropLast(4) else name
            return base.equals("explorer", ignoreCase = true)
        }
}

/** Response body of `GET /api/v1/os/processes`. */
@Serializable
data class OsProcessSample(
    val sampledAt: String = "",
    val totalMemoryBytes: Long = 0L,
    val cpuCount: Int = 0,
    val processes: List<OsProcess> = emptyList(),
)

/** Outcome the app maps from the kill route: 200, 404 and 409 respectively. */
enum class KillProcessOutcome { KILLED, GONE, PROTECTED }

const val OS_CATEGORY_APPS: String = "apps"
const val OS_CATEGORY_BACKGROUND: String = "background"
const val OS_CATEGORY_WINDOWS: String = "windows"
const val OS_CATEGORY_AGENTS: String = "agents"
const val OS_CATEGORY_IDUPI: String = "idupi"

/**
 * Working-set memory as the process list shows it: whole MB below a gigabyte,
 * one decimal of GB above it. Locale.US keeps the rendering identical on every
 * device and in JVM tests.
 */
fun formatProcessMemory(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1024.0) {
        String.format(Locale.US, "%.1f GB", megabytes / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", megabytes)
    }
}

/** One decimal of CPU %, or null when the server has no delta yet. */
fun formatProcessCpu(percent: Double?): String? {
    if (percent == null || !percent.isFinite()) return null
    return String.format(Locale.US, "%.1f %%", percent)
}
