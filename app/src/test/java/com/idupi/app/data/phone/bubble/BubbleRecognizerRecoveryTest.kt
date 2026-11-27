package com.idupi.app.data.phone.bubble

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Recognizer recovery table for in-bubble dictation (phone-bubble T22).
 *
 * The owner's 1.56 bug had no adb trace: tapping the bubble's mic, granting
 * RECORD_AUDIO, then the panel showed
 * [VoiceInputContract.FAILURE_MESSAGE]. The two candidates are an on-device
 * model without the phone's Spanish locale
 * (`ERROR_LANGUAGE_NOT_SUPPORTED` / `ERROR_LANGUAGE_UNAVAILABLE`) and a
 * background-microphone refusal (`ERROR_INSUFFICIENT_PERMISSIONS` /
 * `ERROR_CLIENT`); both ended on `OtherError` and the shared failure copy.
 *
 * This pins the decision for every platform code on both recognizers, for
 * unknown codes and for a `startListening` exception, so the overlay can only
 * apply the table. Codes 10-15 are named literals in the production file
 * because `minSdk` is 29; here the platform constants are used so a wrong
 * literal cannot pass.
 */
class BubbleRecognizerRecoveryTest {

    @Test
    fun `an on-device failure retries the standard recognizer except no speech`() {
        val expected = mapOf(
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to RecognizerRecovery.RETRY_STANDARD,
            SpeechRecognizer.ERROR_NETWORK to RecognizerRecovery.RETRY_STANDARD,
            SpeechRecognizer.ERROR_AUDIO to RecognizerRecovery.RETRY_STANDARD,
            SpeechRecognizer.ERROR_SERVER to RecognizerRecovery.RETRY_STANDARD,
            SpeechRecognizer.ERROR_CLIENT to RecognizerRecovery.RETRY_STANDARD,
            // No speech is the one failure the T11 copy already explains.
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT to RecognizerRecovery.REPORT,
            SpeechRecognizer.ERROR_NO_MATCH to RecognizerRecovery.REPORT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY to RecognizerRecovery.RETRY_STANDARD,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to RecognizerRecovery.RETRY_STANDARD,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS to RecognizerRecovery.RETRY_STANDARD,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED to RecognizerRecovery.RETRY_STANDARD,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to RecognizerRecovery.RETRY_STANDARD,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE to RecognizerRecovery.RETRY_STANDARD,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT to RecognizerRecovery.RETRY_STANDARD,
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS to
                RecognizerRecovery.RETRY_STANDARD,
        )

        expected.forEach { (code, decision) ->
            assertEquals("on-device code $code", decision, onDevice(code))
        }
    }

    @Test
    fun `a standard failure with reducer copy is reported`() {
        listOf(
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        ).forEach { code ->
            assertEquals("standard code $code", RecognizerRecovery.REPORT, standard(code))
        }
    }

    @Test
    fun `a standard failure without reducer copy opens the system sheet`() {
        listOf(
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
        ).forEach { code ->
            assertEquals("standard code $code", RecognizerRecovery.SYSTEM_SHEET, standard(code))
        }
    }

    @Test
    fun `unknown codes retry on device and open the sheet on the standard recognizer`() {
        listOf(0, -1, 99).forEach { code ->
            assertEquals(
                "on-device code $code",
                RecognizerRecovery.RETRY_STANDARD,
                onDevice(code),
            )
            assertEquals(
                "standard code $code",
                RecognizerRecovery.SYSTEM_SHEET,
                standard(code),
            )
        }
    }

    @Test
    fun `a failed start on the on-device recognizer retries the standard one`() {
        assertEquals(
            RecognizerRecovery.RETRY_STANDARD,
            BubbleRecognizerRecovery.forStartFailure(onDevice = true),
        )
    }

    @Test
    fun `a failed start on the standard recognizer opens the system sheet`() {
        assertEquals(
            RecognizerRecovery.SYSTEM_SHEET,
            BubbleRecognizerRecovery.forStartFailure(onDevice = false),
        )
    }

    private fun onDevice(code: Int): RecognizerRecovery =
        BubbleRecognizerRecovery.forError(onDevice = true, errorCode = code)

    private fun standard(code: Int): RecognizerRecovery =
        BubbleRecognizerRecovery.forError(onDevice = false, errorCode = code)
}
