package com.idupi.app.data.phone.bubble

/**
 * What the overlay must do after an in-bubble dictation failure (T22).
 *
 * @see BubbleRecognizerRecovery.forError
 * @see BubbleRecognizerRecovery.forStartFailure
 */
enum class RecognizerRecovery {

    /**
     * The on-device recognizer failed for a reason other than silence. Destroy
     * it, keep it disabled for the rest of the overlay's life, and listen
     * again with the standard recognizer: on-device models usually lack the
     * phone's Spanish locale, while the network recognizer is the one the
     * phone is known to accept.
     */
    RETRY_STANDARD,

    /**
     * The in-process recognizer cannot answer on this phone. Outside the T21
     * read-back loop, go Idle and open the system dictation sheet; inside the
     * loop, stop the loop and leave the request unsent.
     */
    SYSTEM_SHEET,

    /** Keep T11's behaviour: the reducer already has exact copy for this code. */
    REPORT,
}

/**
 * Pure recovery table for in-bubble dictation (phone-bubble T22).
 *
 * The owner's bug on 1.56: tapping the bubble's mic, granting RECORD_AUDIO,
 * then the panel showed [VoiceInputContract.FAILURE_MESSAGE] and adb was not
 * available. T11 prefers `SpeechRecognizer.createOnDeviceSpeechRecognizer` on
 * API 31+ and asks for `es-CL`; on-device models usually lack that locale and
 * answer `ERROR_LANGUAGE_NOT_SUPPORTED` / `ERROR_LANGUAGE_UNAVAILABLE`, and a
 * background microphone can be refused with `ERROR_INSUFFICIENT_PERMISSIONS`
 * or `ERROR_CLIENT`. All of them ended on `OtherError` and the shared copy.
 *
 * The decision is split from the Android callback so it is testable on the
 * JVM: the overlay only applies it. The codes are named literals instead of
 * `SpeechRecognizer` constants because the ones added in API 31 (10-11) and
 * API 33 (12-15) cannot be named while `minSdk` is 29 without `InlinedApi`
 * lint noise; the numbers are the public, stable platform values.
 *
 * @see BubbleRecognizerRecoveryTest
 */
object BubbleRecognizerRecovery {

    /** `SpeechRecognizer.ERROR_NETWORK_TIMEOUT` (since API 8). */
    private const val ERROR_NETWORK_TIMEOUT = 1

    /** `SpeechRecognizer.ERROR_NETWORK` (since API 8). */
    private const val ERROR_NETWORK = 2

    /** `SpeechRecognizer.ERROR_AUDIO` (since API 8). */
    private const val ERROR_AUDIO = 3

    /** `SpeechRecognizer.ERROR_SERVER` (since API 8). */
    private const val ERROR_SERVER = 4

    /** `SpeechRecognizer.ERROR_CLIENT` (since API 8). */
    private const val ERROR_CLIENT = 5

    /** `SpeechRecognizer.ERROR_SPEECH_TIMEOUT` (since API 8). */
    private const val ERROR_SPEECH_TIMEOUT = 6

    /** `SpeechRecognizer.ERROR_NO_MATCH` (since API 8). */
    private const val ERROR_NO_MATCH = 7

    /** `SpeechRecognizer.ERROR_RECOGNIZER_BUSY` (since API 8). */
    private const val ERROR_RECOGNIZER_BUSY = 8

    /** `SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS` (since API 8). */
    private const val ERROR_INSUFFICIENT_PERMISSIONS = 9

    /** `SpeechRecognizer.ERROR_TOO_MANY_REQUESTS` (API 31). */
    private const val ERROR_TOO_MANY_REQUESTS = 10

    /** `SpeechRecognizer.ERROR_SERVER_DISCONNECTED` (API 31). */
    private const val ERROR_SERVER_DISCONNECTED = 11

    /** `SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED` (API 31). */
    private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12

    /** `SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE` (API 31). */
    private const val ERROR_LANGUAGE_UNAVAILABLE = 13

    /** `SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT` (API 33). */
    private const val ERROR_CANNOT_CHECK_SUPPORT = 14

    /**
     * Decides what to do after `RecognitionListener.onError`.
     *
     * [onDevice] says whether the recognizer that failed was the on-device one.
     *
     * - On-device: no speech ([ERROR_NO_MATCH], [ERROR_SPEECH_TIMEOUT]) keeps
     *   T11's "No te escuché" copy; every other code, network included, retries
     *   the standard recognizer once. The retry cannot repeat because the next
     *   failure is evaluated as a standard one.
     * - Standard: no-speech, network and `ERROR_RECOGNIZER_BUSY` keep T11's
     *   copy; client, server, permission, rate, disconnection, locale and
     *   support-check codes (plus anything unknown) open the system sheet,
     *   which is the path that has worked since 1.55.
     */
    fun forError(onDevice: Boolean, errorCode: Int): RecognizerRecovery {
        if (onDevice) {
            return if (isNoSpeech(errorCode)) {
                RecognizerRecovery.REPORT
            } else {
                RecognizerRecovery.RETRY_STANDARD
            }
        }
        return when (errorCode) {
            ERROR_NETWORK_TIMEOUT,
            ERROR_NETWORK,
            ERROR_SPEECH_TIMEOUT,
            ERROR_NO_MATCH,
            ERROR_RECOGNIZER_BUSY,
            -> RecognizerRecovery.REPORT
            ERROR_AUDIO,
            ERROR_SERVER,
            ERROR_CLIENT,
            ERROR_INSUFFICIENT_PERMISSIONS,
            ERROR_TOO_MANY_REQUESTS,
            ERROR_SERVER_DISCONNECTED,
            ERROR_LANGUAGE_NOT_SUPPORTED,
            ERROR_LANGUAGE_UNAVAILABLE,
            ERROR_CANNOT_CHECK_SUPPORT,
            -> RecognizerRecovery.SYSTEM_SHEET
            else -> RecognizerRecovery.SYSTEM_SHEET
        }
    }

    /**
     * Decides what to do when `startListening` throws before any callback.
     *
     * An on-device start that throws gets the standard recognizer; a standard
     * one that throws leaves the system sheet as the only dictation left.
     */
    fun forStartFailure(onDevice: Boolean): RecognizerRecovery =
        if (onDevice) {
            RecognizerRecovery.RETRY_STANDARD
        } else {
            RecognizerRecovery.SYSTEM_SHEET
        }

    private fun isNoSpeech(errorCode: Int): Boolean =
        errorCode == ERROR_NO_MATCH || errorCode == ERROR_SPEECH_TIMEOUT
}
