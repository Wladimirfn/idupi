package com.idupi.app.data.phone.bubble

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

/**
 * Tiny transparent activity that opens the system dictation sheet for the
 * bubble. Fallback only since T11: in-bubble dictation owns the microphone
 * directly and comes here when RECORD_AUDIO is denied or no recognizer exists.
 *
 * `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` is only startable from an
 * Activity, and the overlay lives in an AccessibilityService, so this
 * trampoline exists for that one call. It shows no UI, publishes the transcript
 * (or [VoiceInputEvent.Failed]) on the process-wide [VoiceInputChannel] and
 * finishes; the bubble puts the text in its field for editing and never sends
 * it automatically.
 *
 * The microphone belongs to the system recognizer, so the app needs no
 * RECORD_AUDIO permission and no runtime grant.
 */
class VoiceInputActivity : ComponentActivity() {

    private val speech = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val transcript = VoiceInputContract.transcriptFrom(
            result.resultCode,
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS),
        )
        if (transcript != null) {
            VoiceInputChannel.publish(VoiceInputEvent.Transcript(transcript))
        } else {
            VoiceInputChannel.publish(VoiceInputEvent.Failed)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated trampoline has no dictation to resume: the system sheet
        // owns the request and would deliver its result to the old instance.
        if (savedInstanceState != null) {
            finish()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            fail()
            return
        }
        val language = VoiceInputContract.resolveLanguage(Locale.getDefault().toLanguageTag())
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla tu pedido")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speech.launch(intent)
        } catch (error: ActivityNotFoundException) {
            fail()
        } catch (error: SecurityException) {
            fail()
        }
    }

    private fun fail() {
        VoiceInputChannel.publish(VoiceInputEvent.Failed)
        finish()
    }
}
