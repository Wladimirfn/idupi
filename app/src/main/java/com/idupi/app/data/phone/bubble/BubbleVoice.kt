package com.idupi.app.data.phone.bubble

/**
 * In-bubble dictation state (phone-bubble T11).
 *
 * The overlay owns a `SpeechRecognizer`; this model owns the rules so the
 * service only starts/stops the recognizer and paints what the state exposes.
 * Partial hypotheses only ever reach the field for editing. Since T21 a final
 * transcript is read back by the overlay and may be sent after the owner's
 * spoken confirmation; that decision lives in [BubbleVoiceConfirmReducer].
 */
sealed interface BubbleVoiceState {

    /** Nothing is listening; the Dictar button is ready. */
    data object Idle : BubbleVoiceState

    /** The tiny permission activity is asking for RECORD_AUDIO. */
    data object RequestingPermission : BubbleVoiceState

    /**
     * The microphone is live.
     *
     * @property level normalized 0..1 loudness from `onRmsChanged`, for the bars.
     * @property partial the latest partial transcript already streamed to the
     *   field; empty until the recognizer emits the first hypothesis.
     */
    data class Listening(
        val level: Float = 0f,
        val partial: String = "",
    ) : BubbleVoiceState

    /** A short, neutral Spanish sentence shown under the field. */
    data class Error(val message: String) : BubbleVoiceState
}

/** One thing that can happen to the dictation attempt. */
sealed interface BubbleVoiceEvent {

    /**
     * The owner tapped the mic toggle.
     *
     * @property permissionGranted RECORD_AUDIO is granted right now.
     * @property recognizerAvailable `SpeechRecognizer.isRecognitionAvailable`
     *   answered true; when false there is no in-bubble path at all and the
     *   system sheet is the only dictation left.
     */
    data class MicTapped(
        val permissionGranted: Boolean,
        val recognizerAvailable: Boolean,
    ) : BubbleVoiceEvent

    /** The permission activity reported a grant. */
    data object PermissionGranted : BubbleVoiceEvent

    /** The permission activity reported a denial: use the system sheet. */
    data object PermissionDenied : BubbleVoiceEvent

    /** One `onRmsChanged` reading, in decibels. */
    data class Level(val rmsDb: Float) : BubbleVoiceEvent

    /** One `onPartialResults` hypothesis. */
    data class Partial(val text: String) : BubbleVoiceEvent

    /** The `onResults` transcript. */
    data class Final(val text: String) : BubbleVoiceEvent

    /** `ERROR_NO_MATCH` or `ERROR_SPEECH_TIMEOUT`. */
    data object NoSpeech : BubbleVoiceEvent

    /** `ERROR_NETWORK` or `ERROR_NETWORK_TIMEOUT`. */
    data object NetworkError : BubbleVoiceEvent

    /** `ERROR_RECOGNIZER_BUSY`. */
    data object Busy : BubbleVoiceEvent

    /** Anything else the recognizer reports. */
    data object OtherError : BubbleVoiceEvent
}

/** One side effect the overlay must apply on the main thread. */
sealed interface BubbleVoiceAction {

    /** Start [MicPermissionActivity] once; it publishes its answer. */
    data object RequestMicrophonePermission : BubbleVoiceAction

    /** Create (if needed) and start the in-bubble recognizer. */
    data object StartRecognizer : BubbleVoiceAction

    /** Ask the recognizer to finish and deliver its final transcript. */
    data object StopRecognizer : BubbleVoiceAction

    /**
     * Open the existing [VoiceInputActivity] system sheet: the fallback when
     * RECORD_AUDIO is denied or no in-process recognizer exists.
     */
    data object LaunchSystemDictation : BubbleVoiceAction

    /**
     * Place [text] in the input field, cursor at the end.
     *
     * Partial hypotheses never send. Since T21 the overlay follows a final
     * transcript with the read-back loop ("¿Lo envío o quieres agregar
     * algo?"), whose affirmative answer sends the field through the same path
     * as the send button.
     */
    data class PutText(val text: String) : BubbleVoiceAction
}

/**
 * @property state the next dictation state.
 * @property actions side effects in the order the overlay must apply them.
 */
data class BubbleVoiceDecision(
    val state: BubbleVoiceState,
    val actions: List<BubbleVoiceAction> = emptyList(),
)

/**
 * Pure event -> state -> action table for in-bubble dictation (T11).
 *
 * The owner's feedback: the Google recognizer sheet over the app looked
 * unpolished. The mic toggle now asks for the permission once, listens inside
 * the panel, streams partial hypotheses into the field and falls back to the old
 * sheet only when the phone cannot dictate in-process (permission denied or no
 * recognizer installed).
 *
 * Loudness comes from `onRmsChanged`, roughly -2 dB in silence to +10 dB at a
 * normal speaking voice; [level] maps that to the bars' 0..1.
 */
object BubbleVoiceReducer {

    /** Label under the bars while the recognizer is live. */
    const val MESSAGE_LISTENING = "Escuchando…"

    /** `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT`. */
    const val MESSAGE_NO_SPEECH = "No te escuché, intenta de nuevo"

    /** `ERROR_NETWORK` / `ERROR_NETWORK_TIMEOUT`. */
    const val MESSAGE_NO_NETWORK = "Sin conexión para el dictado"

    private const val RMS_MIN_DB = -2f
    private const val RMS_MAX_DB = 10f

    /** Maps decibels from `onRmsChanged` to the bars' 0..1 range. */
    fun level(rmsDb: Float): Float {
        if (rmsDb.isNaN()) return 0f
        return ((rmsDb - RMS_MIN_DB) / (RMS_MAX_DB - RMS_MIN_DB)).coerceIn(0f, 1f)
    }

    fun reduce(
        state: BubbleVoiceState,
        event: BubbleVoiceEvent,
    ): BubbleVoiceDecision = when (event) {
        is BubbleVoiceEvent.MicTapped -> onMicTapped(state, event)
        BubbleVoiceEvent.PermissionGranted -> when (state) {
            BubbleVoiceState.RequestingPermission -> BubbleVoiceDecision(
                state = BubbleVoiceState.Listening(),
                actions = listOf(BubbleVoiceAction.StartRecognizer),
            )
            // A late grant after the owner gave up changes nothing.
            else -> BubbleVoiceDecision(state)
        }
        BubbleVoiceEvent.PermissionDenied -> when (state) {
            BubbleVoiceState.RequestingPermission -> BubbleVoiceDecision(
                state = BubbleVoiceState.Idle,
                actions = listOf(BubbleVoiceAction.LaunchSystemDictation),
            )
            else -> BubbleVoiceDecision(state)
        }
        is BubbleVoiceEvent.Level -> when (state) {
            is BubbleVoiceState.Listening ->
                BubbleVoiceDecision(state.copy(level = level(event.rmsDb)))
            else -> BubbleVoiceDecision(state)
        }
        is BubbleVoiceEvent.Partial -> when (state) {
            is BubbleVoiceState.Listening -> BubbleVoiceDecision(
                state = state.copy(partial = event.text),
                actions = listOf(BubbleVoiceAction.PutText(event.text)),
            )
            else -> BubbleVoiceDecision(state)
        }
        is BubbleVoiceEvent.Final -> when (state) {
            is BubbleVoiceState.Listening -> BubbleVoiceDecision(
                state = BubbleVoiceState.Idle,
                actions = listOf(BubbleVoiceAction.PutText(event.text)),
            )
            else -> BubbleVoiceDecision(state)
        }
        BubbleVoiceEvent.NoSpeech -> fail(state, MESSAGE_NO_SPEECH)
        BubbleVoiceEvent.NetworkError -> fail(state, MESSAGE_NO_NETWORK)
        BubbleVoiceEvent.Busy, BubbleVoiceEvent.OtherError ->
            fail(state, VoiceInputContract.FAILURE_MESSAGE)
    }

    private fun onMicTapped(
        state: BubbleVoiceState,
        event: BubbleVoiceEvent.MicTapped,
    ): BubbleVoiceDecision = when (state) {
        is BubbleVoiceState.Listening -> BubbleVoiceDecision(
            // `stopListening` still delivers the final transcript, so the state
            // waits for it instead of dropping what the owner already said.
            state = state,
            actions = listOf(BubbleVoiceAction.StopRecognizer),
        )
        BubbleVoiceState.RequestingPermission -> BubbleVoiceDecision(
            // The owner gave up while the system dialog was up. A late grant
            // is ignored by the PermissionGranted branch above.
            state = BubbleVoiceState.Idle,
        )
        else -> when {
            !event.recognizerAvailable -> BubbleVoiceDecision(
                state = BubbleVoiceState.Idle,
                actions = listOf(BubbleVoiceAction.LaunchSystemDictation),
            )
            !event.permissionGranted -> BubbleVoiceDecision(
                state = BubbleVoiceState.RequestingPermission,
                actions = listOf(BubbleVoiceAction.RequestMicrophonePermission),
            )
            else -> BubbleVoiceDecision(
                state = BubbleVoiceState.Listening(),
                actions = listOf(BubbleVoiceAction.StartRecognizer),
            )
        }
    }

    /** Only a live attempt can fail; a stale callback never repaints an idle UI. */
    private fun fail(state: BubbleVoiceState, message: String): BubbleVoiceDecision =
        if (state is BubbleVoiceState.Listening) {
            BubbleVoiceDecision(BubbleVoiceState.Error(message))
        } else {
            BubbleVoiceDecision(state)
        }
}
