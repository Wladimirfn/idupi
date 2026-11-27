package com.idupi.app.data.phone.bubble

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Recipient and composed message read from the screen for the voice approval
 * (phone-bubble T23).
 *
 * @property recipient conversation title: WhatsApp's
 *   `com.whatsapp:id/conversation_contact_name`, or a generic window/toolbar
 *   fallback. Null when nothing readable was found.
 * @property text the composed message: WhatsApp's `com.whatsapp:id/entry`, or
 *   the focused / first editable field of the active window.
 */
data class ApprovalScreenContent(
    val recipient: String?,
    val text: String?,
)

/**
 * Reads and edits the app behind the bubble with the accessibility service.
 *
 * This is glue only: the spoken sentence and its truncation live in
 * [ApprovalVoiceCopy], and the screen text never reaches the decision logic.
 * The bubble's own accessibility-overlay windows are skipped, so an expanded
 * panel never shadows the WhatsApp conversation it is asking about. Logs carry
 * ids and outcomes, never message text.
 */
class ApprovalScreenReader(private val service: AccessibilityService) {

    /** Reads the conversation title and the composed message, best effort. */
    fun read(): ApprovalScreenContent {
        val roots = roots()
        return ApprovalScreenContent(
            recipient = knownRecipient(roots) ?: genericRecipient(roots),
            text = composeField(roots)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Appends [addition] to the compose field with `ACTION_SET_TEXT`, preserving
     * what the owner already wrote and separating it with a single space.
     *
     * @return false when no editable field exists or the app refuses the text;
     *   the caller then asks whether to send it as it is.
     */
    fun append(addition: String): Boolean {
        val text = addition.trim()
        if (text.isEmpty()) return false
        val field = composeField(roots()) ?: run {
            Log.i(TAG, "append skipped: no compose field")
            return false
        }
        if (field.isPassword) return false
        val current = field.text?.toString().orEmpty()
        val combined = if (current.isBlank()) text else current.trimEnd() + " " + text
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                combined,
            )
        }
        val appended = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.i(TAG, "append to compose field: ${if (appended) "ok" else "refused"}")
        return appended
    }

    // --- windows ----------------------------------------------------------

    /**
     * Roots of the windows behind the bubble, active first. Accessibility
     * overlays are filtered out: that is the bubble itself.
     */
    private fun roots(): List<AccessibilityNodeInfo> {
        val fromWindows = runCatching {
            service.windows
                .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                .sortedByDescending { if (it.isActive) 1 else 0 }
                .mapNotNull { window -> window.root }
        }.getOrDefault(emptyList())
        if (fromWindows.isNotEmpty()) return fromWindows
        // Last resort when the window list is unavailable: never our own
        // overlay, which would read the bubble's field instead of the app's.
        return runCatching {
            service.rootInActiveWindow
                ?.takeIf { it.packageName?.toString() != service.packageName }
                ?.let(::listOf)
        }.getOrNull().orEmpty()
    }

    // --- recipient --------------------------------------------------------

    private fun knownRecipient(roots: List<AccessibilityNodeInfo>): String? {
        roots.forEach { root ->
            knownRecipient(root)?.let { return it }
        }
        return null
    }

    private fun knownRecipient(node: AccessibilityNodeInfo): String? {
        val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        if (id in RECIPIENT_IDS) nodeLabel(node)?.let { return it }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            knownRecipient(child)?.let { return it }
        }
        return null
    }

    /**
     * Generic fallback: the active window title, then the top-most toolbar
     * text, then the first visible text of the top band of the screen.
     */
    private fun genericRecipient(roots: List<AccessibilityNodeInfo>): String? {
        activeWindowTitle()?.let { return it }
        roots.forEach { root ->
            toolbarText(root)?.let { return it }
        }
        val band = (service.resources.displayMetrics.heightPixels * TOP_BAND_FRACTION).toInt()
        roots.forEach { root ->
            topText(root, band)?.let { return it }
        }
        return null
    }

    private fun activeWindowTitle(): String? = runCatching {
        service.windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .firstOrNull { it.isActive }
            ?.title
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun toolbarText(node: AccessibilityNodeInfo): String? {
        val id = node.viewIdResourceName.orEmpty().lowercase()
        val className = node.className?.toString().orEmpty()
        val isToolbar = className.contains("Toolbar", ignoreCase = true) ||
            id.contains("toolbar") ||
            id.contains("action_bar")
        if (isToolbar) {
            firstText(node)?.let { return it }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            toolbarText(child)?.let { return it }
        }
        return null
    }

    private fun topText(node: AccessibilityNodeInfo, band: Int): String? {
        if (node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.top in 0 until band) {
                nodeLabel(node)?.let { return it }
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            topText(child, band)?.let { return it }
        }
        return null
    }

    private fun firstText(node: AccessibilityNodeInfo): String? {
        nodeLabel(node)?.let { return it }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            firstText(child)?.let { return it }
        }
        return null
    }

    // --- compose field ----------------------------------------------------

    private fun composeField(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        roots.forEach { root ->
            findById(root, ENTRY_IDS)?.let { node ->
                if (node.isEditable && node.isVisibleToUser) return node
            }
        }
        roots.forEach { root ->
            val focused = runCatching {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            }.getOrNull()
            if (focused != null && focused.isEditable && focused.isVisibleToUser) return focused
        }
        roots.forEach { root ->
            firstEditable(root)?.let { return it }
        }
        return null
    }

    private fun findById(
        node: AccessibilityNodeInfo,
        ids: Set<String>,
    ): AccessibilityNodeInfo? {
        val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        if (id.isNotEmpty() && id in ids) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findById(child, ids)?.let { return it }
        }
        return null
    }

    private fun firstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            firstEditable(child)?.let { return it }
        }
        return null
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        private const val TAG = "ApprovalScreenReader"

        /** Top band where a conversation title normally sits. */
        private const val TOP_BAND_FRACTION = 0.22f

        /** Conversation title ids in the apps the owner uses, plus generics. */
        private val RECIPIENT_IDS = setOf(
            "conversation_contact_name",
            "conversation_title",
            "conversation_title_view",
            "chat_title",
            "toolbar_title",
        )

        /** Compose field ids: WhatsApp's entry first, plus generic names. */
        private val ENTRY_IDS = setOf(
            "entry",
            "message_input",
            "message_text",
            "chat_input",
            "compose",
        )
    }
}
