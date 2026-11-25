package com.idupi.app.data.inbox

import com.idupi.app.domain.model.InboxItem
import java.time.Instant

/**
 * Open items of one chat, ready for the Alertas list (`inbox-management` T4).
 *
 * A conversation with one open item renders as the usual card; two or more
 * render as one conversation card with a digest. Keeping this grouping pure
 * makes the ordering rules testable without Compose.
 */
data class InboxConversationGroup(
    val conversationKey: String,
    val app: String,
    /** PackageManager label captured by the phone; newest non-blank one wins. */
    val appLabel: String,
    val conversation: String,
    /** Newest first, matching the server list order. */
    val items: List<InboxItem>,
    val newestAtMillis: Long
) {
    val count: Int get() = items.size

    /** The item whose suggested reply/actions a single-item card uses. */
    val newestItem: InboxItem get() = items.first()
}

/** Pure grouping and ordering for the Alertas conversation cards. */
object InboxConversationGroups {

    /**
     * Key of the single card that holds every open promotion (alerts-flow A2).
     * It contains no separator, so it can never collide with a real
     * `app|conversation` key.
     */
    const val PROMOTIONS_KEY = "promotions"

    /**
     * Groups open items by `app|conversation`, newest conversation first.
     * Items with no parseable timestamp sort last (oldest), so a malformed
     * date never hides a real message behind it.
     *
     * Promotions are pulled out of their conversations into one extra group
     * that always comes last (alerts-flow A2): marketing never renders as an
     * individual pending card, whatever app or chat it came from.
     */
    fun group(items: List<InboxItem>): List<InboxConversationGroup> {
        val promotions = promotionItems(items)
        val regular = items.filterNot { it.promotion }
        val byKey = LinkedHashMap<String, MutableList<InboxItem>>()
        for (item in regular) {
            val key = inboxConversationKey(item.app, item.conversation)
            byKey.getOrPut(key) { mutableListOf() }.add(item)
        }

        val groups = byKey
            .map { (key, grouped) ->
                val ordered = grouped.sortedByDescending(::newestMillis)
                val label = ordered.firstOrNull { it.appLabel.isNotBlank() }?.appLabel.orEmpty()
                InboxConversationGroup(
                    conversationKey = key,
                    app = ordered.first().app,
                    appLabel = label,
                    conversation = ordered.first().conversation,
                    items = ordered,
                    newestAtMillis = newestMillis(ordered.first())
                )
            }
            .sortedWith(
                compareByDescending<InboxConversationGroup> { it.newestAtMillis }
                    .thenBy { it.conversationKey }
            )

        if (promotions.isEmpty()) return groups
        return groups + InboxConversationGroup(
            conversationKey = PROMOTIONS_KEY,
            app = "",
            appLabel = "",
            conversation = "Promociones",
            items = promotions,
            newestAtMillis = newestMillis(promotions.first())
        )
    }

    /** Promotion items of every app, newest first; the screen shows them all. */
    fun promotionItems(items: List<InboxItem>): List<InboxItem> =
        items.filter { it.promotion }.sortedByDescending(::newestMillis)

    /** `receivedAt` wins over `postedAt`; 0 when neither parses. */
    fun newestMillis(item: InboxItem): Long =
        parseInstantMillis(item.receivedAt)
            ?: parseInstantMillis(item.postedAt)
            ?: 0L

    private fun parseInstantMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }
}
