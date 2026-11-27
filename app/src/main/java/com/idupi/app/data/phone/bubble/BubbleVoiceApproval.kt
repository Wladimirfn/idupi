package com.idupi.app.data.phone.bubble

/**
 * Copy of the voice approval of an outbound message (phone-bubble T23).
 *
 * Neutral Latin American Spanish (tú, no voseo): the owner hears and reads the
 * same sentence. The app label comes from the foreground app; when it is not
 * known the sentence says "esta app" instead of guessing. Screen text read
 * aloud is data only — [readPrompt] only shapes words, it never decides.
 */
object ApprovalVoiceCopy {

    /** Used when the foreground app cannot be named. */
    const val UNKNOWN_APP = "esta app"

    /** A composed message is read back up to this many characters. */
    const val MAX_READ_CHARS = 400

    /** Spoken after the appended words reached the compose field. */
    const val PROMPT_APPENDED = "Listo, agregué eso. ¿Está bien?"

    /** Spoken when the compose field could not be found or set. */
    const val PROMPT_APPEND_FAILED = "No pude agregar el texto. ¿Lo envío así?"

    /** Spoken after the owner said no; the message is not sent. */
    const val MESSAGE_REJECTED = "No lo envié."

    /**
     * The package of an Android resource id (`com.whatsapp:id/send` ->
     * `com.whatsapp`), or null when [resourceId] is not one.
     */
    fun packageFromResourceId(resourceId: String): String? {
        val separator = resourceId.indexOf(":id/")
        if (separator <= 0) return null
        return resourceId.substring(0, separator).trim().takeIf { it.isNotEmpty() }
    }

    /** "Voy a enviar el mensaje por WhatsApp. ¿Está bien?" */
    fun sendPrompt(appLabel: String?): String {
        val label = appLabel?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_APP
        return "Voy a enviar el mensaje por $label. ¿Está bien?"
    }

    /**
     * "Para Juan Pérez: nos vemos mañana. ¿Está bien?".
     *
     * Missing parts are omitted, whitespace is collapsed and the message is
     * truncated to [MAX_READ_CHARS]. With neither part the question is the
     * whole sentence, which is still a valid prompt.
     */
    fun readPrompt(recipient: String?, text: String?): String {
        val who = recipient?.let(::singleLine)?.takeIf { it.isNotEmpty() }
        val what = text
            ?.let(::singleLine)
            ?.trimEnd('.', ' ')
            ?.takeIf { it.isNotEmpty() }
            ?.let(::truncate)
        val body = when {
            who != null && what != null -> "Para $who: $what"
            who != null -> "Para $who"
            what != null -> what
            else -> null
        }
        return if (body == null) "¿Está bien?" else "$body. ¿Está bien?"
    }

    private fun singleLine(text: String): String =
        text.replace(WHITESPACE, " ").trim()

    private fun truncate(text: String): String =
        if (text.length <= MAX_READ_CHARS) {
            text
        } else {
            text.take(MAX_READ_CHARS).trimEnd() + "…"
        }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * Pure event -> state -> action table for the voice approval of an outbound
 * message (phone-bubble T23).
 *
 * Only an approval the routing rule sent to the bubble may reach this reducer;
 * the overlay binds the approve/reject actions to that one id. The owner's
 * answers are classified by [BubbleVoiceConfirm]:
 *
 * - affirmative: approve and say nothing more;
 * - read ("léemelo"): read the recipient and the composed text from the screen
 *   and ask again;
 * - add with content / anything else: append to the compose field and ask
 *   again; a failed append asks whether to send it as it is;
 * - bare add intent: ask what to add;
 * - cancel: reject and say "No lo envié.";
 * - silence, a recognizer error, a typed takeover or a collapsed panel fall
 *   back to the usual approval notification so nothing hangs silently;
 * - an expired or externally decided approval stops without a notification.
 *
 * The side effects stay ordered so the overlay can apply them on the main
 * thread exactly as listed.
 */
object BubbleVoiceApprovalReducer {

    fun reduce(
        state: BubbleConfirmState,
        event: BubbleConfirmEvent,
    ): BubbleConfirmDecision = when (event) {
        is BubbleConfirmEvent.StartApproval -> onStart(state, event.prompt)
        is BubbleConfirmEvent.Answer -> onAnswer(state, event.text)
        is BubbleConfirmEvent.ReadDone -> onReadDone(state, event)
        BubbleConfirmEvent.TtsDone,
        BubbleConfirmEvent.TtsUnavailable,
        -> onSpeechFinished(state)
        BubbleConfirmEvent.AppendDone -> onAppendFinished(state, ok = true)
        BubbleConfirmEvent.AppendFailed -> onAppendFinished(state, ok = false)
        BubbleConfirmEvent.NoSpeech -> onNoSpeech(state)
        BubbleConfirmEvent.Cancelled,
        BubbleConfirmEvent.UserTyped,
        BubbleConfirmEvent.SendTapped,
        -> stopWithFallback(state, stopSpeech = true)
        BubbleConfirmEvent.Expired,
        BubbleConfirmEvent.DecidedElsewhere,
        -> stopSilently(state)
        // A request transcript belongs to the request path, never to an
        // approval the owner is deciding out loud.
        is BubbleConfirmEvent.FinalTranscript -> BubbleConfirmDecision(state)
    }

    private fun onStart(state: BubbleConfirmState, prompt: String): BubbleConfirmDecision {
        if (state != BubbleConfirmState.Idle) return BubbleConfirmDecision(state)
        return BubbleConfirmDecision(
            state = BubbleConfirmState.Speaking(prompt, ConfirmListenTarget.ANSWER),
            actions = listOf(
                BubbleConfirmAction.ShowPrompt(prompt),
                BubbleConfirmAction.Speak(prompt),
            ),
        )
    }

    private fun onAnswer(state: BubbleConfirmState, raw: String): BubbleConfirmDecision {
        val answer = raw.trim()
        return when (state) {
            BubbleConfirmState.AwaitingAnswer -> onAnswerToApproval(answer)
            BubbleConfirmState.AwaitingAddition -> onAddition(answer)
            // Anywhere else the microphone is not the approval's: a late
            // callback must never approve or reject.
            else -> BubbleConfirmDecision(state)
        }
    }

    /**
     * The words heard after "Dime qué agrego". A plain cancel still wins: the
     * owner changed her mind, and appending "no" to the message would be
     * exactly the wrong thing to do.
     */
    private fun onAddition(answer: String): BubbleConfirmDecision {
        if (answer.isEmpty()) {
            return stopWithFallback(BubbleConfirmState.AwaitingAddition, stopSpeech = false)
        }
        if (BubbleVoiceConfirm.classify(answer) == ConfirmReply.Cancel) {
            return rejectAndSayIt()
        }
        return writeToCompose(answer)
    }

    private fun onAnswerToApproval(answer: String): BubbleConfirmDecision {
        if (answer.isEmpty()) {
            return stopWithFallback(BubbleConfirmState.AwaitingAnswer, stopSpeech = false)
        }
        return when (val reply = BubbleVoiceConfirm.classify(answer)) {
            ConfirmReply.Affirmative -> BubbleConfirmDecision(
                state = BubbleConfirmState.Idle,
                actions = listOf(BubbleConfirmAction.HidePrompt, BubbleConfirmAction.Approve),
            )
            ConfirmReply.Read -> BubbleConfirmDecision(
                state = BubbleConfirmState.Reading,
                actions = listOf(BubbleConfirmAction.ReadScreen),
            )
            is ConfirmReply.AddWithContent -> writeToCompose(reply.addition)
            ConfirmReply.AddOnly -> BubbleConfirmDecision(
                state = BubbleConfirmState.Speaking(
                    BubbleVoiceConfirm.PROMPT_ADD,
                    ConfirmListenTarget.ADDITION,
                ),
                actions = listOf(
                    BubbleConfirmAction.ShowPrompt(BubbleVoiceConfirm.PROMPT_ADD),
                    BubbleConfirmAction.Speak(BubbleVoiceConfirm.PROMPT_ADD),
                ),
            )
            ConfirmReply.Cancel -> BubbleConfirmDecision(
                state = BubbleConfirmState.Idle,
                actions = listOf(
                    BubbleConfirmAction.HidePrompt,
                    BubbleConfirmAction.Speak(ApprovalVoiceCopy.MESSAGE_REJECTED),
                    BubbleConfirmAction.Reject,
                ),
            )
            is ConfirmReply.Other -> writeToCompose(reply.text)
        }
    }

    private fun rejectAndSayIt(): BubbleConfirmDecision = BubbleConfirmDecision(
        state = BubbleConfirmState.Idle,
        actions = listOf(
            BubbleConfirmAction.HidePrompt,
            BubbleConfirmAction.Speak(ApprovalVoiceCopy.MESSAGE_REJECTED),
            BubbleConfirmAction.Reject,
        ),
    )

    private fun onReadDone(
        state: BubbleConfirmState,
        event: BubbleConfirmEvent.ReadDone,
    ): BubbleConfirmDecision {
        if (state != BubbleConfirmState.Reading) return BubbleConfirmDecision(state)
        val prompt = ApprovalVoiceCopy.readPrompt(event.recipient, event.text)
        return BubbleConfirmDecision(
            state = BubbleConfirmState.Speaking(prompt, ConfirmListenTarget.ANSWER),
            actions = listOf(
                BubbleConfirmAction.ShowPrompt(prompt),
                BubbleConfirmAction.Speak(prompt),
            ),
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

    private fun writeToCompose(addition: String): BubbleConfirmDecision =
        BubbleConfirmDecision(
            state = BubbleConfirmState.Writing,
            actions = listOf(BubbleConfirmAction.AppendToCompose(addition)),
        )

    private fun onAppendFinished(
        state: BubbleConfirmState,
        ok: Boolean,
    ): BubbleConfirmDecision {
        if (state != BubbleConfirmState.Writing) return BubbleConfirmDecision(state)
        val prompt = if (ok) {
            ApprovalVoiceCopy.PROMPT_APPENDED
        } else {
            ApprovalVoiceCopy.PROMPT_APPEND_FAILED
        }
        return BubbleConfirmDecision(
            state = BubbleConfirmState.Speaking(prompt, ConfirmListenTarget.ANSWER),
            actions = listOf(
                BubbleConfirmAction.ShowPrompt(prompt),
                BubbleConfirmAction.Speak(prompt),
            ),
        )
    }

    private fun onNoSpeech(state: BubbleConfirmState): BubbleConfirmDecision =
        when (state) {
            BubbleConfirmState.AwaitingAnswer,
            BubbleConfirmState.AwaitingAddition,
            -> stopWithFallback(state, stopSpeech = false)
            // The recognizer never runs in the other states, so this is a stale
            // callback and must not end a prompt or a screen read.
            else -> BubbleConfirmDecision(state)
        }

    /** Stops and leaves the fallback notification as the only surface. */
    private fun stopWithFallback(
        state: BubbleConfirmState,
        stopSpeech: Boolean,
    ): BubbleConfirmDecision {
        if (state == BubbleConfirmState.Idle) return BubbleConfirmDecision(state)
        val actions = buildList {
            if (stopSpeech) add(BubbleConfirmAction.StopTts)
            add(BubbleConfirmAction.HidePrompt)
            add(BubbleConfirmAction.PostFallbackNotification)
        }
        return BubbleConfirmDecision(state = BubbleConfirmState.Idle, actions = actions)
    }

    /** Expired or decided elsewhere: stop, but nothing is left to notify. */
    private fun stopSilently(state: BubbleConfirmState): BubbleConfirmDecision {
        if (state == BubbleConfirmState.Idle) return BubbleConfirmDecision(state)
        return BubbleConfirmDecision(
            state = BubbleConfirmState.Idle,
            actions = listOf(
                BubbleConfirmAction.StopTts,
                BubbleConfirmAction.HidePrompt,
            ),
        )
    }
}
