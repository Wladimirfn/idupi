package com.idupi.app.data.phone.bubble

import java.text.Normalizer
import java.util.Locale

/**
 * Meaning of the owner's spoken answer to the read-back question (T21).
 *
 * The owner asked for a hands-free flow "like Android Auto with Gemini": after
 * a dictated request the bubble reads it back ("¿Lo envío o quieres agregar
 * algo?") and her answer decides what happens next. Classification is a pure
 * question of text, so it lives here and is pinned by unit tests.
 */
sealed interface ConfirmReply {

    /** "sí", "envíalo", "está bien": send the field the way the button does. */
    data object Affirmative : ConfirmReply

    /**
     * An add verb followed by content ("agrega que mañana tengo reunión").
     *
     * @property addition the answer with the verb removed, trimmed and ready
     *   to append to the request with a single space.
     */
    data class AddWithContent(val addition: String) : ConfirmReply

    /** Only an add intent ("agregar algo"): ask what to add and listen again. */
    data object AddOnly : ConfirmReply

    /** "no", "cancela", "déjalo": stop the loop; the text stays unsent. */
    data object Cancel : ConfirmReply

    /**
     * Anything else non-empty.
     *
     * The task's rule is deliberate: an unclassified answer is treated as an
     * addition with content, so "a Juan" extends the request instead of being
     * thrown away or sent blindly. [text] is the whole answer, trimmed.
     */
    data class Other(val text: String) : ConfirmReply
}

/**
 * Reply classifier and loop copy for the voice confirmation loop (T21).
 *
 * Matching ignores case, accents and punctuation, so "Sí, envíalo." is the
 * same answer as "si envialo". Accents are folded with NFD and combining marks
 * removed, and every non letter/digit character becomes a word separator. The
 * whole answer must match an affirmative or cancel phrase; a sentence that
 * merely starts with one is not a yes/no.
 */
object BubbleVoiceConfirm {

    /** Shown and spoken after a dictated request. Neutral Spanish, no voseo. */
    const val PROMPT_CONFIRM = "¿Lo envío o quieres agregar algo?"

    /** Shown and spoken when the owner asked to add something without saying what. */
    const val PROMPT_ADD = "Dime qué agrego"

    fun classify(answer: String): ConfirmReply {
        val trimmed = answer.trim()
        val normalized = normalize(trimmed)
        if (normalized.isEmpty()) return ConfirmReply.Other(trimmed)
        if (normalized in AFFIRMATIVE) return ConfirmReply.Affirmative
        if (normalized in CANCEL) return ConfirmReply.Cancel
        if (normalized in ADD_ONLY) return ConfirmReply.AddOnly
        additionAfterVerb(trimmed)?.let { return ConfirmReply.AddWithContent(it) }
        return ConfirmReply.Other(trimmed)
    }

    /**
     * True when [answer] is only the word "no" (ignoring case, accents and
     * punctuation). The T17 follow-up sends exactly that short answer straight
     * to the agent; "no lo envíes" is a longer reply and goes through the
     * read-back loop instead.
     */
    fun isPlainNo(answer: String): Boolean = normalize(answer) == "no"

    /** Case/accent/punctuation-insensitive form used for every whole match. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace(NON_WORD, " ")
            .trim()

    /**
     * The words after a leading add verb, or null when the answer does not
     * start with one. Word boundaries come from the original answer so the
     * addition keeps its accents and its own punctuation.
     */
    private fun additionAfterVerb(answer: String): String? {
        val words = answer.split(WHITESPACE).filter { it.isNotEmpty() }
        for (prefix in ADD_PREFIXES) {
            if (words.size <= prefix.size) continue
            val head = words.take(prefix.size).map(::normalize)
            if (head != prefix) continue
            val addition = trimPunctuation(words.drop(prefix.size).joinToString(" "))
            if (addition.isNotEmpty() && addition.any { it.isLetterOrDigit() }) return addition
        }
        return null
    }

    private fun trimPunctuation(text: String): String =
        text.trim().trim { it.isWhitespace() || it in PUNCTUATION }

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")

    /** Punctuation stripped from the edges of an extracted addition. */
    private const val PUNCTUATION = ".,;:!?¡¿\"'()[]{}…-–—"

    /** Whole-answer affirmatives, already folded (sin tildes, minúsculas). */
    private val AFFIRMATIVE = setOf(
        "si",
        "si envialo",
        "envialo",
        "envia",
        "esta bien",
        "ok",
        "okay",
        // Input vocabulary only, never shown or spoken; exempted by name in
        // NeutralSpanishCopyTest.
        "dale",
        "listo",
        "correcto",
        "perfecto",
        "de acuerdo",
        "claro",
        "si esta bien",
        "mandalo",
        "si mandalo",
    )

    /** Whole-answer cancels. "para" only counts alone, never inside a phrase. */
    private val CANCEL = setOf(
        "no",
        "cancela",
        "cancelar",
        "olvidalo",
        "dejalo",
        "no lo envies",
        "para",
    )

    /** Answers that ask to add something without saying what. */
    private val ADD_ONLY = setOf(
        "agrega",
        "agregar",
        "agregale",
        "anade",
        "anadir",
        "anadele",
        "y tambien",
        "tambien",
        "ademas",
        "quiero agregar",
        "quiero agregar algo",
        "si quiero agregar",
        "agregar algo",
        "quiero anadir",
        "quiero anadir algo",
        "si quiero anadir",
        "anadir algo",
    )

    /**
     * Leading add phrases, folded. Two-word "y también" is checked before the
     * one-word forms so the addition never starts with a stray "y".
     */
    private val ADD_PREFIXES = listOf(
        listOf("y", "tambien"),
        listOf("tambien"),
        listOf("ademas"),
        listOf("agrega"),
        listOf("agregar"),
        listOf("agregale"),
        listOf("anade"),
        listOf("anadir"),
        listOf("anadele"),
    )
}

/**
 * Where the loop is in the spoken conversation (T21).
 *
 * Idle -> Speaking -> AwaitingAnswer -> (Speaking | Idle) and, when the owner
 * only asked to add, AwaitingAddition -> Speaking -> AwaitingAnswer.
 */
sealed interface BubbleConfirmState {

    /** No loop is running; a final transcript is a new request again. */
    data object Idle : BubbleConfirmState

    /**
     * TextToSpeech is reading [prompt] out loud.
     *
     * @property prompt the sentence also shown on the panel line.
     * @property next what to listen for once the speech ends.
     */
    data class Speaking(
        val prompt: String,
        val next: ConfirmListenTarget,
    ) : BubbleConfirmState

    /** The recognizer is listening for yes / add / cancel. */
    data object AwaitingAnswer : BubbleConfirmState

    /** The recognizer is listening for the words to append. */
    data object AwaitingAddition : BubbleConfirmState
}

/** What the loop listens for after speaking a prompt. */
enum class ConfirmListenTarget { ANSWER, ADDITION }

/** One thing that can happen during the voice confirmation loop. */
sealed interface BubbleConfirmEvent {

    /**
     * The in-bubble recognizer delivered a final transcript and the T11
     * reducer already placed it in the field.
     *
     * @property text the transcript, trimmed by the reducer.
     * @property followUp the mic was opened by the T17 agent-question
     *   follow-up, so a short yes/no is an answer to the agent and is sent
     *   directly instead of being read back.
     */
    data class FinalTranscript(
        val text: String,
        val followUp: Boolean = false,
    ) : BubbleConfirmEvent

    /** `UtteranceProgressListener.onDone`, posted to the main thread. */
    data object TtsDone : BubbleConfirmEvent

    /** TextToSpeech failed to initialize, has no Spanish voice, or failed. */
    data object TtsUnavailable : BubbleConfirmEvent

    /** A final transcript heard while the loop listens. */
    data class Answer(val text: String) : BubbleConfirmEvent

    /** Silence or a recognizer error while the loop listens. */
    data object NoSpeech : BubbleConfirmEvent

    /** The panel collapsed, the service stopped, or the mic was tapped. */
    data object Cancelled : BubbleConfirmEvent

    /** The owner typed in the field while the loop ran. */
    data object UserTyped : BubbleConfirmEvent

    /** The owner tapped Enviar while the loop ran. */
    data object SendTapped : BubbleConfirmEvent
}

/** One side effect the overlay must apply on the main thread. */
sealed interface BubbleConfirmAction {

    /** Show [text] on the read-back line above the input. */
    data class ShowPrompt(val text: String) : BubbleConfirmAction

    /** Speak [text] with TextToSpeech, flushing anything still queued. */
    data class Speak(val text: String) : BubbleConfirmAction

    /** Start the in-bubble recognizer for one answer. */
    data object StartRecognizer : BubbleConfirmAction

    /** Append [text] to the field with a single separating space. */
    data class AppendText(val text: String) : BubbleConfirmAction

    /** Send the field through the send button's own path. */
    data object Send : BubbleConfirmAction

    /** Hide the read-back line. */
    data object HidePrompt : BubbleConfirmAction

    /** Stop the spoken prompt immediately. */
    data object StopTts : BubbleConfirmAction
}

/**
 * @property state the next loop state.
 * @property actions side effects in the order the overlay must apply them.
 */
data class BubbleConfirmDecision(
    val state: BubbleConfirmState,
    val actions: List<BubbleConfirmAction> = emptyList(),
)

/**
 * Pure event -> state -> action table for the voice confirmation loop (T21).
 *
 * The owner's rule: a dictated final transcript is read back on screen and out
 * loud. An affirmative sends the field, an add verb with content appends it and
 * asks again, a bare add intent asks what to add, a cancel or silence stops and
 * leaves the text in the field, and anything else is appended whole before
 * asking again (see [ConfirmReply.Other]). Typed requests never enter the loop:
 * the overlay only routes in-bubble final transcripts here.
 *
 * The T17 follow-up (the agent asked a question) is the one shortcut: a short
 * yes or plain "no" is the answer itself, so it is sent directly as the next
 * request instead of being read back.
 */
object BubbleVoiceConfirmReducer {

    fun reduce(
        state: BubbleConfirmState,
        event: BubbleConfirmEvent,
    ): BubbleConfirmDecision = when (event) {
        is BubbleConfirmEvent.FinalTranscript -> onFinalTranscript(state, event)
        is BubbleConfirmEvent.Answer -> onAnswer(state, event.text)
        BubbleConfirmEvent.TtsDone,
        BubbleConfirmEvent.TtsUnavailable,
        -> onSpeechFinished(state)
        BubbleConfirmEvent.NoSpeech -> stop(state, stopSpeech = false)
        BubbleConfirmEvent.Cancelled,
        BubbleConfirmEvent.UserTyped,
        BubbleConfirmEvent.SendTapped,
        -> stop(state, stopSpeech = true)
    }

    private fun onFinalTranscript(
        state: BubbleConfirmState,
        event: BubbleConfirmEvent.FinalTranscript,
    ): BubbleConfirmDecision {
        val answer = event.text.trim()
        if (answer.isEmpty()) {
            // A blank transcript ends an active loop; if the prompt is still
            // being spoken it must be silenced too.
            return stop(state, stopSpeech = state is BubbleConfirmState.Speaking)
        }
        if (state != BubbleConfirmState.Idle) return onAnswer(state, answer)
        if (event.followUp && isDirectAgentAnswer(answer)) {
            return BubbleConfirmDecision(
                state = BubbleConfirmState.Idle,
                actions = listOf(
                    BubbleConfirmAction.HidePrompt,
                    BubbleConfirmAction.Send,
                ),
            )
        }
        return askOnScreenAndOutLoud()
    }

    /** A dictated yes / plain no is the agent question's answer, not a request. */
    private fun isDirectAgentAnswer(answer: String): Boolean =
        BubbleVoiceConfirm.classify(answer) == ConfirmReply.Affirmative ||
            BubbleVoiceConfirm.isPlainNo(answer)

    private fun askOnScreenAndOutLoud(): BubbleConfirmDecision = BubbleConfirmDecision(
        state = BubbleConfirmState.Speaking(
            prompt = BubbleVoiceConfirm.PROMPT_CONFIRM,
            next = ConfirmListenTarget.ANSWER,
        ),
        actions = listOf(
            BubbleConfirmAction.ShowPrompt(BubbleVoiceConfirm.PROMPT_CONFIRM),
            BubbleConfirmAction.Speak(BubbleVoiceConfirm.PROMPT_CONFIRM),
        ),
    )

    private fun onAnswer(state: BubbleConfirmState, raw: String): BubbleConfirmDecision {
        val answer = raw.trim()
        return when (state) {
            BubbleConfirmState.Idle -> BubbleConfirmDecision(state)
            // The prompt is still being spoken; the recognizer is not running,
            // so this can only be a stale callback and must not double-answer.
            is BubbleConfirmState.Speaking -> BubbleConfirmDecision(state)
            BubbleConfirmState.AwaitingAnswer -> onAnswerToReadBack(answer)
            BubbleConfirmState.AwaitingAddition -> {
                if (answer.isEmpty()) {
                    stop(state, stopSpeech = false)
                } else {
                    appendAndAskAgain(answer)
                }
            }
        }
    }

    private fun onAnswerToReadBack(answer: String): BubbleConfirmDecision {
        if (answer.isEmpty()) return stop(BubbleConfirmState.AwaitingAnswer, stopSpeech = false)
        return when (val reply = BubbleVoiceConfirm.classify(answer)) {
            ConfirmReply.Affirmative -> BubbleConfirmDecision(
                state = BubbleConfirmState.Idle,
                actions = listOf(
                    BubbleConfirmAction.HidePrompt,
                    BubbleConfirmAction.Send,
                ),
            )
            is ConfirmReply.AddWithContent -> appendAndAskAgain(reply.addition)
            ConfirmReply.AddOnly -> askWhatToAdd()
            ConfirmReply.Cancel -> stop(BubbleConfirmState.AwaitingAnswer, stopSpeech = false)
            is ConfirmReply.Other -> appendAndAskAgain(reply.text)
        }
    }

    private fun askWhatToAdd(): BubbleConfirmDecision = BubbleConfirmDecision(
        state = BubbleConfirmState.Speaking(
            prompt = BubbleVoiceConfirm.PROMPT_ADD,
            next = ConfirmListenTarget.ADDITION,
        ),
        actions = listOf(
            BubbleConfirmAction.ShowPrompt(BubbleVoiceConfirm.PROMPT_ADD),
            BubbleConfirmAction.Speak(BubbleVoiceConfirm.PROMPT_ADD),
        ),
    )

    private fun appendAndAskAgain(addition: String): BubbleConfirmDecision {
        val next = askOnScreenAndOutLoud()
        return next.copy(
            actions = listOf(BubbleConfirmAction.AppendText(addition)) + next.actions,
        )
    }

    private fun onSpeechFinished(state: BubbleConfirmState): BubbleConfirmDecision =
        when (state) {
            is BubbleConfirmState.Speaking -> BubbleConfirmDecision(
                state = when (state.next) {
                    ConfirmListenTarget.ANSWER -> BubbleConfirmState.AwaitingAnswer
                    ConfirmListenTarget.ADDITION -> BubbleConfirmState.AwaitingAddition
                },
                actions = listOf(BubbleConfirmAction.StartRecognizer),
            )
            // A late TTS callback after the loop stopped or before it started
            // owns nothing.
            else -> BubbleConfirmDecision(state)
        }

    /** Cancels an active loop; stale events on an idle loop change nothing. */
    private fun stop(
        state: BubbleConfirmState,
        stopSpeech: Boolean,
    ): BubbleConfirmDecision {
        if (state == BubbleConfirmState.Idle) return BubbleConfirmDecision(state)
        val actions = buildList {
            if (stopSpeech) add(BubbleConfirmAction.StopTts)
            add(BubbleConfirmAction.HidePrompt)
        }
        return BubbleConfirmDecision(state = BubbleConfirmState.Idle, actions = actions)
    }
}
