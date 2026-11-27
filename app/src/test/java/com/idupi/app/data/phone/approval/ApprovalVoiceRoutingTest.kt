package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalApp
import com.idupi.app.domain.model.PhoneApprovalCommand
import com.idupi.app.domain.model.PhoneApprovalReason
import com.idupi.app.domain.model.PhoneApprovalRiskKind
import com.idupi.app.domain.model.PhoneApprovalState
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing rule for voice approval of the outbound message (phone-bubble T23).
 *
 * Owner story, 2026-11-26: during a bubble turn the PC agent tapped WhatsApp's
 * send button, the phone's own gate refused it (`phone_detected_risky_target`)
 * and the owner had to approve with her fingers. While a bubble turn started on
 * this phone is active, a messaging approval goes to the bubble by voice
 * instead. Destructive steps are voiced too (T4 + T6): cache, uninstall, a data
 * wipe and any other delete. Money is never voiced.
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
    fun `a generic destructive step during an owner turn goes to the bubble`() {
        listOf(
            "Eliminar",
            "Borrar",
            "Delete",
            "Eliminar chat",
            "Borrar archivo",
        ).forEach { target ->
            assertEquals(
                "'$target' is asked in the bubble",
                ApprovalVoiceRoute.Voice,
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
    fun `a server-created destructive approval during an owner turn goes to the bubble`() {
        val candidate = approval(
            reason = PhoneApprovalReason.RISKY_TEXT,
            target = "Eliminar chat",
            args = buildJsonObject { put("text", "Eliminar chat") },
            requestedBy = "cli",
        )

        assertEquals(
            ApprovalVoiceRoute.Voice,
            ApprovalVoiceRouting.decide(candidate, true, true, now),
        )
    }

    // --- cache and uninstall inside an owner turn (phone-dashboard T4) ----

    @Test
    fun `clearing the cache during an owner turn goes to the bubble`() {
        listOf("Borrar caché", "Clear cache", "Borrar cache").forEach { target ->
            assertEquals(
                "'$target' is asked in the bubble",
                ApprovalVoiceRoute.Voice,
                ApprovalVoiceRouting.decide(
                    approval(
                        target = target,
                        args = buildJsonObject { put("text", target) },
                    ),
                    true,
                    true,
                    now,
                ),
            )
        }
    }

    @Test
    fun `uninstalling during an owner turn goes to the bubble`() {
        listOf("Desinstalar", "Uninstall", "Desinstalar WhatsApp").forEach { target ->
            assertEquals(
                "'$target' is asked in the bubble",
                ApprovalVoiceRoute.Voice,
                ApprovalVoiceRouting.decide(
                    approval(
                        target = target,
                        args = buildJsonObject { put("text", target) },
                    ),
                    true,
                    true,
                    now,
                ),
            )
        }
    }

    @Test
    fun `a server-created cache approval during an owner turn goes to the bubble`() {
        // The server classifier gates the CLI tap ("risky_text", requestedBy
        // "cli") before the phone sees it. It is still the owner's own turn.
        val candidate = approval(
            reason = PhoneApprovalReason.RISKY_TEXT,
            target = "Borrar caché",
            args = buildJsonObject { put("text", "Borrar caché") },
            requestedBy = "cli",
        )

        assertEquals(
            ApprovalVoiceRoute.Voice,
            ApprovalVoiceRouting.decide(candidate, true, true, now),
        )
    }

    @Test
    fun `destructive approvals outside an owner turn keep the notification`() {
        listOf("Borrar caché", "Desinstalar", "Eliminar chat", "Borrar datos").forEach { target ->
            val candidate = approval(target = target, args = buildJsonObject { put("text", target) })
            assertEquals(
                "'$target' without a turn notifies",
                ApprovalVoiceRoute.Notification,
                ApprovalVoiceRouting.decide(candidate, false, true, now),
            )
            assertEquals(
                "'$target' with a detached bubble notifies",
                ApprovalVoiceRoute.Notification,
                ApprovalVoiceRouting.decide(candidate, true, false, now),
            )
            assertEquals(
                "'$target' expired notifies",
                ApprovalVoiceRoute.Notification,
                ApprovalVoiceRouting.decide(candidate.copy(expiresAt = ""), true, true, now),
            )
        }
    }

    @Test
    fun `money still wins over a cache, uninstall or wipe word`() {
        listOf("Pagar y borrar caché", "Comprar y desinstalar", "Pagar y borrar datos").forEach { target ->
            assertEquals(
                "'$target' stays money",
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
    fun `a data wipe during an owner turn is voiced with its own kind`() {
        val candidate = approval(
            reason = PhoneApprovalReason.RISKY_TEXT,
            target = "Borrar datos",
            args = buildJsonObject { put("text", "Borrar datos") },
            requestedBy = "cli",
            riskKind = PhoneApprovalRiskKind.DATA_WIPE,
        )

        assertEquals(
            ApprovalVoiceRoute.Voice,
            ApprovalVoiceRouting.decide(candidate, true, true, now),
        )
        assertEquals(ApprovalVoiceKind.DATA_WIPE, ApprovalVoiceRouting.voiceKind(candidate))
    }

    @Test
    fun `the data-wipe kind also comes from the phrase when the server is older`() {
        // A server before T6 sends the phrase without riskKind; the phone still
        // recognizes it and words it with what is lost.
        val candidate = approval(
            reason = PhoneApprovalReason.PHONE_DETECTED,
            target = "Borrar datos",
            args = buildJsonObject { put("text", "Borrar datos") },
        )
        assertNull(candidate.riskKind)

        assertEquals(
            ApprovalVoiceRoute.Voice,
            ApprovalVoiceRouting.decide(candidate, true, true, now),
        )
        assertEquals(ApprovalVoiceKind.DATA_WIPE, ApprovalVoiceRouting.voiceKind(candidate))
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

    @Test
    fun `cache and uninstall targets classify apart from other destructive steps`() {
        listOf(
            listOf("Borrar caché"),
            listOf("Clear cache"),
            listOf("borrar la cache"),
        ).forEach { texts ->
            assertEquals("$texts is cache", ApprovalVoiceCategory.CACHE_CLEAR, ApprovalVoiceClassifier.classify(texts))
        }

        listOf(
            listOf("Desinstalar"),
            listOf("Uninstall WhatsApp"),
            listOf("desinstala la app"),
        ).forEach { texts ->
            assertEquals("$texts is uninstall", ApprovalVoiceCategory.UNINSTALL, ApprovalVoiceClassifier.classify(texts))
        }

        assertEquals(
            ApprovalVoiceCategory.DESTRUCTIVE,
            ApprovalVoiceClassifier.classify(listOf("Eliminar chat")),
        )
    }

    @Test
    fun `a data wipe classifies apart from a generic destructive step`() {
        listOf(
            listOf("Borrar datos"),
            listOf("Borrar los datos"),
            listOf("Clear data"),
            listOf("clear storage"),
        ).forEach { texts ->
            assertEquals(
                "$texts is a data wipe",
                ApprovalVoiceCategory.DATA_WIPE,
                ApprovalVoiceClassifier.classify(texts),
            )
        }

        assertEquals(
            ApprovalVoiceCategory.DESTRUCTIVE,
            ApprovalVoiceClassifier.classify(listOf("Eliminar chat")),
        )
        assertEquals(
            "money wins",
            ApprovalVoiceCategory.MONEY,
            ApprovalVoiceClassifier.classify(listOf("Pagar y borrar caché")),
        )
        assertEquals(
            "money wins over a wipe too",
            ApprovalVoiceCategory.MONEY,
            ApprovalVoiceClassifier.classify(listOf("Pagar y borrar datos")),
        )
    }

    @Test
    fun `the voice kind mirrors the approval family`() {
        assertEquals(
            ApprovalVoiceKind.CACHE,
            ApprovalVoiceRouting.voiceKind(
                approval(target = "Borrar caché", args = buildJsonObject { put("text", "Borrar caché") }),
            ),
        )
        assertEquals(
            ApprovalVoiceKind.UNINSTALL,
            ApprovalVoiceRouting.voiceKind(
                approval(target = "Desinstalar", args = buildJsonObject { put("text", "Desinstalar") }),
            ),
        )
        assertEquals(
            ApprovalVoiceKind.DATA_WIPE,
            ApprovalVoiceRouting.voiceKind(
                approval(target = "Borrar datos", args = buildJsonObject { put("text", "Borrar datos") }),
            ),
        )
        assertEquals(
            ApprovalVoiceKind.DESTRUCTIVE,
            ApprovalVoiceRouting.voiceKind(
                approval(target = "Eliminar chat", args = buildJsonObject { put("text", "Eliminar chat") }),
            ),
        )
        assertEquals(ApprovalVoiceKind.MESSAGE, ApprovalVoiceRouting.voiceKind(approval()))
        assertNull(
            ApprovalVoiceRouting.voiceKind(
                approval(target = "Confirmar", args = buildJsonObject { put("text", "Confirmar") }),
            ),
        )
    }

    @Test
    fun `the destructive action comes from the approval text`() {
        assertEquals(
            "eliminar",
            ApprovalVoiceRouting.destructiveAction(
                approval(target = "Eliminar chat", args = buildJsonObject { put("text", "Eliminar chat") }),
            ),
        )
        assertEquals(
            "borrar",
            ApprovalVoiceRouting.destructiveAction(
                approval(target = "Borrar archivo", args = buildJsonObject { put("text", "Borrar archivo") }),
            ),
        )
        assertNull(ApprovalVoiceRouting.destructiveAction(approval()))
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

    @Test
    fun `an approval bound to a turn that is no longer alive notifies`() {
        val notified = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        val dispatcher = dispatcher(
            ownerTurnActive = true,
            attached = true,
            delivered = delivered,
            notified = notified,
            ownerTurnActiveFor = { false },
        )

        dispatcher.handle(
            approval(
                reason = PhoneApprovalReason.RISKY_TEXT,
                target = "Borrar caché",
                args = buildJsonObject { put("text", "Borrar caché") },
                requestedBy = "cli",
                ownerTurnId = "pat-0123456789ab",
            )
        )

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
        ownerTurnActiveFor: ((PhoneApproval) -> Boolean)? = null,
    ): ApprovalVoiceDispatcher = ApprovalVoiceDispatcher(
        isOwnerTurnActive = { ownerTurnActive },
        isBubbleAttached = { attached },
        clock = { now },
        deliverToBubble = { candidate ->
            if (accept) delivered += candidate.id
            accept
        },
        notifyFallback = { candidate -> notified += candidate.id },
        isOwnerTurnActiveFor = ownerTurnActiveFor ?: { ownerTurnActive },
    )

    private fun approval(
        id: String = "apr-f028e4165b37",
        state: String = PhoneApprovalState.PENDING,
        reason: String = PhoneApprovalReason.PHONE_DETECTED,
        target: String? = "Enviar",
        commandType: String = "tap",
        args: JsonObject = buildJsonObject { put("id", "com.whatsapp:id/send") },
        appLabel: String? = "WhatsApp",
        requestedBy: String = "phone",
        ownerTurnId: String? = null,
        riskKind: String? = null,
        expiresAtMillis: Long = now + 60_000L,
        expiresAt: String = Instant.ofEpochMilli(expiresAtMillis).toString(),
    ): PhoneApproval = PhoneApproval(
        id = id,
        state = state,
        command = PhoneApprovalCommand(type = commandType, args = args),
        reason = reason,
        riskKind = riskKind,
        target = target,
        foregroundApp = appLabel?.let { PhoneApprovalApp("com.whatsapp", it) },
        requestedBy = requestedBy,
        createdAt = Instant.ofEpochMilli(now).toString(),
        expiresAt = expiresAt,
        ownerTurnId = ownerTurnId,
    )
}
