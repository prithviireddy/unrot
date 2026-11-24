package app.unrot

import app.unrot.service.FgsAction
import app.unrot.service.KILL_CARD_MS
import app.unrot.service.KILL_NOTICE_GAP_MS
import app.unrot.service.RESUME_GRACE_MS
import app.unrot.service.blockingOn
import app.unrot.service.foregroundWanted
import app.unrot.service.fgsTransition
import app.unrot.service.killCardVisible
import app.unrot.service.killNoticeDue
import app.unrot.service.notRunningWhileOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the foreground notification, the "switched on but not running" check and the kill card (decision 0011). */
class ServiceHealthTest {

    // ---- fgsTransition ----

    @Test fun allOffToAnyOnStarts() {
        assertEquals(FgsAction.START, fgsTransition(prevOn = false, nowOn = true))
    }

    @Test fun anyOnToAllOffStops() {
        assertEquals(FgsAction.STOP, fgsTransition(prevOn = true, nowOn = false))
    }

    @Test fun noChangeDoesNothing() {
        assertEquals(FgsAction.NONE, fgsTransition(prevOn = true, nowOn = true))
        assertEquals(FgsAction.NONE, fgsTransition(prevOn = false, nowOn = false))
    }

    // ---- foregroundWanted and blockingOn ----

    @Test fun instagramOrYouTubeKeepsTheNotificationOn() {
        assertTrue(foregroundWanted(instagramEnabled = true, youtubeEnabled = false))
        assertTrue(foregroundWanted(instagramEnabled = false, youtubeEnabled = true))
        assertFalse(foregroundWanted(instagramEnabled = false, youtubeEnabled = false))
    }

    @Test fun blockingNeedsOnboardingAndATarget() {
        assertTrue(blockingOn(onboarded = true, instagramEnabled = true, youtubeEnabled = false))
        assertFalse(blockingOn(onboarded = false, instagramEnabled = true, youtubeEnabled = true))
        assertFalse(blockingOn(onboarded = true, instagramEnabled = false, youtubeEnabled = false))
    }

    // ---- notRunningWhileOn ----

    @Test fun switchedOnButNotBoundAfterTheGraceShowsNotRunning() {
        assertTrue(notRunningWhileOn(enabled = true, bound = false, running = false, sinceResumeMs = RESUME_GRACE_MS))
    }

    @Test fun withinTheGraceItDoesNotShow() {
        assertFalse(notRunningWhileOn(enabled = true, bound = false, running = false, sinceResumeMs = RESUME_GRACE_MS - 1))
    }

    @Test fun boundOrRunningIsNeverNotRunning() {
        assertFalse(notRunningWhileOn(enabled = true, bound = true, running = false, sinceResumeMs = Long.MAX_VALUE))
        assertFalse(notRunningWhileOn(enabled = true, bound = false, running = true, sinceResumeMs = Long.MAX_VALUE))
    }

    @Test fun switchedOffIsNeverNotRunning() {
        assertFalse(notRunningWhileOn(enabled = false, bound = false, running = false, sinceResumeMs = Long.MAX_VALUE))
    }

    // ---- kill card ----

    private val day = 24L * 60 * 60 * 1000

    @Test fun killCardShowsWithinSevenDays() {
        assertTrue(killCardVisible(killAtMs = 1_000, nowMs = 1_000 + 6 * day, dismissed = false))
    }

    @Test fun killCardGoesAfterSevenDays() {
        assertFalse(killCardVisible(killAtMs = 1_000, nowMs = 1_000 + KILL_CARD_MS, dismissed = false))
    }

    @Test fun dismissedKillCardStaysHidden() {
        assertFalse(killCardVisible(killAtMs = 1_000, nowMs = 2_000, dismissed = true))
    }

    @Test fun noKillRecordShowsNoCard() {
        assertFalse(killCardVisible(killAtMs = 0, nowMs = 2_000, dismissed = false))
    }

    // ---- kill notification rate limit ----

    @Test fun firstKillNoticeIsDue() {
        assertTrue(killNoticeDue(lastNoticeAtMs = 0, nowMs = 1_000))
    }

    @Test fun killNoticeWaitsSixHours() {
        assertFalse(killNoticeDue(lastNoticeAtMs = 1_000, nowMs = 1_000 + KILL_NOTICE_GAP_MS - 1))
        assertTrue(killNoticeDue(lastNoticeAtMs = 1_000, nowMs = 1_000 + KILL_NOTICE_GAP_MS))
    }
}
