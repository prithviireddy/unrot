package app.unrot.service

/*
 * Pure rules for keeping the service alive and telling the user when it stopped (decisions 0011 and 0012, plan 0011 steps 1, 3
 * and 2's display rules, plan 0013 step 3). The shell gathers the facts and applies the answers.
 */

/** What the foreground notification must do after a settings change. */
enum class FgsAction { START, STOP, NONE }

/**
 * The foreground notification starts when the features go from all off to any on, and is removed when they go from any on
 * to all off. Nothing else (a counter, a colour, a heartbeat) changes it.
 */
fun fgsTransition(prevOn: Boolean, nowOn: Boolean): FgsAction = when {
    !prevOn && nowOn -> FgsAction.START
    prevOn && !nowOn -> FgsAction.STOP
    else -> FgsAction.NONE
}

/**
 * Whether a failed foreground start is retried now: the features want it, it is not running, and at least a minute has passed
 * since the last retry ([lastAtMs]). Decision 0012.
 */
fun fgsRetryDue(wanted: Boolean, running: Boolean, nowMs: Long, lastAtMs: Long): Boolean =
    wanted && !running && nowMs - lastAtMs >= 60_000L

/** The foreground notification's features: Instagram or YouTube blocking (caught-up alone does not count). */
fun foregroundWanted(instagramEnabled: Boolean, youtubeEnabled: Boolean): Boolean = instagramEnabled || youtubeEnabled

/** Blocking counts as on once onboarding is done and Instagram or YouTube blocking is on (decision 0011, round 2 N2). */
fun blockingOn(onboarded: Boolean, instagramEnabled: Boolean, youtubeEnabled: Boolean): Boolean =
    onboarded && (instagramEnabled || youtubeEnabled)

/** Grace after the app comes to the front before "switched on but not running" may show: the service may still be binding. */
const val RESUME_GRACE_MS = 2_500L

/**
 * "Switched on, but your phone stopped it": the accessibility switch is on, the service is neither bound (in the enabled
 * services list) nor running, and the app has been in front for at least the grace time ([sinceResumeMs]).
 */
fun notRunningWhileOn(enabled: Boolean, bound: Boolean, running: Boolean, sinceResumeMs: Long): Boolean =
    enabled && !bound && !running && sinceResumeMs >= RESUME_GRACE_MS

/** A kill card stays on Home for this long after the kill it reports (then it goes away on its own). */
const val KILL_CARD_MS = 7L * 24 * 60 * 60 * 1000

/** At most one kill notification per this many ms. */
const val KILL_NOTICE_GAP_MS = 6L * 60 * 60 * 1000

/** Whether a kill notice at [killAtMs] is still shown at [nowMs], given whether the user dismissed or fixed it. */
fun killCardVisible(killAtMs: Long, nowMs: Long, dismissed: Boolean): Boolean =
    killAtMs > 0 && !dismissed && nowMs - killAtMs < KILL_CARD_MS

/** Whether the rate limit allows a new kill notification: none yet, or the last one is at least [KILL_NOTICE_GAP_MS] old. */
fun killNoticeDue(lastNoticeAtMs: Long, nowMs: Long): Boolean = lastNoticeAtMs <= 0L || nowMs - lastNoticeAtMs >= KILL_NOTICE_GAP_MS
