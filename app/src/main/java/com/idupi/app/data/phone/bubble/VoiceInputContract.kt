package com.idupi.app.data.phone.bubble

import android.app.Activity

/**
 * Dictation rules for the bubble (phone-bubble T5).
 *
 * The microphone belongs to the system recognizer activity
 * (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`), so IDUPI declares no
 * RECORD_AUDIO permission and never touches audio itself. These two pure
 * functions are the whole contract the bubble depends on: the language tag it
 * hands over and how the recognizer's answer becomes editable text.
 */
object VoiceInputContract {

    /** The owner is Chilean; the recognizer gets her regional variant first. */
    const val PREFERRED_LANGUAGE = "es-CL"

    /** Shown when there is no recognizer, the owner cancels, or nothing was heard. */
    const val FAILURE_MESSAGE = "No se pudo usar el dictado. Escribe tu pedido."

    /**
     * `es-CL` on a phone whose language is Spanish; otherwise the phone's own
     * tag. Forcing Spanish on an English phone would pick a model the
     * recognizer may not have installed, so that case falls back to the
     * device default instead.
     */
    fun resolveLanguage(deviceLanguageTag: String?): String {
        val tag = deviceLanguageTag?.trim().orEmpty()
        if (tag.isEmpty()) return PREFERRED_LANGUAGE
        return if (tag.startsWith("es", ignoreCase = true)) PREFERRED_LANGUAGE else tag
    }

    /**
     * First non-blank transcript, trimmed; null when the owner cancelled or the
     * recognizer returned nothing usable. The caller shows [FAILURE_MESSAGE]
     * for null, and never sends the transcript without the owner's edit.
     */
    fun transcriptFrom(resultCode: Int, results: List<String>?): String? {
        if (resultCode != Activity.RESULT_OK) return null
        return results
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
