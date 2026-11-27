package com.idupi.app.data.phone.bubble

import com.idupi.app.data.phone.approval.ApprovalVoiceKind

/**
 * Copy of the voice approval of an outbound message (phone-bubble T23) and of
 * every destructive step (phone-dashboard T4 + T6): cache, uninstall, data
 * wipe and any other delete.
 *
 * Neutral Latin American Spanish (tú, no voseo): the owner hears and reads the
 * same sentence. The app label comes from the foreground app; when it is not
 * known the sentence says "esta app" instead of guessing. Screen text read
 * aloud is data only — [readPrompt] only shapes words, it never decides.
 */
object ApprovalVoiceCopy {

    /** Used when the foreground app cannot be named. */
    const val UNKNOWN_APP = "esta app"

    /** Used when the screen shows nothing the bubble can name. */
    const val UNKNOWN_SUBJECT = "esto"

    /** Spoken when the owner said no to a data wipe (T6). */
    const val REJECTED_DATA_WIPE = "No borré los datos."

    /** Spoken when the owner said no to any other destructive step (T6). */
    const val REJECTED_DESTRUCTIVE = "No borré nada."

    /** A composed message is read back up to this many characters. */
    const val MAX_READ_CHARS = 400

    /** Spoken after the appended words reached the compose field. */
    const val PROMPT_APPENDED = "Listo, agregué eso. ¿Está bien?"

    /** Spoken when the compose field could not be found or set. */
    const val PROMPT_APPEND_FAILED = "No pude agregar el texto. ¿Lo envío así?"

    /** Spoken after the owner said no; the message is not sent. */
    const val MESSAGE_REJECTED = "No lo envié."

    /** Spoken when the owner said no to a cache clear (T4). */
    const val REJECTED_CACHE = "No borré la caché."

    /** Spoken when the owner said no to an uninstall (T4). */
    const val REJECTED_UNINSTALL = "No desinstalé la app."

    /** Re-asked when an addition does not apply to a cache/uninstall prompt. */
    const val PROMPT_YES_NO = "Responde sí o no."

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

    /** "Voy a borrar la caché de WhatsApp. ¿Está bien?" (T4) */
    fun cachePrompt(appLabel: String?): String {
        val label = appLabel?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_APP
        return "Voy a borrar la caché de $label. ¿Está bien?"
    }

    /** "Voy a desinstalar WhatsApp. ¿Está bien?" (T4) */
    fun uninstallPrompt(appLabel: String?): String {
        val label = appLabel?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_APP
        return "Voy a desinstalar $label. ¿Está bien?"
    }

    /**
     * "Voy a borrar los datos de WhatsApp. Se cierra la sesión y se pierden
     * sus archivos en el teléfono. ¿Estás seguro?" (T6)
     */
    fun dataWipePrompt(appLabel: String?): String {
        val label = appLabel?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_APP
        return "Voy a borrar los datos de $label. " +
            "Se cierra la sesión y se pierden sus archivos en el teléfono. " +
            "¿Estás seguro?"
    }

    /**
     * "Voy a eliminar la foto. No se puede deshacer. ¿Estás seguro?" (T6).
     * [action] is the destructive verb the approval carries; [subject] is what
     * the screen shows. A title that repeats the verb is not said twice, and
     * an unknown subject becomes "esto".
     */
    fun destructivePrompt(action: String?, subject: String?): String {
        val verb = destructiveVerb(action)
        val raw = subject?.let(::singleLine)?.takeIf { it.isNotEmpty() }
        val what = raw?.let { withoutLeadingVerb(it, verb) }
            ?.takeIf { it.isNotEmpty() }
            ?: UNKNOWN_SUBJECT
        return "Voy a $verb $what. No se puede deshacer. ¿Estás seguro?"
    }

    /** The prompt of one approval family. */
    fun promptFor(
        kind: ApprovalVoiceKind,
        appLabel: String?,
        subject: String? = null,
        action: String? = null,
    ): String = when (kind) {
        ApprovalVoiceKind.MESSAGE -> sendPrompt(appLabel)
        ApprovalVoiceKind.CACHE -> cachePrompt(appLabel)
        ApprovalVoiceKind.UNINSTALL -> uninstallPrompt(appLabel)
        ApprovalVoiceKind.DATA_WIPE -> dataWipePrompt(appLabel)
        ApprovalVoiceKind.DESTRUCTIVE -> destructivePrompt(action, subject)
    }

    /** What the owner hears after rejecting a step of [kind]. */
    fun rejectMessage(kind: ApprovalVoiceKind): String = when (kind) {
        ApprovalVoiceKind.MESSAGE -> MESSAGE_REJECTED
        ApprovalVoiceKind.CACHE -> REJECTED_CACHE
        ApprovalVoiceKind.UNINSTALL -> REJECTED_UNINSTALL
        ApprovalVoiceKind.DATA_WIPE -> REJECTED_DATA_WIPE
        ApprovalVoiceKind.DESTRUCTIVE -> REJECTED_DESTRUCTIVE
    }

    /** Spanish verb for the generic destructive sentence; "Delete" -> eliminar. */
    private fun destructiveVerb(action: String?): String =
        when (val verb = action?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) {
            null -> "eliminar"
            "delete", "remove" -> "eliminar"
            else -> verb
        }

    /** Drops a leading repetition of [verb] ("Borrar descargas" -> "descargas"). */
    private fun withoutLeadingVerb(text: String, verb: String): String {
        val normalized = text.lowercase()
        if (!normalized.startsWith(verb)) return text
        val rest = text.substring(verb.length)
        if (rest.isNotEmpty() && rest.first().isLetterOrDigit()) return text
        return rest.trimStart(' ', ':', '-', '–', '.', ',')
    }

    /**
     * "Léemelo" on a cache/uninstall prompt (T4): the Settings size line when
     * the screen has one ("Caché 1,2 GB" -> "Caché 1,2 GB. ¿Está bien?"),
     * otherwise the title, otherwise the bare question.
     */
    fun storageReadPrompt(detail: String?, appLabel: String?): String {
        val line = detail?.let(::singleLine)?.takeIf { it.isNotEmpty() }
            ?: appLabel?.let(::singleLine)?.takeIf { it.isNotEmpty() }
        return if (line == null) "¿Está bien?" else "$line. ¿Está bien?"
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
 * message (phone-bubble T23) and of a cache/uninstall step (phone-dashboard
 * T4).
 *
 * Only an approval the routing rule sent to the bubble may reach this reducer;
 * the overlay binds the approve/reject actions to that one id. The owner's
 * answers are classified by [BubbleVoiceConfirm]:
 *
 * - affirmative: approve and say nothing more;
 * - read ("léemelo"): read the recipient and the composed text from the screen
 *   (a cache/uninstall prompt reads the size line or the Settings title) and
 *   ask again;
 * - add with content / anything else: append to the compose field and ask
 *   again; a failed append asks whether to send it as it is. For a cache or
 *   uninstall prompt an addition makes no sense, so every non-answer asks
 *   "Responde sí o no." again;
 * - bare add intent: ask what to add (messages) or yes/no (cache/uninstall);
 * - cancel: reject and say the family's rejection copy;
 * - the Aprobar / Rechazar buttons decide from any active state through the
 *   same Approve/Reject actions as a spoken answer;
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
        kind: ApprovalVoiceKind = ApprovalVoiceKind.MESSAGE,
    ): BubbleConfirmDecision = when (event) {
        is BubbleConfirmEvent.StartApproval -> onStart(state, event.prompt)
        is BubbleConfirmEvent.Answer -> onAnswer(state, event.text, kind)
        is BubbleConfirmEvent.ReadDone -> onReadDone(state, event, kind)
        BubbleConfirmEvent.ButtonApprove -> onButton(state, approve = true, kind = kind)
        BubbleConfirmEvent.ButtonReject -> onButton(state, approve = false, kind = kind)
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

    private fun onAnswer(
        state: BubbleConfirmState,
        raw: String,
        kind: ApprovalVoiceKind,
    ): BubbleConfirmDecision {
        val answer = raw.trim()
        return when (state) {
            BubbleConfirmState.AwaitingAnswer -> onAnswerToApproval(answer, kind)
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
            return rejectAndSayIt(ApprovalVoiceKind.MESSAGE)
        }
        return writeToCompose(answer)
    }

    private fun onAnswerToApproval(
        answer: String,
        kind: ApprovalVoiceKind,
    ): BubbleConfirmDecision {
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
            is ConfirmReply.AddWithContent -> additionOrAsk(reply.addition, kind)
            ConfirmReply.AddOnly -> additionOrAsk(null, kind)
            ConfirmReply.Cancel -> rejectAndSayIt(kind)
            is ConfirmReply.Other -> additionOrAsk(reply.text, kind)
        }
    }

    /**
     * T23 messages append what the owner said; a cache/uninstall prompt has
     * nothing to append, so every addition-shaped answer asks yes or no again.
     */
    private fun additionOrAsk(
        addition: String?,
        kind: ApprovalVoiceKind,
    ): BubbleConfirmDecision {
        if (kind != ApprovalVoiceKind.MESSAGE) return askYesOrNo()
        if (addition == null) {
            return BubbleConfirmDecision(
                state = BubbleConfirmState.Speaking(
                    BubbleVoiceConfirm.PROMPT_ADD,
                    ConfirmListenTarget.ADDITION,
                ),
                actions = listOf(
                    BubbleConfirmAction.ShowPrompt(BubbleVoiceConfirm.PROMPT_ADD),
                    BubbleConfirmAction.Speak(BubbleVoiceConfirm.PROMPT_ADD),
                ),
            )
        }
        return writeToCompose(addition)
    }

    private fun askYesOrNo(): BubbleConfirmDecision = BubbleConfirmDecision(
        state = BubbleConfirmState.Speaking(
            ApprovalVoiceCopy.PROMPT_YES_NO,
            ConfirmListenTarget.ANSWER,
        ),
        actions = listOf(
            BubbleConfirmAction.ShowPrompt(ApprovalVoiceCopy.PROMPT_YES_NO),
            BubbleConfirmAction.Speak(ApprovalVoiceCopy.PROMPT_YES_NO),
        ),
    )

    private fun rejectAndSayIt(kind: ApprovalVoiceKind): BubbleConfirmDecision =
        BubbleConfirmDecision(
            state = BubbleConfirmState.Idle,
            actions = listOf(
                BubbleConfirmAction.HidePrompt,
                BubbleConfirmAction.Speak(ApprovalVoiceCopy.rejectMessage(kind)),
                BubbleConfirmAction.Reject,
            ),
        )

    /**
     * Aprobar / Rechazar (T4): the buttons decide through the same Approve
     * and Reject actions a spoken answer produces. The overlay stops TTS and
     * the recognizer before dispatching; from Idle nothing is owned.
     */
    private fun onButton(
        state: BubbleConfirmState,
        approve: Boolean,
        kind: ApprovalVoiceKind,
    ): BubbleConfirmDecision {
        if (state == BubbleConfirmState.Idle) return BubbleConfirmDecision(state)
        return if (approve) {
            BubbleConfirmDecision(
                state = BubbleConfirmState.Idle,
                actions = listOf(BubbleConfirmAction.HidePrompt, BubbleConfirmAction.Approve),
            )
        } else {
            BubbleConfirmDecision(
                state = BubbleConfirmState.Idle,
                actions = listOf(
                    BubbleConfirmAction.HidePrompt,
                    BubbleConfirmAction.Speak(ApprovalVoiceCopy.rejectMessage(kind)),
                    BubbleConfirmAction.Reject,
                ),
            )
        }
    }

    private fun onReadDone(
        state: BubbleConfirmState,
        event: BubbleConfirmEvent.ReadDone,
        kind: ApprovalVoiceKind,
    ): BubbleConfirmDecision {
        if (state != BubbleConfirmState.Reading) return BubbleConfirmDecision(state)
        val prompt = if (kind == ApprovalVoiceKind.MESSAGE) {
            ApprovalVoiceCopy.readPrompt(event.recipient, event.text)
        } else {
            ApprovalVoiceCopy.storageReadPrompt(event.detail, event.recipient)
        }
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
