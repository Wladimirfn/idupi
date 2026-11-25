package com.idupi.app.data.phone.control

/**
 * Plain, Android-free snapshot of one accessibility node (phone-agent T3).
 *
 * The service walks the live `AccessibilityNodeInfo` tree once and turns it
 * into this model, so the dump format, matching and their tests stay pure JVM
 * code. [visible] mirrors `isVisibleToUser`; invisible nodes are never dumped
 * or used as a command target.
 *
 * @property className fully qualified class name as reported by the platform
 *   (`android.widget.TextView`); the dump prints only the short name.
 * @property bounds screen pixel rectangle.
 */
data class NodeSnapshot(
    val className: String = "",
    val text: String = "",
    val desc: String = "",
    val viewId: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val visible: Boolean = true,
    val bounds: NodeBounds = NodeBounds(0, 0, 0, 0),
    val children: List<NodeSnapshot> = emptyList(),
)

/** Screen pixel rectangle: [x],[y] top-left corner plus [width] and [height]. */
data class NodeBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
}
