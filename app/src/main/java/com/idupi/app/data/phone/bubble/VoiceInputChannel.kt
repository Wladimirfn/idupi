package com.idupi.app.data.phone.bubble

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** What the dictation activity hands back to the bubble. */
sealed interface VoiceInputEvent {
    /** Editable transcript; the bubble places it in the field and never sends it. */
    data class Transcript(val text: String) : VoiceInputEvent

    /** No recognizer, a cancelled dictation, or no speech recognised. */
    data object Failed : VoiceInputEvent
}

/**
 * Process-wide bridge between the transparent [VoiceInputActivity] and the
 * overlay (phone-bubble T5). They live in the same process, so a shared flow
 * replaces a binder; with no replay an event is only delivered while the bubble
 * is attached, which is exactly when the mic could have been tapped.
 */
object VoiceInputChannel {

    private val _events = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VoiceInputEvent> = _events.asSharedFlow()

    fun publish(event: VoiceInputEvent) {
        _events.tryEmit(event)
    }
}
