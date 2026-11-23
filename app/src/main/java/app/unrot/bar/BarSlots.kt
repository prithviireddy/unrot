package app.unrot.bar

import app.unrot.rules.BarLayout
import app.unrot.rules.Box

/** One slot of the redrawn bar: the real tab it stands for, where its crop is drawn, and its description (for TalkBack). */
data class Slot(val identity: String, val dest: Box, val desc: String? = null)

/**
 * Index of the slot under x, for [slotCount] slots tiling [tabsBox] as [layoutSlots] does (slot i starts at
 * `l + i*W/n`). x and tabsBox must share a coordinate system. Null when x is outside tabsBox or there are no slots.
 */
fun slotIndexAt(tabsBox: Box, slotCount: Int, x: Int): Int? {
    if (slotCount <= 0 || x < tabsBox.l || x >= tabsBox.r) return null
    val w = tabsBox.width
    var i = ((x - tabsBox.l).toLong() * slotCount / w).toInt().coerceIn(0, slotCount - 1)
    while (i > 0 && tabsBox.l + i * w / slotCount > x) i--
    while (i < slotCount - 1 && tabsBox.l + (i + 1) * w / slotCount <= x) i++
    return i
}

/**
 * Slots for the kept (not hidden) tabs, left to right. Slot i spans `tabsBox.l + i*W/n` to `tabsBox.l + (i+1)*W/n`
 * (integer maths), so the slots tile with no gaps. Each tab is centred in its slot, keeping its own width and vertical
 * extent. Null when there are no kept tabs or a tab is wider than its slot.
 */
fun layoutSlots(layout: BarLayout): List<Slot>? {
    val kept = layout.tabs.filter { !it.hidden }.sortedBy { it.box.l }
    val n = kept.size
    if (n == 0) return null
    val tb = layout.tabsBox
    val w = tb.width
    val out = ArrayList<Slot>(n)
    for ((i, tab) in kept.withIndex()) {
        val start = tb.l + i * w / n
        val end = tb.l + (i + 1) * w / n
        val slotW = end - start
        val tw = tab.box.width
        if (tw > slotW) return null
        val l = start + (slotW - tw) / 2
        out += Slot(tab.identity, Box(l, tab.box.t, l + tw, tab.box.b), tab.desc)
    }
    return out
}

/**
 * Background sample points at the vertical centre of tabsBox: 4 px in from the left edge, 5 px in from the right edge,
 * and at every boundary between adjacent tabs. Points inside the patch box are skipped.
 */
fun samplePoints(layout: BarLayout): List<Pair<Int, Int>> {
    val tb = layout.tabsBox
    val y = tb.t + tb.height / 2
    val tabs = layout.tabs.sortedBy { it.box.l }
    val xs = buildList {
        add(tb.l + 4)
        tabs.dropLast(1).forEach { add(it.box.r) }
        add(tb.r - 5)
    }
    val pb = layout.patchBox
    return xs.distinct().sorted()
        .filter { x -> pb == null || !(x >= pb.l && x < pb.r && y >= pb.t && y < pb.b) }
        .map { it to y }
}

/** The same x values as [samplePoints], at row [row] (0..2) below the top of tabsBox. */
fun topRowPoints(layout: BarLayout, row: Int): List<Pair<Int, Int>> {
    val y = layout.tabsBox.t + row
    return samplePoints(layout).map { it.first to y }
}
