package com.idupi.app.data.inbox

/**
 * What happened to one capture's upload attempt (alerts-flow A1).
 *
 * Only [ACCEPTED] proves the PC received the capture; a queued or discarded
 * upload must not cost the owner the notification they may still need.
 */
enum class InboxUploadOutcome { ACCEPTED, QUEUED, DISCARDED }

/**
 * When a captured notification leaves the phone shade (alerts-flow A1).
 *
 * Two rules coexist:
 * - "Clear captured from the shade": only an [InboxUploadOutcome.ACCEPTED]
 *   upload may cancel the notification. Queued (PC unreachable) and discarded
 *   (PC refused) captures stay visible so the owner is never left without the
 *   alert they cannot find anywhere else.
 * - Silenced conversations keep their older rule: they are cancelled as soon
 *   as IDUPI captured them, even before the upload runs.
 *
 * Pure by design: the listener owns the binder call and this object owns the
 * decision, so the truth table is unit-testable without Android.
 */
object InboxShadePolicy {

    fun shouldCancel(
        outcome: InboxUploadOutcome,
        silenced: Boolean,
        clearCaptured: Boolean
    ): Boolean = silenced || (clearCaptured && outcome == InboxUploadOutcome.ACCEPTED)
}
