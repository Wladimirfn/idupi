package com.idupi.app.data.phone.shell

/** Outcome of one `pm install`/`cmd package install` output. */
sealed interface InstallResult {
    data object Success : InstallResult

    /** [reason] is the package manager's short reason, without the brackets. */
    data class Failure(val reason: String) : InstallResult
}

/**
 * Parses the output of a helper install (phone-shell T3b).
 *
 * Both `pm install` and `cmd package install` print `Success` on success and
 * `Failure [REASON]` on rejection; a failure line wins even if a success line
 * also sneaked in, and anything else is treated as an unknown failure so the
 * bootstrap never reports a false success.
 */
object InstallResultParser {

    private const val SUCCESS = "Success"
    private const val FAILURE_PREFIX = "Failure"
    private const val UNKNOWN_REASON = "error desconocido"
    private const val NO_RESULT = "sin resultado"

    fun parse(output: String): InstallResult {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val failure = lines.lastOrNull { it.startsWith(FAILURE_PREFIX) }
        if (failure != null) return InstallResult.Failure(reasonOf(failure))
        if (lines.any { it == SUCCESS }) return InstallResult.Success
        return InstallResult.Failure(NO_RESULT)
    }

    private fun reasonOf(line: String): String {
        val open = line.indexOf('[')
        val close = line.lastIndexOf(']')
        val raw = if (open >= 0 && close > open) {
            line.substring(open + 1, close)
        } else {
            line.removePrefix(FAILURE_PREFIX)
        }
        return raw.trim().ifEmpty { UNKNOWN_REASON }
    }
}
