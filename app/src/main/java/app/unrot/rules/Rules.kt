package app.unrot.rules

import org.json.JSONArray
import org.json.JSONObject

/**
 * Detection rules, loaded from JSON (bundled in assets/rules.json, optionally replaced by a remote copy).
 *
 * Schema (v2; v1 files still parse):
 * {
 *   "schema": 2, "version": 2,
 *   "apps": {
 *     "com.instagram.android": [            // rulesets, first one whose version range fits wins
 *       { "minVersionCode": 0, "maxVersionCode": null,
 *         "targets": [ { "id": "...", "kind": "block"|"marker"|"cover"|"bar",
 *                        "feature": "reels"|"shorts"|"caughtup"|"dm",
 *                        "action": "back"|"tap", "tapViewId": "pkg:id/feed_tab",   // block only
 *                        "dmEligible": true, "swipeViewIds": ["..."],             // block only
 *                        "coverLight": "#FFFFFF", "coverDark": "#000000",          // cover and bar
 *                        "all": [Cond], "any": [Cond], "none": [Cond],
 *                        "mode": "rebuild"|"patch",                                // bar only
 *                        "bar": [Cond], "tabsContainer": [Cond], "hide": [Cond],   // bar only
 *                        "noSelect": [Cond], "occluderIgnore": [Cond],             // bar only
 *                        "minTabs": 4, "maxTabs": 6 } ] } ] } }                    // bar only
 *
 * kind=block  : the screen is blocked (Back, or tap [tapViewId] e.g. the Home tab to jump to the top).
 * kind=marker : sets a context flag (e.g. "dm" = user is in a DM thread) used by the policy.
 * kind=cover  : every node matching an `any` cond gets an opaque patch drawn over it that swallows taps.
 * kind=bar    : the bottom tab bar. `bar` finds the container, `hide` picks the tab to remove, and
 *               `mode` says whether the bar may be rebuilt (rebuild) or only patched (patch, the default).
 *               See rules/Bar.kt. Any other kind is an error.
 *
 * A Cond matches when at least one node in the tree satisfies every field it sets:
 *   viewId (exact, or suffix match if it has no ':'), viewIdRegex, desc (case-insensitive equals),
 *   descContains, descRegex, text, textContains, className (suffix), selected,
 *   minWidthFrac / minHeightFrac (node size relative to the screen), visible (default true).
 */
data class RuleSet(val schema: Int, val version: Int, val apps: Map<String, List<AppRuleSet>>) {
    fun forApp(pkg: String, versionCode: Long): AppRuleSet? =
        apps[pkg]?.firstOrNull { versionCode >= it.minVersionCode && (it.maxVersionCode == null || versionCode <= it.maxVersionCode) }

    companion object {
        const val SUPPORTED_SCHEMA = 2

        fun parse(json: String): RuleSet {
            val root = JSONObject(json)
            val schema = root.optInt("schema", 1)
            require(schema <= SUPPORTED_SCHEMA) { "Unsupported rules schema $schema" }
            val appsObj = root.getJSONObject("apps")
            val apps = buildMap {
                for (pkg in appsObj.keys()) {
                    val arr = appsObj.getJSONArray(pkg)
                    put(pkg, (0 until arr.length()).map { AppRuleSet.parse(arr.getJSONObject(it)) })
                }
            }
            return RuleSet(schema, root.optInt("version", 0), apps)
        }
    }
}

data class AppRuleSet(val minVersionCode: Long, val maxVersionCode: Long?, val targets: List<Target>) {
    companion object {
        fun parse(o: JSONObject) = AppRuleSet(
            minVersionCode = o.optLong("minVersionCode", 0),
            maxVersionCode = if (o.isNull("maxVersionCode")) null else o.getLong("maxVersionCode"),
            targets = o.getJSONArray("targets").let { a -> (0 until a.length()).map { Target.parse(a.getJSONObject(it)) } },
        )
    }
}

enum class TargetKind { BLOCK, MARKER, COVER, BAR }

enum class BlockAction { BACK, TAP }

data class Target(
    val id: String,
    val kind: TargetKind,
    val feature: String,
    val action: BlockAction,
    /** Full view id of the node to click when action = TAP. */
    val tapViewId: String?,
    val coverLight: Int,
    val coverDark: Int,
    /** If true, this screen may be allowed once when opened from a DM (the DM exception). */
    val dmEligible: Boolean,
    /** Scroll events from these view ids count as "swiped to next". Empty = any scroll event. */
    val swipeViewIds: List<String>,
    val all: List<Cond>,
    val any: List<Cond>,
    val none: List<Cond>,
    /** kind=bar: matches the bar container (any cond). */
    val bar: List<Cond> = emptyList(),
    val barMode: BarMode = BarMode.PATCH,
    /** kind=bar: the tabs container. Empty = descend from the container (see Bar.kt). */
    val tabsContainer: List<Cond> = emptyList(),
    /** kind=bar: the tab to remove (matches the tab itself or a descendant). */
    val hide: List<Cond> = emptyList(),
    /** kind=bar: tabs matching these never become selected. */
    val noSelect: List<Cond> = emptyList(),
    /** kind=bar: nodes matching these never count as occluders. */
    val occluderIgnore: List<Cond> = emptyList(),
    /** kind=bar: tab count including the hidden tab. */
    val minTabs: Int = 4,
    val maxTabs: Int = 6,
) {
    fun matches(nodes: List<NodeSnap>, screenW: Int, screenH: Int): Boolean {
        if (all.isEmpty() && any.isEmpty()) return false
        if (!all.all { it.matchesAny(nodes, screenW, screenH) }) return false
        if (any.isNotEmpty() && any.none { it.matchesAny(nodes, screenW, screenH) }) return false
        if (none.any { it.matchesAny(nodes, screenW, screenH) }) return false
        return true
    }

    /** For cover targets: the nodes to draw patches over (skipping anything implausibly large). */
    fun coverNodes(nodes: List<NodeSnap>, screenW: Int, screenH: Int): List<NodeSnap> {
        if (all.isNotEmpty() && !all.all { it.matchesAny(nodes, screenW, screenH) }) return emptyList()
        if (none.any { it.matchesAny(nodes, screenW, screenH) }) return emptyList()
        return nodes.filter { n ->
            n.width > 0 && n.height > 0 &&
                n.width <= screenW * 0.5 && n.height <= screenH * 0.2 &&
                any.any { it.matches(n, screenW, screenH) }
        }
    }

    /** The full view ids of this target's `any` conds that name one (a cond with a viewId, for the block probe). */
    fun exactViewIds(pkg: String): List<String> =
        any.mapNotNull { c -> c.viewId?.let { fullViewId(it, pkg) } }.distinct()

    /**
     * kind=bar: the full view id the bar-back probe looks for, from the first `bar` cond that names a view id. Null when none
     * does. Not [exactViewIds]: the probe reads the bar's container, not the block's ids.
     */
    fun probeViewId(pkg: String): String? = bar.firstNotNullOfOrNull { it.viewId }?.let { fullViewId(it, pkg) }

    /**
     * Fast path (the block probe in BlockerService): the full view ids this target can be decided from, or empty when it
     * cannot. Only reel and Shorts blocks without `all`/`none` qualify, and only through their exact-id `any` conds; the
     * other conds need the full tree, which the full read still evaluates.
     */
    fun fastViewIds(pkg: String): List<String> {
        if (kind != TargetKind.BLOCK || feature !in FAST_FEATURES || all.isNotEmpty() || none.isNotEmpty()) return emptyList()
        return exactViewIds(pkg)
    }

    /**
     * Fast path: true if one of the `any` conds that name a view id matches among [found] (nodes found by those ids, one
     * level each), counting only settled full-width nodes (see [isSettledFullWidth]). This is the same conditions as the full
     * read's, on a subset of the tree, plus the full-width rule: a sliver of a pager that stays loaded beside another tab
     * does not count. The probe also needs two probes in a row to agree (see BlockerService).
     */
    fun matchesFast(found: List<NodeSnap>, screenW: Int, screenH: Int): Boolean {
        val full = found.filter { isSettledFullWidth(it, screenW) }
        return any.any { it.viewId != null && it.matchesAny(full, screenW, screenH) }
    }

    /** True if one of the `any` conds that name a view id matches among [found], with no width rule (markers, see the DM probe). */
    fun matchesById(found: List<NodeSnap>, screenW: Int, screenH: Int): Boolean =
        any.any { it.viewId != null && it.matchesAny(found, screenW, screenH) }

    companion object {
        /** Features whose blocks the fast path may decide (not the caught-up tap, not markers). */
        val FAST_FEATURES = setOf("reels", "shorts")

        fun parse(o: JSONObject) = Target(
            id = o.getString("id"),
            kind = when (val k = o.optString("kind", "block")) {
                "block" -> TargetKind.BLOCK
                "marker" -> TargetKind.MARKER
                "cover" -> TargetKind.COVER
                "bar" -> TargetKind.BAR
                else -> throw IllegalArgumentException("Unknown target kind '$k' in ${o.optString("id")}")
            },
            feature = o.optString("feature", ""),
            action = if (o.optString("action", "back") == "tap") BlockAction.TAP else BlockAction.BACK,
            tapViewId = if (o.has("tapViewId")) o.getString("tapViewId") else null,
            coverLight = parseColor(o.optString("coverLight", "#FFFFFF")),
            coverDark = parseColor(o.optString("coverDark", "#000000")),
            dmEligible = o.optBoolean("dmEligible", false),
            swipeViewIds = o.optJSONArray("swipeViewIds").strings(),
            all = o.optJSONArray("all").conds(),
            any = o.optJSONArray("any").conds(),
            none = o.optJSONArray("none").conds(),
            bar = o.optJSONArray("bar").conds(),
            barMode = if (o.optString("mode") == "rebuild") BarMode.REBUILD else BarMode.PATCH,
            tabsContainer = o.optJSONArray("tabsContainer").conds(),
            hide = o.optJSONArray("hide").conds(),
            noSelect = o.optJSONArray("noSelect").conds(),
            occluderIgnore = o.optJSONArray("occluderIgnore").conds(),
            minTabs = o.optInt("minTabs", 4),
            maxTabs = o.optInt("maxTabs", 6),
        )

        /** #RRGGBB or #AARRGGBB, parsed without android.graphics.Color so it runs in JVM tests. */
        internal fun parseColor(hex: String): Int {
            val h = hex.removePrefix("#")
            val v = h.toLong(16)
            return (if (h.length == 6) v or 0xFF000000 else v).toInt()
        }

        private fun JSONArray?.conds() = if (this == null) emptyList() else (0 until length()).map { Cond.parse(getJSONObject(it)) }
    }
}

/** The full view id of [id] in [pkg]: [id] itself when it already has its package (a ':'), else "pkg:id/id". */
fun fullViewId(id: String, pkg: String): String = if (id.contains(':')) id else "$pkg:id/$id"

internal fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }

/** A settled full-width node: its left edge within 2% of the screen width from the left, its right edge within 2% from the right. */
fun isSettledFullWidth(n: NodeSnap, screenW: Int): Boolean =
    screenW > 0 && n.left <= screenW * 0.02 && n.right >= screenW * 0.98

data class Cond(
    val viewId: String? = null,
    val viewIdRegex: Regex? = null,
    val desc: String? = null,
    val descContains: String? = null,
    val descRegex: Regex? = null,
    val text: String? = null,
    val textContains: String? = null,
    val className: String? = null,
    val selected: Boolean? = null,
    val minWidthFrac: Double? = null,
    val minHeightFrac: Double? = null,
    /** Node's top edge must be above this fraction of the screen height (e.g. 0.4 = upper 40%). */
    val maxTopFrac: Double? = null,
    val visible: Boolean = true,
) {
    fun matchesAny(nodes: List<NodeSnap>, screenW: Int, screenH: Int) = nodes.any { matches(it, screenW, screenH) }

    fun matches(n: NodeSnap, screenW: Int, screenH: Int): Boolean {
        if (visible && !n.visible) return false
        if (viewId != null) {
            val id = n.viewId ?: return false
            if (viewId.contains(':')) { if (id != viewId) return false }
            else if (!id.endsWith("/$viewId")) return false
        }
        if (viewIdRegex != null && (n.viewId == null || !viewIdRegex.containsMatchIn(n.viewId))) return false
        if (desc != null && !desc.equals(n.desc, ignoreCase = true)) return false
        if (descContains != null && n.desc?.contains(descContains, ignoreCase = true) != true) return false
        if (descRegex != null && (n.desc == null || !descRegex.containsMatchIn(n.desc))) return false
        if (text != null && !text.equals(n.text, ignoreCase = true)) return false
        if (textContains != null && n.text?.contains(textContains, ignoreCase = true) != true) return false
        if (className != null && n.className?.endsWith(className) != true) return false
        if (selected != null && n.selected != selected) return false
        if (minWidthFrac != null && screenW > 0 && n.width < screenW * minWidthFrac) return false
        if (minHeightFrac != null && screenH > 0 && n.height < screenH * minHeightFrac) return false
        if (maxTopFrac != null && screenH > 0 && n.top > screenH * maxTopFrac) return false
        return true
    }

    companion object {
        fun parse(o: JSONObject) = Cond(
            viewId = o.optStringOrNull("viewId"),
            viewIdRegex = o.optStringOrNull("viewIdRegex")?.toRegex(),
            desc = o.optStringOrNull("desc"),
            descContains = o.optStringOrNull("descContains"),
            descRegex = o.optStringOrNull("descRegex")?.toRegex(RegexOption.IGNORE_CASE),
            text = o.optStringOrNull("text"),
            textContains = o.optStringOrNull("textContains"),
            className = o.optStringOrNull("className"),
            selected = if (o.has("selected")) o.getBoolean("selected") else null,
            minWidthFrac = if (o.has("minWidthFrac")) o.getDouble("minWidthFrac") else null,
            minHeightFrac = if (o.has("minHeightFrac")) o.getDouble("minHeightFrac") else null,
            maxTopFrac = if (o.has("maxTopFrac")) o.getDouble("maxTopFrac") else null,
            visible = o.optBoolean("visible", true),
        )

        private fun JSONObject.optStringOrNull(k: String) = if (has(k) && !isNull(k)) getString(k) else null
    }
}

/** A patch to draw: screen rect + colours. */
data class Cover(val targetId: String, val feature: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val light: Int, val dark: Int)

/** Result of running one app's rules over one screen. */
data class Detection(val blocks: List<Target>, val markers: Set<String>, val covers: List<Cover>, val bar: BarLayout? = null) {
    val isBlocked get() = blocks.isNotEmpty()

    companion object {
        val NONE = Detection(emptyList(), emptySet(), emptyList())

        /** [navBar] is the navigation-bar inset at the bottom of the screen, if any; [truncated] = the snapshot is incomplete. */
        fun run(rules: AppRuleSet?, root: NodeSnap, screenW: Int, screenH: Int, navBar: Box? = null, truncated: Boolean = false): Detection {
            if (rules == null) return NONE
            val nodes = root.flatten()
            val blocks = ArrayList<Target>()
            val markers = HashSet<String>()
            val covers = ArrayList<Cover>()
            for (t in rules.targets) when (t.kind) {
                TargetKind.BLOCK -> if (t.matches(nodes, screenW, screenH)) blocks += t
                TargetKind.MARKER -> if (t.matches(nodes, screenW, screenH)) markers += t.feature
                TargetKind.COVER -> t.coverNodes(nodes, screenW, screenH).forEach {
                    covers += Cover(t.id, t.feature, it.left, it.top, it.right, it.bottom, t.coverLight, t.coverDark)
                }
                TargetKind.BAR -> Unit // evaluated separately below
            }
            val barTarget = rules.targets.firstOrNull { it.kind == TargetKind.BAR }
            val bar = barTarget?.let { Bar.detect(it, root, screenW, screenH, navBar, truncated) }
            return Detection(blocks, markers, covers.distinctBy { listOf(it.left, it.top, it.right, it.bottom) }, bar)
        }
    }
}
