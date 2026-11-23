package app.unrot.rules

import kotlin.math.abs

/** REBUILD draws our own copy of the bar; PATCH only covers the hidden tab. */
enum class BarMode { REBUILD, PATCH }

/** One tab of the bar, left to right. [hidden] is the tab the rule removes; [selects] is false for tabs that never switch screens. */
data class BarTab(
    val index: Int, val identity: String, val viewId: String?, val desc: String?, val box: Box,
    val selected: Boolean, val hidden: Boolean, val selects: Boolean, val signature: String,
)

/**
 * The bar on one screen. [patchBox] is where the hidden tab's patch goes (null when there is no single
 * hidden tab, or when an occluder would sit under the patch). [rejectReason] says why a bar that could be
 * rebuilt was downgraded; it is null when the rule itself asks for PATCH.
 */
data class BarLayout(
    val targetId: String, val feature: String, val container: Box, val tabsBox: Box, val tabs: List<BarTab>,
    val patchBox: Box?, val mode: BarMode, val rejectReason: String?, val light: Int, val dark: Int,
)

/** Pure detection of a bottom tab bar from a snapshot. Thresholds and rules: docs/plans/0008-bar-redraw-plan.md, Step B. */
object Bar {

    fun detect(t: Target, root: NodeSnap, screenW: Int, screenH: Int, navBar: Box?, truncated: Boolean): BarLayout? {
        val nodes = root.flatten()
        val container = nodes.firstOrNull { n ->
            n.visible && n.width > 0 && n.height > 0 && t.bar.any { it.matches(n, screenW, screenH) }
        } ?: return containerMiss(t, root, nodes, screenW, screenH)
        val box = container.box

        val tabsNode = if (t.tabsContainer.isEmpty()) descend(container)
            else container.flatten().firstOrNull { n -> t.tabsContainer.any { it.matches(n, screenW, screenH) } }
        val tabNodes = tabsNode?.children.orEmpty()
            .filter { it.visible && it.width > 0 && it.height > 0 }
            .sortedBy { it.left }
        if (tabNodes.isEmpty()) {
            return BarLayout(t.id, t.feature, box, box, emptyList(), null, BarMode.PATCH, "noTabs", t.coverLight, t.coverDark)
        }

        val tabsBox = tabNodes.map { it.box }.reduce(Box::union)
        val hiddenFlags = tabNodes.map { tab -> t.hide.any { it.matchesAny(tab.flatten(), screenW, screenH) } }
        val hiddenIdx = hiddenFlags.indices.filter { hiddenFlags[it] }

        val reasons = buildList {
            if (tabsBox.t < screenH * 0.75 || tabsBox.height > screenH * 0.12) add("notBottom")
            if (tabNodes.size !in t.minTabs..t.maxTabs) add("tabCount=${tabNodes.size}")
            if (hiddenIdx.size != 1) add("hidden=${hiddenIdx.size}")
            val widths = tabNodes.map { it.width }.sorted()
            val median = widths[widths.size / 2]
            if (tabNodes.any { abs(it.width - median) > 0.25 * median }) add("tabWidths")
            if (tabNodes.any { !box.contains(it.box) }) add("tabOutside")
            if (navBar != null && tabsBox.intersects(navBar)) add("nav")
            if (truncated) add("truncated")
        }

        val hiddenTab = if (hiddenIdx.size == 1) tabNodes[hiddenIdx[0]] else null
        val occ = occluders(root, container, tabsBox, t.occluderIgnore, screenW, screenH)
        val patchBox = hiddenTab?.box?.takeUnless { hb -> occ.any { it.box.intersects(hb) } }
        val rejectReason = if (occ.isNotEmpty()) "occluded" else reasons.firstOrNull()
        val mode = if (rejectReason != null || t.barMode == BarMode.PATCH) BarMode.PATCH else BarMode.REBUILD

        val viewIds = tabNodes.map { it.viewId }
        val descs = tabNodes.map { it.desc }
        val tabs = tabNodes.mapIndexed { i, tab ->
            val identity = tab.viewId?.takeIf { v -> viewIds.count { it == v } == 1 }
                ?: tab.desc?.takeIf { d -> descs.count { it == d } == 1 }
                ?: "#$i"
            BarTab(
                index = i,
                identity = identity,
                viewId = tab.viewId,
                desc = tab.desc,
                box = tab.box,
                selected = tab.selected,
                hidden = hiddenFlags[i],
                selects = t.noSelect.none { it.matchesAny(tab.flatten(), screenW, screenH) },
                signature = signature(tab),
            )
        }
        return BarLayout(t.id, t.feature, box, tabsBox, tabs, patchBox, mode, rejectReason, t.coverLight, t.coverDark)
    }

    /**
     * No bar container: a tab-sized node in the bottom quarter that matches a `hide` cond naming a view id (only those, see
     * containerMiss) gets a patch, if nothing is drawn over it.
     */
    private fun containerMiss(t: Target, root: NodeSnap, nodes: List<NodeSnap>, w: Int, h: Int): BarLayout? {
        // Only a hide cond that names a view id (exactly) may patch without a container: a description alone (a profile's own
        // "Reels" grid tab, a Shorts description in the lower quarter) is not a bar.
        val node = nodes.firstOrNull { n ->
            n.visible && n.width > 0 && n.height > 0 &&
                n.width <= w * 0.5 && n.height <= h * 0.2 && n.top >= h * 0.75 &&
                t.hide.any { it.viewId != null && it.matches(n, w, h) }
        } ?: return null
        val box = node.box
        if (occluders(root, node, box, t.occluderIgnore, w, h).isNotEmpty()) return null
        return BarLayout(t.id, t.feature, box, box, emptyList(), box, BarMode.PATCH, "noContainer", t.coverLight, t.coverDark)
    }

    /** Follows single children down from the container; the children of the first node with 2+ children are the tabs. */
    private fun descend(start: NodeSnap): NodeSnap {
        var n = start
        while (n.children.size == 1) n = n.children[0]
        return n
    }

    /**
     * Nodes drawn over [box] by something that is later in drawing order than [target]'s ancestors
     * (compared per sibling as (drawingOrder, index)). Only nodes that could take a tap or show content count.
     */
    private fun occluders(root: NodeSnap, target: NodeSnap, box: Box, ignore: List<Cond>, w: Int, h: Int): List<NodeSnap> {
        val out = ArrayList<NodeSnap>()
        fun walk(node: NodeSnap): Boolean {
            if (node === target) return true
            node.children.forEachIndexed { i, child ->
                if (walk(child)) {
                    node.children.forEachIndexed { j, sib ->
                        val later = sib.drawingOrder > child.drawingOrder || (sib.drawingOrder == child.drawingOrder && j > i)
                        if (j != i && later) sib.flatten().filterTo(out) { isOccluder(it, box, ignore, w, h) }
                    }
                    return true
                }
            }
            return false
        }
        walk(root)
        return out
    }

    private fun isOccluder(n: NodeSnap, box: Box, ignore: List<Cond>, w: Int, h: Int): Boolean =
        n.visible && n.box.intersects(box) &&
            (n.clickable || !n.text.isNullOrEmpty() || !n.desc.isNullOrEmpty() ||
                n.className?.let { it.endsWith("ImageView") || it.endsWith("TextView") } == true) &&
            ignore.none { it.matches(n, w, h) }

    /**
     * Tab content for the cache key: its description, and every descendant's id, text and visibility. Selection is not
     * part of it: BarTab.selected is a separate key field (see BarController), so a predicted selection can change only that.
     */
    private fun signature(tab: NodeSnap): String = buildString {
        append(tab.desc.orEmpty())
        tab.flatten().drop(1).forEach { d ->
            append('|').append(d.viewId.orEmpty()).append(',').append(d.text.orEmpty()).append(',').append(d.visible)
        }
    }

    private val NodeSnap.box get() = Box(left, top, right, bottom)
}
