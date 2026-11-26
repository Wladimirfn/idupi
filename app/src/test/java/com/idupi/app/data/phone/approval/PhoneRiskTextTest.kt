package com.idupi.app.data.phone.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of `idupi-server/lib/phone-risk.mjs` to the phone (phone-approvals T2).
 *
 * The exact same words must be risky on both sides: "Enviar" is a send,
 * "Reenviar" is not, and "Pagaré mañana" is a plan. Accents never hide a verb.
 */
class PhoneRiskTextTest {

    @Test
    fun `the guarded verbs match as whole words`() {
        listOf(
            "Enviar",
            "send",
            "Pagar",
            "pay",
            "Eliminar",
            "borrar",
            "Delete",
            "Aprobar",
            "approve",
            "Confirmar",
            "confirm",
            "Comprar",
            "buy",
            "Transferir",
            "transfer",
            "Publicar",
            "post",
            "Share",
            "compartir",
        ).forEach { verb ->
            assertTrue("expected risky: $verb", PhoneRiskText.isRisky(verb))
        }
    }

    @Test
    fun `a verb inside a phrase is risky when it stands alone`() {
        assertTrue(PhoneRiskText.isRisky("Pagar ahora"))
        assertTrue(PhoneRiskText.isRisky("Enviar mensaje"))
        assertTrue(PhoneRiskText.isRisky("Delete all"))
        assertTrue(PhoneRiskText.isRisky("Aprobar solicitud"))
    }

    @Test
    fun `words that merely contain a verb stay free`() {
        listOf(
            "Reenviar",
            "Responder",
            "Pagaré mañana",
            "Enviado",
            "sendero",
            "El poste",
            "Compra",
            "Transferencia",
            "",
            "   ",
        ).forEach { text ->
            assertFalse("expected free: $text", PhoneRiskText.isRisky(text))
        }
    }

    @Test
    fun `case and accents never hide a verb`() {
        assertTrue(PhoneRiskText.isRisky("ENVIÁR"))
        assertTrue(PhoneRiskText.isRisky("Aprobár"))
        assertTrue(PhoneRiskText.isRisky("Transferír"))
    }

    @Test
    fun `normalize lowercases and strips combining marks`() {
        assertEquals("enviar", PhoneRiskText.normalize("ENVIÁR"))
        assertEquals("aprobar", PhoneRiskText.normalize("Aprobár"))
        assertEquals("pagar manana", PhoneRiskText.normalize("Pagar mañana"))
    }
}
