package app.unrot

import app.unrot.bar.BarProbe
import app.unrot.bar.ProbeOutcome
import app.unrot.bar.ProbeWin
import app.unrot.rules.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarProbeTest {
    private val tabs = Box(0, 1800, 1080, 1920)
    private val container = Box(0, 1780, 1080, 1920)
    private val pkg = "com.instagram.android"

    /** The probed window (id 7, layer 10, active, tracked as the probed app) and the given extra windows. */
    private fun wins(vararg extra: ProbeWin, probedActive: Boolean = true): List<ProbeWin> = listOf(
        ProbeWin(7, 10, probedActive, ime = false, own = false, targetPkg = pkg, box = Box(0, 0, 1080, 1920)),
    ) + extra

    private fun above(id: Int = 9, box: Box = tabs, own: Boolean = false, targetPkg: String? = null, layer: Int = 20) =
        ProbeWin(id, layer, active = false, ime = false, own = own, targetPkg = targetPkg, box = box)

    // ---- cadence ----

    @Test fun cadenceIs50msFirstThenMidThenSlow() {
        assertEquals(50L, BarProbe.intervalMs(0))
        assertEquals(50L, BarProbe.intervalMs(999))
        assertEquals(150L, BarProbe.intervalMs(1_000))
        assertEquals(150L, BarProbe.intervalMs(2_999))
        assertEquals(1_000L, BarProbe.intervalMs(3_000))
        assertEquals(1_000L, BarProbe.intervalMs(60_000))
    }

    // ---- window-list verdict ----

    @Test fun probedActiveWithNothingAboveReadsItsNodes() {
        assertNull(BarProbe.windowVerdict(wins(), 7, pkg, tabs))
    }

    @Test fun anotherTargetWindowActiveIsNotBack() {
        val w = listOf(ProbeWin(8, 20, true, false, false, targetPkg = pkg, box = tabs))
        assertEquals(ProbeOutcome.NOT_BACK to "otherTargetWindow", BarProbe.windowVerdict(w, 7, pkg, tabs))
    }

    @Test fun ownWindowActiveIsNotBack() {
        val w = listOf(ProbeWin(8, 20, true, false, own = true, targetPkg = null, box = tabs))
        assertEquals(ProbeOutcome.NOT_BACK to "own", BarProbe.windowVerdict(w, 7, pkg, tabs))
    }

    @Test fun anotherAppActiveStops() {
        val w = listOf(ProbeWin(8, 20, true, false, false, targetPkg = null, box = Box(0, 0, 1080, 1920)))
        assertEquals(ProbeOutcome.STOP to "left", BarProbe.windowVerdict(w, 7, pkg, tabs))
    }

    @Test fun keyboardUpIsNotBackWithoutAnyRead() {
        val w = wins(ProbeWin(9, 30, false, ime = true, own = false, targetPkg = null, box = Box(0, 1000, 1080, 1920)))
        assertEquals(ProbeOutcome.NOT_BACK to "ime", BarProbe.windowVerdict(w, 7, pkg, tabs))
    }

    @Test fun probedWindowNotTrackedAsThisAppIsNotRead() {
        // The probed id is active but its events came from another target app (a YouTube window with the same id).
        val w = listOf(ProbeWin(7, 10, true, false, false, targetPkg = "com.google.android.youtube", box = tabs))
        assertEquals(ProbeOutcome.NOT_BACK to "untrackedWindow", BarProbe.windowVerdict(w, 7, pkg, tabs))
    }

    @Test fun foreignWindowAboveTheTabsIsNotBack() {
        // Another app's picture-in-picture: a higher layer over the tabs box.
        val pip = above(box = Box(600, 1500, 1080, 1950))
        assertEquals(ProbeOutcome.NOT_BACK to "windowAbove", BarProbe.windowVerdict(wins(pip), 7, pkg, tabs))
    }

    @Test fun targetWindowAboveTheTabsIsNotBackToo() {
        val story = above(targetPkg = pkg)
        assertEquals(ProbeOutcome.NOT_BACK to "windowAbove", BarProbe.windowVerdict(wins(story), 7, pkg, tabs))
    }

    @Test fun ownWindowAboveIsIgnored() {
        assertNull(BarProbe.windowVerdict(wins(above(own = true)), 7, pkg, tabs))
    }

    @Test fun windowAboveNotOverTheTabsIsIgnored() {
        assertNull(BarProbe.windowVerdict(wins(above(box = Box(0, 0, 1080, 800))), 7, pkg, tabs))
    }

    @Test fun windowBelowTheProbedOneIsIgnored() {
        assertNull(BarProbe.windowVerdict(wins(above(layer = 5)), 7, pkg, tabs))
    }

    // ---- node verdict ----

    @Test fun nodeAtTheContainerIsBack() {
        assertEquals(ProbeOutcome.BACK to "", BarProbe.nodeVerdict(listOf(Box(2, 1782, 1078, 1918)), container, tabs))
    }

    @Test fun nodeInTheTabsBandElsewhereIsAppearing() {
        // Same top and bottom as the tabs box, shifted sideways: not at the container, but in its band.
        val n = Box(300, 1801, 1380, 1919)
        assertEquals(ProbeOutcome.APPEARING, BarProbe.nodeVerdict(listOf(n), container, tabs).first)
    }

    @Test fun nodeSlidingIntoTheContainerIsAppearing() {
        val n = Box(0, 1850, 1080, 2010)
        assertEquals(ProbeOutcome.APPEARING, BarProbe.nodeVerdict(listOf(n), container, tabs).first)
    }

    @Test fun farNodeIsNotBack() {
        assertEquals(ProbeOutcome.NOT_BACK to "noBar", BarProbe.nodeVerdict(listOf(Box(0, 0, 100, 100)), container, tabs))
        assertEquals(ProbeOutcome.NOT_BACK to "noBar", BarProbe.nodeVerdict(emptyList(), container, tabs))
    }

    // ---- acceptance by start time ----

    @Test fun resultsInsideTheReentrySettleAreNotBack() {
        val a = BarProbe.acceptResult(ProbeOutcome.BACK, "", startedMs = 1_200, settleUntilMs = 1_450, lastChangeMs = 0)
        assertEquals(ProbeOutcome.NOT_BACK, a.outcome)
        assertEquals("settle", a.reason)
        assertFalse(a.kick)
        val b = BarProbe.acceptResult(ProbeOutcome.APPEARING, "appearing", 1_200, 1_450, 0)
        assertEquals(ProbeOutcome.NOT_BACK, b.outcome)
    }

    @Test fun resultsFromAfterTheSettleAreAccepted() {
        val a = BarProbe.acceptResult(ProbeOutcome.BACK, "", startedMs = 1_450, settleUntilMs = 1_450, lastChangeMs = 0)
        assertEquals(ProbeOutcome.BACK, a.outcome)
    }

    @Test fun aResultStartedBeforeTheLastScreenChangeIsStaleAndKicksAgain() {
        val a = BarProbe.acceptResult(ProbeOutcome.BACK, "", startedMs = 900, settleUntilMs = 0, lastChangeMs = 1_000)
        assertEquals(ProbeOutcome.NOT_BACK, a.outcome)
        assertEquals("stale", a.reason)
        assertTrue(a.kick)
    }

    @Test fun notBackResultsPassThroughUnchanged() {
        val a = BarProbe.acceptResult(ProbeOutcome.NOT_BACK, "noBar", startedMs = 0, settleUntilMs = 9_999, lastChangeMs = 9_999)
        assertEquals(ProbeOutcome.NOT_BACK, a.outcome)
        assertEquals("noBar", a.reason)
        assertFalse(a.kick)
    }

    // ---- episode cap: from the probe's own start or kick ----

    @Test fun theCapCountsFromTheProbeStartNotFromTheNoBarEpisode() {
        // A no-bar episode began 10 minutes ago, but the probe started 1 s ago: not over.
        assertFalse(BarProbe.episodeOver(nowMs = 601_000, fastFromMs = 600_000))
    }

    @Test fun theCapIsOverAfterSixtySecondsFromTheLastKick() {
        assertFalse(BarProbe.episodeOver(nowMs = 60_000, fastFromMs = 0))
        assertTrue(BarProbe.episodeOver(nowMs = 60_001, fastFromMs = 0))
        // A kick restarts the count.
        assertFalse(BarProbe.episodeOver(nowMs = 100_000, fastFromMs = 70_000))
    }

    @Test fun anUnarmedProbeNeverShowsProvisionally() {
        assertFalse(BarProbe.provisionalFromProbe(ProbeOutcome.APPEARING, ime = false, provisional = false, canRebuild = true, outsideReentry = false, armed = false))
    }

    // ---- when a provisional bar may show ----

    @Test fun appearingAfterAnInsideReturnShowsProvisionally() {
        assertTrue(BarProbe.provisionalFromProbe(ProbeOutcome.APPEARING, ime = false, provisional = false, canRebuild = true, outsideReentry = false, armed = true))
    }

    @Test fun neverProvisionalFromOutsideTheApp() {
        assertFalse(BarProbe.provisionalFromProbe(ProbeOutcome.APPEARING, ime = false, provisional = false, canRebuild = true, outsideReentry = true, armed = true))
    }

    @Test fun noProvisionalWhenTheKeyboardIsUpOrOneIsShown() {
        assertFalse(BarProbe.provisionalFromProbe(ProbeOutcome.APPEARING, ime = true, provisional = false, canRebuild = true, outsideReentry = false, armed = true))
        assertFalse(BarProbe.provisionalFromProbe(ProbeOutcome.APPEARING, ime = false, provisional = true, canRebuild = true, outsideReentry = false, armed = true))
        assertFalse(BarProbe.provisionalFromProbe(ProbeOutcome.APPEARING, ime = false, provisional = false, canRebuild = false, outsideReentry = false, armed = true))
    }

    @Test fun onlyAnAppearingResultShowsProvisionally() {
        assertFalse(BarProbe.provisionalFromProbe(ProbeOutcome.BACK, ime = false, provisional = false, canRebuild = true, outsideReentry = false, armed = true))
        assertFalse(BarProbe.provisionalFromProbe(ProbeOutcome.NOT_BACK, ime = false, provisional = false, canRebuild = true, outsideReentry = false, armed = true))
    }
}
