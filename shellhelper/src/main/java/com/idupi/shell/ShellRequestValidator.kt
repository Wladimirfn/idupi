package com.idupi.shell

/**
 * Typed shell request the helper accepts (phone-shell T4 contract).
 *
 * Mirrors the app-side `ShellOperation`: info topic, trim-caches, force-stop,
 * background allow/ignore, disable and enable. Nothing else is reachable.
 */
enum class ShellAction(val wire: String) {
    INFO("info"),
    TRIM_CACHES("trim-caches"),
    FORCE_STOP("force-stop"),
    BACKGROUND("background"),
    DISABLE("disable"),
    ENABLE("enable"),
}

enum class ShellMode(val wire: String) {
    ALLOW("allow"),
    IGNORE("ignore"),
}

enum class ShellTopic(val wire: String) {
    STORAGE("storage"),
    MEMORY("memory"),
    BATTERY("battery"),
    PACKAGES("packages"),
}

data class ShellRequest(
    val action: ShellAction,
    val packageName: String? = null,
    val mode: ShellMode? = null,
    val topic: ShellTopic? = null,
)

sealed interface ShellValidation {
    data class Valid(val request: ShellRequest) : ShellValidation

    /** Owner-facing neutral Spanish; the app shows it when the helper refuses. */
    data class Invalid(val message: String) : ShellValidation
}

/**
 * Execution-boundary validation (phone-shell T4).
 *
 * The server and the app already validate the same contract; this is the last
 * check before an argv is built. It re-validates the action, the package, the
 * mode/topic and the protected-package list, so a forged Binder call cannot
 * widen the surface.
 */
object ShellRequestValidator {

    const val MAX_PACKAGE_CHARS = 200

    /**
     * Packages the helper refuses to force-stop, background or disable. `enable`
     * is always allowed: it is the undo of a disable.
     */
    val PROTECTED_PACKAGES: Set<String> = setOf(
        "com.idupi.app",
        "com.idupi.shell",
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.providers.settings",
        "com.google.android.gms",
        "com.sec.android.app.launcher",
        "com.samsung.android.honeyboard",
    )

    /** Same rule as the app's PackageNames: at least two letter-first segments. */
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun validate(
        action: String?,
        packageName: String?,
        mode: String?,
        topic: String?,
    ): ShellValidation {
        val requested = ShellAction.entries.firstOrNull { it.wire == action }
            ?: return ShellValidation.Invalid("acción inválida")
        // Exact wire values: never trim a package or a mode into validity.
        val pkg = packageName?.takeIf { it.isNotEmpty() }
        val modeValue = mode?.takeIf { it.isNotEmpty() }
        val topicValue = topic?.takeIf { it.isNotEmpty() }

        return when (requested) {
            ShellAction.INFO -> {
                if (pkg != null || modeValue != null) {
                    return ShellValidation.Invalid("argumento no permitido")
                }
                val requestedTopic = ShellTopic.entries.firstOrNull { it.wire == topicValue }
                    ?: return ShellValidation.Invalid("tema inválido")
                ShellValidation.Valid(ShellRequest(ShellAction.INFO, topic = requestedTopic))
            }
            ShellAction.TRIM_CACHES -> {
                if (pkg != null || modeValue != null || topicValue != null) {
                    return ShellValidation.Invalid("argumento no permitido")
                }
                ShellValidation.Valid(ShellRequest(ShellAction.TRIM_CACHES))
            }
            ShellAction.FORCE_STOP,
            ShellAction.DISABLE,
            -> packageOnly(requested, pkg, modeValue, topicValue, refuseProtected = true)
            ShellAction.ENABLE ->
                packageOnly(requested, pkg, modeValue, topicValue, refuseProtected = false)
            ShellAction.BACKGROUND -> {
                // The mode is valid here, so it must not be rejected as an
                // extra argument; topic still is.
                val validated = packageOnly(
                    requested,
                    pkg,
                    mode = null,
                    topic = topicValue,
                    refuseProtected = true,
                )
                if (validated is ShellValidation.Invalid) return validated
                val requestedMode = ShellMode.entries.firstOrNull { it.wire == modeValue }
                    ?: return ShellValidation.Invalid("modo inválido")
                ShellValidation.Valid(
                    ShellRequest(
                        ShellAction.BACKGROUND,
                        packageName = pkg,
                        mode = requestedMode,
                    )
                )
            }
        }
    }

    private fun packageOnly(
        action: ShellAction,
        packageName: String?,
        mode: String?,
        topic: String?,
        refuseProtected: Boolean,
    ): ShellValidation {
        if (mode != null || topic != null) {
            return ShellValidation.Invalid("argumento no permitido")
        }
        val pkg = packageName ?: return ShellValidation.Invalid("falta el paquete")
        // Protected first: the list also names framework ids that the shape
        // regex would reject, and they must never be stopped or disabled.
        if (refuseProtected && pkg in PROTECTED_PACKAGES) {
            return ShellValidation.Invalid("paquete protegido")
        }
        if (pkg.length > MAX_PACKAGE_CHARS || !PACKAGE_NAME.matches(pkg)) {
            return ShellValidation.Invalid("paquete inválido")
        }
        return ShellValidation.Valid(ShellRequest(action, packageName = pkg))
    }
}
