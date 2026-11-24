package com.idupi.app.data.inbox

/**
 * Selection rule for "notifications already in the shade" (inbox capture fix
 * T1). Pure over [NotificationSnapshot], so the active-notification scan uses
 * exactly the same parser as live posts and stays unit-testable without
 * Robolectric: the listener is the only place that touches the Android objects.
 *
 * The system list can repeat a key while an app updates a notification; the
 * first snapshot for each key wins, matching what the live flow would have
 * captured first.
 */
object InboxActiveScan {

    fun select(
        snapshots: List<NotificationSnapshot>,
        ownPackageName: String = NotificationParser.OWN_PACKAGE
    ): List<CapturedMessage> {
        val seenKeys = HashSet<String>(snapshots.size)
        val selected = ArrayList<CapturedMessage>(snapshots.size)
        for (snapshot in snapshots) {
            val captured = NotificationParser.parse(snapshot, ownPackageName) ?: continue
            if (captured.notificationKey.isNotBlank() &&
                !seenKeys.add(captured.notificationKey)
            ) {
                continue
            }
            selected.add(captured)
        }
        return selected
    }
}
