package com.idupi.app.data.notification

import com.idupi.app.domain.model.DeliveryInfo

/**
 * What one poll of `/api/v1/deliveries` implies for notifications, plus the
 * marker state to persist for the next poll/restart.
 */
data class DeliveryNotificationDecision(
    val toNotify: List<DeliveryInfo>,
    val seenIds: Set<String>,
    val newestCreatedAt: String?
)

/** Cap so the persisted id set cannot grow forever across months of use. */
const val MAX_SEEN_DELIVERY_IDS: Int = 200

/**
 * Pure new-delivery detection, shared by the poller and its tests.
 *
 * [newestSeenCreatedAt] is the durable marker: the ISO-8601 instant of the
 * newest delivery already known (ISO strings compare chronologically). Old
 * deliveries stay silent after a restart even when the seen-id set was
 * trimmed; ids seen within the marker's window cover same-instant newcomers.
 *
 * The very first poll (no marker yet) is a bootstrap: everything currently on
 * the PC is treated as old news, and only deliveries pushed afterwards
 * notify. That is what keeps reinstalling the app from replaying the whole
 * list as notifications.
 *
 * @param deliveries current server list, newest first.
 * @param seenIds ids already observed (persisted).
 * @param newestSeenCreatedAt persisted marker, `null` before the first poll.
 */
fun deliveryNotificationDecision(
    deliveries: List<DeliveryInfo>,
    seenIds: Set<String>,
    newestSeenCreatedAt: String?
): DeliveryNotificationDecision {
    val currentIds = deliveries
        .map { it.id }
        .filter { it.isNotBlank() }
        .toSet()
    val newestInList = deliveries
        .mapNotNull { it.createdAt?.takeIf(String::isNotBlank) }
        .maxOrNull()
    val updatedNewest = listOfNotNull(newestSeenCreatedAt, newestInList).maxOrNull()

    val updatedSeenIds = if (seenIds.size + currentIds.size > MAX_SEEN_DELIVERY_IDS) {
        currentIds
    } else {
        seenIds + currentIds
    }

    if (newestSeenCreatedAt == null) {
        return DeliveryNotificationDecision(
            toNotify = emptyList(),
            seenIds = updatedSeenIds,
            newestCreatedAt = updatedNewest
        )
    }

    val toNotify = deliveries.filter { delivery ->
        delivery.id.isNotBlank() &&
            delivery.id !in seenIds &&
            (delivery.createdAt == null || delivery.createdAt >= newestSeenCreatedAt)
    }
    return DeliveryNotificationDecision(
        toNotify = toNotify,
        seenIds = updatedSeenIds,
        newestCreatedAt = updatedNewest
    )
}
