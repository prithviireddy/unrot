package app.unrot.service

/*
 * Pure decisions of [BlockerService], kept out of the Android shell so they run in JVM unit tests. The shell gathers the
 * facts (window list, clocks, markers) and applies the answers. Times are elapsed ms of the service unless a name says wall.
 */

/** After a Back (or a tap), a read taken within this long describes the screen that was left, or one still settling. */
const val BLOCK_SETTLE_MS = 1_000L

/** Consecutive full reads skipped as stale that show the same block target: the next one decides anyway (starvation bound). */
const val STALE_READS_TO_DECIDE = 3

/**
 * Whether a full read may decide a block. A read taken before the last Back (or within [BLOCK_SETTLE_MS] after it) decides
 * nothing. A read taken before the target app last changed screen (or a tap was forwarded) is stale and decides nothing,
 * unless [streak] (the stale reads skipped in a row that showed the same target, before this one) reaches the bound.
 */
fun blockReadMayDecide(capturedAt: Long, lastTargetChangeAt: Long, lastActionAt: Long, streak: Int): Boolean {
    if (capturedAt < lastActionAt + BLOCK_SETTLE_MS) return false
    if (capturedAt >= lastTargetChangeAt) return true
    return streak + 1 >= STALE_READS_TO_DECIDE
}

/**
 * Whether an action (Back, or a tap) may run for a decision of [pkg] taken from window [windowId] (null when unknown). The
 * focused window ([focusedId], null when none is reported) must be a target window of [pkg], and the decision's window when
 * it is known. Our overlays are not focusable, so they never count. One window-list read (main thread) per action.
 */
fun actVerdict(focusedId: Int?, targetWindows: Map<Int, String>, pkg: String, windowId: Int?): Boolean {
    if (focusedId == null) return false
    if (targetWindows[focusedId] != pkg) return false
    return windowId == null || focusedId == windowId
}

/** The verdict on a display (single shot) capture's result. */
enum class DisplayVerdict { ACCEPT, UNKNOWN_WINDOW, STALE }

/**
 * A display capture's result is unusable when the active window is unknown (the shot may not be the bar's window), or when
 * the target app changed screen after the read the capture was requested with ([readAt]).
 */
fun displayResultVerdict(activeKnown: Boolean, readAt: Long, lastTargetChangeAt: Long): DisplayVerdict = when {
    !activeKnown -> DisplayVerdict.UNKNOWN_WINDOW
    lastTargetChangeAt > readAt -> DisplayVerdict.STALE
    else -> DisplayVerdict.ACCEPT
}

/** Instagram's package: the DM context and the Reels block are Instagram's. */
const val INSTAGRAM_PKG = "com.instagram.android"

/**
 * The DM flag after a read: set when the last applied full read saw a DM marker, cleared by a read without one and by a read
 * that was skipped (null markers). While it is set, Instagram events keep the DM context fresh (see BlockerService).
 */
fun dmFlagAfter(markers: Set<String>?): Boolean = markers != null && "dm" in markers

/**
 * Whether a set DM flag still holds, given the active window ([activeWindowId], null when unknown) and the target windows
 * (window id to package). It is cleared once the active window is known and is not an Instagram window of [targetWindows]:
 * the user has left the chat. An unknown active window keeps it (no read was made to say the user left). The caller passes
 * null for one of our own windows (the bar, when TalkBack focuses it), which is not a sign that the user left.
 */
fun dmFlagStillValid(activeWindowId: Int?, targetWindows: Map<Int, String>): Boolean {
    if (activeWindowId == null) return true
    return targetWindows[activeWindowId] == INSTAGRAM_PKG
}
