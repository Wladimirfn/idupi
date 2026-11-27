package com.idupi.app.data.phone.bubble

/** Where the request that produced the current turn came from (phone-bubble T17). */
enum class BubbleRequestOrigin { TYPED, DICTATED }

/**
 * Voice follow-up decision (phone-bubble T17).
 *
 * A dictated request is "hands free": if the agent answers with a question back
 * to the owner, the bubble opens with the answer visible and the microphone
 * ready so she can answer by voice. A typed request is left exactly as it was,
 * a statement is just read, and a failed turn never reopens the microphone.
 *
 * The question heuristic is deliberately simple and pinned by tests: the
 * trimmed answer ends with "?" or contains the Spanish opening mark "¿".
 * Listening is only prepared here. Since T21 the dictated reply is handled by
 * the voice confirmation loop: a short yes or plain "no" is sent straight to
 * the agent as the next request, and anything longer goes through the read-back
 * before it can be sent.
 */
object BubbleVoiceFollowUp {

    fun shouldListen(
        origin: BubbleRequestOrigin,
        phase: BubbleChatPhase,
        answer: String?,
    ): Boolean = origin == BubbleRequestOrigin.DICTATED &&
        phase == BubbleChatPhase.DONE &&
        answer != null &&
        isQuestion(answer)

    /** True when [text] reads as a question back to the owner. */
    fun isQuestion(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.endsWith("?") || trimmed.contains("¿")
    }
}
