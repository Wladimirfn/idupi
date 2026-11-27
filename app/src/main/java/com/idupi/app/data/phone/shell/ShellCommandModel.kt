package com.idupi.app.data.phone.shell

/** `background` mode from the T4 contract. */
enum class ShellMode(val wire: String) {
    ALLOW("allow"),
    IGNORE("ignore"),
    ;

    companion object {
        fun fromWire(value: String?): ShellMode? = entries.firstOrNull { it.wire == value }
    }
}

/** `info` topic from the T4 contract. */
enum class ShellTopic(val wire: String) {
    STORAGE("storage"),
    MEMORY("memory"),
    BATTERY("battery"),
    PACKAGES("packages"),
    ;

    companion object {
        fun fromWire(value: String?): ShellTopic? = entries.firstOrNull { it.wire == value }
    }
}

/** The typed `shell` operations; nothing outside this set is reachable. */
sealed interface ShellOperation {
    data object TrimCaches : ShellOperation

    data class ForceStop(val packageName: String) : ShellOperation

    data class Background(val packageName: String, val mode: ShellMode) : ShellOperation

    data class Disable(val packageName: String) : ShellOperation

    data class Enable(val packageName: String) : ShellOperation

    data class Uninstall(val packageName: String) : ShellOperation

    data class Info(val topic: ShellTopic) : ShellOperation

    /** Runs in the app: sizes, usage and cleanup candidates (T4b). */
    data object Scan : ShellOperation

    /** 1-30 typed steps executed in order under ONE plan approval (T4b). */
    data class Batch(val steps: List<ShellOperation>) : ShellOperation
}

/** Phone-side risk class (phone-shell T4/T4b contract). */
enum class ShellRisk {
    /** Runs with no approval: info, scan and enable (the undo). */
    FREE,

    /** Auto-approved in an owner turn, asked otherwise. */
    OPTIMIZATION,

    /** Voice approval. */
    DESTRUCTIVE,

    /** A batch: always one plan approval, never auto-approved. */
    PLAN,
}

/**
 * The allowlist rules the app enforces before the helper sees a request
 * (phone-shell T4). The helper repeats them at the execution boundary.
 */
object ShellCommandRules {

    const val MAX_PACKAGE_CHARS = 200

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

    fun isProtected(packageName: String): Boolean = packageName in PROTECTED_PACKAGES

    fun riskOf(operation: ShellOperation): ShellRisk = when (operation) {
        is ShellOperation.Info, is ShellOperation.Scan, is ShellOperation.Enable -> ShellRisk.FREE
        is ShellOperation.TrimCaches,
        is ShellOperation.ForceStop,
        is ShellOperation.Background,
        -> ShellRisk.OPTIMIZATION
        is ShellOperation.Disable, is ShellOperation.Uninstall -> ShellRisk.DESTRUCTIVE
        is ShellOperation.Batch -> ShellRisk.PLAN
    }
}

/** Category the server attaches to an owner-turn optimization approval. */
const val SHELL_OPTIMIZATION_CATEGORY = "optimization"

fun ShellOperation.wireAction(): String = when (this) {
    is ShellOperation.TrimCaches -> "trim-caches"
    is ShellOperation.ForceStop -> "force-stop"
    is ShellOperation.Background -> "background"
    is ShellOperation.Disable -> "disable"
    is ShellOperation.Enable -> "enable"
    is ShellOperation.Uninstall -> "uninstall"
    is ShellOperation.Info -> "info"
    is ShellOperation.Scan -> "scan"
    is ShellOperation.Batch -> "batch"
}

fun ShellOperation.packageName(): String? = when (this) {
    is ShellOperation.ForceStop -> packageName
    is ShellOperation.Background -> packageName
    is ShellOperation.Disable -> packageName
    is ShellOperation.Enable -> packageName
    is ShellOperation.Uninstall -> packageName
    else -> null
}

fun ShellOperation.mode(): ShellMode? =
    (this as? ShellOperation.Background)?.mode

fun ShellOperation.topic(): ShellTopic? =
    (this as? ShellOperation.Info)?.topic

/** True for the steps a plan cannot auto-approve and the voice must warn about. */
fun ShellOperation.isDestructiveStep(): Boolean =
    this is ShellOperation.Disable || this is ShellOperation.Uninstall

/** One-line summary for the recent-command log. */
fun ShellOperation.summary(): String = when (this) {
    is ShellOperation.TrimCaches -> "trim-caches"
    is ShellOperation.ForceStop -> "force-stop $packageName"
    is ShellOperation.Background -> "background $packageName ${mode.wire}"
    is ShellOperation.Disable -> "disable $packageName"
    is ShellOperation.Enable -> "enable $packageName"
    is ShellOperation.Uninstall -> "uninstall $packageName"
    is ShellOperation.Info -> "info ${topic.wire}"
    is ShellOperation.Scan -> "scan"
    is ShellOperation.Batch -> "batch ${steps.size} pasos"
}
