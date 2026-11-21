package app.unrot

import app.unrot.data.Settings
import app.unrot.policy.Decision
import app.unrot.policy.Policy
import app.unrot.rules.Detection
import app.unrot.rules.NodeSnap
import app.unrot.rules.RuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun coversReelsTabIconOnly() {
        val d = detect(home)
        assertEquals(listOf("ig_reels_tab_cover"), d.covers.map { it.targetId })
        assertEquals(2250, d.covers.single().top)
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
}
