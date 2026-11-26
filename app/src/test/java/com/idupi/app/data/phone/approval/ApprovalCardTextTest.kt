package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalApp
import com.idupi.app.domain.model.PhoneApprovalCommand
import com.idupi.app.domain.model.PhoneApprovalReason
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Copy for the Alertas pending-approval card (phone-approvals T4): what the
 * phone would do, in which app, why, who asked, how long is left and the exact
 * command arguments behind "Ver detalle".
 */
class ApprovalCardTextTest {

    private fun approval(
        type: String = "tap",
        reason: String = PhoneApprovalReason.RISKY_TEXT,
        target: String? = "Enviar",
        args: JsonObject = JsonObject(emptyMap()),
        appLabel: String? = "WhatsApp",
        packageName: String = "com.whatsapp",
        requestedBy: String = "cli",
        expiresAt: String = "2026-11-26T12:08:00Z",
    ) = PhoneApproval(
        id = "apr-123456789abc",
        state = "pending",
        command = PhoneApprovalCommand(type = type, args = args),
        reason = reason,
        target = target,
        foregroundApp = (appLabel != null || packageName.isNotEmpty())
            .let { PhoneApprovalApp(packageName = packageName, label = appLabel.orEmpty()) }
            .takeIf { appLabel != null || packageName.isNotEmpty() },
        requestedBy = requestedBy,
        createdAt = "2026-11-26T11:58:00Z",
        expiresAt = expiresAt,
    )

    private fun textArgs(value: String) = buildJsonObject { put("text", value) }

    // --- action line ------------------------------------------------------

    @Test
    fun `a tap names its text target`() {
        assertEquals(
            "Tocar «Enviar»",
            ApprovalCardText.actionLine(approval(target = "Enviar", args = textArgs("Enviar"))),
        )
    }

    @Test
    fun `a long press says so`() {
        assertEquals(
            "Mantener presionado «Mensaje»",
            ApprovalCardText.actionLine(
                approval(type = "long_press", target = "Mensaje", args = textArgs("Mensaje"))
            ),
        )
    }

    @Test
    fun `typing with submit shows the exact text`() {
        assertEquals(
            "Escribir «Hola, voy en camino» y enviar",
            ApprovalCardText.actionLine(
                approval(
                    type = "type",
                    reason = PhoneApprovalReason.TYPE_SUBMIT,
                    target = null,
                    args = buildJsonObject {
                        put("text", "Hola, voy en camino")
                        put("submit", true)
                    },
                )
            ),
        )
    }

    @Test
    fun `a view id target names the control`() {
        assertEquals(
            "Tocar el control com.whatsapp:id/send",
            ApprovalCardText.actionLine(
                approval(
                    reason = PhoneApprovalReason.SENSITIVE_APP,
                    target = "com.whatsapp:id/send",
                    args = buildJsonObject { put("id", "com.whatsapp:id/send") },
                )
            ),
        )
    }

    @Test
    fun `a coordinate target shows the position`() {
        assertEquals(
            "Tocar en (540, 1200)",
            ApprovalCardText.actionLine(
                approval(
                    reason = PhoneApprovalReason.SENSITIVE_APP,
                    target = "540,1200",
                    args = buildJsonObject {
                        put("x", 540)
                        put("y", 1200)
                    },
                )
            ),
        )
    }

    @Test
    fun `an approval without arguments still reads as an action`() {
        assertEquals(
            "Acción en el teléfono",
            ApprovalCardText.actionLine(
                approval(reason = "other", target = null, args = JsonObject(emptyMap()))
            ),
        )
    }

    @Test
    fun `a target without args falls back to the server target`() {
        assertEquals(
            "Tocar «Pagar»",
            ApprovalCardText.actionLine(
                approval(
                    reason = PhoneApprovalReason.PHONE_DETECTED,
                    target = "Pagar",
                    args = JsonObject(emptyMap()),
                )
            ),
        )
    }

    // --- app line ---------------------------------------------------------

    @Test
    fun `the app line shows the label and the package`() {
        assertEquals(
            "WhatsApp · com.whatsapp",
            ApprovalCardText.appLine(approval()),
        )
    }

    @Test
    fun `the app line survives a missing label or package`() {
        assertEquals(
            "WhatsApp",
            ApprovalCardText.appLine(approval(packageName = "")),
        )
        assertEquals(
            "com.whatsapp",
            ApprovalCardText.appLine(approval(appLabel = null)),
        )
        assertNull(ApprovalCardText.appLine(approval(appLabel = null, packageName = "")))
    }

    // --- why / who --------------------------------------------------------

    @Test
    fun `each reason has a plain Spanish label`() {
        assertEquals(
            "Acción riesgosa",
            ApprovalCardText.reasonLabel(approval(reason = PhoneApprovalReason.RISKY_TEXT)),
        )
        assertEquals(
            "Envío de un mensaje",
            ApprovalCardText.reasonLabel(approval(reason = PhoneApprovalReason.TYPE_SUBMIT)),
        )
        assertEquals(
            "App sensible en primer plano",
            ApprovalCardText.reasonLabel(approval(reason = PhoneApprovalReason.SENSITIVE_APP)),
        )
        assertEquals(
            "Riesgo detectado en el teléfono",
            ApprovalCardText.reasonLabel(
                approval(reason = PhoneApprovalReason.PHONE_DETECTED)
            ),
        )
        assertEquals(
            "Pendiente de tu aprobación",
            ApprovalCardText.reasonLabel(approval(reason = "other")),
        )
    }

    @Test
    fun `who asked is shown when present`() {
        assertEquals("La pidió: cli", ApprovalCardText.requestedByLine(approval()))
        assertNull(ApprovalCardText.requestedByLine(approval(requestedBy = "")))
        assertNull(ApprovalCardText.requestedByLine(approval(requestedBy = "   ")))
    }

    // --- time left --------------------------------------------------------

    @Test
    fun `time left rounds up to whole minutes`() {
        val now = Instant.parse("2026-11-26T12:00:00Z").toEpochMilli()

        assertEquals(
            "vence en 8 min",
            ApprovalCardText.timeLeftLine("2026-11-26T12:08:00Z", now),
        )
        assertEquals(
            "vence en 2 min",
            ApprovalCardText.timeLeftLine("2026-11-26T12:01:30Z", now),
        )
        assertEquals(
            "vence en menos de 1 min",
            ApprovalCardText.timeLeftLine("2026-11-26T12:00:30Z", now),
        )
        assertEquals(
            "vencida",
            ApprovalCardText.timeLeftLine("2026-11-26T11:59:00Z", now),
        )
        assertEquals(
            "vencida",
            ApprovalCardText.timeLeftLine("2026-11-26T12:00:00Z", now),
        )
    }

    @Test
    fun `an unreadable deadline has no time left line`() {
        assertNull(ApprovalCardText.timeLeftLine("", 1_000L))
        assertNull(ApprovalCardText.timeLeftLine("soon", 1_000L))
    }

    // --- details ----------------------------------------------------------

    @Test
    fun `the details list the exact tap arguments and the approval id`() {
        assertEquals(
            listOf(
                "Comando: tap",
                "Texto del objetivo: «Enviar»",
                "Aprobación: apr-123456789abc",
            ),
            ApprovalCardText.detailLines(approval(args = textArgs("Enviar"))),
        )
    }

    @Test
    fun `the details list the typed text and the submit flag`() {
        assertEquals(
            listOf(
                "Comando: type",
                "Texto a escribir: «Hola»",
                "Presionar enviar: sí",
                "Aprobación: apr-123456789abc",
            ),
            ApprovalCardText.detailLines(
                approval(
                    type = "type",
                    reason = PhoneApprovalReason.TYPE_SUBMIT,
                    target = null,
                    args = buildJsonObject {
                        put("text", "Hola")
                        put("submit", true)
                    },
                )
            ),
        )
    }

    @Test
    fun `the details list a view id`() {
        assertEquals(
            listOf(
                "Comando: tap",
                "Id de vista: com.whatsapp:id/send",
                "Aprobación: apr-123456789abc",
            ),
            ApprovalCardText.detailLines(
                approval(
                    reason = PhoneApprovalReason.SENSITIVE_APP,
                    target = "com.whatsapp:id/send",
                    args = buildJsonObject { put("id", "com.whatsapp:id/send") },
                )
            ),
        )
    }

    @Test
    fun `the details list the coordinates`() {
        assertEquals(
            listOf(
                "Comando: tap",
                "Coordenadas: (540, 1200)",
                "Aprobación: apr-123456789abc",
            ),
            ApprovalCardText.detailLines(
                approval(
                    reason = PhoneApprovalReason.SENSITIVE_APP,
                    target = "540,1200",
                    args = buildJsonObject {
                        put("x", 540)
                        put("y", 1200)
                    },
                )
            ),
        )
    }

    @Test
    fun `the details always carry the approval id, even without arguments`() {
        assertEquals(
            listOf("Comando: tap", "Aprobación: apr-123456789abc"),
            ApprovalCardText.detailLines(
                approval(target = null, args = JsonObject(emptyMap()))
            ),
        )
    }
}
