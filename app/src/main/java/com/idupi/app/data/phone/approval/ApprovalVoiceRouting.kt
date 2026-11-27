package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalReason
import com.idupi.app.domain.model.PhoneApprovalState
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * How risky a phone-side approval is for the purpose of speaking it (T23).
 *
 * Reuses the same words as [PhoneRiskText] plus the wider family the owner
 * named: sending is messaging, money and destructive steps are never voiced.
 */
enum class ApprovalVoiceCategory { MESSAGING, MONEY, DESTRUCTIVE, UNKNOWN }

/**
 * Classifies the texts an approval carries (target, command args, app label)
 * into the three families the voice flow needs.
 *
 * Whole words only: "Reenviar" is not "enviar", "Pagaremos" is not "Pagar" and
 * "Eliminado" is not "Eliminar". Money and destructive words win over a send
 * word, so a button that says "Enviar y eliminar" is never voiced.
 */
object ApprovalVoiceClassifier {

    fun classify(texts: List<String>): ApprovalVoiceCategory {
        val tokens = texts
            .flatMap { PhoneRiskText.normalize(it).split(SEPARATOR) }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ApprovalVoiceCategory.UNKNOWN
        if (tokens.any { it in DESTRUCTIVE }) return ApprovalVoiceCategory.DESTRUCTIVE
        if (tokens.any { it in MONEY }) return ApprovalVoiceCategory.MONEY
        if (tokens.any { it in MESSAGING }) return ApprovalVoiceCategory.MESSAGING
        return ApprovalVoiceCategory.UNKNOWN
    }

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
    )
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
 * T23).
 *
 * Owner story, 2026-11-26: during a bubble turn the PC agent tapped WhatsApp's
 * send button, the phone's own gate refused it
 * (`phone_detected_risky_target`) and the owner had to approve with her
 * fingers. The approval may go to the bubble by voice only while:
 *
 * 1. a bubble turn started ON THIS PHONE is still active ([OwnerTurnRegistry],
 *    the same rule as T18);
 * 2. the bubble overlay is attached and enabled;
 * 3. the step is messaging, never money or destructive.
 *
 * Everything else — including server-created approvals for other reasons,
 * already decided or expired rows — keeps the notification. Failing closed is
 * the point: the voice loop is a convenience for a case the owner already
 * authorized on this phone, never a new approval surface.
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
        if (approval.reason != PhoneApprovalReason.PHONE_DETECTED) {
            return ApprovalVoiceRoute.Notification
        }
        // An expiry we cannot read fails closed for voice; the notification
        // path already knows how to show it.
        val expiry = expiryMillis(approval) ?: return ApprovalVoiceRoute.Notification
        if (expiry <= nowMillis) return ApprovalVoiceRoute.Notification
        val category = ApprovalVoiceClassifier.classify(texts(approval))
        return if (category == ApprovalVoiceCategory.MESSAGING) {
            ApprovalVoiceRoute.Voice
        } else {
            ApprovalVoiceRoute.Notification
        }
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
) {

    fun handle(approval: PhoneApproval) {
        val route = ApprovalVoiceRouting.decide(
            approval = approval,
            ownerTurnActive = isOwnerTurnActive(),
            bubbleAttached = isBubbleAttached(),
            nowMillis = clock(),
        )
        if (route == ApprovalVoiceRoute.Voice && deliverToBubble(approval)) return
        notifyFallback(approval)
    }
}
