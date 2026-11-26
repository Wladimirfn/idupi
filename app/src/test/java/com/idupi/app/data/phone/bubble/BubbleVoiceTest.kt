package com.idupi.app.data.phone.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-bubble dictation state machine (phone-bubble T11).
 *
 * The owner's complaint: tapping Dictar dropped her into the Google recognizer
 * sheet over the app. The overlay now owns a `SpeechRecognizer`; this reducer is
 * the whole event -> state -> action table, so the service only starts/stops the
 * recognizer and paints what the state exposes.
 *
 * Copy is neutral Latin American Spanish, and partial hypotheses are never
 * sent: they only reach the field for editing. Since T21 a final transcript is
 * read back by the overlay before anything can be sent; that rule lives in
 * [BubbleVoiceConfirmTest].
 */
class BubbleVoiceTest {

    private fun reduce(
        state: BubbleVoiceState,
        event: BubbleVoiceEvent,
    ): BubbleVoiceDecision = BubbleVoiceReducer.reduce(state, event)

    @Test
    fun `a mic tap without the microphone permission asks for it first`() {
        val decision = reduce(
            BubbleVoiceState.Idle,
            BubbleVoiceEvent.MicTapped(permissionGranted = false, recognizerAvailable = true),
        )

        assertEquals(BubbleVoiceState.RequestingPermission, decision.state)
        assertEquals(
            listOf(BubbleVoiceAction.RequestMicrophonePermission),
            decision.actions,
        )
    }

    @Test
    fun `the grant starts listening`() {
        val decision = reduce(
            BubbleVoiceState.RequestingPermission,
            BubbleVoiceEvent.PermissionGranted,
        )

        assertEquals(BubbleVoiceState.Listening(), decision.state)
        assertEquals(listOf(BubbleVoiceAction.StartRecognizer), decision.actions)
    }

    @Test
    fun `a denied permission falls back to the system dictation sheet`() {
        val decision = reduce(
            BubbleVoiceState.RequestingPermission,
            BubbleVoiceEvent.PermissionDenied,
        )

        assertEquals(BubbleVoiceState.Idle, decision.state)
        assertEquals(listOf(BubbleVoiceAction.LaunchSystemDictation), decision.actions)
    }

    @Test
    fun `a phone without a recognizer falls back instead of asking for the mic`() {
        val decision = reduce(
            BubbleVoiceState.Idle,
            BubbleVoiceEvent.MicTapped(permissionGranted = true, recognizerAvailable = false),
        )

        assertEquals(BubbleVoiceState.Idle, decision.state)
        assertEquals(listOf(BubbleVoiceAction.LaunchSystemDictation), decision.actions)
    }

    @Test
    fun `a mic tap while listening asks the recognizer to finish`() {
        val listening = BubbleVoiceState.Listening(level = 0.6f, partial = "abre")

        val decision = reduce(
            listening,
            BubbleVoiceEvent.MicTapped(permissionGranted = true, recognizerAvailable = true),
        )

        // The final transcript may still arrive; the state waits for it.
        assertEquals(listening, decision.state)
        assertEquals(listOf(BubbleVoiceAction.StopRecognizer), decision.actions)
    }

    @Test
    fun `a mic tap while the permission dialog runs abandons the attempt`() {
        val decision = reduce(
            BubbleVoiceState.RequestingPermission,
            BubbleVoiceEvent.MicTapped(permissionGranted = false, recognizerAvailable = true),
        )

        assertEquals(BubbleVoiceState.Idle, decision.state)
        assertTrue(decision.actions.isEmpty())
    }

    @Test
    fun `partial results stream into the field and keep the level`() {
        val decision = reduce(
            BubbleVoiceState.Listening(level = 0.7f),
            BubbleVoiceEvent.Partial("abre WhatsApp"),
        )

        assertEquals(
            BubbleVoiceState.Listening(level = 0.7f, partial = "abre WhatsApp"),
            decision.state,
        )
        assertEquals(
            listOf(BubbleVoiceAction.PutText("abre WhatsApp")),
            decision.actions,
        )
    }

    @Test
    fun `the final transcript replaces the field and returns to idle`() {
        val decision = reduce(
            BubbleVoiceState.Listening(level = 0.2f, partial = "abre whats"),
            BubbleVoiceEvent.Final("abre WhatsApp y dime qué me escribió Juan"),
        )

        assertEquals(BubbleVoiceState.Idle, decision.state)
        assertEquals(
            listOf(
                BubbleVoiceAction.PutText("abre WhatsApp y dime qué me escribió Juan"),
            ),
            decision.actions,
        )
    }

    @Test
    fun `rms decibels become a zero to one level`() {
        assertEquals(0f, BubbleVoiceReducer.level(-2f), TOLERANCE)
        assertEquals(1f, BubbleVoiceReducer.level(10f), TOLERANCE)
        assertEquals(0.5f, BubbleVoiceReducer.level(4f), TOLERANCE)
        assertEquals(0f, BubbleVoiceReducer.level(-40f), TOLERANCE)
        assertEquals(1f, BubbleVoiceReducer.level(40f), TOLERANCE)
    }

    @Test
    fun `a missing rms reading leaves the bars alone`() {
        assertEquals(0f, BubbleVoiceReducer.level(Float.NaN), TOLERANCE)
    }

    @Test
    fun `no speech asks the owner to try again`() {
        val decision = reduce(BubbleVoiceState.Listening(), BubbleVoiceEvent.NoSpeech)

        assertEquals(
            BubbleVoiceState.Error("No te escuché, intenta de nuevo"),
            decision.state,
        )
        assertTrue(decision.actions.isEmpty())
    }

    @Test
    fun `a network failure says there is no connection for dictation`() {
        val decision = reduce(BubbleVoiceState.Listening(), BubbleVoiceEvent.NetworkError)

        assertEquals(
            BubbleVoiceState.Error("Sin conexión para el dictado"),
            decision.state,
        )
    }

    @Test
    fun `busy and unknown failures keep the shared failure copy`() {
        assertEquals(
            BubbleVoiceState.Error(VoiceInputContract.FAILURE_MESSAGE),
            reduce(BubbleVoiceState.Listening(), BubbleVoiceEvent.Busy).state,
        )
        assertEquals(
            BubbleVoiceState.Error(VoiceInputContract.FAILURE_MESSAGE),
            reduce(BubbleVoiceState.Listening(), BubbleVoiceEvent.OtherError).state,
        )
    }

    @Test
    fun `a mic tap after an error starts a new attempt`() {
        val decision = reduce(
            BubbleVoiceState.Error(VoiceInputContract.FAILURE_MESSAGE),
            BubbleVoiceEvent.MicTapped(permissionGranted = true, recognizerAvailable = true),
        )

        assertEquals(BubbleVoiceState.Listening(), decision.state)
        assertEquals(listOf(BubbleVoiceAction.StartRecognizer), decision.actions)
    }

    @Test
    fun `events that belong to no active attempt are ignored`() {
        assertEquals(
            BubbleVoiceState.Idle,
            reduce(BubbleVoiceState.Idle, BubbleVoiceEvent.Level(4f)).state,
        )
        assertTrue(
            reduce(BubbleVoiceState.Idle, BubbleVoiceEvent.Level(4f)).actions.isEmpty(),
        )
        assertEquals(
            BubbleVoiceState.Idle,
            reduce(BubbleVoiceState.Idle, BubbleVoiceEvent.Final("hola")).state,
        )
        assertTrue(
            reduce(BubbleVoiceState.Idle, BubbleVoiceEvent.Final("hola")).actions.isEmpty(),
        )
        assertEquals(
            BubbleVoiceState.Idle,
            reduce(BubbleVoiceState.Idle, BubbleVoiceEvent.PermissionGranted).state,
        )
    }

    @Test
    fun `the listening label is neutral Spanish`() {
        assertEquals("Escuchando…", BubbleVoiceReducer.MESSAGE_LISTENING)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
