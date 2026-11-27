package com.idupi.app.data.phone.control

import kotlinx.serialization.json.Json
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
}
