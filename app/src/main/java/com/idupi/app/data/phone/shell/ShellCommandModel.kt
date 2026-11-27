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

    data class Info(val topic: ShellTopic) : ShellOperation
}

/** Phone-side risk class (phone-shell T4 contract). */
enum class ShellRisk {
    /** Runs with no approval: info and enable (the undo). */
    FREE,

    /** Auto-approved in an owner turn, asked otherwise. */
    OPTIMIZATION,

    /** Voice approval. */
    DESTRUCTIVE,
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
        is ShellOperation.Info, is ShellOperation.Enable -> ShellRisk.FREE
        is ShellOperation.TrimCaches,
        is ShellOperation.ForceStop,
        is ShellOperation.Background,
        -> ShellRisk.OPTIMIZATION
        is ShellOperation.Disable -> ShellRisk.DESTRUCTIVE
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
    is ShellOperation.Info -> "info"
}

fun ShellOperation.packageName(): String? = when (this) {
    is ShellOperation.ForceStop -> packageName
    is ShellOperation.Background -> packageName
    is ShellOperation.Disable -> packageName
    is ShellOperation.Enable -> packageName
    else -> null
}

fun ShellOperation.mode(): ShellMode? =
    (this as? ShellOperation.Background)?.mode

fun ShellOperation.topic(): ShellTopic? =
    (this as? ShellOperation.Info)?.topic

/** One-line summary for the recent-command log. */
fun ShellOperation.summary(): String = when (this) {
    is ShellOperation.TrimCaches -> "trim-caches"
    is ShellOperation.ForceStop -> "force-stop $packageName"
    is ShellOperation.Background -> "background $packageName ${mode.wire}"
    is ShellOperation.Disable -> "disable $packageName"
    is ShellOperation.Enable -> "enable $packageName"
    is ShellOperation.Info -> "info ${topic.wire}"
}
