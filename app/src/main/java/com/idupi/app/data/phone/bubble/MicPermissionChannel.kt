package com.idupi.app.data.phone.bubble

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide bridge between [MicPermissionActivity] and the overlay
 * (phone-bubble T11), the same shape as [VoiceInputChannel].
 *
 * The permission belongs to the app, not the bubble: the activity only answers
 * the one dialog the overlay asked for. A late answer after the owner collapsed
 * the bubble is ignored by the reducer.
 */
object MicPermissionChannel {

    private val _results = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val results: SharedFlow<Boolean> = _results.asSharedFlow()

    fun publish(granted: Boolean) {
        _results.tryEmit(granted)
    }
}
