package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalReason
import com.idupi.app.domain.model.PhoneApprovalRiskKind
import com.idupi.app.domain.model.PhoneApprovalState
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * How risky a phone-side approval is for the purpose of speaking it (T23,
 * extended by phone-dashboard T4).
 *
 * Reuses the same words as [PhoneRiskText] plus the wider family the owner
 * named: sending is messaging, money is never voiced, and every destructive
 * step (cache, uninstall, data wipe, any other delete) may be asked by voice
 * during the owner's own turn.
 */
enum class ApprovalVoiceCategory {
    MESSAGING,
    MONEY,
    DESTRUCTIVE,
    CACHE_CLEAR,
    UNINSTALL,
    DATA_WIPE,
    UNKNOWN,
}

/**
 * What the bubble must ask when it voices a destructive approval
 * (phone-dashboard T4 + T6). [MESSAGE] keeps the T23 wording; [CACHE] and
 * [UNINSTALL] name the Settings app and read the size line; [DATA_WIPE] says
 * what is lost before asking; [DESTRUCTIVE] names the action and the screen
 * title and warns that it cannot be undone.
 */
enum class ApprovalVoiceKind { MESSAGE, CACHE, UNINSTALL, DATA_WIPE, DESTRUCTIVE }

/**
 * Classifies the texts an approval carries (target, command args, app label)
 * into the families the voice flow needs.
 *
 * Whole words only: "Reenviar" is not "enviar", "Pagaremos" is not "Pagar" and
 * "Eliminado" is not "Eliminar". Money wins over every destructive word, so a
 * button that says "Pagar y borrar caché" is never voiced. "Borrar caché" and
 * "Clear cache" classify as [ApprovalVoiceCategory.CACHE_CLEAR] before the
 * generic destructive check, and a data wipe gets its own category so the
 * bubble can say what is lost (phone-dashboard T6).
 */
object ApprovalVoiceClassifier {

    fun classify(texts: List<String>): ApprovalVoiceCategory {
        val tokens = texts
            .flatMap { PhoneRiskText.normalize(it).split(SEPARATOR) }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ApprovalVoiceCategory.UNKNOWN
        if (tokens.any { it in MONEY }) return ApprovalVoiceCategory.MONEY
        if (texts.any { PhoneRiskText.isDataWipe(it) }) {
            return ApprovalVoiceCategory.DATA_WIPE
        }
        if (tokens.any { it in CACHE } && tokens.any { it in CLEAR }) {
            return ApprovalVoiceCategory.CACHE_CLEAR
        }
        if (tokens.any { it in UNINSTALL }) return ApprovalVoiceCategory.UNINSTALL
        if (tokens.any { it in DESTRUCTIVE }) return ApprovalVoiceCategory.DESTRUCTIVE
        if (tokens.any { it in MESSAGING }) return ApprovalVoiceCategory.MESSAGING
        return ApprovalVoiceCategory.UNKNOWN
    }

    /** The first destructive verb of [text], lowercase, or null. */
    fun destructiveVerb(text: String): String? =
        PhoneRiskText.normalize(text)
            .split(SEPARATOR)
            .firstOrNull { it in DESTRUCTIVE }

    private val SEPARATOR = Regex("[^a-z0-9]+")

    private val MESSAGING = setOf(
        "enviar",
        "envia",
        "envio",
        "send",
        "mandar",
        "manda",
        "mensaje",
        "message",
    )

    private val MONEY = setOf(
        "pagar",
        "pago",
        "pagos",
        "pay",
        "payment",
        "transferir",
        "transferencia",
        "transfer",
        "comprar",
        "compra",
        "buy",
        "purchase",
        "banco",
        "bank",
        "billetera",
        "wallet",
        "depositar",
        "deposito",
        "retirar",
        "retiro",
    )

    private val DESTRUCTIVE = setOf(
        "eliminar",
        "elimina",
        "borrar",
        "borra",
        "delete",
        "remove",
        "destruir",
        "desinstalar",
        "desinstala",
        "uninstall",
    )

    /** The cache noun; normalized text has no accents. */
    private val CACHE = setOf("cache", "caché")

    /** Deletion verbs that make "caché" a cache clear rather than a menu. */
    private val CLEAR = setOf("borrar", "borra", "eliminar", "elimina", "delete", "clear", "limpiar", "limpia")

    private val UNINSTALL = setOf("desinstalar", "desinstala", "desinstalacion", "uninstall")
}

/** Where a phone-side approval is handled (T23). */
sealed interface ApprovalVoiceRoute {

    /** The bubble asks the owner out loud; no notification is posted. */
    data object Voice : ApprovalVoiceRoute

    /** The usual approval notification, exactly as before T23. */
    data object Notification : ApprovalVoiceRoute
}

/**
 * Routing rule for the voice approval of an outbound message (phone-bubble
 * T23) and of the cache/uninstall steps of an owner optimization
 * (phone-dashboard T4).
 *
 * Owner story, 2026-11-26: during a bubble turn the PC agent tapped WhatsApp's
 * send button, the phone's own gate refused it
 * (`phone_detected_risky_target`) and the owner had to approve with her
 * fingers. The approval may go to the bubble by voice only while:
 *
 * 1. a bubble turn started ON THIS PHONE is still active ([OwnerTurnRegistry],
 *    the same rule as T18); when the approval carries `ownerTurnId`, the
 *    caller refines this to that exact turn;
 * 2. the bubble overlay is attached and enabled;
 * 3. the step is messaging, or a destructive step the owner's own turn asked
 *    for: cache, uninstall, a data wipe or any other delete. Money is never
 *    voice-approvable. Owner correction (T6): a data wipe is not blocked; it
 *    is one more destructive step that the bubble states and the owner
 *    approves.
 *
 * A server-created cache/uninstall approval (`risky_text`, requestedBy "cli")
 * follows the same rule as the phone-detected one: it came from the CLI child
 * of the same owner turn.
 *
 * Everything else — already decided or expired rows included — keeps the
 * notification. Failing closed is the point: the voice loop is a convenience
 * for a case the owner already authorized on this phone, never a new approval
 * surface.
 */
object ApprovalVoiceRouting {

    fun decide(
        approval: PhoneApproval,
        ownerTurnActive: Boolean,
        bubbleAttached: Boolean,
        nowMillis: Long,
    ): ApprovalVoiceRoute {
        if (!ownerTurnActive || !bubbleAttached) return ApprovalVoiceRoute.Notification
        if (approval.id.isBlank()) return ApprovalVoiceRoute.Notification
        if (approval.state != PhoneApprovalState.PENDING) return ApprovalVoiceRoute.Notification
        // An expiry we cannot read fails closed for voice; the notification
        // path already knows how to show it.
        val expiry = expiryMillis(approval) ?: return ApprovalVoiceRoute.Notification
        if (expiry <= nowMillis) return ApprovalVoiceRoute.Notification
        // phone-shell T4: disabling an app is destructive and may be voiced
        // during the owner's own turn, exactly like a delete.
        if (isShellDestructive(approval)) return ApprovalVoiceRoute.Voice
        val category = ApprovalVoiceClassifier.classify(texts(approval))
        return when {
            // T23: only the phone-detected refusal of a messaging step.
            category == ApprovalVoiceCategory.MESSAGING &&
                approval.reason == PhoneApprovalReason.PHONE_DETECTED ->
                ApprovalVoiceRoute.Voice
            // T4 + T6: a destructive step may be gated by the phone or by the
            // server before the tap runs; both belong to the same owner turn.
            category in DESTRUCTIVE_CATEGORIES &&
                approval.reason in DESTRUCTIVE_REASONS ->
                ApprovalVoiceRoute.Voice
            else -> ApprovalVoiceRoute.Notification
        }
    }

    /** Every destructive family the bubble may ask during an owner turn. */
    private val DESTRUCTIVE_CATEGORIES = setOf(
        ApprovalVoiceCategory.CACHE_CLEAR,
        ApprovalVoiceCategory.UNINSTALL,
        ApprovalVoiceCategory.DATA_WIPE,
        ApprovalVoiceCategory.DESTRUCTIVE,
    )

    /** Reasons a command of the owner's own turn can carry. */
    private val DESTRUCTIVE_REASONS = setOf(
        PhoneApprovalReason.PHONE_DETECTED,
        PhoneApprovalReason.RISKY_TEXT,
    )

    /**
     * Which sentence the bubble must speak for this approval, or null when the
     * approval is not one the voice flow owns. `riskKind` wins over the text:
     * a server that knows the step is a data wipe can say so even when the
     * command only carries an id.
     */
    fun voiceKind(approval: PhoneApproval): ApprovalVoiceKind? {
        if (approval.riskKind == PhoneApprovalRiskKind.DATA_WIPE) {
            return ApprovalVoiceKind.DATA_WIPE
        }
        if (isShellDestructive(approval)) return ApprovalVoiceKind.DESTRUCTIVE
        return when (ApprovalVoiceClassifier.classify(texts(approval))) {
            ApprovalVoiceCategory.MESSAGING -> ApprovalVoiceKind.MESSAGE
            ApprovalVoiceCategory.CACHE_CLEAR -> ApprovalVoiceKind.CACHE
            ApprovalVoiceCategory.UNINSTALL -> ApprovalVoiceKind.UNINSTALL
            ApprovalVoiceCategory.DATA_WIPE -> ApprovalVoiceKind.DATA_WIPE
            ApprovalVoiceCategory.DESTRUCTIVE -> ApprovalVoiceKind.DESTRUCTIVE
            else -> null
        }
    }

    /** The destructive verb the bubble should say ("eliminar", "borrar"), or null. */
    fun destructiveAction(approval: PhoneApproval): String? {
        if (isShellDestructive(approval)) return "desactivar"
        val candidates = buildList {
            approval.target?.let(::add)
            addAll(commandTexts(approval))
        }
        return candidates.firstNotNullOfOrNull(ApprovalVoiceClassifier::destructiveVerb)
    }

    /** A typed `shell` disable is the destructive shell step (phone-shell T4). */
    private fun isShellDestructive(approval: PhoneApproval): Boolean {
        if (approval.command.type != "shell") return false
        val action = (approval.command.args["action"] as? JsonPrimitive)?.contentOrNull
        return action == "disable"
    }

    /** True when the approval carries a readable deadline that already passed. */
    fun isExpired(approval: PhoneApproval, nowMillis: Long): Boolean =
        expiryMillis(approval)?.let { it <= nowMillis } ?: false

    /** The approval deadline in epoch millis, or null when unreadable. */
    fun expiryMillis(approval: PhoneApproval): Long? = runCatching {
        Instant.parse(approval.expiresAt).toEpochMilli()
    }.getOrNull()

    /** The texts the classifier reads: who, what and where. */
    private fun texts(approval: PhoneApproval): List<String> = buildList {
        approval.target?.let(::add)
        addAll(commandTexts(approval))
        approval.foregroundApp?.label?.let(::add)
        approval.foregroundApp?.packageName?.let(::add)
    }

    private fun commandTexts(approval: PhoneApproval): List<String> = buildList {
        val args = approval.command.args
        (args[ARG_TEXT] as? JsonPrimitive)?.contentOrNull?.let(::add)
        (args[ARG_ID] as? JsonPrimitive)?.contentOrNull?.let(::add)
    }

    private const val ARG_TEXT = "text"
    private const val ARG_ID = "id"
}

/**
 * When the voice loop ends without an answer, the owner still needs a surface:
 * the normal notification is posted only while the approval is live and
 * pending, never for one already decided or expired.
 */
object ApprovalVoiceFallback {

    fun shouldNotify(
        approval: PhoneApproval,
        stillPending: Boolean,
        nowMillis: Long,
    ): Boolean {
        if (!stillPending) return false
        if (approval.id.isBlank()) return false
        if (approval.state != PhoneApprovalState.PENDING) return false
        // An unreadable expiry does not block the fallback: the notification
        // path already tolerates it (the card shows no countdown).
        val expiry = ApprovalVoiceRouting.expiryMillis(approval) ?: return true
        return expiry > nowMillis
    }
}

/**
 * Decides the surface of each freshly polled approval (phone-bubble T23).
 *
 * The approval poll knows which rows are new; this coordinator asks the pure
 * routing rule and either hands the approval to the attached bubble or posts
 * the usual notification. A bubble that cannot take it (detached between the
 * check and the offer, another loop already running) falls back to the
 * notification through [notifyFallback], so a routed approval is never lost.
 */
class ApprovalVoiceDispatcher(
    private val isOwnerTurnActive: () -> Boolean,
    private val isBubbleAttached: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deliverToBubble: (PhoneApproval) -> Boolean,
    private val notifyFallback: (PhoneApproval) -> Unit,
    /**
     * Per-approval refinement of [isOwnerTurnActive]: when an approval carries
     * `ownerTurnId`, production also checks that exact turn is still active on
     * this phone (phone-dashboard T4). Defaults to the global condition so the
     * existing behaviour and tests stay unchanged.
     */
    private val isOwnerTurnActiveFor: (PhoneApproval) -> Boolean = { isOwnerTurnActive() },
) {

    fun handle(approval: PhoneApproval) {
        val route = ApprovalVoiceRouting.decide(
            approval = approval,
            ownerTurnActive = isOwnerTurnActiveFor(approval),
            bubbleAttached = isBubbleAttached(),
            nowMillis = clock(),
        )
        if (route == ApprovalVoiceRoute.Voice && deliverToBubble(approval)) return
        notifyFallback(approval)
    }
}
