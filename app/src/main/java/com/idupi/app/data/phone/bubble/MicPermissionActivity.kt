package com.idupi.app.data.phone.bubble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Tiny transparent activity that asks for RECORD_AUDIO for in-bubble dictation
 * (phone-bubble T11).
 *
 * An accessibility overlay cannot request runtime permissions, so the mic
 * toggle opens this once; it publishes the answer on [MicPermissionChannel] and
 * finishes without drawing anything. A denial is not an error: the overlay
 * falls back to the system recognizer sheet of [VoiceInputActivity].
 */
class MicPermissionActivity : ComponentActivity() {

    private val request = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        MicPermissionChannel.publish(granted)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated trampoline would duplicate the answer to a request that
        // belonged to the previous instance.
        if (savedInstanceState != null) {
            finish()
            return
        }
        if (MicPermission.isGranted(this)) {
            MicPermissionChannel.publish(true)
            finish()
            return
        }
        request.launch(MicPermission.PERMISSION)
    }
}
