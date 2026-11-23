package app.unrot

import app.unrot.bar.BarController
import app.unrot.bar.CaptureKind
import app.unrot.bar.CaptureRequest
import app.unrot.bar.CaptureResult
import app.unrot.bar.Env
import app.unrot.bar.Output
import app.unrot.bar.RenderState
import app.unrot.bar.layoutSlots
import app.unrot.bar.readMayDrawBar
import app.unrot.bar.samplePoints
import app.unrot.bar.topRowPoints
import app.unrot.rules.BarLayout
import app.unrot.rules.BarMode
import app.unrot.rules.BarTab
import app.unrot.rules.Box
import app.unrot.rules.Detection
import app.unrot.rules.NodeSnap
import app.unrot.rules.RuleSet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** BarController on the real Instagram and YouTube fixtures, plus synthetic layouts for slots and sample points. */
class BarControllerTest {
    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val w = 1080
    private val h = 2400
    private val bgColour = 0xFF0C1014.toInt()
    private val restored = 0xFF00AA55.toInt()

    private val rules: RuleSet by lazy {
        val f = listOf("src/main/assets/rules.json", "app/src/main/assets/rules.json").map(::File).first { it.exists() }
        RuleSet.parse(f.readText())
    }

    // ---- fixtures ----

    private fun text(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name.json")!!.bufferedReader().use { it.readText() }

    private fun navOf(j: JSONObject): Box {
        val b = j.getJSONObject("extra").getJSONObject("navInsets").getInt("b")
        return Box(0, h - b, w, h)
    }

    private fun detect(pkg: String, j: JSONObject): BarLayout {
        val root = NodeSnap.fromJson(j.getJSONObject("tree"))
        return Detection.run(rules.forApp(pkg, 1), root, w, h, navOf(j), false).bar!!
    }

    private fun layout(pkg: String, name: String): BarLayout = detect(pkg, JSONObject(text(name)))

    /** ig_dms with the Message tab's unread badge text replaced: the tab's signature changes, nothing else does. */
    private fun dmLayout(badge: String): BarLayout {
        val j = JSONObject(text("ig_dms"))
        val direct = findJson(j.getJSONObject("tree")) { it.optString("id").endsWith("/direct_tab") }!!
        findJson(direct) { it.optString("id").endsWith("/notification") }!!.put("text", badge)
        return detect(ig, j)
    }

    private fun findJson(o: JSONObject, pred: (JSONObject) -> Boolean): JSONObject? {
        if (pred(o)) return o
        val kids = o.optJSONArray("children") ?: return null
        for (i in 0 until kids.length()) findJson(kids.getJSONObject(i), pred)?.let { return it }
        return null
    }

    // ---- helpers ----

    private fun env(
        nowMs: Long = 0L, api: Int = 33, night: Boolean = false, featureOn: Boolean = true, imeVisible: Boolean = false,
        windowAbove: Boolean = false, simple: Boolean = false, disclosure: Boolean = true,
        appVersion: Long = 1L, rules: Int = 1,
    ) = Env(
        api, nowMs, night, featureOn, imeVisible, simple, disclosure, appVersion, rules,
        foreignWindowAbove = windowAbove,
    )

    private fun ctl(max: Int = 4 * 1024 * 1024) = BarController<String>(sizeOf = { 1000 }, same = { a, b -> a == b }, maxBytes = max)

    private fun result(req: CaptureRequest, tag: String = "x", bg: List<Int> = List(3) { bgColour }, valid: Boolean = true) =
        CaptureResult(req.gen, req.sig, req.tabs.associate { it.first to "$tag-${it.first}" }, valid, bg, List(3) { List(3) { bgColour } })

    /**
     * Warm-up until Rebuilt, following the controller's own evalAt times and answering each capture with [bg]. On the
     * display path (api 33 default) one capture at base+400 is enough, so Rebuilt is returned at base+450. In WINDOW
     * mode two agreeing captures are needed: at base+400 and base+750, Rebuilt at base+800.
     */
    private fun warmUp(
        c: BarController<String>, lay: BarLayout, pkg: String = ig, base: Long = 0L,
        bg: List<Int> = List(3) { bgColour }, e: (Long) -> Env = { env(nowMs = it) },
    ): Output {
        var t = base
        var o = c.onEvaluate(pkg, lay, e(t))
        repeat(6) {
            if (o.state is RenderState.Rebuilt<*>) return o
            o.evalAtMs?.let { if (it > t) t = it }
            o = c.onEvaluate(pkg, lay, e(t))
            o.capture?.let {
                c.onCaptureResult(result(it, bg = bg), t + 50)
                t += 50
                o = c.onEvaluate(pkg, lay, e(t))
            }
        }
        return o
    }

    /** Window-mode env (API 34, WINDOW captures): two agreeing captures per crop set. */
    private fun wenv(t: Long, night: Boolean = false) = env(nowMs = t, api = 34, night = night)

    /** Copy of [lay] with the tab whose view id ends in [viewSuffix] selected and the others not. */
    private fun select(lay: BarLayout, viewSuffix: String) =
        lay.copy(tabs = lay.tabs.map { it.copy(selected = it.viewId?.endsWith(viewSuffix) == true) })

    private fun RenderState.rb() = this as RenderState.Rebuilt<*>
    private fun RenderState.patchColour() = (this as RenderState.Patch).color

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    // ---- hidden, invalidation ----

    @Test fun hiddenWithoutLayoutOrWhenGateIsClosed() {
        val lay = layout(ig, "ig_home")
        assertEquals(RenderState.Hidden, ctl().onEvaluate(ig, null, env()).state)
        for (e in listOf(env(featureOn = false), env(imeVisible = true))) {
            assertEquals(RenderState.Hidden, ctl().onEvaluate(ig, lay, e).state)
        }
    }

    @Test fun packageChangeDropsInFlightCapture() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        c.onEvaluate(ig, lay, env(0))
        val r1 = c.onEvaluate(ig, lay, env(400)).capture!!
        c.onEvaluate(yt, layout(yt, "yt_home"), env(500))
        assertFalse(c.onCaptureResult(result(r1), 600))
    }

    @Test fun invalidateDropsInFlightCapture() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        c.onEvaluate(ig, lay, env(0))
        val r1 = c.onEvaluate(ig, lay, env(400)).capture!!
        c.invalidate()
        assertFalse(c.onCaptureResult(result(r1), 500))
        assertNull(c.onEvaluate(ig, lay, env(1000)).capture) // re-entry waits for the screen to settle
        val o = c.onEvaluate(ig, lay, env(1400))
        assertNotNull(o.capture)
        assertEquals(r1.gen + 1, o.capture!!.gen)
    }

    @Test fun staleSignatureResultIsIgnored() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        c.onEvaluate(ig, lay, env(0))
        val r1 = c.onEvaluate(ig, lay, env(400)).capture!!
        c.onEvaluate(ig, dmLayout("99"), env(500))
        assertFalse(c.onCaptureResult(result(r1), 600))
    }

    // ---- patch-only rules ----

    private fun assertPatchOnly(e: Env) {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        assertEquals(RenderState.Patch(lay.patchBox!!, lay.light), c.onEvaluate(ig, lay, e).state)
        assertNull(c.onEvaluate(ig, lay, e.copy(nowMs = 1000)).capture)
        assertNull(c.onEvaluate(ig, lay, e.copy(nowMs = 2000)).capture)
    }

    @Test fun ruleSayingPatchNeverCaptures() {
        val c = ctl()
        val p = layout(ig, "ig_home").copy(mode = BarMode.PATCH)
        val o = c.onEvaluate(ig, p, env(0))
        assertEquals(RenderState.Patch(p.patchBox!!, p.light), o.state)
        assertNull(o.capture)
        assertNull(o.evalAtMs)
    }

    @Test fun api29IsPatchWithNoCapture() = assertPatchOnly(env(api = 29))

    @Test fun simplePatchIsPatchWithNoCapture() = assertPatchOnly(env(simple = true))

    @Test fun noDisclosureV2IsPatchWithNoCapture() = assertPatchOnly(env(disclosure = false))

    @Test fun windowAboveBarIsPatchWithNoCapture() = assertPatchOnly(env(windowAbove = true))

    @Test fun tabTooWideForItsSlotIsPatchWithNoCapture() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        val wide = lay.copy(tabs = lay.tabs.map { if (!it.hidden && it.index == 0) it.copy(box = Box(it.box.l, it.box.t, it.box.l + 300, it.box.b)) else it })
        assertEquals(RenderState.Patch(wide.patchBox!!, wide.light), c.onEvaluate(ig, wide, env(0)).state)
        assertNull(c.onEvaluate(ig, wide, env(1000)).capture)
    }

    // ---- warm-up and agreement ----

    @Test fun firstEvaluationPatchesAndCapturesAfterStability() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        val o0 = c.onEvaluate(ig, lay, env(0))
        assertTrue(o0.state is RenderState.Patch)
        assertNull(o0.capture)
        assertEquals(400L, o0.evalAtMs)
        val o1 = c.onEvaluate(ig, lay, env(400))
        assertEquals(CaptureKind.DISPLAY_NO_HIDE, o1.capture!!.kind)
        assertEquals(4, o1.capture!!.tabs.size)
        assertTrue(o1.state is RenderState.Patch)
    }

    @Test fun twoAgreeingResultsRebuildWithFourSlots() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        val s = warmUp(c, lay).state.rb()
        assertEquals(4, s.slots.size)
        assertEquals(4, s.crops.size)
        assertEquals(bgColour, s.color)
    }

    @Test fun displayPathRebuildsAfterOneResult() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        c.onEvaluate(ig, lay, env(0))
        val r1 = c.onEvaluate(ig, lay, env(400)).capture!!
        assertTrue(c.onCaptureResult(result(r1), 450))
        assertTrue(c.onEvaluate(ig, lay, env(450)).state is RenderState.Rebuilt<*>)
    }

    @Test fun windowModeOneResultStaysPatch() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        c.onEvaluate(ig, lay, wenv(0))
        val r1 = c.onEvaluate(ig, lay, wenv(400)).capture!!
        assertTrue(c.onCaptureResult(result(r1), 450))
        assertTrue(c.onEvaluate(ig, lay, wenv(450)).state is RenderState.Patch)
    }

    @Test fun disagreementBacksOffThenCapsAtSixAttempts() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        c.onEvaluate(ig, lay, wenv(0))
        val tags = listOf("a", "b", "c", "d", "e", "f")
        val starts = mutableListOf<Long>()
        var t = 400L
        var o = c.onEvaluate(ig, lay, wenv(t))
        for (i in 0 until 6) {
            val req = o.capture!!
            starts += t
            c.onCaptureResult(result(req, tags[i]), t + 1)
            val after = c.onEvaluate(ig, lay, wenv(t + 1))
            if (i < 5) {
                t = after.evalAtMs!!
                o = c.onEvaluate(ig, lay, wenv(t))
            } else {
                assertNull(after.evalAtMs)
            }
        }
        // Gaps: 350 after the first (agreement not yet), then 350, 2000, 10000, 30000 after each disagreement.
        assertEquals(listOf(400L, 750L, 1101L, 3102L, 13103L, 43104L), starts)
        assertNull(c.onEvaluate(ig, lay, wenv(100_000)).capture)
    }

    @Test fun displayDisagreementCannotHappenBecauseOneShotIsCommitted() {
        // Display captures commit from one result: differing shots never back off.
        val c = ctl()
        val lay = layout(ig, "ig_home")
        c.onEvaluate(ig, lay, env(0))
        val r1 = c.onEvaluate(ig, lay, env(400)).capture!!
        c.onCaptureResult(result(r1, "a"), 450)
        assertTrue(c.onEvaluate(ig, lay, env(450)).state is RenderState.Rebuilt<*>)
        assertNull(c.onEvaluate(ig, lay, env(451)).capture)
    }

    // ---- signature change and capture kinds ----

    @Test fun badgeChangeOnApi33UsesHideCaptureAndPatchWhileInFlight() {
        val c = ctl()
        val a = dmLayout("4")
        val b = dmLayout("99")
        warmUp(c, a)
        val o1 = c.onEvaluate(ig, b, env(1000))
        assertTrue(o1.state is RenderState.Rebuilt<*>)
        assertEquals(1400L, o1.evalAtMs)
        val o2 = c.onEvaluate(ig, b, env(1400))
        assertEquals(CaptureKind.DISPLAY_WITH_HIDE, o2.capture!!.kind)
        assertTrue(o2.state is RenderState.Patch)
    }

    @Test fun badgeChangeOnApi34WindowCaptureKeepsRebuilt() {
        val e = { t: Long -> env(nowMs = t, api = 34) }
        val c = ctl()
        val a = dmLayout("4")
        val b = dmLayout("99")
        warmUp(c, a, e = e)
        val o1 = c.onEvaluate(ig, b, e(1000))
        assertTrue(o1.state is RenderState.Rebuilt<*>)
        assertEquals(1400L, o1.evalAtMs)
        val o2 = c.onEvaluate(ig, b, e(1400))
        assertEquals(CaptureKind.WINDOW, o2.capture!!.kind)
        assertTrue(o2.state is RenderState.Rebuilt<*>)
    }

    // ---- no timed refresh: crops are replaced only when their key changes ----

    @Test fun api33CropsAreStillUsedAfterThirtyMinutes() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay) // crops and colour committed at 450
        val o = c.onEvaluate(ig, lay, env(1_800_000))
        assertTrue(o.state is RenderState.Rebuilt<*>)
        assertTrue(o.state.rb().crops.all { (it as String).startsWith("x-") }) // the crops from warm-up, not re-captured
        assertNull(o.capture) // the colour is stale, but a re-sample on the display path needs a hide while Rebuilt: not done
    }

    @Test fun windowModeRequestsNoRefreshCaptureAfterFiveMinutes() {
        val e = { t: Long -> env(nowMs = t, api = 34) }
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay, e = e)
        val early = c.onEvaluate(ig, lay, e(800))
        assertTrue(early.state is RenderState.Rebuilt<*>)
        assertNull(early.capture)
        assertNull(early.evalAtMs) // no 60 s refresh timer

        val later = c.onEvaluate(ig, lay, e(300_000))
        assertTrue(later.state is RenderState.Rebuilt<*>)
        assertNull(later.capture)
        assertNull(later.evalAtMs)
    }

    // ---- saved pictures: preload, commit and drop callbacks ----

    @Test fun preloadedCropsRebuildTheBarOnTheFirstEvaluationWithoutAnyCapture() {
        val first = ctl()
        val saved = mutableListOf<Pair<String, String>>()
        first.onCommit = { k, crop -> saved += k to crop }
        val lay = layout(ig, "ig_home")
        warmUp(first, lay)
        assertTrue(saved.isNotEmpty())

        val c = ctl()
        val commits = mutableListOf<String>()
        c.onCommit = { k, _ -> commits += k }
        c.restoreColors(first.colorsSnapshot())
        c.preload(saved)
        val o = c.onEvaluate(ig, lay, env(nowMs = 0))
        assertTrue(o.state is RenderState.Rebuilt<*>)
        assertNull(o.capture)
        assertTrue(commits.isEmpty()) // preloaded entries are not saved again
    }

    @Test fun committedKeysAreTheSameInEveryRun() {
        val a = ctl()
        val b = ctl()
        val ka = mutableListOf<String>()
        val kb = mutableListOf<String>()
        a.onCommit = { k, _ -> ka += k }
        b.onCommit = { k, _ -> kb += k }
        val lay = layout(ig, "ig_home")
        warmUp(a, lay)
        warmUp(b, lay)
        assertTrue(ka.isNotEmpty())
        assertEquals(ka, kb)
    }

    @Test fun evictionCallsOnDropWithTheEvictedKeys() {
        val c = ctl(max = 3000) // three 1000-byte crops fit
        val dropped = mutableListOf<String>()
        c.onDrop = { keys -> dropped += keys }
        c.preload(listOf("a" to "A", "b" to "B", "c" to "C"))
        assertTrue(dropped.isEmpty())
        c.preload(listOf("d" to "D"))
        assertEquals(listOf("a"), dropped)
    }

    @Test fun clearCropsCallsOnDropForAllKeys() {
        val c = ctl()
        val dropped = mutableListOf<String>()
        c.onDrop = { keys -> dropped += keys }
        c.preload(listOf("a" to "A", "b" to "B"))
        c.clearCrops()
        assertEquals(setOf("a", "b"), dropped.toSet())
        assertEquals(2, dropped.size)
    }

    @Test fun trimCropsDropsMemoryOnlyAndNeverCallsOnDrop() {
        val c = ctl()
        var calls = 0
        c.onDrop = { calls++ }
        c.preload(listOf("a" to "A"))
        c.trimCrops()
        assertEquals(0, calls)
        assertTrue(c.onEvaluate(ig, layout(ig, "ig_home"), env(0)).state !is RenderState.Rebuilt<*>)
    }

    @Test fun preloadedCropsOfAnotherAppVersionAreDroppedOnFirstSight() {
        val first = ctl()
        val saved = mutableListOf<Pair<String, String>>()
        first.onCommit = { k, crop -> saved += k to crop }
        val lay = layout(ig, "ig_home")
        warmUp(first, lay)

        val c = ctl()
        val dropped = mutableListOf<String>()
        c.onDrop = { keys -> dropped += keys }
        c.restoreColors(first.colorsSnapshot())
        // Saved for app version 7; the app is now version 1 (the default in env()).
        c.preload(saved.map { (k, crop) -> k.replaceFirst("|1|", "|7|") to crop })
        val o = c.onEvaluate(ig, lay, env(nowMs = 0))
        assertTrue(o.state !is RenderState.Rebuilt<*>)
        assertEquals(saved.size, dropped.size)
    }

    // ---- cache ----

    @Test fun byteCapEvictsLeastRecentlyUsedCrop() {
        val c = ctl(max = 3000) // three 1000-byte crops fit, four do not
        val lay = layout(ig, "ig_home")
        c.onEvaluate(ig, lay, env(0))
        val r = c.onEvaluate(ig, lay, env(400)).capture!!
        c.onCaptureResult(result(r), 450) // the fourth commit evicts the eldest
        assertTrue(c.onEvaluate(ig, lay, env(450)).state is RenderState.Patch)
        assertNotNull(c.onEvaluate(ig, lay, env(800)).capture)
    }

    @Test fun rulesVersionChangeClearsCache() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        assertTrue(c.onEvaluate(ig, lay, env(900, rules = 2)).state is RenderState.Patch)
    }

    @Test fun appVersionChangeClearsPackageAndItsFailedFlag() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        c.onTap(1, false, 800)
        c.onTapResult(false)
        c.onTap(1, false, 802)
        c.onTapResult(false)
        assertTrue(c.onEvaluate(ig, lay, env(900)).state is RenderState.Patch)
        // A new app version clears the flag and the cache, so the bar warms up again and rebuilds.
        val out = warmUp(c, lay, base = 1000, e = { env(nowMs = it, appVersion = 2) })
        assertTrue(out.state is RenderState.Rebuilt<*>)
    }

    // ---- colour ----

    @Test fun ruleColourIsUsedBeforeSamplingPerNightSetting() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        assertEquals(lay.light, c.onEvaluate(ig, lay, env(0)).state.patchColour())
        assertEquals(lay.dark, c.onEvaluate(ig, lay, env(10, night = true)).state.patchColour())
    }

    @Test fun sampledColourIsUsedAfterWarmUp() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        assertEquals(bgColour, warmUp(c, lay).state.rb().color)
    }

    @Test fun nonUniformSamplesAreNotCommitted() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        val bg = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val out = warmUp(c, lay, bg = bg)
        assertTrue(out.state is RenderState.Patch)
        assertEquals(lay.light, out.state.patchColour())
        assertTrue(c.colorsSnapshot().isEmpty())
    }

    @Test fun translucentBarCommitsMedianAfterThreeLooselyUniformResults() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        c.onEvaluate(ig, lay, wenv(0))
        val r1 = c.onEvaluate(ig, lay, wenv(400)).capture!!
        c.onCaptureResult(result(r1, bg = listOf(gray(0x0F), gray(0x0F), gray(0x1F))), 450)
        val r2 = c.onEvaluate(ig, lay, wenv(750)).capture!!
        c.onCaptureResult(result(r2, bg = listOf(gray(0x1F), gray(0x1F), gray(0x0F))), 800)
        // Two non-uniform results are not enough yet.
        assertFalse(c.colorsSnapshot().containsValue(gray(0x1F)))
        val r3 = c.onEvaluate(ig, lay, wenv(1100)).capture!!
        c.onCaptureResult(result(r3, bg = listOf(gray(0x0F), gray(0x1F), gray(0x1F))), 1150)
        // The committed colour is dark, so the app's appearance flips to dark: crops are keyed afresh and must be
        // captured again (two agreeing captures), so the bar is Patch until then.
        assertEquals(gray(0x1F), c.onEvaluate(ig, lay, wenv(1150)).state.patchColour())
        val r4 = c.onEvaluate(ig, lay, wenv(1500)).capture!!
        c.onCaptureResult(result(r4, bg = List(3) { gray(0x1F) }), 1550)
        assertEquals(gray(0x1F), c.onEvaluate(ig, lay, wenv(1550)).state.rb().color)
    }

    @Test fun colourOnceKnownIsNotRemovedOrForcedToPatchByNonUniformResults() {
        val c = ctl()
        val a = dmLayout("4")
        val b = dmLayout("99")
        warmUp(c, a)
        c.onEvaluate(ig, b, env(1000))
        val req = c.onEvaluate(ig, b, env(1400)).capture!!
        c.onCaptureResult(result(req, bg = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())), 1450)
        // The dark sample sets the app's appearance to dark (changed from the phone's light mode in this test's env).
        assertEquals(mapOf("$ig|true" to bgColour, "$ig|appearance" to 1, "$ig|night" to 0), c.colorsSnapshot())
        assertEquals(bgColour, c.onEvaluate(ig, a, env(1500)).state.rb().color)
    }

    @Test fun restoreColorsSeedsTheSampledColour() {
        val c1 = ctl()
        warmUp(c1, layout(ig, "ig_home"))
        val snap = c1.colorsSnapshot()
        assertEquals(mapOf("$ig|true" to bgColour, "$ig|appearance" to 1, "$ig|night" to 0), snap)

        val c2 = ctl()
        c2.restoreColors(mapOf("$ig|false" to restored))
        assertEquals(restored, c2.onEvaluate(ig, layout(ig, "ig_home"), env(0)).state.patchColour())
    }

    @Test fun systemDarkWithLightAppFlipsAppearanceAndKeysColourAndCropsUnderLight() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        val light = 0xFFFFFFFF.toInt()
        val e = { t: Long -> env(nowMs = t, night = true) }
        val s = warmUp(c, lay, bg = List(3) { light }, e = e).state.rb()
        assertEquals(light, s.color)
        assertEquals(4, s.crops.size)
        assertEquals(mapOf("$ig|false" to light, "$ig|appearance" to 0, "$ig|night" to 1), c.colorsSnapshot())
        // A later evaluation with the phone still in dark mode keeps the app's light appearance.
        assertEquals(light, c.onEvaluate(ig, lay, e(900)).state.rb().color)
    }

    @Test fun restoredAppearanceSelectsTheMatchingColourKey() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        val light = 0xFFFFFFFF.toInt()
        c.restoreColors(mapOf("$ig|false" to light, "$ig|appearance" to 0, "$ig|night" to 1)) // saved with the phone dark
        // Phone in dark mode, but the app was last seen light: the light colour shows at once.
        assertEquals(light, c.onEvaluate(ig, lay, env(0, night = true)).state.patchColour())
    }

    @Test fun reentryInWindowModeShowsRebuiltFromCacheAndRefreshesWithoutBlocking() {
        val e = { t: Long -> env(nowMs = t, api = 34) }
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay, e = e)
        c.invalidate() // left the app
        val back = c.onEvaluate(ig, lay, e(5_000))
        assertTrue(back.state is RenderState.Rebuilt<*>)
        assertNull(back.capture)
        assertEquals(5_400L, back.evalAtMs) // re-sample after the settle wait, with no hide
        val req = c.onEvaluate(ig, lay, e(5_400)).capture!!
        assertEquals(CaptureKind.WINDOW, req.kind)
        assertTrue(c.onEvaluate(ig, lay, e(5_400)).state is RenderState.Rebuilt<*>)
    }

    @Test fun reentryOnApi33RebuildsFromCacheWithoutAHideForTheColour() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        c.invalidate()
        val o1 = c.onEvaluate(ig, lay, env(5_000))
        assertTrue(o1.state is RenderState.Rebuilt<*>) // from cache, at once
        assertNull(o1.capture)
        // The colour is stale after entry, but a hide only to re-sample it is not worth it: no capture.
        val o2 = c.onEvaluate(ig, lay, env(5_400))
        assertNull(o2.capture)
        assertTrue(o2.state is RenderState.Rebuilt<*>)
    }

    @Test fun startupOnApi33ReachesRebuiltAfterTheFirstCaptureResult() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        assertTrue(c.onEvaluate(ig, lay, env(0)).state is RenderState.Patch)
        val req = c.onEvaluate(ig, lay, env(400)).capture!!
        assertEquals(CaptureKind.DISPLAY_NO_HIDE, req.kind) // Patch on screen: no hide needed
        c.onCaptureResult(result(req), 450)
        assertTrue(c.onEvaluate(ig, lay, env(450)).state is RenderState.Rebuilt<*>)
    }

    /** Reads with no bar layout until the bar is Hidden (a first miss may be a Patch). Returns the state reached. */
    private fun noBarHidden(c: BarController<String>, base: Long = 5_000L): RenderState {
        c.onEvaluate(ig, null, env(nowMs = base))
        return c.onEvaluate(ig, null, env(nowMs = base + 100)).state
    }

    @Test fun onBarBackRedrawsTheLastRebuiltLayoutFromCacheAfterANoBarRead() {
        val c = ctl()
        val rebuilt = warmUp(c, layout(ig, "ig_home")).state.rb()
        assertEquals(RenderState.Hidden, noBarHidden(c))
        assertTrue(c.canRebuild())
        val o = c.onBarBack(5_200)!!
        val s = o.state.rb()
        assertEquals(rebuilt.tabsBox, s.tabsBox)
        assertEquals(rebuilt.slots, s.slots)
        assertEquals(rebuilt.crops, s.crops)
        assertEquals(rebuilt.patchBox, s.patchBox)
        assertEquals(rebuilt.color, s.color)
        assertNull(o.capture)
        assertNull(o.evalAtMs)
    }

    @Test fun onBarBackIsNullWhenACropIsMissing() {
        val c = ctl()
        warmUp(c, layout(ig, "ig_home"))
        noBarHidden(c)
        c.trimCrops()
        assertFalse(c.canRebuild())
        assertNull(c.onBarBack(5_200))
    }

    @Test fun onBarBackIsNullAfterInvalidate() {
        val c = ctl()
        warmUp(c, layout(ig, "ig_home"))
        noBarHidden(c)
        c.invalidate()
        assertNull(c.onBarBack(5_200))
    }

    @Test fun onBarBackIsNullAfterAnAppearanceChange() {
        val c = ctl()
        warmUp(c, layout(ig, "ig_home"))
        noBarHidden(c)
        c.onEvaluate(ig, null, env(nowMs = 5_200, night = true)) // phone night mode flips: appearance awaits a new sample
        assertNull(c.onBarBack(5_300))
    }

    /** The bar of a fixture, or null when the screen has none (unlike [layout], which requires one). */
    private fun barOrNull(pkg: String, name: String): BarLayout? {
        val j = JSONObject(text(name))
        return Detection.run(rules.forApp(pkg, 1), NodeSnap.fromJson(j.getJSONObject("tree")), w, h, navOf(j), false).bar
    }

    @Test fun aCommentsSheetOverTheBarForgetsTheRebuiltLayout() {
        val c = ctl()
        warmUp(c, layout(ig, "ig_home"))
        val sheet = c.onEvaluate(ig, barOrNull(ig, "ig_comments_sheet"), env(nowMs = 5_000))
        assertTrue(sheet.state !is RenderState.Rebuilt<*>)
        // Then a story or reel with no bar: the sheet's old tab bar must not come back over the composer.
        noBarHidden(c, base = 6_000)
        assertFalse(c.canRebuild())
        assertNull(c.onBarBack(7_000))
    }

    @Test fun aFailedAppVersionCannotRedrawFromCache() {
        val c = ctl()
        warmUp(c, layout(ig, "ig_home"))
        c.onTapResult(false)
        c.onTapResult(false) // two strikes: this app version is marked failed
        noBarHidden(c)
        assertFalse(c.canRebuild())
        assertNull(c.onBarBack(5_200))
    }

    @Test fun aHideCaptureInFlightBlocksRedrawFromCache() {
        val c = ctl()
        val a = dmLayout("4")
        val b = dmLayout("99")
        warmUp(c, a)
        assertTrue(c.onEvaluate(ig, b, env(1000)).state is RenderState.Rebuilt<*>)
        assertEquals(CaptureKind.DISPLAY_WITH_HIDE, c.onEvaluate(ig, b, env(1400)).capture!!.kind)
        noBarHidden(c, base = 1_500)
        assertFalse(c.canRebuild())
        assertNull(c.onBarBack(1_600))
    }

    @Test fun hideCaptureIsPatchWhileInFlightAndRebuiltRightAfterItsResult() {
        val c = ctl()
        val a = dmLayout("4")
        val b = dmLayout("99")
        warmUp(c, a)
        assertTrue(c.onEvaluate(ig, b, env(1000)).state is RenderState.Rebuilt<*>) // changed tab keeps its on-screen crop
        val req = c.onEvaluate(ig, b, env(1400)).capture!!
        assertEquals(CaptureKind.DISPLAY_WITH_HIDE, req.kind)
        assertTrue(c.onEvaluate(ig, b, env(1420)).state is RenderState.Patch)
        assertNull(c.onEvaluate(ig, b, env(1440)).capture)
        c.onCaptureResult(result(req, tag = "new"), 1450)
        val s = c.onEvaluate(ig, b, env(1450)).state.rb()
        assertEquals(4, s.crops.size)
        assertTrue(s.crops.all { (it as String).startsWith("new-") })
    }

    @Test fun hideCaptureForUnchangedTabsKeepsOnScreenCropsWhenNoResultCame() {
        val c = ctl()
        val a = dmLayout("4")
        val b = dmLayout("99")
        warmUp(c, a)
        c.onEvaluate(ig, b, env(1000))
        assertNotNull(c.onEvaluate(ig, b, env(1400)).capture)
        // The hide is still in flight at 1600 (its result has not come): still Patch, and the on-screen state is kept.
        assertTrue(c.onEvaluate(ig, b, env(1600)).state is RenderState.Patch)
        c.onCaptureFailed(c.generation, 1650)
        // After the failed capture the badge fallback shows the "4" picture of the same tab rather than the patch.
        assertTrue(c.onEvaluate(ig, b, env(1651)).state is RenderState.Rebuilt<*>)
    }

    @Test fun appearanceChangeNeverShowsOnScreenCropsOfTheOtherAppearance() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        val light = 0xFFFFFFFF.toInt()
        warmUp(c, lay, bg = List(3) { light }, e = { env(nowMs = it, night = true) }) // shown: light appearance
        // A tab's badge changes, so crops are wanted; the sample taken with them is dark, so the appearance flips. Only
        // crops of the new appearance may show. (A stale colour alone does not capture on the display path while Rebuilt.)
        val dm = dmLayout("9")
        c.onEvaluate(ig, dm, env(700_000, night = true))
        val req = c.onEvaluate(ig, dm, env(700_400, night = true)).capture!!
        c.onCaptureResult(result(req, tag = "dk", bg = List(3) { bgColour }), 700_450)
        val s = c.onEvaluate(ig, dm, env(700_450, night = true)).state.rb()
        assertEquals(bgColour, s.color)
        assertTrue(s.crops.all { (it as String).startsWith("dk-") })
    }

    @Test fun movingBarIsPatchUntilItsTabsBoxHasBeenStillForThreeHundredMs() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        val moved = lay.copy(tabsBox = lay.tabsBox.copy(t = lay.tabsBox.t - 40, b = lay.tabsBox.b - 40))
        assertTrue(c.onEvaluate(ig, moved, env(1000)).state is RenderState.Patch)
        assertTrue(c.onEvaluate(ig, moved, env(1299)).state is RenderState.Patch)
        assertTrue(c.onEvaluate(ig, moved, env(1301)).state is RenderState.Rebuilt<*>)
    }

    @Test fun movingPatchAndTabsHideTheBarEntirely() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        val moved = lay.copy(
            tabsBox = lay.tabsBox.copy(t = lay.tabsBox.t - 40, b = lay.tabsBox.b - 40),
            patchBox = lay.patchBox?.copy(t = lay.patchBox!!.t - 40, b = lay.patchBox!!.b - 40),
        )
        assertEquals(RenderState.Hidden, c.onEvaluate(ig, moved, env(1000)).state)
    }

    @Test fun nonUniformResampleWhileColourKnownIsConfirmedAndNotRepeatedAfterStale() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        // Window mode: a stale colour is re-sampled with a capture (no hide is needed there).
        warmUp(c, lay, e = { wenv(it) })
        val nonUniform = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        // Colour stale after ten minutes: a non-uniform re-sample confirms it, so no further colour captures follow.
        val later = c.onEvaluate(ig, lay, wenv(601_000))
        c.onCaptureResult(result(later.capture!!, bg = nonUniform), 601_050)
        assertNull(c.onEvaluate(ig, lay, wenv(601_500)).capture)
    }

    @Test fun nightModeToggleOnApi33ShowsPatchThenNoHideSampleThenRebuildsInTheNewAppearance() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay) // day: dark-appearance crops cached and shown
        assertTrue(c.onEvaluate(ig, lay, env(1000, night = true)).state is RenderState.Patch)
        assertNull(c.onEvaluate(ig, lay, env(1000, night = true)).capture) // waits for the screen to settle
        val o = c.onEvaluate(ig, lay, env(1400, night = true))
        assertEquals(CaptureKind.DISPLAY_NO_HIDE, o.capture!!.kind) // Patch is on screen: no hide needed
        val light = 0xFFFFFFFF.toInt()
        c.onCaptureResult(result(o.capture!!, tag = "nt", bg = List(3) { light }), 1450)
        val s = c.onEvaluate(ig, lay, env(1450, night = true)).state.rb()
        assertEquals(light, s.color)
        assertTrue(s.crops.all { (it as String).startsWith("nt-") })
    }

    // ---- optimistic selection ----

    /** Home selected (crops tagged "x") and DM selected (tagged "dm"), so DM-selected crops are cached for the prediction. */
    /** Caches crops for each layout in turn (each selected-tab state), ending Rebuilt on the last one; returns the next free time. */
    private fun cacheStates(c: BarController<String>, layouts: List<BarLayout>): Long {
        var t = 0L
        for (l in layouts) {
            c.onEvaluate(ig, l, env(t))
            val req = c.onEvaluate(ig, l, env(t + 400)).capture
            if (req != null) c.onCaptureResult(result(req), t + 450)
            assertTrue(c.onEvaluate(ig, l, env(t + 450)).state is RenderState.Rebuilt<*>)
            t += 1000
        }
        return t
    }

    private fun withBothSelectionsCached(c: BarController<String>, home: BarLayout): BarLayout {
        val dm = select(home, "direct_tab")
        warmUp(c, home)
        c.onEvaluate(ig, dm, env(1000))
        val req = c.onEvaluate(ig, dm, env(1400)).capture!!
        c.onCaptureResult(result(req, tag = "dm"), 1450)
        assertTrue(c.onEvaluate(ig, dm, env(1450)).state is RenderState.Rebuilt<*>)
        return dm
    }

    @Test fun tapShowsCachedSelectionAtOnceBeforeTheLayoutChanges() {
        val c = ctl()
        val home = layout(ig, "ig_home")
        val dm = withBothSelectionsCached(c, home)
        val cmd = c.onTap(0, false, 1500)!!           // Home is slot 0; it is not selected on the real layout
        val s = c.onEvaluate(ig, dm, env(1510)).state.rb() // the real layout still shows DM selected
        assertEquals("x-${cmd.identity}", s.crops[0])     // the predicted crop: Home selected, from the first warm-up
    }

    @Test fun tapWithAMissingPredictedCropPredictsNothing() {
        val c = ctl()
        val home = layout(ig, "ig_home")
        warmUp(c, home)                                   // DM-selected crops were never captured
        c.onTap(1, false, 800)
        val plain = ctl()
        warmUp(plain, home)
        assertEquals(plain.onEvaluate(ig, home, env(810)).state, c.onEvaluate(ig, home, env(810)).state)
        assertNull(c.onEvaluate(ig, home, env(810)).capture)
    }

    @Test fun predictedSelectionThatNeverArrivesStillCountsAsStrikes() {
        val c = ctl()
        val home = layout(ig, "ig_home")
        val dm = withBothSelectionsCached(c, home)
        c.onTap(0, false, 1500)
        assertTrue(c.onEvaluate(ig, dm, env(1510)).state is RenderState.Rebuilt<*>)
        // Instagram never selects Home: the deadline (1500 + 700) passes. One strike, real crops again.
        assertTrue(c.onEvaluate(ig, dm, env(2300)).state is RenderState.Rebuilt<*>)
        c.onTap(0, false, 2400)
        c.onEvaluate(ig, dm, env(2410))
        c.onEvaluate(ig, dm, env(3200)) // second timeout
        assertTrue(c.onEvaluate(ig, dm, env(3210)).state is RenderState.Patch)
    }

    // ---- taps ----

    @Test fun tapReturnsNullUnlessRebuilt() {
        assertNull(ctl().onTap(0, false, 0))
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        c.onEvaluate(ig, lay, env(900, featureOn = false)) // not Rebuilt any more
        assertNull(c.onTap(1, false, 950))
    }

    @Test fun tapMapsSlotToKeptTabAndSkipsLongPressExpectation() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        val cmd = c.onTap(1, true, 800)!!
        assertTrue(cmd.longPress)
        assertTrue(cmd.viewId!!.endsWith("/direct_tab"))
        // A long press sets no expectation, so no timeout strike is counted for it.
        assertTrue(c.onEvaluate(ig, lay, env(2000)).state is RenderState.Rebuilt<*>)
    }

    @Test fun successfulTapResetsStrikes() {
        val c = ctl()
        val lay = select(layout(ig, "ig_home"), "feed_tab")
        val dm = select(lay, "direct_tab")
        val t = cacheStates(c, listOf(dm, lay)) // both selection states cached, Home selected on screen
        c.onTap(1, false, t)
        assertTrue(c.onEvaluate(ig, dm, env(t + 100)).state is RenderState.Rebuilt<*>)
        c.onTap(2, false, t + 200)
        // One timeout after a verified tap is one strike, not two.
        assertTrue(c.onEvaluate(ig, dm, env(t + 1000)).state is RenderState.Rebuilt<*>)
    }

    @Test fun twoTimeoutsFailTheBarToPatch() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        c.onTap(1, false, 800)
        assertTrue(c.onEvaluate(ig, lay, env(1600)).state is RenderState.Rebuilt<*>)
        c.onTap(1, false, 1700)
        assertTrue(c.onEvaluate(ig, lay, env(2500)).state is RenderState.Patch)
    }

    @Test fun failedPerformActionCountsAsStrikes() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        c.onTap(1, false, 800)
        c.onTapResult(false)
        c.onTap(1, false, 802)
        c.onTapResult(false)
        assertTrue(c.onEvaluate(ig, lay, env(900)).state is RenderState.Patch)
    }

    @Test fun selectsFalseExpectationIsMetWhenTheLayoutGoes() {
        val c = ctl()
        val y = layout(yt, "yt_home")
        warmUp(c, y, pkg = yt)
        c.onTap(1, false, 800)                                  // Create: no selection, times out
        assertTrue(c.onEvaluate(yt, y, env(1600)).state is RenderState.Rebuilt<*>)
        c.onTap(1, false, 1700)                                 // Create again, layout goes away: met
        c.onEvaluate(yt, null, env(1800))
        assertTrue(c.onEvaluate(yt, y, env(1850)).state is RenderState.Rebuilt<*>)
        c.onTap(1, false, 1900)
        // The met expectation reset the strike count, so this single timeout does not fail the bar.
        assertTrue(c.onEvaluate(yt, y, env(2700)).state is RenderState.Rebuilt<*>)
    }

    @Test fun newerTapReplacesPendingExpectation() {
        val c = ctl()
        val lay = select(layout(ig, "ig_home"), "feed_tab")
        val search = select(lay, "search_tab")
        val t = cacheStates(c, listOf(search, lay)) // both states cached, Home selected on screen
        c.onTap(1, false, t)        // Message, expects selection by t+700
        c.onTap(2, false, t + 50)   // Search replaces it, expects selection by t+750
        assertTrue(c.onEvaluate(ig, search, env(t + 800)).state is RenderState.Rebuilt<*>)
        c.onTap(1, false, t + 900)  // Message times out: one strike only
        assertTrue(c.onEvaluate(ig, search, env(t + 1700)).state is RenderState.Rebuilt<*>)
    }

    @Test fun tapRendersThePredictedSelectionAtOnceWithoutANewLayout() {
        val c = ctl()
        val home = layout(ig, "ig_home")
        withBothSelectionsCached(c, home)                  // Rebuilt on screen, Home and DM crops cached
        val cmd = c.onTap(0, false, 1500)!!
        val o = c.onTapRender()!!                      // no evaluation, no layout: straight from the cache
        assertTrue(o.state is RenderState.Rebuilt<*>)
        assertNull(o.capture)
        assertEquals("x-${cmd.identity}", o.state.rb().crops[0])
    }

    @Test fun tapRenderIsNullWithoutACachedPredictedCrop() {
        val c = ctl()
        val home = layout(ig, "ig_home")
        warmUp(c, home)                                    // only the DM-selected crops exist
        c.onTap(1, false, 800)
        assertNull(c.onTapRender())
    }

    @Test fun strikeIsCountedOnlyForAReadTakenAfterTheDeadline() {
        val lines = ArrayList<String>()
        val c = BarController<String>(sizeOf = { 1000 }, same = { a, b -> a == b }, trace = { lines += it })
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        c.onTap(1, false, 800)                             // deadline 1500
        c.onEvaluate(ig, lay, env(1500))                   // read at the deadline: not yet judged
        assertFalse(lines.any { it.startsWith("tap timed out") })
        c.onEvaluate(ig, lay, env(1501))                   // read after the deadline, old selection still shown
        assertTrue(lines.any { it.startsWith("tap timed out") })
    }

    @Test fun lateReadOfAnOldSnapshotDoesNotCountAStrikeWithoutABar() {
        val lines = ArrayList<String>()
        val c = BarController<String>(sizeOf = { 1000 }, same = { a, b -> a == b }, trace = { lines += it })
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        c.onTap(1, false, 800)
        c.onEvaluate(ig, null, env(1600))                  // no bar in the read: expired, not a strike
        assertFalse(lines.any { it.startsWith("tap timed out") })
        assertTrue(lines.any { it == "tap expired without bar" })
    }

    // ---- slots and sample points ----

    private fun tab(i: Int, id: String, box: Box, hidden: Boolean = false, selected: Boolean = false) =
        BarTab(i, id, null, null, box, selected, hidden, true, "sig-$id")

    private fun synth(tabs: List<BarTab>, tabsBox: Box, patch: Box? = null) =
        BarLayout("t", "f", tabsBox, tabsBox, tabs, patch, BarMode.REBUILD, null, bgColour, bgColour)

    private fun tilingLayout(n: Int, width: Int): BarLayout {
        val tb = Box(0, 2208, width, 2352)
        val tabs = (0 until n).map { i -> tab(i, "t$i", Box(i * width / n, 2208, (i + 1) * width / n, 2352)) }
        return synth(tabs, tb)
    }

    @Test fun slotsTileWithoutGapsForFourFiveAndSixTabs() {
        for ((n, width) in listOf(4 to 1080, 5 to 1080, 6 to 1080, 5 to 1081)) {
            val lay = tilingLayout(n, width)
            val slots = layoutSlots(lay)!!
            assertEquals(n, slots.size)
            assertEquals(lay.tabs.map { it.box }, slots.map { it.dest })
            slots.zipWithNext().forEach { (a, b) -> assertEquals(a.dest.r, b.dest.l) }
            assertEquals(0, slots.first().dest.l)
            assertEquals(width, slots.last().dest.r)
        }
    }

    @Test fun narrowTabsAreCentredInTheirSlots() {
        val tb = Box(0, 2208, 1080, 2352)
        val tabs = (0 until 4).map { i -> tab(i, "t$i", Box(i * 270, 2208, i * 270 + 200, 2352)) }
        val slots = layoutSlots(synth(tabs, tb))!!
        assertEquals(listOf(35, 305, 575, 845), slots.map { it.dest.l })
    }

    @Test fun tabWiderThanItsSlotGivesNull() {
        val tb = Box(0, 2208, 1080, 2352)
        val tabs = (0 until 4).map { i -> tab(i, "t$i", Box(i * 270, 2208, i * 270 + 271, 2352)) }
        assertNull(layoutSlots(synth(tabs, tb)))
        assertNull(layoutSlots(synth(emptyList(), tb)))
    }

    @Test fun samplePointsSkipThePatchBox() {
        val tb = Box(0, 2208, 1080, 2352)
        val tabs = (0 until 4).map { i -> tab(i, "t$i", Box(i * 270, 2208, (i + 1) * 270, 2352)) }
        val withPatch = samplePoints(synth(tabs, tb, patch = Box(270, 2208, 540, 2352)))
        assertEquals(listOf(4 to 2280, 540 to 2280, 810 to 2280, 1075 to 2280), withPatch)
        assertEquals(listOf(4, 270, 540, 810, 1075), samplePoints(synth(tabs, tb)).map { it.first })
        assertEquals(listOf(2208, 2209, 2210), (0..2).map { topRowPoints(synth(tabs, tb), it).first().second })
    }

    // ---- stale reads (pure rule) ----

    @Test fun readsTakenBeforeTheLastTargetChangeMayNotDrawTheBar() {
        assertFalse(readMayDrawBar(capturedAtMs = 1_000, lastTargetChangeMs = 1_200, ime = false))
        assertTrue(readMayDrawBar(capturedAtMs = 1_200, lastTargetChangeMs = 1_200, ime = false))
        assertTrue(readMayDrawBar(capturedAtMs = 1_500, lastTargetChangeMs = 1_200, ime = false))
    }

    @Test fun aReadWithTheKeyboardUpMayAlwaysDrawBecauseItHides() {
        assertTrue(readMayDrawBar(capturedAtMs = 1_000, lastTargetChangeMs = 1_200, ime = true))
        // Inside the re-entry settle after an outside return: no full read draws the bar.
        assertFalse(readMayDrawBar(capturedAtMs = 1_300, lastTargetChangeMs = 1_000, ime = false, settleUntilMs = 1_450))
        assertTrue(readMayDrawBar(capturedAtMs = 1_450, lastTargetChangeMs = 1_000, ime = false, settleUntilMs = 1_450))
    }

    // ---- restored appearance of the other night mode (Blocker 2) ----

    /** A saved appearance from a dark phone (night on), with its crops kept as the saved pictures would be. */
    private fun savedDarkRun(): Pair<Map<String, Int>, List<Pair<String, String>>> {
        val first = ctl()
        val saved = mutableListOf<Pair<String, String>>()
        first.onCommit = { k, crop -> saved += k to crop }
        warmUp(first, layout(ig, "ig_home"), e = { env(nowMs = it, night = true) })
        assertTrue(saved.isNotEmpty())
        return first.colorsSnapshot() to saved
    }

    @Test fun restoredDarkAppearanceOnALightPhoneIsNotShownUntilSampled() {
        val (snap, saved) = savedDarkRun()
        assertEquals(1, snap["$ig|night"])
        val c = ctl()
        c.restoreColors(snap)
        c.preload(saved)
        val lay = layout(ig, "ig_home")
        val o = c.onEvaluate(ig, lay, env(nowMs = 0, night = false))
        assertFalse(o.state is RenderState.Rebuilt<*>) // no dark crops over the light bar
        assertEquals(lay.light, o.state.patchColour())  // the rule colour of the light phone, not the restored dark one
        assertNull(c.onEvaluate(ig, lay, env(nowMs = 300, night = false)).capture) // settle first
        assertFalse(c.onEvaluate(ig, lay, env(nowMs = 2_000, night = false)).state is RenderState.Rebuilt<*>)
    }

    @Test fun restoredDarkAppearanceOnADarkPhoneRebuildsAtOnce() {
        val (snap, saved) = savedDarkRun()
        val c = ctl()
        c.restoreColors(snap)
        c.preload(saved)
        val o = c.onEvaluate(ig, layout(ig, "ig_home"), env(nowMs = 0, night = true))
        assertTrue(o.state is RenderState.Rebuilt<*>)
        assertNull(o.capture)
        assertTrue(o.state.rb().crops.all { (it as String).startsWith("x-") })
    }

    // ---- a capture that started before the window facts changed is dropped (Major 2) ----

    @Test fun captureInFlightIsDroppedWhenAnOccluderAppears() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        c.onEvaluate(ig, lay, env(0))
        val r1 = c.onEvaluate(ig, lay, env(400)).capture!!
        c.onEvaluate(ig, lay, env(410, windowAbove = true))
        assertFalse(c.onCaptureResult(result(r1), 450))
    }

    @Test fun captureInFlightIsDroppedWhenTheBarBecomesPatchOnly() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        c.onEvaluate(ig, lay, env(0))
        val r1 = c.onEvaluate(ig, lay, env(400)).capture!!
        c.onEvaluate(ig, lay.copy(mode = BarMode.PATCH), env(410))
        assertFalse(c.onCaptureResult(result(r1), 450))
    }

    @Test fun anAppearanceSavedWithoutTheNightModeIsTreatedAsUnknown() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        val light = 0xFFFFFFFF.toInt()
        c.restoreColors(mapOf("$ig|false" to light, "$ig|appearance" to 0)) // older build: no night key
        // Phone dark: the saved light appearance is not trusted, so the dark rule colour shows until a sample.
        assertEquals(lay.dark, c.onEvaluate(ig, lay, env(0, night = true)).state.patchColour())
    }
    // ---- re-entry memo: leaving the app keeps the last Rebuilt layout; moving keeps it for a redraw ----

    @Test fun rebuiltSpotIsTheWindowAndBoxOfTheLastRebuiltLayout() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay, e = { env(nowMs = it).copy(windowId = 7) })
        val spot = c.rebuiltSpot()!!
        assertEquals(ig, spot.pkg)
        assertEquals(7, spot.windowId)
        assertEquals(lay.container, spot.container)
    }

    @Test fun reentryMemoSurvivesLeavingTheAppAndRedrawsFromCache() {
        val c = ctl()
        val rebuilt = warmUp(c, layout(ig, "ig_home")).state.rb()
        c.invalidate(keepRebuilt = true) // left the app
        assertEquals(RenderState.Hidden, c.onEvaluate(ig, null, env(nowMs = 5_000)).state)
        assertTrue(c.canRebuild())
        val s = c.onBarBack(5_200)!!.state.rb()
        assertEquals(rebuilt.tabsBox, s.tabsBox)
        assertEquals(rebuilt.crops, s.crops)
        assertEquals(rebuilt.patchBox, s.patchBox)
    }

    @Test fun plainInvalidateForgetsTheLayoutSoNoReentryIsPossible() {
        val c = ctl()
        warmUp(c, layout(ig, "ig_home"))
        c.invalidate() // a full reset (rotation, teardown, rules): nothing is kept
        assertNull(c.rebuiltSpot())
        assertNull(c.onBarBack(5_200))
    }

    /** A Rebuilt layout, then the app left (kept for re-entry), then the bar's memo is looked at after [act]. */
    private fun leftAndThen(act: (BarController<String>) -> Unit): BarController<String> {
        val c = ctl()
        warmUp(c, layout(ig, "ig_home"))
        c.invalidate(keepRebuilt = true)
        assertNotNull(c.rebuiltSpot())
        act(c)
        return c
    }

    @Test fun nightChangeClearsTheReentryMemo() {
        val c = leftAndThen { it.onEvaluate(ig, null, env(nowMs = 5_000, night = true)) }
        assertNull(c.rebuiltSpot())
        assertNull(c.onBarBack(5_200))
    }

    @Test fun simplePatchClearsTheReentryMemo() {
        val c = leftAndThen { it.onEvaluate(ig, null, env(nowMs = 5_000, simple = true)) }
        assertNull(c.rebuiltSpot())
    }

    @Test fun clearCropsClearsTheReentryMemo() {
        val c = leftAndThen { it.clearCrops() }
        assertNull(c.rebuiltSpot())
        assertNull(c.onBarBack(5_200))
    }

    @Test fun appVersionChangeClearsTheReentryMemo() {
        val c = leftAndThen { it.onEvaluate(ig, null, env(nowMs = 5_000, appVersion = 2L)) }
        assertNull(c.rebuiltSpot())
    }

    @Test fun anOccludedLayoutClearsTheReentryMemo() {
        val lay = layout(ig, "ig_home")
        val c = leftAndThen { it.onEvaluate(ig, lay, env(nowMs = 5_000, windowAbove = true)) }
        assertNull(c.rebuiltSpot())
        assertNull(c.onBarBack(5_200))
    }

    @Test fun aFailedAppVersionClearsTheReentryMemo() {
        val c = leftAndThen {
            it.onTapResult(false)
            it.onTapResult(false) // two strikes: this app version is marked failed
        }
        assertNull(c.rebuiltSpot())
    }

    @Test fun aMovingBarIsHeldAndItsLayoutCanBeRedrawn() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        val moved = lay.copy(tabsBox = lay.tabsBox.copy(t = lay.tabsBox.t - 40, b = lay.tabsBox.b - 40))
        assertTrue(c.onEvaluate(ig, moved, env(1000)).state is RenderState.Patch)
        assertTrue(c.holdingForMove)
        assertTrue(c.canRebuild())
        assertNotNull(c.onBarBack(1000))
    }

    // ---- another window over the bar: the target app's own window keeps the memo, a foreign one forgets it ----

    @Test fun aTargetWindowAboveHidesTheBarAndKeepsTheReentryMemo() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        val o = c.onEvaluate(ig, lay, env(nowMs = 5_000).copy(targetWindowAbove = true))
        assertEquals(RenderState.Hidden, o.state)
        assertNull(o.capture)
        assertNotNull(c.rebuiltSpot())
        assertTrue(c.canRebuild())
        assertNotNull(c.onBarBack(5_200))
    }

    @Test fun theBarComesBackFromCacheWhenTheTargetWindowAboveCloses() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        val rebuilt = warmUp(c, lay).state.rb()
        c.onEvaluate(ig, lay, env(nowMs = 5_000).copy(targetWindowAbove = true))
        val back = c.onEvaluate(ig, lay, env(nowMs = 6_000))
        assertTrue(back.state is RenderState.Rebuilt<*>)
        assertEquals(rebuilt.tabsBox, back.state.rb().tabsBox)
    }

    @Test fun aForeignWindowAboveStillForgetsTheReentryMemo() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        assertTrue(c.onEvaluate(ig, lay, env(nowMs = 5_000, windowAbove = true)).state is RenderState.Patch)
        assertNull(c.rebuiltSpot())
        assertFalse(c.canRebuild())
        assertNull(c.onBarBack(5_200))
    }

    @Test fun aReadAtTheRememberedBoxAfterAMoveHoldRedrawsTheBar() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        val moved = lay.copy(tabsBox = lay.tabsBox.copy(t = lay.tabsBox.t - 40, b = lay.tabsBox.b - 40))
        assertTrue(c.onEvaluate(ig, moved, env(1000)).state is RenderState.Patch)
        assertNotNull(c.onBarBack(1000))
        // The confirming read is at the remembered box: no change, so no move hold and the bar is Rebuilt again.
        assertTrue(c.onEvaluate(ig, lay, env(1100)).state is RenderState.Rebuilt<*>)
    }

    @Test fun forgetMemoDropsTheRememberedLayout() {
        val c = ctl()
        warmUp(c, layout(ig, "ig_home"))
        c.forgetMemo(ig)
        assertNull(c.rebuiltSpot())
        assertFalse(c.canRebuild())
    }

    @Test fun aResyncAfterASkippedMidSlideReadRedrawsTheBarAndTheConfirmingReadKeepsIt() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        val moved = lay.copy(tabsBox = lay.tabsBox.copy(t = lay.tabsBox.t - 40, b = lay.tabsBox.b - 40))
        c.invalidate(keepRebuilt = true) // the bar was hidden: its layout is kept for the return
        // The provisional bar: Rebuilt from the cache. Then a skipped mid-slide read leaves the controller Patch (held), and the
        // shell re-syncs with onBarBack.
        assertNotNull(c.onBarBack(1000))
        assertTrue(c.onEvaluate(ig, moved, env(1050)).state is RenderState.Patch)
        assertTrue(c.holdingForMove)
        val synced = c.onBarBack(1060)
        assertNotNull(synced)
        assertTrue(synced!!.state is RenderState.Rebuilt<*>)
        // The confirming read at the remembered box is no change: Rebuilt, not a 300 ms move hold.
        assertTrue(c.onEvaluate(ig, lay, env(1100)).state is RenderState.Rebuilt<*>)
    }

    @Test fun aPatchOnlyLayoutIsNotHeldAndCannotBeRedrawn() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        assertTrue(c.onEvaluate(ig, lay.copy(mode = BarMode.PATCH), env(1000)).state is RenderState.Patch)
        assertFalse(c.holdingForMove)
        assertFalse(c.canRebuild())
        assertNull(c.onBarBack(1000))
    }

    @Test fun aSecondTapMovesTheHighlightAtOnceEvenBeforeTheReadCatchesUp() {
        val c = ctl()
        val home = select(layout(ig, "ig_home"), "feed_tab")
        val dm = select(home, "direct_tab")
        val t = cacheStates(c, listOf(dm, home)) // both states cached; Home selected on screen
        c.onTap(1, false, t)                      // DMs
        assertNotNull(c.onTapRender())         // DMs highlighted at once
        // The reads still say Home is selected (they lag). Tapping Home must still predict and move the highlight back.
        val cmd = c.onTap(0, false, t + 200)!!
        val back = c.onTapRender()
        assertNotNull(back)
        assertTrue(back!!.state is RenderState.Rebuilt<*>)
        assertEquals("com.instagram.android:id/feed_tab", cmd.identity)
    }

    @Test fun aNewBadgeCountShowsTheSameTabsOtherBadgePictureInsteadOfThePatch() {
        val c = ctl()
        warmUp(c, dmLayout("4"))      // pictures saved with "4" unread
        c.invalidate(keepRebuilt = true) // left and came back: nothing on screen to reuse
        val s = c.onEvaluate(ig, dmLayout("5"), env(5_000)).state // the count is now 5: no picture for it yet
        assertTrue(s is RenderState.Rebuilt<*>)  // the "4" picture shows until the "5" one is captured
    }

    @Test fun theBadgeFallbackNeverCrossesASelectionChange() {
        val c = ctl()
        val home = select(layout(ig, "ig_home"), "feed_tab")
        warmUp(c, home)
        c.invalidate(keepRebuilt = true)
        // DMs now selected: its picture differs in selection, not just badge, so no fallback (patch until captured).
        assertFalse(c.onEvaluate(ig, select(home, "direct_tab"), env(5_000)).state is RenderState.Rebuilt<*>)
    }

    // ---- B2: a tap right after a redraw from the cache (a probe's bar-back) ----

    @Test fun tapWorksAfterTheBarIsRedrawnFromTheCache() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        warmUp(c, lay)
        c.invalidate(keepRebuilt = true)          // the app was left: the bar is hidden, its layout kept
        assertNotNull(c.onBarBack(1000))          // the bar-back probe draws it again from the cache
        val cmd = c.onTap(0, false, 1100)
        assertNotNull(cmd)
        assertEquals(ig, cmd!!.pkg)
    }

    // ---- M3: a single-shot display crop must agree with the bar's colour ----

    @Test fun displayCropsOfAnotherColourAreNotCommittedAndBackOff() {
        val c = ctl()
        val a = dmLayout("4")
        val b = dmLayout("99")
        warmUp(c, a)                                  // the bar's colour is the dark one (bgColour)
        c.onEvaluate(ig, b, env(1000))
        val req = c.onEvaluate(ig, b, env(1400)).capture!!
        val off = gray(0x40)                          // uniform, but 52 per channel off the bar's colour
        c.onCaptureResult(result(req, tag = "new", bg = List(3) { off }), 1450)
        val o = c.onEvaluate(ig, b, env(1450))
        val st = o.state
        val newCrops = if (st is RenderState.Rebuilt<*>) st.crops.count { (it as String).startsWith("new-") } else 0
        assertEquals(0, newCrops)
        assertEquals(1800L, o.evalAtMs)               // rejected like a disagreement: the next capture waits for the back-off
        assertEquals(off, c.colorsSnapshot()["$ig|true"]) // the colour is still sampled
    }

    @Test fun themeFlipRecoversWithCropsOfTheNewAppearance() {
        val c = ctl()
        val lay = layout(ig, "ig_home")
        val light = 0xFFFFFFFF.toInt()
        warmUp(c, lay)                                // day: the dark colour is sampled and shown
        val nightReq = c.onEvaluate(ig, lay, env(1000, night = true))
        assertTrue(nightReq.state is RenderState.Patch)
        val req = c.onEvaluate(ig, lay, env(1400, night = true)).capture!!
        c.onCaptureResult(result(req, tag = "nt", bg = List(3) { light }), 1450)
        assertEquals(light, c.onEvaluate(ig, lay, env(1450, night = true)).state.rb().color)
        // Back to day: the dark colour is known again, a fresh dark sample agrees with it, and the bar recovers.
        val back = warmUp(c, lay, base = 2_000L)
        assertEquals(bgColour, back.state.rb().color)
        assertEquals(bgColour, c.colorsSnapshot()["$ig|true"])
    }

    @Test fun aSelectionChangeNeverReusesTheOldHighlightedCrop() {
        val c = ctl()
        val home = select(layout(ig, "ig_home"), "feed_tab")
        warmUp(c, home) // Rebuilt with "Home selected"
        // Instagram switched to DMs: no crops yet for "Home unselected" or "DMs selected".
        val dms = select(layout(ig, "ig_home"), "direct_tab")
        val s = c.onEvaluate(ig, dms, env(1000)).state
        // The on-screen "Home selected" crop must not be reused next to a new "DMs selected" one (two highlights).
        assertFalse(s is RenderState.Rebuilt<*>)
    }

    @Test fun aBadgeChangeStillReusesTheOnScreenCropWhileItsReplacementIsCaptured() {
        val c = ctl(); val a = dmLayout("4"); val b = dmLayout("99")
        warmUp(c, a)
        // Same selection, only the DM count changed: the crop on screen may stay until the new one is committed.
        assertTrue(c.onEvaluate(ig, b, env(1000)).state is RenderState.Rebuilt<*>)
    }
}
