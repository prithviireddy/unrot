package app.unrot

import app.unrot.data.Settings
import app.unrot.policy.Decision
import app.unrot.policy.Policy
import app.unrot.rules.Detection
import app.unrot.rules.NodeSnap
import app.unrot.rules.RuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.io.File

class RulesAndPolicyTest {
    private val w = 1080
    private val h = 2400
    private val ig = "com.instagram.android"

    private val rules: RuleSet by lazy {
        val f = listOf("src/main/assets/rules.json", "app/src/main/assets/rules.json").map(::File).first { it.exists() }
        RuleSet.parse(f.readText())
    }

    private fun node(
        id: String? = null, desc: String? = null, text: String? = null, cls: String = "android.widget.FrameLayout",
        l: Int = 0, t: Int = 0, r: Int = 100, b: Int = 100, selected: Boolean = false, kids: List<NodeSnap> = emptyList(),
    ) = NodeSnap(id, cls, desc, text, l, t, r, b, selected, false, false, true, 0, kids)

    private fun screen(vararg kids: NodeSnap) = node(l = 0, t = 0, r = w, b = h, kids = kids.toList())
    private fun detect(root: NodeSnap) = Detection.run(rules.forApp(ig, 1), root, w, h)

    private val navReels = node(id = "$ig:id/clips_tab", desc = "Reels", l = 432, t = 2250, r = 648, b = 2400)
    private val home = screen(node(id = "$ig:id/feed_tab", desc = "Home", t = 2250, b = 2400), navReels)
    private val reelViewer = screen(node(id = "$ig:id/clips_viewer_view_pager", l = 0, t = 0, r = w, b = h))
    private val dmThread = screen(node(id = "$ig:id/row_thread_composer_edittext", t = 2200, b = 2300))
    private val s = Settings(onboarded = true)

    @Test fun bundledRulesParse() {
        assertTrue(rules.apps.keys.containsAll(listOf(ig, "com.google.android.youtube")))
    }

    @Test fun reelsTabPatchedWhenBarContainerIsMissing() {
        val d = detect(home)
        assertTrue(d.covers.isEmpty())
        assertEquals(app.unrot.rules.Box(432, 2250, 648, 2400), d.bar?.patchBox)
        assertEquals("noContainer", d.bar?.rejectReason)
        assertTrue(!d.isBlocked)
    }

    @Test fun reelViewerFromFeedIsBlocked() {
        val p = Policy { 0 }
        p.onScreen(detect(home), s)
        assertTrue(p.onScreen(detect(reelViewer), s) is Decision.Block)
    }

    @Test fun dmReelAllowedOnceThenBlockedOnSwipe() {
        var now = 0L
        val p = Policy { now }
        p.onScreen(detect(dmThread), s)
        now = 1_000
        assertEquals(Decision.AllowDm, p.onScreen(detect(reelViewer), s))
        now = 1_500 // pager settle scroll, ignored
        assertEquals(Decision.None, p.onScroll("$ig:id/clips_viewer_view_pager"))
        now = 8_000
        assertEquals(Decision.AllowDm, p.onScreen(detect(reelViewer), s)) // still watching the same reel
        now = 9_000
        assertTrue(p.onScroll("$ig:id/clips_viewer_view_pager") is Decision.Block)
        assertTrue(p.onScreen(detect(reelViewer), s) is Decision.Block)
    }

    @Test fun dmExceptionOffBlocksDmReel() {
        val p = Policy { 0 }
        p.onScreen(detect(dmThread), s)
        assertTrue(p.onScreen(detect(reelViewer), s.copy(dmException = false)) is Decision.Block)
    }

    @Test fun staleDmContextDoesNotGrantPass() {
        var now = 0L
        val p = Policy { now }
        p.onScreen(detect(dmThread), s)
        now = 60_000
        p.onScreen(detect(home), s)
        assertTrue(p.onScreen(detect(reelViewer), s) is Decision.Block)
    }

    @Test fun caughtUpTapsHomeWhenNearTop() {
        val low = screen(node(text = "You're all caught up", t = 2000, b = 2100))
        val high = screen(node(text = "You're all caught up", t = 500, b = 600))
        assertTrue(!detect(low).isBlocked)
        val d = detect(high)
        assertEquals("ig_caught_up", d.blocks.single().id)
        assertEquals("$ig:id/feed_tab", d.blocks.single().tapViewId)
        assertEquals(Decision.None, Policy { 0 }.onScreen(d, s.copy(caughtUpEnabled = false)))
    }

    @Test fun disabledFeatureNotBlocked() {
        assertEquals(Decision.None, Policy { 0 }.onScreen(detect(reelViewer), s.copy(instagramEnabled = false)))
    }

    private val viewerTarget get() = rules.forApp(ig, 1)!!.targets.first { it.id == "ig_reels_viewer" }

    /** The block probe's detection for a reel viewer; [markers] stand for those of the last full read (possibly stale). */
    private fun fastReel(markers: Set<String> = setOf("dm")) = Detection(listOf(viewerTarget), markers, emptyList())

    @Test fun fastPathReadsOnlyExactViewIdTargets() {
        assertEquals(listOf("$ig:id/clips_viewer_view_pager"), viewerTarget.fastViewIds(ig))
        val tab = rules.forApp(ig, 1)!!.targets.first { it.id == "ig_reels_tab" }
        assertEquals(listOf("$ig:id/clips_tab"), tab.fastViewIds(ig))
        // caught-up, DM markers and targets with all/none conds need the full tree
        assertTrue(rules.forApp(ig, 1)!!.targets.first { it.id == "ig_caught_up" }.fastViewIds(ig).isEmpty())
        assertTrue(rules.forApp(ig, 1)!!.targets.first { it.id == "ig_dm_thread" }.fastViewIds(ig).isEmpty())
        assertTrue(rules.forApp("com.google.android.youtube", 1)!!.targets.first { it.id == "yt_shorts_player" }
            .fastViewIds("com.google.android.youtube").contains("com.google.android.youtube:id/reel_recycler"))
    }

    @Test fun fastPathMatchesOnlyWhenTheNodeIsTallEnough() {
        val tall = node(id = "$ig:id/clips_viewer_view_pager", l = 0, t = 0, r = w, b = h)
        val short = node(id = "$ig:id/clips_viewer_view_pager", l = 0, t = 0, r = w, b = 100)
        assertTrue(viewerTarget.matchesFast(listOf(tall), w, h))
        assertTrue(!viewerTarget.matchesFast(listOf(short), w, h))
        assertTrue(!viewerTarget.matchesFast(emptyList(), w, h))
    }

    @Test fun fastBlockAfterDmReadAllowsOneReel() {
        var now = 0L
        val p = Policy { now }
        p.onScreen(detect(dmThread), s)
        now = 1_000
        assertEquals(Decision.AllowDm, p.onScreen(fastReel(), s, fast = true))
        now = 9_000
        assertTrue(p.onScroll("$ig:id/clips_viewer_view_pager") is Decision.Block)
    }

    @Test fun fastBlockKeepsDmStampOfTheLastFullRead() {
        var now = 0L
        val p = Policy { now }
        p.onScreen(detect(dmThread), s) // the DM stamp is from here
        now = 60_000
        // The fast detection's markers are stale: they must not restamp the DM context
        assertTrue(p.onScreen(fastReel(setOf("dm")), s, fast = true) is Decision.Block)
    }

    @Test fun fastBlockWithoutAnyDmReadBlocks() {
        val p = Policy { 1_000L }
        assertTrue(p.onScreen(fastReel(setOf("dm")), s, fast = true) is Decision.Block)
    }

    @Test fun reelsTabAlongsideTheViewerNeverGetsTheDmPass() {
        var now = 0L
        val p = Policy { now }
        p.dmSeen(0L) // the chat was seen just before
        now = 1_000L
        val tab = rules.forApp(ig, 1)!!.targets.first { it.id == "ig_reels_tab" }
        // The probe's hit is the viewer (DM-eligible); the Reels tab is judged by id and joins it, so the pass is not allowed
        assertTrue(p.onScreen(Detection(listOf(viewerTarget, tab), setOf("dm"), emptyList()), s, fast = true) is Decision.Block)
    }

    @Test fun fastPathIgnoresASliverOfThePager() {
        val sliver = node(id = "$ig:id/clips_viewer_view_pager", l = 1000, t = 110, r = w, b = 2124)
        assertTrue(!viewerTarget.matchesFast(listOf(sliver), w, h))
        val full = node(id = "$ig:id/clips_viewer_view_pager", l = 0, t = 110, r = w, b = 2124)
        assertTrue(viewerTarget.matchesFast(listOf(full), w, h))
    }

    /** The Instagram profile dump, with its reel pager moved to a sliver at the right edge (a tab slide on Home). */
    private fun shiftedProfileTree(): NodeSnap {
        val text = listOf("src/test/resources/fixtures/ig_profile_3button.json", "app/src/test/resources/fixtures/ig_profile_3button.json")
            .map(::File).first { it.exists() }.readText()
        val tree = org.json.JSONObject(text).getJSONObject("tree")
        fun JSONObject.shift(): Boolean {
            if (optString("id") == "$ig:id/clips_viewer_view_pager") {
                put("bounds", "[1000,110][1080,2124]")
                remove("visible") // visible on screen, as a peeking sliver is
                return true
            }
            val kids = optJSONArray("children") ?: return false
            return (0 until kids.length()).any { kids.getJSONObject(it).shift() }
        }
        assertTrue(tree.shift())
        return NodeSnap.fromJson(tree)
    }

    @Test fun fullReadOfShiftedPagerSliverDoesNotBlock() {
        val d = Detection.run(rules.forApp(ig, 386215122), shiftedProfileTree(), w, h)
        assertTrue(d.blocks.none { it.id == "ig_reels_viewer" })
    }

    @Test fun probeDmSightingLetsAReelHitAtEightHundredMsPass() {
        var now = 0L
        val p = Policy { now }
        p.dmSeen(0L) // the probe saw the DM thread at t=0
        now = 800L
        assertEquals(Decision.AllowDm, p.onScreen(fastReel(emptySet()), s, fast = true))
        now = 3_000L
        assertTrue(p.onScroll("$ig:id/clips_viewer_view_pager") is Decision.Block)
    }

    @Test fun staleReadDmStampCountsForAFastReelWithin5s() {
        var now = 0L
        val p = Policy { now }
        p.dmSeen(0L) // a read skipped as stale still stamped its DM marker
        now = 4_000L
        assertEquals(Decision.AllowDm, p.onScreen(fastReel(emptySet()), s, fast = true))
    }

    @Test fun dmSeenNeverMovesBack() {
        var now = 10_000L
        val p = Policy { now }
        p.dmSeen(10_000L)
        p.dmSeen(2_000L)
        now = 14_000L
        assertEquals(Decision.AllowDm, p.onScreen(fastReel(emptySet()), s, fast = true))
    }
}
