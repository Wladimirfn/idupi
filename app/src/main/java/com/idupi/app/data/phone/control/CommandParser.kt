package com.idupi.app.data.phone.control

import com.idupi.app.data.phone.shell.PackageNames
import com.idupi.app.data.phone.shell.ShellCommandRules
import com.idupi.app.data.phone.shell.ShellMode
import com.idupi.app.data.phone.shell.ShellOperation
import com.idupi.app.data.phone.shell.ShellTopic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface CommandParseResult {
    data class Valid(val command: PhoneCommand) : CommandParseResult

    /**
     * The command could not be turned into a [PhoneCommand]. [commandId] is
     * present whenever the wire id was readable, so the poller can still post a
     * failure and the PC does not wait 30 s for nothing.
     */
    data class Invalid(
        val commandId: String?,
        val type: String?,
        val error: String,
    ) : CommandParseResult
}

/**
 * Parses and validates one command JSON object (phone-agent T3).
 *
 * Validation mirrors the server (`idupi-server/lib/phone-store.mjs`,
 * `normalizeCommandArgs`) so the phone rejects exactly what the server would:
 * unknown types, unknown argument keys, wrong target combinations, non-string
 * text, out-of-range coordinates and waits. Extra top-level fields are ignored
 * because the server adds state/timestamps around the same object.
 */
object CommandParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): CommandParseResult {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return CommandParseResult.Invalid(null, null, "comando inválido")

        val id = root.string("id")?.takeIf { it.isNotBlank() }
        val type = root.string("type")
        if (id == null) return CommandParseResult.Invalid(null, type, "falta el id del comando")

        val argsElement = root["args"]
        if (argsElement != null && argsElement !is JsonObject) {
            return CommandParseResult.Invalid(id, type, "args inválidos")
        }
        val args = argsElement as? JsonObject ?: JsonObject(emptyMap())
        val expiresAt = root.string("expiresAt")?.takeIf { it.isNotBlank() }
        // The server adds the approval both inside args and at the top level.
        // These are hints for the executor; the local ledger is the authority.
        val approval = approvalOf(root, args)

        return when (type) {
            "screen" -> {
                unknownArg(args, emptySet())?.let { return invalid(id, type, it) }
                CommandParseResult.Valid(PhoneCommand.Screen(id, expiresAt))
            }
            "status" -> {
                unknownArg(args, emptySet())?.let { return invalid(id, type, it) }
                CommandParseResult.Valid(PhoneCommand.ReportStatus(id, expiresAt))
            }
            "tap" -> target("tap", args, id, expiresAt, approval) { target ->
                PhoneCommand.Tap(id, expiresAt, target, approval)
            }
            "long_press" ->
                target("long_press", args, id, expiresAt, approval) { target ->
                    PhoneCommand.LongPress(id, expiresAt, target, approval)
                }
            "type" -> {
                unknownArg(args, setOf("text", "submit") + APPROVAL_ARG_KEYS)
                    ?.let { return invalid(id, type, it) }
                val text = args.string("text")?.takeIf { it.isNotBlank() }
                    ?: return invalid(id, type, "text inválido")
                if (text.length > MAX_TEXT_CHARS) return invalid(id, type, "text demasiado largo")
                val submitPrimitive = args["submit"]
                if (submitPrimitive != null && !submitPrimitive.isBoolean()) {
                    return invalid(id, type, "submit inválido")
                }
                val submit = (submitPrimitive as? JsonPrimitive)?.content == "true"
                CommandParseResult.Valid(
                    PhoneCommand.TypeText(id, expiresAt, text, submit, approval)
                )
            }
            "scroll" -> {
                unknownArg(args, setOf("direction"))?.let { return invalid(id, type, it) }
                val direction = when (args.string("direction")) {
                    "up" -> ScrollDirection.UP
                    "down" -> ScrollDirection.DOWN
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> return invalid(id, type, "dirección inválida")
                }
                CommandParseResult.Valid(PhoneCommand.Scroll(id, expiresAt, direction))
            }
            "global" -> {
                unknownArg(args, setOf("action"))?.let { return invalid(id, type, it) }
                val action = when (args.string("action")) {
                    "back" -> GlobalAction.BACK
                    "home" -> GlobalAction.HOME
                    "recents" -> GlobalAction.RECENTS
                    "notifications" -> GlobalAction.NOTIFICATIONS
                    "quick_settings" -> GlobalAction.QUICK_SETTINGS
                    else -> return invalid(id, type, "acción inválida")
                }
                CommandParseResult.Valid(PhoneCommand.Global(id, expiresAt, action))
            }
            "open" -> {
                unknownArg(args, setOf("app"))?.let { return invalid(id, type, it) }
                val app = args.string("app")?.takeIf { it.isNotBlank() }
                    ?: return invalid(id, type, "app inválida")
                if (app.length > MAX_APP_CHARS) return invalid(id, type, "app demasiado larga")
                CommandParseResult.Valid(PhoneCommand.OpenApp(id, expiresAt, app))
            }
            "settings" -> {
                // phone-dashboard T7: fixed allowlist, never a free-form intent.
                unknownArg(args, setOf("screen", "package"))
                    ?.let { return invalid(id, type, it) }
                val screen = SettingsScreen.fromWire(args.string("screen"))
                    ?: return invalid(id, type, "screen inválido")
                if (screen != SettingsScreen.APP) {
                    // Only the app screen takes a package; anything else with
                    // one is extra input from the PC.
                    if (args["package"] != null) {
                        return invalid(id, type, "package no se permite con esta pantalla")
                    }
                    return CommandParseResult.Valid(
                        PhoneCommand.Settings(id, expiresAt, screen)
                    )
                }
                val packageName = args.string("package")?.takeIf { it.isNotBlank() }
                    ?: return invalid(id, type, "package inválido")
                if (!isValidPackageName(packageName)) {
                    return invalid(id, type, "package inválido")
                }
                if (packageName.length > MAX_APP_CHARS) {
                    return invalid(id, type, "package demasiado largo")
                }
                return CommandParseResult.Valid(
                    PhoneCommand.Settings(id, expiresAt, screen, packageName)
                )
            }
            "shell" -> shellCommand(id, expiresAt, args, approval)
            "wait" -> {
                unknownArg(args, setOf("ms"))?.let { return invalid(id, type, it) }
                val millis = args.int("ms")?.takeIf { it in 0..MAX_WAIT_MS }
                    ?: return invalid(id, type, "ms inválido")
                CommandParseResult.Valid(PhoneCommand.Wait(id, expiresAt, millis))
            }
            null -> invalid(id, type, "falta el tipo de comando")
            else -> invalid(id, type, "tipo desconocido")
        }
    }

    /**
     * Typed `shell` command (phone-shell T4/T4b contract).
     *
     * Single actions take exactly their arguments. `scan` runs in the app and
     * takes none. `batch` takes 1-30 steps, each one a single non-free action
     * with its normal arguments; info/scan/batch inside a plan and duplicate
     * steps are refused. Unknown keys/actions and irrelevant arguments are
     * rejected, never guessed.
     */
    private fun shellCommand(
        id: String,
        expiresAt: String?,
        args: JsonObject,
        approval: CommandApproval?,
    ): CommandParseResult {
        val action = args.string("action")?.takeIf { it.isNotBlank() }
            ?: return invalid(id, "shell", "falta la acción")
        if (action == "batch") return batchCommand(id, expiresAt, args, approval)
        if (action == "scan") {
            unknownArg(args, setOf("action") + APPROVAL_ARG_KEYS)
                ?.let { return invalid(id, "shell", it) }
            return CommandParseResult.Valid(
                PhoneCommand.Shell(id, expiresAt, ShellOperation.Scan, approval)
            )
        }

        unknownArg(args, SHELL_ARG_KEYS)?.let { return invalid(id, "shell", it) }
        val packageName = args.string("package")?.takeIf { it.isNotBlank() }
        val mode = args.string("mode")?.takeIf { it.isNotBlank() }
        val topic = args.string("topic")?.takeIf { it.isNotBlank() }
        return when (val parsed = parseShellStep(action, packageName, mode, topic)) {
            is ShellStepParse.Ok -> CommandParseResult.Valid(
                PhoneCommand.Shell(id, expiresAt, parsed.operation, approval)
            )
            is ShellStepParse.Error -> invalid(id, "shell", parsed.message)
        }
    }

    private fun batchCommand(
        id: String,
        expiresAt: String?,
        args: JsonObject,
        approval: CommandApproval?,
    ): CommandParseResult {
        unknownArg(args, setOf("action", "steps") + APPROVAL_ARG_KEYS)
            ?.let { return invalid(id, "shell", it) }
        val rawSteps = args["steps"] as? JsonArray
            ?: return invalid(id, "shell", "steps inválidos")
        if (rawSteps.size !in 1..MAX_BATCH_STEPS) {
            return invalid(
                id,
                "shell",
                "el plan debe tener entre 1 y $MAX_BATCH_STEPS pasos",
            )
        }
        val steps = mutableListOf<ShellOperation>()
        val seen = mutableSetOf<String>()
        rawSteps.forEachIndexed { index, element ->
            val step = element as? JsonObject
                ?: return invalid(id, "shell", "paso ${index + 1} inválido")
            val stepAction = step.string("action")?.takeIf { it.isNotBlank() }
                ?: return invalid(id, "shell", "paso ${index + 1}: falta la acción")
            if (stepAction in NON_BATCH_ACTIONS) {
                return invalid(
                    id,
                    "shell",
                    "paso ${index + 1}: acción no permitida en un plan",
                )
            }
            val allowed = when (stepAction) {
                "trim-caches" -> setOf("action")
                "force-stop", "disable", "enable", "uninstall" ->
                    setOf("action", "package")
                "background" -> setOf("action", "package", "mode")
                else -> return invalid(id, "shell", "paso ${index + 1}: acción inválida")
            }
            unknownArg(step, allowed)
                ?.let { return invalid(id, "shell", "paso ${index + 1}: $it") }
            when (
                val parsed = parseShellStep(
                    action = stepAction,
                    packageName = step.string("package")?.takeIf { it.isNotBlank() },
                    mode = step.string("mode")?.takeIf { it.isNotBlank() },
                    topic = null,
                )
            ) {
                is ShellStepParse.Error -> return invalid(
                    id,
                    "shell",
                    "paso ${index + 1}: ${parsed.message}",
                )
                is ShellStepParse.Ok -> {
                    if (!seen.add(parsed.operation.duplicateKey())) {
                        return invalid(id, "shell", "paso ${index + 1}: paso repetido")
                    }
                    steps += parsed.operation
                }
            }
        }
        return CommandParseResult.Valid(
            PhoneCommand.Shell(id, expiresAt, ShellOperation.Batch(steps), approval)
        )
    }

    private fun parseShellStep(
        action: String,
        packageName: String?,
        mode: String?,
        topic: String?,
    ): ShellStepParse {
        return when (action) {
            "info" -> {
                if (packageName != null || mode != null) {
                    return ShellStepParse.Error("argumento no permitido")
                }
                val requestedTopic = ShellTopic.fromWire(topic)
                    ?: return ShellStepParse.Error("tema inválido")
                ShellStepParse.Ok(ShellOperation.Info(requestedTopic))
            }
            "trim-caches" -> {
                if (packageName != null || mode != null || topic != null) {
                    ShellStepParse.Error("argumento no permitido")
                } else {
                    ShellStepParse.Ok(ShellOperation.TrimCaches)
                }
            }
            "force-stop", "disable", "enable", "uninstall" -> {
                if (mode != null || topic != null) {
                    return ShellStepParse.Error("argumento no permitido")
                }
                val pkg = packageName ?: return ShellStepParse.Error("falta el paquete")
                if (action != "enable" && ShellCommandRules.isProtected(pkg)) {
                    return ShellStepParse.Error("paquete protegido")
                }
                if (!isShellPackage(pkg)) return ShellStepParse.Error("paquete inválido")
                ShellStepParse.Ok(
                    when (action) {
                        "force-stop" -> ShellOperation.ForceStop(pkg)
                        "disable" -> ShellOperation.Disable(pkg)
                        "uninstall" -> ShellOperation.Uninstall(pkg)
                        else -> ShellOperation.Enable(pkg)
                    }
                )
            }
            "background" -> {
                if (topic != null) return ShellStepParse.Error("argumento no permitido")
                val pkg = packageName ?: return ShellStepParse.Error("falta el paquete")
                if (ShellCommandRules.isProtected(pkg)) {
                    return ShellStepParse.Error("paquete protegido")
                }
                if (!isShellPackage(pkg)) return ShellStepParse.Error("paquete inválido")
                val requestedMode = ShellMode.fromWire(mode)
                    ?: return ShellStepParse.Error("modo inválido")
                ShellStepParse.Ok(ShellOperation.Background(pkg, requestedMode))
            }
            else -> ShellStepParse.Error("acción inválida")
        }
    }

    /** Identity used to refuse a duplicated step inside one plan. */
    private fun ShellOperation.duplicateKey(): String = when (this) {
        is ShellOperation.TrimCaches -> "trim-caches"
        is ShellOperation.ForceStop -> "force-stop|$packageName"
        is ShellOperation.Background -> "background|$packageName|${mode.wire}"
        is ShellOperation.Disable -> "disable|$packageName"
        is ShellOperation.Enable -> "enable|$packageName"
        is ShellOperation.Uninstall -> "uninstall|$packageName"
        is ShellOperation.Info -> "info|${topic.wire}"
        is ShellOperation.Scan -> "scan"
        is ShellOperation.Batch -> "batch"
    }

    private sealed interface ShellStepParse {
        data class Ok(val operation: ShellOperation) : ShellStepParse
        data class Error(val message: String) : ShellStepParse
    }

    private fun isShellPackage(value: String): Boolean =
        value.length <= ShellCommandRules.MAX_PACKAGE_CHARS && PackageNames.isValid(value)

    private fun target(
        type: String,
        args: JsonObject,
        id: String,
        expiresAt: String?,
        approval: CommandApproval?,
        build: (NodeTarget) -> PhoneCommand,
    ): CommandParseResult {
        unknownArg(args, setOf("text", "id", "x", "y") + APPROVAL_ARG_KEYS)
            ?.let { return invalid(id, type, it) }

        val text = args.string("text")
        val viewId = args.string("id")
        val x = args.int("x")
        val y = args.int("y")
        val forms = listOf(text != null, viewId != null, x != null || y != null).count { it }
        if (forms != 1) return invalid(id, type, "se requiere text, id o x/y")

        val target = when {
            text != null -> {
                if (text.isBlank()) return invalid(id, type, "text inválido")
                if (text.length > MAX_TARGET_TEXT_CHARS) {
                    return invalid(id, type, "text demasiado largo")
                }
                NodeTarget.Text(text)
            }
            viewId != null -> {
                if (viewId.isBlank()) return invalid(id, type, "id inválido")
                if (viewId.length > MAX_TARGET_ID_CHARS) {
                    return invalid(id, type, "id demasiado largo")
                }
                NodeTarget.Id(viewId)
            }
            else -> {
                if (x == null || y == null) return invalid(id, type, "x e y deben ir juntos")
                if (x !in 0..MAX_COORDINATE || y !in 0..MAX_COORDINATE) {
                    return invalid(id, type, "coordenadas inválidas")
                }
                NodeTarget.Point(x, y)
            }
        }
        return CommandParseResult.Valid(build(target))
    }

    /**
     * Reads the approval hint the server attaches to a requeued command. It is
     * tolerated but never trusted: [ApprovalGate] still checks the local ledger.
     */
    private fun approvalOf(root: JsonObject, args: JsonObject): CommandApproval? {
        val approvalId = (args.string("approvalId") ?: root.string("approvalId"))
            ?.takeIf { it.isNotBlank() && it.length <= MAX_APPROVAL_ID_CHARS }
            ?: return null
        val approvedTarget = (args.string("approvedTarget") ?: root.string("approvalTarget"))
            ?.takeIf { it.isNotBlank() }
        val reason = (args.string("approvalReason") ?: root.string("approvalReason"))
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
        // Owner-request fields (phone-bubble, T18). A manual approval omits them
        // and every default stays null; a free command has no approval at all.
        val ownerTurnId = (args.string("ownerTurnId") ?: root.string("ownerTurnId"))
            ?.takeIf { it.isNotBlank() }
        val category = (args.string("approvalCategory") ?: root.string("approvalCategory"))
            ?.takeIf { it.isNotBlank() }
        val expiresAt =
            (args.string("approvalExpiresAt") ?: root.string("approvalExpiresAt"))
                ?.takeIf { it.isNotBlank() }
        return CommandApproval(
            approvalId = approvalId,
            approvedTarget = approvedTarget,
            reason = reason,
            ownerTurnId = ownerTurnId,
            category = category,
            expiresAt = expiresAt,
        )
    }

    private fun invalid(commandId: String, type: String?, error: String) =
        CommandParseResult.Invalid(commandId, type, error)

    private fun unknownArg(args: JsonObject, allowed: Set<String>): String? =
        args.keys.firstOrNull { it !in allowed }?.let { "argumento desconocido: $it" }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

    private fun kotlinx.serialization.json.JsonElement.isBoolean(): Boolean =
        (this as? JsonPrimitive)?.let { !it.isString && (it.content == "true" || it.content == "false") }
            ?: false

    private const val MAX_TEXT_CHARS = 4_000
    private const val MAX_TARGET_TEXT_CHARS = 500
    private const val MAX_TARGET_ID_CHARS = 200
    private const val MAX_APP_CHARS = 200
    private const val MAX_COORDINATE = 100_000
    private const val MAX_WAIT_MS = 10_000
    private const val MAX_APPROVAL_ID_CHARS = 80

    private val APPROVAL_ARG_KEYS = setOf(
        "approvalId",
        "approvedTarget",
        "approvalReason",
        "ownerTurnId",
        "approvalCategory",
        "approvalExpiresAt",
    )

    /** `shell` keys plus the common approval hints; each action rejects the rest. */
    private val SHELL_ARG_KEYS = setOf("action", "package", "mode", "topic") + APPROVAL_ARG_KEYS

    private const val MAX_BATCH_STEPS = 30
    private val NON_BATCH_ACTIONS = setOf("info", "scan", "batch")
}
