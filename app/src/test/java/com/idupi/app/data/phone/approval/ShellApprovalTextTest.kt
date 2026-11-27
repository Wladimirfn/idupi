package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalCommand
import com.idupi.app.domain.model.PhoneApprovalReason
import com.idupi.app.domain.model.PhoneApprovalState
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shell approvals in the Ajustes card, notification and voice routing
 * (phone-shell T4). The owner must read what the command will do, and a
 * disable is the one shell step the bubble may ask by voice during a turn.
 */
class ShellApprovalTextTest {

    private val now = 1_000_000L

    private fun shellApproval(
        action: String,
        packageName: String? = null,
        mode: String? = null,
        reason: String = "optimization",
        ownerTurnId: String? = null,
    ): PhoneApproval {
        val args = buildJsonObject {
            put("action", action)
            packageName?.let { put("package", it) }
            mode?.let { put("mode", it) }
        }
        return PhoneApproval(
            id = "apr-shell-123456",
            state = PhoneApprovalState.PENDING,
            command = PhoneApprovalCommand(type = "shell", args = args),
            reason = reason,
            expiresAt = Instant.ofEpochMilli(now + 600_000L).toString(),
            ownerTurnId = ownerTurnId,
        )
    }

    @Test
    fun `the card names each shell action`() {
        assertEquals(
            "Liberar la caché de todas las apps",
            ApprovalCardText.actionLine(shellApproval("trim-caches")),
        )
        assertEquals(
            "Detener la app «com.example.app»",
            ApprovalCardText.actionLine(shellApproval("force-stop", "com.example.app")),
        )
        assertEquals(
            "Permitir el segundo plano de «com.example.app»",
            ApprovalCardText.actionLine(shellApproval("background", "com.example.app", "allow")),
        )
        assertEquals(
            "Limitar el segundo plano de «com.example.app»",
            ApprovalCardText.actionLine(shellApproval("background", "com.example.app", "ignore")),
        )
        assertEquals(
            "Desactivar la app «com.example.app»",
            ApprovalCardText.actionLine(shellApproval("disable", "com.example.app")),
        )
        assertEquals(
            "Activar la app «com.example.app»",
            ApprovalCardText.actionLine(shellApproval("enable", "com.example.app")),
        )
    }

    @Test
    fun `the detail lines show the action and the package`() {
        val lines = ApprovalCardText.detailLines(
            shellApproval("disable", "com.example.app"),
        )
        assertTrue(lines.contains("Acción: disable"))
        assertTrue(lines.contains("App: com.example.app"))
        assertTrue(lines.contains("Aprobación: apr-shell-123456"))
    }

    @Test
    fun `risk reasons have their own label`() {
        assertEquals(
            "Optimización del teléfono",
            ApprovalCardText.reasonLabel(shellApproval("trim-caches", reason = "optimization")),
        )
        assertEquals(
            "Acción destructiva",
            ApprovalCardText.reasonLabel(
                shellApproval("disable", "com.example.app", reason = "destructive")
            ),
        )
    }

    @Test
    fun `the notification describes the shell action`() {
        assertEquals(
            "Desactivar la app «com.example.app»",
            ApprovalNotificationText.describe(
                shellApproval("disable", "com.example.app", reason = "destructive")
            ),
        )
    }

    @Test
    fun `a disable is voiced during an owner turn`() {
        val approval = shellApproval(
            "disable",
            "com.example.app",
            reason = PhoneApprovalReason.DESTRUCTIVE,
            ownerTurnId = "pat-0123456789ab",
        )

        assertEquals(
            ApprovalVoiceRoute.Voice,
            ApprovalVoiceRouting.decide(
                approval = approval,
                ownerTurnActive = true,
                bubbleAttached = true,
                nowMillis = now,
            ),
        )
        assertEquals(ApprovalVoiceKind.DESTRUCTIVE, ApprovalVoiceRouting.voiceKind(approval))
        assertEquals("desactivar", ApprovalVoiceRouting.destructiveAction(approval))
    }

    @Test
    fun `a disable without a turn keeps the notification`() {
        val approval = shellApproval("disable", "com.example.app", reason = "destructive")
        assertEquals(
            ApprovalVoiceRoute.Notification,
            ApprovalVoiceRouting.decide(
                approval = approval,
                ownerTurnActive = false,
                bubbleAttached = true,
                nowMillis = now,
            ),
        )
        assertNull(ApprovalVoiceRouting.voiceKind(shellApproval("trim-caches")))
    }
}
