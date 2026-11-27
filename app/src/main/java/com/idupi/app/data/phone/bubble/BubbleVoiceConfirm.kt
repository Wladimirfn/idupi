package com.idupi.app.data.phone.bubble

import java.text.Normalizer
import java.util.Locale

/**
 * Meaning of the owner's spoken answer (phone-bubble T21, READ added by T23).
 *
 * T21 began as the read-back of a dictated request; T23 removed that read-back
 * ("quita la de T21"), but the same classifier still decides every spoken
 * answer of the approval loop: yes approves, no rejects, "léemelo" asks to
 * hear the message, an add verb appends to the compose field, and anything
 * else is treated as content.
 */
sealed interface ConfirmReply {

    /** "sí", "envíalo", "está bien": approve / send. */
    data object Affirmative : ConfirmReply

    /**
     * "léemelo", "qué dice", "repítelo": read the message back from the screen.
     *
     * Screen text is data only; it shapes the spoken sentence and never changes
     * the decision logic.
     */
    data object Read : ConfirmReply

    /**
     * An add verb followed by content ("agrégale que mañana tengo reunión").
     *
     * @property addition the answer with the verb removed, trimmed and ready
     *   to append to the compose field with a single space.
     */
    data class AddWithContent(val addition: String) : ConfirmReply

    /** Only an add intent ("agregar algo"): ask what to add and listen again. */
    data object AddOnly : ConfirmReply

    /** "no", "cancela", "déjalo": reject; the message is not sent. */
    data object Cancel : ConfirmReply

    /**
     * Anything else non-empty.
     *
     * An unclassified answer is treated as an addition with content, so "a
     * Juan" extends the message instead of being thrown away or approved
     * blindly. [text] is the whole answer, trimmed.
     */
    data class Other(val text: String) : ConfirmReply
}

/**
 * Reply classifier and input copy of the voice loops (T21, extended by T23).
 *
 * Matching ignores case, accents and punctuation, so "Sí, envíalo." is the
 * same answer as "si envialo". Accents are folded with NFD and combining marks
 * removed, and every non letter/digit character becomes a word separator. The
 * whole answer must match an affirmative, read or cancel phrase; a sentence
 * that merely starts with one is not a yes/no.
 */
object BubbleVoiceConfirm {

    /** Shown and spoken when the owner asked to add something without saying what. */
    const val PROMPT_ADD = "Dime qué agrego"

    fun classify(answer: String): ConfirmReply {
        val trimmed = answer.trim()
        val normalized = normalize(trimmed)
        if (normalized.isEmpty()) return ConfirmReply.Other(trimmed)
        if (normalized in AFFIRMATIVE) return ConfirmReply.Affirmative
        if (normalized in CANCEL) return ConfirmReply.Cancel
        if (normalized in READ) return ConfirmReply.Read
        if (normalized in ADD_ONLY) return ConfirmReply.AddOnly
        additionAfterVerb(trimmed)?.let { return ConfirmReply.AddWithContent(it) }
        return ConfirmReply.Other(trimmed)
    }

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

    /** Whole-answer read requests (T23). Input vocabulary, never copy. */
    private val READ = setOf(
        "leemelo",
        "leelo",
        "lee",
        "lee el mensaje",
        "leeme",
        "leer",
        "leerlo",
        "que dice",
        "repitelo",
        "repitemelo",
        "repite",
    )

    /** Answers that ask to add something without saying what. */
    private val ADD_ONLY = setOf(
        "agrega",
        "agregar",
        "agregale",
        "agrega algo",
        "agregale algo",
        "anade",
        "anadir",
        "anadele",
        "anade algo",
        "anadele algo",
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
 * Where a spoken loop is (T21, reused by the approval loop in T23).
 *
 * Idle -> Speaking -> AwaitingAnswer -> (Speaking | Reading | Writing | Idle)
 * and, when the owner only asked to add, AwaitingAddition -> Writing ->
 * Speaking -> AwaitingAnswer. Since T23 Idle is also the resting state of the
 * bubble outside any approval: a dictated final transcript is a request and is
 * sent straight to the agent instead of entering the loop.
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

    /** The recognizer is listening for yes / read / add / cancel. */
    data object AwaitingAnswer : BubbleConfirmState

    /** The recognizer is listening for the words to append. */
    data object AwaitingAddition : BubbleConfirmState

    /** The overlay is reading the recipient and message from the screen (T23). */
    data object Reading : BubbleConfirmState

    /** The overlay is setting the compose field of the app behind (T23). */
    data object Writing : BubbleConfirmState
}

/** What the loop listens for after speaking a prompt. */
enum class ConfirmListenTarget { ANSWER, ADDITION }

/** One thing that can happen during a spoken loop. */
sealed interface BubbleConfirmEvent {

    /**
     * The in-bubble recognizer delivered a final transcript outside a loop.
     *
     * Since T23 the bubble sends it straight to the agent (the send button's
     * own path); there is no request read-back any more.
     */
    data class FinalTranscript(val text: String) : BubbleConfirmEvent

    /**
     * Start the approval loop with [prompt] (T23): the sentence is shown and
     * spoken, then the answer is heard.
     */
    data class StartApproval(val prompt: String) : BubbleConfirmEvent

    /** `UtteranceProgressListener.onDone`, posted to the main thread. */
    data object TtsDone : BubbleConfirmEvent

    /** TextToSpeech failed to initialize, has no Spanish voice, or failed. */
    data object TtsUnavailable : BubbleConfirmEvent

    /** A final transcript heard while the loop listens. */
    data class Answer(val text: String) : BubbleConfirmEvent

    /** Silence, an empty answer or a recognizer error while the loop listens. */
    data object NoSpeech : BubbleConfirmEvent

    /** The panel collapsed, the service stopped, or the mic was tapped. */
    data object Cancelled : BubbleConfirmEvent

    /** The owner typed in the field while the loop ran. */
    data object UserTyped : BubbleConfirmEvent

    /** The owner tapped Enviar while the loop ran. */
    data object SendTapped : BubbleConfirmEvent

    /**
     * The screen read finished. Screen text is untrusted data: it only feeds
     * the spoken sentence, never the decision.
     */
    data class ReadDone(val recipient: String?, val text: String?) : BubbleConfirmEvent

    /** The compose field took the appended words. */
    data object AppendDone : BubbleConfirmEvent

    /** The compose field was not found or refused the text. */
    data object AppendFailed : BubbleConfirmEvent

    /** The approval deadline passed while the loop ran. */
    data object Expired : BubbleConfirmEvent

    /** Another surface decided the approval while the loop ran. */
    data object DecidedElsewhere : BubbleConfirmEvent
}

/** One side effect the overlay must apply on the main thread. */
sealed interface BubbleConfirmAction {

    /** Show [text] on the read-back line above the input. */
    data class ShowPrompt(val text: String) : BubbleConfirmAction

    /** Speak [text] with TextToSpeech, flushing anything still queued. */
    data class Speak(val text: String) : BubbleConfirmAction

    /** Start the in-bubble recognizer for one answer. */
    data object StartRecognizer : BubbleConfirmAction

    /** Send the bubble field through the send button's own path. */
    data object Send : BubbleConfirmAction

    /** Hide the read-back line. */
    data object HidePrompt : BubbleConfirmAction

    /** Stop the spoken prompt immediately. */
    data object StopTts : BubbleConfirmAction

    /** Approve the bound approval through the notification's approve path. */
    data object Approve : BubbleConfirmAction

    /** Reject the bound approval through the notification's reject path. */
    data object Reject : BubbleConfirmAction

    /** Read the recipient and composed text with the accessibility service. */
    data object ReadScreen : BubbleConfirmAction

    /** Append [text] to the other app's compose field (accessibility set-text). */
    data class AppendToCompose(val text: String) : BubbleConfirmAction

    /** Post the normal approval notification, so nothing hangs silently. */
    data object PostFallbackNotification : BubbleConfirmAction
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
 * Pure event -> state -> action table for a dictated final transcript (T21,
 * changed by T23).
 *
 * The owner removed the request read-back: a final transcript outside a loop
 * is the request itself and is sent straight through the send button's path.
 * That includes a dictated reply to an agent question; the T17 follow-up still
 * reopens the microphone, it just never asks the owner to confirm the text.
 * The loop itself lives in [BubbleVoiceApprovalReducer].
 */
object BubbleVoiceConfirmReducer {

    fun reduce(
        state: BubbleConfirmState,
        event: BubbleConfirmEvent,
    ): BubbleConfirmDecision = when (event) {
        is BubbleConfirmEvent.FinalTranscript -> onFinalTranscript(state, event.text)
        // Every other event belongs to the approval loop; outside it nothing
        // happened that this reducer owns.
        else -> BubbleConfirmDecision(state)
    }

    private fun onFinalTranscript(
        state: BubbleConfirmState,
        raw: String,
    ): BubbleConfirmDecision {
        if (state != BubbleConfirmState.Idle || raw.isBlank()) {
            return BubbleConfirmDecision(state)
        }
        return BubbleConfirmDecision(
            state = BubbleConfirmState.Idle,
            actions = listOf(
                BubbleConfirmAction.HidePrompt,
                BubbleConfirmAction.Send,
            ),
        )
    }
}
