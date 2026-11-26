package com.idupi.app.data.phone.control

sealed interface NodeMatch {
    data class Found(val node: NodeSnapshot) : NodeMatch
    data class NotFound(val reason: String) : NodeMatch
}

/**
 * Finds the node a tap / long_press should act on (phone-agent T3).
 *
 * The documented rule, in order:
 * 1. Only visible nodes are candidates.
 * 2. A [NodeTarget.Text] query first tries exact text equality (case
 *    sensitive). Only when nothing matches does it fall back to a
 *    case-insensitive "contains" over text and content description.
 *    A [NodeTarget.Id] query matches the full view resource id exactly.
 * 3. Every candidate resolves to an actionable node: itself when clickable,
 *    otherwise its nearest clickable ancestor, otherwise itself (the executor
 *    can still tap its center by coordinates).
 * 4. If the resolved actionable nodes collapse to one distinct target, that is
 *    the match. More than one distinct target means the query is ambiguous and
 *    nothing is tapped: a wrong tap is worse than a reported failure.
 *
 * Matching several visible nodes that share the same clickable row (for
 * example a label and its icon) is therefore not ambiguous, which is what makes
 * `tap "Enviar"` reliable on list rows.
 */
object NodeMatcher {

    fun find(root: NodeSnapshot, target: NodeTarget): NodeMatch = find(listOf(root), target)

    fun find(roots: List<NodeSnapshot>, target: NodeTarget): NodeMatch {
        val candidates = mutableListOf<Candidate>()
        roots.forEach { collect(it, emptyList(), candidates) }
        val visible = candidates.filter { it.node.visible }
        if (visible.isEmpty()) return NodeMatch.NotFound(NO_VISIBLE)

        val matched = when (target) {
            is NodeTarget.Text -> {
                val exact = visible.filter { it.node.text == target.text }
                if (exact.isNotEmpty()) {
                    exact
                } else {
                    val needle = target.text
                    visible.filter {
                        it.node.text.contains(needle, ignoreCase = true) ||
                            it.node.desc.contains(needle, ignoreCase = true)
                    }
                }
            }
            is NodeTarget.Id -> visible.filter { it.node.viewId == target.id }
            is NodeTarget.Point -> return NodeMatch.NotFound(POINT_HAS_NO_NODE)
        }

        if (matched.isEmpty()) {
            return NodeMatch.NotFound(
                if (target is NodeTarget.Text) TEXT_NOT_FOUND else ID_NOT_FOUND
            )
        }

        val targets = matched
            .map(::actionable)
            .distinctBy { ActionKey(it.className, it.viewId, it.bounds) }

        return when (targets.size) {
            1 -> NodeMatch.Found(targets.single())
            else -> NodeMatch.NotFound(AMBIGUOUS)
        }
    }

    /**
     * Text and content description of [node] and its descendants, pre-order.
     *
     * Used by the phone-side risky-node check: the visible label of a button is
     * often a child TextView, so looking only at the clickable ancestor would
     * miss "Enviar".
     */
    fun textCandidates(node: NodeSnapshot): List<String> {
        val texts = mutableListOf<String>()
        collectTexts(node, texts)
        return texts
    }

    private fun collectTexts(node: NodeSnapshot, into: MutableList<String>) {
        if (node.text.isNotBlank()) into += node.text
        if (node.desc.isNotBlank()) into += node.desc
        node.children.forEach { child -> collectTexts(child, into) }
    }

    /**
     * Texts of the smallest visible node containing the coordinates, falling
     * back to the nearest annotated ancestor. This is what lets a blind
     * `tap --xy` be stopped on a send button (phone-approvals T2).
     */
    fun textsAtPoint(roots: List<NodeSnapshot>, x: Int, y: Int): List<String> {
        var best: NodeSnapshot? = null
        var bestChain: List<NodeSnapshot> = emptyList()
        var bestArea = Long.MAX_VALUE

        fun walk(node: NodeSnapshot, ancestors: List<NodeSnapshot>) {
            val bounds = node.bounds
            val contains = x >= bounds.x && x < bounds.x + bounds.width &&
                y >= bounds.y && y < bounds.y + bounds.height
            if (node.visible && contains) {
                val area = bounds.width.toLong() * bounds.height.toLong()
                // Deepest/smallest wins; `<=` keeps the later (deeper) node on a tie.
                if (area <= bestArea) {
                    best = node
                    bestChain = ancestors
                    bestArea = area
                }
            }
            node.children.forEach { child -> walk(child, ancestors + node) }
        }

        roots.forEach { walk(it, emptyList()) }
        val node = best ?: return emptyList()
        val own = textCandidates(node)
        if (own.isNotEmpty()) return own
        for (index in bestChain.indices.reversed()) {
            val ancestors = textCandidates(bestChain[index])
            if (ancestors.isNotEmpty()) return ancestors
        }
        return emptyList()
    }

    private fun collect(
        node: NodeSnapshot,
        ancestors: List<NodeSnapshot>,
        into: MutableList<Candidate>,
    ) {
        into += Candidate(node, ancestors)
        node.children.forEach { child -> collect(child, ancestors + node, into) }
    }

    private fun actionable(candidate: Candidate): NodeSnapshot {
        if (candidate.node.clickable) return candidate.node
        for (index in candidate.ancestors.indices.reversed()) {
            val ancestor = candidate.ancestors[index]
            if (ancestor.clickable) return ancestor
        }
        return candidate.node
    }

    private data class Candidate(
        val node: NodeSnapshot,
        val ancestors: List<NodeSnapshot>,
    )

    private data class ActionKey(
        val className: String,
        val viewId: String,
        val bounds: NodeBounds,
    )

    private const val NO_VISIBLE = "no hay nada visible"
    private const val TEXT_NOT_FOUND = "no se encontró el texto"
    private const val ID_NOT_FOUND = "no se encontró el id"
    private const val AMBIGUOUS = "varios elementos coinciden"
    private const val POINT_HAS_NO_NODE = "las coordenadas no se buscan"
}
