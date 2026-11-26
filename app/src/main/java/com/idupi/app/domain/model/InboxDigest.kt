package com.idupi.app.domain.model

import kotlinx.serialization.Serializable

/** Digest states the server reports (`inbox-management` T4). */
object InboxDigestStatus {
    const val READY = "ready"
    const val ANALYZING = "analyzing"
    const val FAILED = "failed"
}

/** Server digest scope of the one promotions summary (alerts-flow A2). */
object InboxDigestScope {
    const val PROMOTIONS = "promotions"
}

/**
 * One conversation digest: what the owner has not seen in a chat, summarized
 * by the PC agent. Built from the open items of `app` + `conversation`; the
 * server caches it until the included item ids and texts change.
 */
@Serializable
data class InboxDigest(
    val app: String = "",
    val conversation: String = "",
    /**
     * alerts-flow A2: "promotions" for the one digest over every open
     * promotion; null for a per-conversation digest.
     */
    val scope: String? = null,
    /** Items the digest covered (0 when nothing is open). */
    val itemCount: Int = 0,
    val summary: String = "",
    /** Questions or requests addressed to the owner that are still pending. */
    val pendingForOwner: List<String> = emptyList(),
    val suggestedReply: String = "",
    val status: String = InboxDigestStatus.READY,
    val error: String? = null,
    val updatedAt: String? = null
)

/** `POST /api/v1/inbox/digest` body. */
@Serializable
data class InboxDigestRequest(
    val app: String,
    val conversation: String
)

/** `POST /api/v1/inbox/conversation/state` body; state is handled|dismissed. */
@Serializable
data class InboxConversationStateRequest(
    val app: String,
    val conversation: String,
    val state: String
)

/** `POST /api/v1/inbox/conversation/state` response. */
@Serializable
data class InboxConversationStateResult(
    val closed: Int = 0
)
