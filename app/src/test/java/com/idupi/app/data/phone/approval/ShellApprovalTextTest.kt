package com.idupi.app.data.phone.approval

import com.idupi.app.data.phone.bubble.ApprovalVoiceCopy
import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalCommand
import com.idupi.app.domain.model.PhoneApprovalReason
import com.idupi.app.domain.model.PhoneApprovalState
import java.time.Instant
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            "Borrar la caché de todas las apps",
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
            reason = PhoneApprovalReason.SHELL_DESTRUCTIVE,
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

    private fun batchApproval(
        steps: List<Pair<String, String?>>,
        reason: String = PhoneApprovalReason.SHELL_PLAN,
        ownerTurnId: String? = null,
    ): PhoneApproval {
        val args = buildJsonObject {
            put("action", "batch")
            put(
                "steps",
                buildJsonArray {
                    steps.forEach { (action, packageName) ->
                        add(buildJsonObject {
                            put("action", action)
                            packageName?.let { put("package", it) }
                        })
                    }
                }
            )
        }
        return PhoneApproval(
            id = "apr-shell-plan-123456",
            state = PhoneApprovalState.PENDING,
            command = PhoneApprovalCommand(type = "shell", args = args),
            reason = reason,
            expiresAt = Instant.ofEpochMilli(now + 600_000L).toString(),
            ownerTurnId = ownerTurnId,
        )
    }

    private val labels = mapOf(
        "com.example.a" to "App A",
        "com.example.b" to "App B",
        "com.example.c" to "App C",
        "com.example.d" to "App D",
        "com.example.e" to "App E",
    )
    private val labelFor: (String) -> String? = { labels[it] }

    @Test
    fun `the card names an uninstall`() {
        assertEquals(
            "Desinstalar la app «com.example.app»",
            ApprovalCardText.actionLine(shellApproval("uninstall", "com.example.app")),
        )
    }

    @Test
    fun `a plan lists every step, grouped, with labels`() {
        val plan = batchApproval(
            listOf(
                "uninstall" to "com.example.c",
                "trim-caches" to null,
                "force-stop" to "com.example.a",
                "force-stop" to "com.example.b",
                "uninstall" to "com.example.d",
                "uninstall" to "com.example.e",
            )
        )
        val expected = "Borrar la caché de todas las apps; detener 2 apps: App A, App B; " +
            "desinstalar 3 apps: App C, App D, App E"

        assertEquals(expected, ApprovalCardText.actionLine(plan, labelFor))
        assertEquals(expected, ApprovalNotificationText.describe(plan, labelFor))
        assertEquals("Plan de optimización", ApprovalCardText.reasonLabel(plan))
    }

    @Test
    fun `the plan detail numbers every step`() {
        val plan = batchApproval(
            listOf(
                "trim-caches" to null,
                "background" to "com.example.a",
                "uninstall" to "com.example.b",
            )
        )
        val lines = ApprovalCardText.detailLines(plan)

        assertTrue(lines.contains("1. Borrar la caché de todas las apps"))
        assertTrue(lines.contains("2. Cambiar el segundo plano de com.example.a"))
        assertTrue(lines.contains("3. Desinstalar com.example.b"))
    }

    @Test
    fun `a plan is asked in the bubble and voiced as a plan`() {
        val plan = batchApproval(
            listOf("trim-caches" to null, "uninstall" to "com.example.app"),
            ownerTurnId = "pat-0123456789ab",
        )

        assertEquals(
            ApprovalVoiceRoute.Voice,
            ApprovalVoiceRouting.decide(
                approval = plan,
                ownerTurnActive = true,
                bubbleAttached = true,
                nowMillis = now,
            ),
        )
        assertEquals(ApprovalVoiceKind.PLAN, ApprovalVoiceRouting.voiceKind(plan))
        assertTrue(ApprovalVoiceRouting.planContainsDestructive(plan))
    }

    @Test
    fun `the plan prompt lists the steps and warns about the irreversible ones`() {
        val destructive = ApprovalVoiceCopy.planPrompt(
            "Borrar la caché de todas las apps; desinstalar 1 app: App A",
            destructive = true,
        )
        assertTrue(destructive.startsWith("Voy a ejecutar este plan:"))
        assertTrue(destructive.contains("Borrar la caché de todas las apps"))
        assertTrue(destructive.contains("Incluye acciones que no se pueden deshacer."))

        val safe = ApprovalVoiceCopy.planPrompt(
            "Borrar la caché de todas las apps",
            destructive = false,
        )
        assertFalse(safe.contains("no se pueden deshacer"))
    }

    @Test
    fun `a plan without an owner turn keeps the notification`() {
        val plan = batchApproval(listOf("trim-caches" to null))
        assertEquals(
            ApprovalVoiceRoute.Notification,
            ApprovalVoiceRouting.decide(
                approval = plan,
                ownerTurnActive = false,
                bubbleAttached = true,
                nowMillis = now,
            ),
        )
        assertFalse(ApprovalVoiceRouting.planContainsDestructive(plan))
    }
}
