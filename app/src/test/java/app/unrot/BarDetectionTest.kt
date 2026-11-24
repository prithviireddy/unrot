package app.unrot

import app.unrot.rules.Bar
import app.unrot.rules.BarMode
import app.unrot.rules.Box
import app.unrot.rules.Cond
import app.unrot.rules.Detection
import app.unrot.rules.NodeSnap
import app.unrot.rules.RuleSet
import app.unrot.rules.Target
import app.unrot.rules.TargetKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Bar detection on the real Inspector dumps (fixtures/) plus synthetic trees for the sanity and occlusion rules. */
class BarDetectionTest {
    private val w = 1080
    private val h = 2400
    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"

    private val rules: RuleSet by lazy {
        val f = listOf("src/main/assets/rules.json", "app/src/main/assets/rules.json").map(::File).first { it.exists() }
        RuleSet.parse(f.readText())
    }

    private class Fixture(val root: NodeSnap, val json: JSONObject) {
        /** The navigation-bar inset the service would compute for this dump (bottom edge of the screen). */
        val nav: Box get() {
            val b = json.getJSONObject("extra").getJSONObject("navInsets").getInt("b")
            return Box(0, h - b, w, h)
        }
        companion object {
            const val w = 1080
            const val h = 2400
        }
    }

    private fun fixture(name: String): Fixture {
        val text = javaClass.classLoader!!.getResourceAsStream("fixtures/$name.json")!!.bufferedReader().use { it.readText() }
        val j = JSONObject(text)
        return Fixture(NodeSnap.fromJson(j.getJSONObject("tree")), j)
    }

    private fun bar(pkg: String, root: NodeSnap, nav: Box?, truncated: Boolean = false) =
        Detection.run(rules.forApp(pkg, 1), root, w, h, nav, truncated).bar

    // ---- synthetic tree helpers ----

    private fun n(
        id: String? = null, desc: String? = null, text: String? = null, cls: String = "android.widget.FrameLayout",
        l: Int = 0, t: Int = 0, r: Int = 100, b: Int = 100, sel: Boolean = false, clk: Boolean = false,
        vis: Boolean = true, order: Int = 0, kids: List<NodeSnap> = emptyList(),
    ) = NodeSnap(id, cls, desc, text, l, t, r, b, sel, clk, false, vis, 0, kids, order)

    private fun igTab(name: String, desc: String, left: Int, width: Int = 216) =
        n(id = "$ig:id/$name", desc = desc, clk = true, l = left, t = 2208, r = left + width, b = 2352)

    private fun igTabs() = listOf(
        igTab("feed_tab", "Home", 0),
        igTab("clips_tab", "Reels", 216),
        igTab("direct_tab", "Message", 432),
        igTab("search_tab", "Search and explore", 648),
        igTab("profile_tab", "Profile", 864),
    )

    private fun igBarNode(tabs: List<NodeSnap> = igTabs()) =
        n(id = "$ig:id/tab_bar", cls = "android.widget.LinearLayout", l = 0, t = 2208, r = w, b = 2352, kids = tabs)

    private fun screen(vararg kids: NodeSnap) = n(l = 0, t = 0, r = w, b = h, kids = kids.toList())

    private val igTarget: Target by lazy { rules.forApp(ig, 1)!!.targets.single { it.id == "ig_bar" } }

    private fun detect(root: NodeSnap, target: Target = igTarget, nav: Box? = null, truncated: Boolean = false) =
        Bar.detect(target, root, w, h, nav, truncated)

    // ---- real dumps ----

    @Test fun instagramBarRebuildsOnMainTabs() {
        for (name in listOf("ig_home", "ig_dms", "ig_search", "ig_profile", "ig_profile_3button")) {
            val f = fixture(name)
            val b = bar(ig, f.root, f.nav)
            assertNotNull(name, b)
            assertEquals(name, BarMode.REBUILD, b!!.mode)
            assertNull(name, b.rejectReason)
            assertEquals(name, 5, b.tabs.size)
            val hidden = b.tabs.single { it.hidden }
            assertEquals(name, 1, hidden.index)
            assertTrue(name, hidden.viewId!!.endsWith("/clips_tab"))
            assertEquals(name, hidden.box, b.patchBox)
            assertEquals(name, b.tabs.map { it.viewId }, b.tabs.map { it.identity })
        }
    }

    @Test fun touchingNavigationEdgeIsNotAnOverlap() {
        val f = fixture("ig_home")
        val b = bar(ig, f.root, Box(0, 2352, 1080, 2400))!!
        assertEquals(BarMode.REBUILD, b.mode)
        assertNull(b.rejectReason)
    }

    @Test fun overlappingNavigationBarRejectsRebuild() {
        val f = fixture("ig_home")
        val b = bar(ig, f.root, Box(0, 2300, 1080, 2400))!!
        assertEquals(BarMode.PATCH, b.mode)
        assertEquals("nav", b.rejectReason)
        // The 3-button rectangle (top 2268) overlaps the gesture-nav fixture's bar, so it must not be used for it.
        assertEquals("nav", bar(ig, f.root, Box(0, 2268, 1080, 2400))!!.rejectReason)
    }

    @Test fun directTabSignatureIncludesBadgeCount() {
        val f = fixture("ig_dms")
        val direct = findJson(f.json.getJSONObject("tree")) { it.optString("id").endsWith("/direct_tab") }!!
        val badge = findJson(direct) { it.optString("id").endsWith("/notification") }!!
        val original = badge.getString("text")
        val before = bar(ig, f.root, f.nav)!!.tabs.single { it.viewId?.endsWith("/direct_tab") == true }
        assertTrue(before.signature.contains(original))

        badge.put("text", "99")
        val changed = bar(ig, NodeSnap.fromJson(f.json.getJSONObject("tree")), f.nav)!!
            .tabs.single { it.viewId?.endsWith("/direct_tab") == true }
        assertNotEquals(before.signature, changed.signature)
    }

    @Test fun sheetsOverTheBarArePatchOnly() {
        for (name in listOf("ig_comments_sheet", "ig_share_sheet")) {
            val f = fixture(name)
            val b = bar(ig, f.root, f.nav)
            assertNotNull(name, b)
            assertNotEquals(name, BarMode.REBUILD, b!!.mode)
            assertEquals(name, "occluded", b.rejectReason)
            // Both sheets draw over the Reels tab too, so no patch may be drawn there.
            assertNull(name, b.patchBox)
        }
    }

    @Test fun youtubeBarRebuildsWithDescriptionOnlyTabs() {
        for (name in listOf("yt_home", "yt_you", "yt_miniplayer")) {
            val f = fixture(name)
            val b = bar(yt, f.root, f.nav)
            assertNotNull(name, b)
            assertEquals(name, BarMode.REBUILD, b!!.mode)
            assertEquals(name, 4, b.tabs.size)
            assertEquals(name, "Shorts", b.tabs.single { it.hidden }.desc)
            assertFalse(name, b.tabs.single { it.desc == "Create" }.selects)
            assertEquals(name, listOf("Home", "Shorts", "Create", "You"), b.tabs.map { it.identity })
        }
    }

    @Test fun youtubeWatchPageDoesNotRebuild() {
        val f = fixture("yt_watch2")
        val b = bar(yt, f.root, f.nav)
        assertTrue(b == null || b.mode != BarMode.REBUILD)
    }

    /** The Shorts camera has its own bottom controls and no bar container: no bar, so nothing is drawn over it. */
    @Test fun youtubeShortsCameraHasNoBar() {
        val f = fixture("yt_shorts_camera")
        assertNull(bar(yt, f.root, f.nav))
    }

    /** B1: the profile's own "Reels" grid tab is in the lower quarter, but it is not a bar: with the tab bar gone, nothing is drawn. */
    @Test fun profileWithoutItsTabBarGetsNoBar() {
        val f = fixture("ig_profile")
        assertTrue(removeChildById(f.json.getJSONObject("tree"), "$ig:id/tab_bar"))
        val root = NodeSnap.fromJson(f.json.getJSONObject("tree"))
        assertNull(bar(ig, root, f.nav))
    }

    /** B1: a description-only Shorts node in the lower quarter (no bottom bar container) is not a bar: no patch. */
    @Test fun descOnlyShortsNodeInTheLowerQuarterGetsNoPatch() {
        val shorts = n(desc = "Shorts", clk = true, l = 270, t = 2208, r = 540, b = 2352)
        assertNull(bar(yt, screen(shorts), null))
    }

    @Test fun barProbeViewIdsAreTheTabBars() {
        assertEquals("$ig:id/tab_bar", rules.forApp(ig, 1)!!.targets.single { it.id == "ig_bar" }.probeViewId(ig))
        assertEquals("$yt:id/bottom_bar_container", rules.forApp(yt, 1)!!.targets.single { it.id == "yt_bar" }.probeViewId(yt))
    }

    /** No block rule matches any bundled dump: the reel and Shorts blocks fire on their own screens, never on these. */
    @Test fun noBlockRuleMatchesAnyFixture() {
        val names = listOf(
            "ig_comments_sheet" to ig, "ig_dms" to ig, "ig_home" to ig, "ig_profile" to ig, "ig_profile_3button" to ig,
            "ig_search" to ig, "ig_share_sheet" to ig, "yt_home" to yt, "yt_miniplayer" to yt, "yt_shorts_camera" to yt,
            "yt_watch2" to yt, "yt_you" to yt,
        )
        for ((name, pkg) in names) {
            val f = fixture(name)
            val d = Detection.run(rules.forApp(pkg, 1), f.root, w, h, f.nav, false)
            assertTrue("$name blocked by ${d.blocks.map { it.id }}", d.blocks.isEmpty())
        }
    }

    // ---- synthetic ----

    @Test fun truncatedSnapshotIsPatchOnly() {
        val b = detect(screen(igBarNode()), truncated = true)!!
        assertEquals(BarMode.PATCH, b.mode)
        assertEquals("truncated", b.rejectReason)
    }

    @Test fun ruleModePatchIsPatchOnly() {
        val b = detect(screen(igBarNode()), target = igTarget.copy(barMode = BarMode.PATCH))!!
        assertEquals(BarMode.PATCH, b.mode)
        assertNull(b.rejectReason)
    }

    @Test fun missingOrUnknownRuleModeIsPatch() {
        val base = """{"id":"x","kind":"bar","feature":"reels","bar":[{"viewId":"tab_bar"}],"hide":[{"viewId":"clips_tab"}]}"""
        for (json in listOf(base, base.replace("\"kind\"", "\"mode\":\"sideways\",\"kind\""))) {
            val t = Target.parse(JSONObject(json))
            assertEquals(TargetKind.BAR, t.kind)
            val b = detect(screen(igBarNode()), target = t)!!
            assertEquals(BarMode.PATCH, b.mode)
            assertNull(b.rejectReason)
        }
        assertEquals(BarMode.REBUILD, Target.parse(JSONObject(base.replace("\"kind\"", "\"mode\":\"rebuild\",\"kind\""))).barMode)
    }

    @Test fun hideMatchingNoTabGivesPatchWithoutPatchBox() {
        val b = detect(screen(igBarNode()), target = igTarget.copy(hide = listOf(Cond(viewId = "nope"))))!!
        assertEquals(BarMode.PATCH, b.mode)
        assertEquals("hidden=0", b.rejectReason)
        assertNull(b.patchBox)
    }

    @Test fun hideMatchingTwoTabsGivesPatch() {
        val target = igTarget.copy(hide = listOf(Cond(viewIdRegex = Regex("clips_tab|direct_tab"))))
        val b = detect(screen(igBarNode()), target = target)!!
        assertEquals(BarMode.PATCH, b.mode)
        assertEquals("hidden=2", b.rejectReason)
        assertNull(b.patchBox)
    }

    @Test fun threeTabsBelowMinimumGivesPatch() {
        val tabs = listOf(igTab("feed_tab", "Home", 0, 360), igTab("clips_tab", "Reels", 360, 360), igTab("profile_tab", "Profile", 720, 360))
        val b = detect(screen(igBarNode(tabs)))!!
        assertEquals(BarMode.PATCH, b.mode)
        assertEquals("tabCount=3", b.rejectReason)
    }

    @Test fun unequalTabWidthsGivePatch() {
        val tabs = listOf(
            igTab("feed_tab", "Home", 0, 200),
            igTab("clips_tab", "Reels", 200, 200),
            igTab("direct_tab", "Message", 400, 200),
            igTab("profile_tab", "Profile", 600, 480),
        )
        val b = detect(screen(igBarNode(tabs)))!!
        assertEquals(BarMode.PATCH, b.mode)
        assertEquals("tabWidths", b.rejectReason)
    }

    @Test fun emptyFullScreenViewDrawnAfterBarIsIgnored() {
        val content = n(l = 0, t = 0, r = w, b = 2208, order = 1, kids = listOf(igBarNode()))
        val overlay = n(l = 0, t = 0, r = w, b = h, order = 9)
        val b = detect(screen(content, overlay))!!
        assertEquals(BarMode.REBUILD, b.mode)
        assertNull(b.rejectReason)
    }

    @Test fun clickableViewDrawnAfterBarOccludesIt() {
        val content = n(l = 0, t = 0, r = w, b = 2208, order = 1, kids = listOf(igBarNode()))
        val sheet = n(desc = "Sheet", clk = true, l = 0, t = 0, r = w, b = h, order = 9)
        val b = detect(screen(content, sheet))!!
        assertEquals(BarMode.PATCH, b.mode)
        assertEquals("occluded", b.rejectReason)
    }

    @Test fun occluderOnHiddenTabRemovesPatch() {
        val content = n(l = 0, t = 0, r = w, b = 2208, order = 1, kids = listOf(igBarNode()))
        val sheet = n(desc = "Sheet", clk = true, l = 150, t = 2200, r = 500, b = 2360, order = 9)
        val b = detect(screen(content, sheet))!!
        assertEquals(BarMode.PATCH, b.mode)
        assertNull(b.patchBox)
    }

    @Test fun occluderIgnoreMatchKeepsRebuild() {
        val content = n(l = 0, t = 0, r = w, b = 2208, order = 1, kids = listOf(igBarNode()))
        val sheet = n(desc = "Sheet", clk = true, l = 0, t = 0, r = w, b = h, order = 9)
        val b = detect(screen(content, sheet), target = igTarget.copy(occluderIgnore = listOf(Cond(desc = "Sheet"))))!!
        assertEquals(BarMode.REBUILD, b.mode)
    }

    @Test fun missingContainerPatchesTheHideNode() {
        val b = detect(screen(igTab("clips_tab", "Reels", 216)))!!
        assertEquals(BarMode.PATCH, b.mode)
        assertEquals("noContainer", b.rejectReason)
        assertEquals(Box(216, 2208, 432, 2352), b.patchBox)
        assertTrue(b.tabs.isEmpty())
    }

    @Test fun boxIntersectionIsStrict() {
        val a = Box(0, 0, 10, 10)
        assertFalse(a.intersects(Box(10, 0, 20, 10)))
        assertTrue(a.intersects(Box(9, 9, 20, 20)))
        assertTrue(a.contains(Box(0, 0, 10, 10)))
        assertEquals(Box(0, 0, 20, 10), a.union(Box(10, 0, 20, 10)))
    }

    // ---- schema ----

    @Test fun schemaOneStillParses() {
        val r = RuleSet.parse("""{"schema":1,"version":1,"apps":{"$ig":[{"minVersionCode":0,"maxVersionCode":null,"targets":[{"id":"a","kind":"block","feature":"reels","any":[{"viewId":"clips_tab"}]}]}]}}""")
        assertEquals(1, r.schema)
        assertEquals(TargetKind.BLOCK, r.forApp(ig, 1)!!.targets.single().kind)
    }

    @Test(expected = IllegalArgumentException::class)
    fun schemaThreeIsRejected() {
        RuleSet.parse("""{"schema":3,"version":1,"apps":{}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownTargetKindIsRejected() {
        Target.parse(JSONObject("""{"id":"x","kind":"sparkle"}"""))
    }

    /** Removes the first node with view id [id] (with its subtree) from [o]'s descendants. True when one was removed. */
    private fun removeChildById(o: JSONObject, id: String): Boolean {
        val kids = o.optJSONArray("children") ?: return false
        for (i in 0 until kids.length()) {
            if (kids.getJSONObject(i).optString("id") == id) {
                kids.remove(i)
                return true
            }
        }
        return (0 until kids.length()).any { removeChildById(kids.getJSONObject(it), id) }
    }

    private fun findJson(o: JSONObject, pred: (JSONObject) -> Boolean): JSONObject? {
        if (pred(o)) return o
        val kids = o.optJSONArray("children") ?: return null
        for (i in 0 until kids.length()) findJson(kids.getJSONObject(i), pred)?.let { return it }
        return null
    }
}
