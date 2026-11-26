package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalApp
import com.idupi.app.domain.model.PhoneApprovalCommand
import com.idupi.app.domain.model.PhoneApprovalReason
import com.idupi.app.domain.model.PhoneApprovalState
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing rule for voice approval of the outbound message (phone-bubble T23).
 *
 * Owner story, 2026-11-26: during a bubble turn the PC agent tapped WhatsApp's
 * send button, the phone's own gate refused it (`phone_detected_risky_target`)
 * and the owner had to approve with her fingers. While a bubble turn started on
 * this phone is active, a messaging approval goes to the bubble by voice
 * instead; everything else keeps the notification, and money or destructive
 * steps are never voiced.
 */
class ApprovalVoiceRoutingTest {

    private val now = Instant.parse("2026-11-26T15:00:00Z").toEpochMilli()

    // --- routing ----------------------------------------------------------

    @Test
    fun `a messaging tap during an active owner turn goes to the bubble`() {
        val decision = ApprovalVoiceRouting.decide(
            approval = approval(),
            ownerTurnActive = true,
            bubbleAttached = true,
            nowMillis = now,
        )

        assertEquals(ApprovalVoiceRoute.Voice, decision)
    }

    @Test
    fun `without an active owner turn the approval keeps the notification`() {
        val decision = ApprovalVoiceRouting.decide(
            approval = approval(),
            ownerTurnActive = false,
            bubbleAttached = true,
            nowMillis = now,
        )

        assertEquals(ApprovalVoiceRoute.Notification, decision)
    }

    @Test
    fun `without the bubble attached the approval keeps the notification`() {
        val decision = ApprovalVoiceRouting.decide(
            approval = approval(),
            ownerTurnActive = true,
            bubbleAttached = false,
            nowMillis = now,
        )

        assertEquals(ApprovalVoiceRoute.Notification, decision)
    }

    @Test
    fun `money steps are never voiced`() {
        listOf(
            approval(target = "Pagar", args = buildJsonObject { put("text", "Pagar") }),
            approval(target = "Transferir", args = buildJsonObject { put("text", "Transferir") }),
            approval(target = "Comprar", args = buildJsonObject { put("text", "Comprar") }),
            approval(target = "Pagar", appLabel = "Mercado Pago"),
            approval(target = "Enviar", appLabel = "Banco de Chile"),
        ).forEach { candidate ->
            assertEquals(
                "money approval ${candidate.target}/${candidate.foregroundApp?.label} never speaks",
                ApprovalVoiceRoute.Notification,
                ApprovalVoiceRouting.decide(candidate, true, true, now),
            )
        }
    }

    @Test
    fun `destructive steps are never voiced`() {
        listOf(
            "Eliminar",
            "Borrar",
            "Delete",
        ).forEach { target ->
            assertEquals(
                "'$target' never speaks",
                ApprovalVoiceRoute.Notification,
                ApprovalVoiceRouting.decide(
                    approval(target = target, args = buildJsonObject { put("text", target) }),
                    true,
                    true,
                    now,
                ),
            )
        }
    }

    @Test
    fun `an unrecognized risky step keeps the notification`() {
        listOf(
            "Confirmar",
            "Compartir",
            "Publicar",
        ).forEach { target ->
            assertEquals(
                "'$target' is not messaging",
                ApprovalVoiceRoute.Notification,
                ApprovalVoiceRouting.decide(
                    approval(target = target, args = buildJsonObject { put("text", target) }),
                    true,
                    true,
                    now,
                ),
            )
        }
    }

    @Test
    fun `only a phone-detected approval may be voiced`() {
        listOf(
            PhoneApprovalReason.RISKY_TEXT,
            PhoneApprovalReason.TYPE_SUBMIT,
            PhoneApprovalReason.SENSITIVE_APP,
        ).forEach { reason ->
            assertEquals(
                "'$reason' keeps the notification",
                ApprovalVoiceRoute.Notification,
                ApprovalVoiceRouting.decide(approval(reason = reason), true, true, now),
            )
        }
    }

    @Test
    fun `a decided or expired approval is never voiced`() {
        assertEquals(
            ApprovalVoiceRoute.Notification,
            ApprovalVoiceRouting.decide(
                approval(state = PhoneApprovalState.APPROVED),
                true,
                true,
                now,
            ),
        )
        assertEquals(
            ApprovalVoiceRoute.Notification,
            ApprovalVoiceRouting.decide(
                approval(expiresAtMillis = now - 1),
                true,
                true,
                now,
            ),
        )
        assertEquals(
            "an unparseable expiry fails closed for voice",
            ApprovalVoiceRoute.Notification,
            ApprovalVoiceRouting.decide(
                approval(expiresAt = ""),
                true,
                true,
                now,
            ),
        )
    }

    @Test
    fun `an approval without an id is never voiced`() {
        assertEquals(
            ApprovalVoiceRoute.Notification,
            ApprovalVoiceRouting.decide(approval(id = " "), true, true, now),
        )
    }

    // --- classifier -------------------------------------------------------

    @Test
    fun `send words and whatsapp send ids classify as messaging`() {
        listOf(
            listOf("Enviar"),
            listOf("enviar mensaje"),
            listOf("Send"),
            listOf("Mandar"),
            listOf("com.whatsapp:id/send"),
            listOf("com.whatsapp:id/send", "WhatsApp"),
        ).forEach { texts ->
            assertEquals(
                "$texts is messaging",
                ApprovalVoiceCategory.MESSAGING,
                ApprovalVoiceClassifier.classify(texts),
            )
        }
    }

    @Test
    fun `money and destructive words win over a send word`() {
        assertEquals(
            ApprovalVoiceCategory.MONEY,
            ApprovalVoiceClassifier.classify(listOf("Enviar", "Pagar")),
        )
        assertEquals(
            ApprovalVoiceCategory.MONEY,
            ApprovalVoiceClassifier.classify(listOf("com.whatsapp:id/send", "Mercado Pago")),
        )
        assertEquals(
            ApprovalVoiceCategory.DESTRUCTIVE,
            ApprovalVoiceClassifier.classify(listOf("Enviar y eliminar")),
        )
    }

    @Test
    fun `text without a known category is unknown`() {
        assertEquals(ApprovalVoiceCategory.UNKNOWN, ApprovalVoiceClassifier.classify(emptyList()))
        assertEquals(ApprovalVoiceCategory.UNKNOWN, ApprovalVoiceClassifier.classify(listOf("  ")))
        assertEquals(ApprovalVoiceCategory.UNKNOWN, ApprovalVoiceClassifier.classify(listOf("Confirmar")))
    }

    @Test
    fun `word fragments never classify`() {
        assertEquals(ApprovalVoiceCategory.UNKNOWN, ApprovalVoiceClassifier.classify(listOf("Reenviar")))
        assertEquals(ApprovalVoiceCategory.UNKNOWN, ApprovalVoiceClassifier.classify(listOf("Pagaremos")))
        assertEquals(ApprovalVoiceCategory.UNKNOWN, ApprovalVoiceClassifier.classify(listOf("Eliminado")))
    }

    // --- fallback ---------------------------------------------------------

    @Test
    fun `the fallback notification posts only while the approval is live`() {
        assertTrue(ApprovalVoiceFallback.shouldNotify(approval(), stillPending = true, nowMillis = now))
        assertFalse(ApprovalVoiceFallback.shouldNotify(approval(), stillPending = false, nowMillis = now))
        assertFalse(
            ApprovalVoiceFallback.shouldNotify(
                approval(expiresAtMillis = now),
                stillPending = true,
                nowMillis = now,
            ),
        )
        assertFalse(
            ApprovalVoiceFallback.shouldNotify(
                approval(state = PhoneApprovalState.REJECTED),
                stillPending = true,
                nowMillis = now,
            ),
        )
        assertFalse(
            ApprovalVoiceFallback.shouldNotify(approval(id = ""), stillPending = true, nowMillis = now),
        )
    }

    @Test
    fun `an unknown expiry still allows the fallback notification`() {
        assertTrue(
            ApprovalVoiceFallback.shouldNotify(
                approval(expiresAt = ""),
                stillPending = true,
                nowMillis = now,
            ),
        )
    }

    // --- dispatcher -------------------------------------------------------

    @Test
    fun `a voiced approval is delivered to the bubble and never notified`() {
        val notified = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        val dispatcher = dispatcher(
            ownerTurnActive = true,
            attached = true,
            delivered = delivered,
            notified = notified,
        )

        dispatcher.handle(approval())

        assertEquals(listOf("apr-f028e4165b37"), delivered)
        assertTrue(notified.isEmpty())
    }

    @Test
    fun `a refused delivery falls back to the notification`() {
        val notified = mutableListOf<String>()
        val dispatcher = dispatcher(
            ownerTurnActive = true,
            attached = true,
            delivered = mutableListOf(),
            notified = notified,
            accept = false,
        )

        dispatcher.handle(approval())

        assertEquals(listOf("apr-f028e4165b37"), notified)
    }

    @Test
    fun `an inactive turn or a detached bubble notifies`() {
        listOf(false to true, true to false).forEach { (ownerTurn, attached) ->
            val notified = mutableListOf<String>()
            val dispatcher = dispatcher(
                ownerTurnActive = ownerTurn,
                attached = attached,
                delivered = mutableListOf(),
                notified = notified,
            )

            dispatcher.handle(approval())

            assertEquals("turn=$ownerTurn attached=$attached notifies", 1, notified.size)
        }
    }

    @Test
    fun `a money approval is notified without asking the bubble`() {
        val notified = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        val dispatcher = dispatcher(
            ownerTurnActive = true,
            attached = true,
            delivered = delivered,
            notified = notified,
        )

        dispatcher.handle(approval(target = "Pagar", args = buildJsonObject { put("text", "Pagar") }))

        assertTrue(delivered.isEmpty())
        assertEquals(1, notified.size)
    }

    // --- fixtures ---------------------------------------------------------

    private fun dispatcher(
        ownerTurnActive: Boolean,
        attached: Boolean,
        delivered: MutableList<String>,
        notified: MutableList<String>,
        accept: Boolean = true,
    ): ApprovalVoiceDispatcher = ApprovalVoiceDispatcher(
        isOwnerTurnActive = { ownerTurnActive },
        isBubbleAttached = { attached },
        clock = { now },
        deliverToBubble = { candidate ->
            if (accept) delivered += candidate.id
            accept
        },
        notifyFallback = { candidate -> notified += candidate.id },
    )

    private fun approval(
        id: String = "apr-f028e4165b37",
        state: String = PhoneApprovalState.PENDING,
        reason: String = PhoneApprovalReason.PHONE_DETECTED,
        target: String? = "Enviar",
        commandType: String = "tap",
        args: JsonObject = buildJsonObject { put("id", "com.whatsapp:id/send") },
        appLabel: String? = "WhatsApp",
        expiresAtMillis: Long = now + 60_000L,
        expiresAt: String = Instant.ofEpochMilli(expiresAtMillis).toString(),
    ): PhoneApproval = PhoneApproval(
        id = id,
        state = state,
        command = PhoneApprovalCommand(type = commandType, args = args),
        reason = reason,
        target = target,
        foregroundApp = appLabel?.let { PhoneApprovalApp("com.whatsapp", it) },
        requestedBy = "phone",
        createdAt = Instant.ofEpochMilli(now).toString(),
        expiresAt = expiresAt,
    )
}
