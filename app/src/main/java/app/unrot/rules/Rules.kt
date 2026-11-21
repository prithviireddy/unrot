package app.unrot.rules

import org.json.JSONArray
import org.json.JSONObject

/**
 * Detection rules, loaded from JSON (bundled in assets/rules.json, optionally replaced by a remote copy).
 *
 * Schema (v1):
 * {
 *   "schema": 1, "version": 3,
 *   "apps": {
 *     "com.instagram.android": [            // rulesets, first one whose version range fits wins
 *       { "minVersionCode": 0, "maxVersionCode": null,
 *         "targets": [ { "id": "...", "kind": "block"|"marker"|"cover",
 *                        "feature": "reels"|"shorts"|"caughtup"|"dm",
 *                        "action": "back"|"tap", "tapViewId": "pkg:id/feed_tab",   // block only
 *                        "dmEligible": true, "swipeViewIds": ["..."],             // block only
 *                        "coverLight": "#FFFFFF", "coverDark": "#000000",          // cover only
 *                        "all": [Cond], "any": [Cond], "none": [Cond] } ] } ] } }
 *
 * kind=block  : the screen is blocked (Back, or tap [tapViewId] e.g. the Home tab to jump to the top).
 * kind=marker : sets a context flag (e.g. "dm" = user is in a DM thread) used by the policy.
 * kind=cover  : every node matching an `any` cond gets an opaque patch drawn over it that swallows taps.
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
        const val SUPPORTED_SCHEMA = 1

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

enum class TargetKind { BLOCK, MARKER, COVER }

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

    companion object {
        fun parse(o: JSONObject) = Target(
            id = o.getString("id"),
            kind = when (o.optString("kind", "block")) {
                "marker" -> TargetKind.MARKER
                "cover" -> TargetKind.COVER
                else -> TargetKind.BLOCK
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

internal fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }

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
data class Detection(val blocks: List<Target>, val markers: Set<String>, val covers: List<Cover>) {
    val isBlocked get() = blocks.isNotEmpty()

    companion object {
        val NONE = Detection(emptyList(), emptySet(), emptyList())

        fun run(rules: AppRuleSet?, root: NodeSnap, screenW: Int, screenH: Int): Detection {
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
            }
            return Detection(blocks, markers, covers.distinctBy { listOf(it.left, it.top, it.right, it.bottom) })
        }
    }
}
