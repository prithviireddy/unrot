package app.unrot.rules

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * A live node the caller asked to keep (see [NodeSnap.captureFull]). The caller owns it and must [recycle] it when it is
 * released (a no-op on API 33+).
 */
class KeptNode(val bounds: Box, val viewId: String?, val desc: String?, val node: AccessibilityNodeInfo)

/** A snapshot, whether it is incomplete because the node budget ran out, and any live nodes kept for later use. */
data class Capture(val root: NodeSnap, val truncated: Boolean, val kept: List<KeptNode> = emptyList(), val nodes: Int = 0)

/**
 * Plain, immutable copy of one accessibility node. We snapshot the tree once per evaluation
 * so the rules engine (and the Inspector) never hold live AccessibilityNodeInfo objects. The only live nodes kept are the
 * bar's tab nodes (see [KeptNode]), which a tap uses without a fresh tree walk.
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
    /** Z-order within the window, from AccessibilityNodeInfo.getDrawingOrder (higher is drawn later, i.e. on top). */
    val drawingOrder: Int = 0,
    /** Window the node belongs to. Only recorded on the root in dumps. */
    val windowId: Int = -1,
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
        put("order", drawingOrder)
        if (depth == 0) put("win", windowId)
        if (children.isNotEmpty()) put("children", JSONArray().apply { children.forEach { put(it.toJson(maxText)) } })
    }

    companion object {
        private const val MAX_NODES = 4000
        private const val MAX_DEPTH = 60

        /** Snapshots [root] and its descendants. Does not recycle [root] itself. */
        fun capture(root: AccessibilityNodeInfo): NodeSnap = captureFull(root).root

        /**
         * One node without its children (a node found by view id, see the block probe). Does not recycle [node].
         */
        @Suppress("DEPRECATION")
        fun single(node: AccessibilityNodeInfo): NodeSnap {
            val r = Rect().also { node.getBoundsInScreen(it) }
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
                depth = 0,
                children = emptyList(),
                drawingOrder = node.drawingOrder,
                windowId = node.windowId,
            )
        }

        /**
         * Like [capture], and says whether the node budget ran out (the snapshot is then incomplete). A child for which
         * [keep] returns true (given its screen bounds, view id and description) is not recycled: it is returned in
         * [Capture.kept] as a live node, so the caller can act on it later without a fresh tree walk.
         */
        fun captureFull(
            root: AccessibilityNodeInfo,
            keep: ((Box, String?, String?) -> Boolean)? = null,
        ): Capture {
            val budget = Budget(MAX_NODES)
            val kept = ArrayList<KeptNode>()
            val snap = captureRec(root, 0, budget, keep, kept)
            return Capture(snap, budget.truncated, kept, MAX_NODES - budget.left)
        }

        /** Parses one node of an Inspector dump (see Inspector.kt / NodeSnap.toJson for the format). */
        fun fromJson(o: JSONObject, depth: Int = 0): NodeSnap {
            val bounds = Regex("-?\\d+").findAll(o.optString("bounds")).map { it.value.toInt() }.toList()
            val kids = o.optJSONArray("children")
            return NodeSnap(
                viewId = o.optStringOrNull("id"),
                className = o.optStringOrNull("cls"),
                desc = o.optStringOrNull("desc"),
                text = o.optStringOrNull("text"),
                left = bounds.getOrElse(0) { 0 },
                top = bounds.getOrElse(1) { 0 },
                right = bounds.getOrElse(2) { 0 },
                bottom = bounds.getOrElse(3) { 0 },
                selected = o.optBoolean("selected", false),
                clickable = o.optBoolean("clickable", false),
                scrollable = o.optBoolean("scrollable", false),
                visible = o.optBoolean("visible", true),
                depth = depth,
                children = (0 until (kids?.length() ?: 0)).map { fromJson(kids!!.getJSONObject(it), depth + 1) },
                drawingOrder = o.optInt("order", 0),
                windowId = o.optInt("win", -1),
            )
        }

        private fun JSONObject.optStringOrNull(k: String) = if (has(k) && !isNull(k)) getString(k) else null

        private class Budget(var left: Int) { var truncated = false }

        @Suppress("DEPRECATION")
        private fun captureRec(
            node: AccessibilityNodeInfo, depth: Int, budget: Budget,
            keep: ((Box, String?, String?) -> Boolean)?, kept: MutableList<KeptNode>,
        ): NodeSnap {
            budget.left--
            val r = Rect().also { node.getBoundsInScreen(it) }
            val kids = ArrayList<NodeSnap>(node.childCount)
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    if (budget.left <= 0) { budget.truncated = true; break }
                    // API 33+: the child comes with its descendants prefetched in the same call, one IPC round trip per child
                    // rather than per node. The framework caps a prefetch at about 50 nodes per request, so a larger subtree
                    // costs further round trips.
                    val child = (if (Build.VERSION.SDK_INT >= 33) node.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST)
                    else node.getChild(i)) ?: continue
                    var keepChild = false
                    try {
                        if (keep != null) {
                            val cr = Rect().also { child.getBoundsInScreen(it) }
                            val box = Box(cr.left, cr.top, cr.right, cr.bottom) // built once per node
                            val vid = child.viewIdResourceName
                            val desc = child.contentDescription?.toString()
                            if (keep(box, vid, desc)) {
                                keepChild = true
                                kept.add(KeptNode(box, vid, desc, child))
                            }
                        }
                        kids.add(captureRec(child, depth + 1, budget, keep, kept))
                    } finally {
                        if (!keepChild) recycleNode(child)
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
                drawingOrder = node.drawingOrder,
                windowId = node.windowId,
            )
        }
    }
}

/** Recycles a node the caller owns. A no-op from API 33 (nodes are not pooled there); below it, the pool gets the node back. */
@Suppress("DEPRECATION")
fun recycleNode(n: AccessibilityNodeInfo) {
    if (Build.VERSION.SDK_INT < 33) runCatching { n.recycle() }
}
