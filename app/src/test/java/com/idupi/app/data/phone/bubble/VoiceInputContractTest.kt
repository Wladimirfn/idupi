package com.idupi.app.data.phone.bubble

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure dictation rules (phone-bubble T5): which language tag is handed to the
 * recognizer and how a recognizer answer becomes editable text.
 *
 * The system speech activity owns the microphone, so the app declares no
 * RECORD_AUDIO permission; the only things that can be wrong on our side are
 * the tag and the mapping, and both are pinned here.
 */
class VoiceInputContractTest {

    @Test
    fun `a Spanish phone dictates in the owner's regional Spanish`() {
        assertEquals("es-CL", VoiceInputContract.resolveLanguage("es-CL"))
        assertEquals("es-CL", VoiceInputContract.resolveLanguage("es-MX"))
        assertEquals("es-CL", VoiceInputContract.resolveLanguage("ES"))
    }

    @Test
    fun `a phone in another language keeps its own tag`() {
        assertEquals("en-US", VoiceInputContract.resolveLanguage("en-US"))
        assertEquals("pt-BR", VoiceInputContract.resolveLanguage("pt-BR"))
    }

    @Test
    fun `an unknown device language falls back to es-CL`() {
        assertEquals("es-CL", VoiceInputContract.resolveLanguage(null))
        assertEquals("es-CL", VoiceInputContract.resolveLanguage(""))
        assertEquals("es-CL", VoiceInputContract.resolveLanguage("   "))
    }

    @Test
    fun `the first non-blank transcript wins and is trimmed`() {
        assertEquals(
            "revisa mi correo",
            VoiceInputContract.transcriptFrom(
                Activity.RESULT_OK,
                listOf("  revisa mi correo  ", "otra cosa"),
            ),
        )
        assertEquals(
            "abre WhatsApp",
            VoiceInputContract.transcriptFrom(
                Activity.RESULT_OK,
                listOf("   ", " abre WhatsApp "),
            ),
        )
    }

    @Test
    fun `an empty recognizer answer is a failure`() {
        assertNull(VoiceInputContract.transcriptFrom(Activity.RESULT_OK, emptyList()))
        assertNull(VoiceInputContract.transcriptFrom(Activity.RESULT_OK, listOf("  ")))
        assertNull(VoiceInputContract.transcriptFrom(Activity.RESULT_OK, null))
    }

    @Test
    fun `a cancelled dictation is a failure`() {
        assertNull(
            VoiceInputContract.transcriptFrom(Activity.RESULT_CANCELED, listOf("hola"))
        )
    }

    @Test
    fun `the failure copy is the agreed neutral Spanish sentence`() {
        assertEquals(
            "No se pudo usar el dictado. Escribe tu pedido.",
            VoiceInputContract.FAILURE_MESSAGE,
        )
    }
}
