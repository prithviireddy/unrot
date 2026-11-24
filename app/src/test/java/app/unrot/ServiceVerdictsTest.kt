package app.unrot

import app.unrot.rules.fullViewId
import app.unrot.service.DisplayVerdict
import app.unrot.service.STALE_READS_TO_DECIDE
import app.unrot.service.actVerdict
import app.unrot.service.blockReadMayDecide
import app.unrot.service.displayResultVerdict
import app.unrot.service.dmFlagAfter
import app.unrot.service.dmFlagStillValid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions of the service's block and action path (see ServiceVerdicts.kt). */
class ServiceVerdictsTest {
    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val targets = mapOf(5 to ig, 6 to yt)

    // ---- blockReadMayDecide ----

    @Test fun readBeforeTheSettleTimeAfterABackDecidesNothing() {
        // a Back at 500: the read at 1000 is inside the 1 s settle
        assertFalse(blockReadMayDecide(capturedAt = 1_000, lastTargetChangeAt = 0, lastActionAt = 500, streak = 0))
        assertFalse(blockReadMayDecide(capturedAt = 1_000, lastTargetChangeAt = 0, lastActionAt = 500, streak = 9))
    }

    @Test fun freshReadDecides() {
        assertTrue(blockReadMayDecide(capturedAt = 2_000, lastTargetChangeAt = 1_500, lastActionAt = 0, streak = 0))
        assertTrue(blockReadMayDecide(capturedAt = 1_500, lastTargetChangeAt = 1_500, lastActionAt = 0, streak = 0))
    }

    @Test fun staleReadsDecideOnlyOnTheThirdInARow() {
        // Reads before the last screen change (at 1500): the streak is the stale reads skipped in a row before this one.
        assertFalse(blockReadMayDecide(capturedAt = 1_000, lastTargetChangeAt = 1_500, lastActionAt = 0, streak = 0))
        assertFalse(blockReadMayDecide(capturedAt = 1_100, lastTargetChangeAt = 1_500, lastActionAt = 0, streak = 1))
        assertEquals(3, STALE_READS_TO_DECIDE)
        assertTrue(blockReadMayDecide(capturedAt = 1_200, lastTargetChangeAt = 1_500, lastActionAt = 0, streak = 2))
    }

    // ---- actVerdict ----

    @Test fun focusedTargetWindowOfTheSamePackageActs() {
        assertTrue(actVerdict(focusedId = 5, targetWindows = targets, pkg = ig, windowId = 5))
        assertTrue(actVerdict(focusedId = 5, targetWindows = targets, pkg = ig, windowId = null))
    }

    @Test fun noFocusedWindowDoesNotAct() {
        assertFalse(actVerdict(focusedId = null, targetWindows = targets, pkg = ig, windowId = 5))
    }

    @Test fun focusedWindowOfAnotherPackageDoesNotAct() {
        assertFalse(actVerdict(focusedId = 6, targetWindows = targets, pkg = ig, windowId = null))
    }

    @Test fun focusedWindowThatIsNotATargetWindowDoesNotAct() {
        // e.g. the shade, a bubble, or our own overlay: no target events came from it
        assertFalse(actVerdict(focusedId = 9, targetWindows = targets, pkg = ig, windowId = null))
    }

    @Test fun focusedWindowOtherThanTheDecisionsDoesNotAct() {
        assertFalse(actVerdict(focusedId = 5, targetWindows = targets, pkg = ig, windowId = 7))
    }

    // ---- displayResultVerdict ----

    @Test fun displayResultWithUnknownActiveWindowFails() {
        assertEquals(DisplayVerdict.UNKNOWN_WINDOW, displayResultVerdict(activeKnown = false, readAt = 100, lastTargetChangeAt = 0))
    }

    @Test fun displayResultFromAReadThatTheAppHasLeftIsStale() {
        assertEquals(DisplayVerdict.STALE, displayResultVerdict(activeKnown = true, readAt = 100, lastTargetChangeAt = 150))
    }

    @Test fun displayResultFromTheCurrentScreenIsAccepted() {
        assertEquals(DisplayVerdict.ACCEPT, displayResultVerdict(activeKnown = true, readAt = 100, lastTargetChangeAt = 100))
        assertEquals(DisplayVerdict.ACCEPT, displayResultVerdict(activeKnown = true, readAt = 100, lastTargetChangeAt = 50))
    }

    // ---- dmFlagAfter ----

    @Test fun dmFlagFollowsTheLastAppliedRead() {
        assertTrue(dmFlagAfter(setOf("dm")))
        assertTrue(dmFlagAfter(setOf("dm", "other")))
        assertFalse(dmFlagAfter(emptySet()))
        assertFalse(dmFlagAfter(setOf("other")))
    }

    @Test fun skippedReadClearsTheDmFlag() {
        assertFalse(dmFlagAfter(null))
    }

    // ---- dmFlagStillValid ----

    @Test fun dmFlagHoldsWhileAnInstagramWindowIsActive() {
        assertTrue(dmFlagStillValid(5, targets))
    }

    @Test fun dmFlagClearsWhenAnotherTargetAppIsActive() {
        assertFalse(dmFlagStillValid(6, targets))
    }

    @Test fun dmFlagClearsWhenANonTargetWindowIsActive() {
        // the launcher, or any window that never sent a target event
        assertFalse(dmFlagStillValid(42, targets))
    }

    @Test fun dmFlagHoldsWhenTheActiveWindowIsUnknown() {
        assertTrue(dmFlagStillValid(null, targets))
    }

    // ---- fullViewId ----

    @Test fun shortViewIdGetsThePackagePrefix() {
        assertEquals("$ig:id/tab_bar", fullViewId("tab_bar", ig))
    }

    @Test fun fullViewIdIsKept() {
        assertEquals("$yt:id/bottom_bar_container", fullViewId("$yt:id/bottom_bar_container", yt))
    }
}
