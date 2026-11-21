package app.unrot.rules

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plain, immutable copy of one accessibility node. We snapshot the tree once per evaluation
 * so the rules engine (and the Inspector) never hold live AccessibilityNodeInfo objects.
 */
data class NodeSnap(
    val viewId: String?,
    val className: String?,
    val desc: String?,
    val text: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val selected: Boolean,
    val clickable: Boolean,
    val scrollable: Boolean,
    val visible: Boolean,
    val depth: Int,
    val children: List<NodeSnap>,
) {
    val width get() = right - left
    val height get() = bottom - top

    fun flatten(out: MutableList<NodeSnap> = ArrayList()): List<NodeSnap> {
        out.add(this)
        children.forEach { it.flatten(out) }
        return out
    }

    fun toJson(maxText: Int = 60): JSONObject = JSONObject().apply {
        viewId?.let { put("id", it) }
        className?.let { put("cls", it) }
        desc?.let { put("desc", it.take(maxText)) }
        text?.let { put("text", it.take(maxText)) }
        put("bounds", "[$left,$top][$right,$bottom]")
        if (selected) put("selected", true)
        if (clickable) put("clickable", true)
        if (scrollable) put("scrollable", true)
        if (!visible) put("visible", false)
        if (children.isNotEmpty()) put("children", JSONArray().apply { children.forEach { put(it.toJson(maxText)) } })
    }

    companion object {
        private const val MAX_NODES = 4000
        private const val MAX_DEPTH = 60

        /** Snapshots [root] and its descendants. Does not recycle [root] itself. */
        fun capture(root: AccessibilityNodeInfo): NodeSnap {
            val budget = intArrayOf(MAX_NODES)
            return captureRec(root, 0, budget)
        }

        @Suppress("DEPRECATION")
        private fun captureRec(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): NodeSnap {
            budget[0]--
            val r = Rect().also { node.getBoundsInScreen(it) }
            val kids = ArrayList<NodeSnap>(node.childCount)
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    if (budget[0] <= 0) break
                    val child = node.getChild(i) ?: continue
                    try {
                        kids.add(captureRec(child, depth + 1, budget))
                    } finally {
                        child.recycle()
                    }
                }
            }
            return NodeSnap(
                viewId = node.viewIdResourceName,
                className = node.className?.toString(),
                desc = node.contentDescription?.toString(),
                text = node.text?.toString(),
                left = r.left, top = r.top, right = r.right, bottom = r.bottom,
                selected = node.isSelected,
                clickable = node.isClickable,
                scrollable = node.isScrollable,
                visible = node.isVisibleToUser,
                depth = depth,
                children = kids,
            )
        }
    }
}
