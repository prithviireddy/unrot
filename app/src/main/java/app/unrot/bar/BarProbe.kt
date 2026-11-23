package app.unrot.bar

import app.unrot.rules.Box

/** The bar-back probe's outcome: the bar is back, a bar node is appearing, not back, or the user has left (stop probing). */
enum class ProbeOutcome { BACK, APPEARING, NOT_BACK, STOP }

/**
 * One window of the window list as the probe sees it (metadata only). [targetPkg] is the target app whose events came from
 * this window, or null. [own] is one of our overlay windows.
 */
data class ProbeWin(
    val id: Int, val layer: Int, val active: Boolean, val ime: Boolean, val own: Boolean, val targetPkg: String?, val box: Box,
)

/** The result of [BarProbe.acceptResult]: the outcome after the settle and stale rules, and whether to probe again at once. */
data class AcceptedProbe(val outcome: ProbeOutcome, val reason: String, val kick: Boolean)

/**
 * The bar-back probe's decisions, as pure functions: the window-list verdict, the node verdict, the cadence, the acceptance
 * of a result by its start time, and when a provisional bar may be shown. The shell gathers the facts (on the probe thread
 * for the reads, on main for the rest) and applies the answers.
 */
object BarProbe {
    /** Tolerance of the bar's position, in screen pixels. */
    const val SLOP_PX = 4

    /** Cadence: [FIRST_EVERY_MS] for [FIRST_MS] after a start or kick, [MID_EVERY_MS] until [MID_MS], then [SLOW_EVERY_MS]. */
    const val FIRST_EVERY_MS = 50L
    const val FIRST_MS = 1_000L
    const val MID_EVERY_MS = 150L
    const val MID_MS = 3_000L
    const val SLOW_EVERY_MS = 1_000L

    /** A probe's episode is capped this long after its own start or last kick (the no-bar episode's start does not count). */
    const val EPISODE_MS = 60_000L

    /** True when a probe that started or was last kicked at [fastFromMs] is past its cap at [nowMs]. */
    fun episodeOver(nowMs: Long, fastFromMs: Long): Boolean = nowMs - fastFromMs > EPISODE_MS

    /** Interval to the next probe, from the time since the probe started or was last kicked. */
    fun intervalMs(sinceFastStartMs: Long): Long = when {
        sinceFastStartMs < FIRST_MS -> FIRST_EVERY_MS
        sinceFastStartMs < MID_MS -> MID_EVERY_MS
        else -> SLOW_EVERY_MS
    }

    /**
     * Window-list verdict. Null when the probed window is the active one, its root may be read. Otherwise the outcome and the
     * reason for the trace:
     * - another window of a target app is active: not back, keep probing; one of our own windows: not back; else: stop;
     * - the keyboard is up: not back, nothing read;
     * - the active window is not tracked as the probed app's: not back, nothing read;
     * - a window that is not ours sits above the probed window over the tabs box (another app's picture-in-picture, a
     *   bubble, an exiting sheet, or another window of the target app): not back.
     */
    fun windowVerdict(wins: List<ProbeWin>, probedId: Int, probedPkg: String, tabs: Box): Pair<ProbeOutcome, String>? {
        val active = wins.firstOrNull { it.active } ?: return ProbeOutcome.NOT_BACK to "noActive"
        if (active.id != probedId) {
            return when {
                active.targetPkg != null -> ProbeOutcome.NOT_BACK to "otherTargetWindow"
                active.own -> ProbeOutcome.NOT_BACK to "own"
                else -> ProbeOutcome.STOP to "left"
            }
        }
        if (wins.any { it.ime }) return ProbeOutcome.NOT_BACK to "ime"
        if (active.targetPkg != probedPkg) return ProbeOutcome.NOT_BACK to "untrackedWindow"
        val probed = wins.firstOrNull { it.id == probedId } ?: return ProbeOutcome.NOT_BACK to "noActive"
        val above = wins.any { w ->
            w.id != probedId && !w.own && w.layer > probed.layer && w.box.intersects(tabs)
        }
        return if (above) ProbeOutcome.NOT_BACK to "windowAbove" else null
    }

    /**
     * Node verdict, from the bounds of the visible nodes with the bar's view id. BACK when one is at the container (within
     * [SLOP_PX]). APPEARING when one is not there yet, but its top and bottom match the tabs box's band (any horizontal
     * offset), or it meets the container (the bar slides in). Otherwise not back.
     */
    fun nodeVerdict(visible: List<Box>, container: Box, tabs: Box): Pair<ProbeOutcome, String> {
        if (visible.any { near(it, container) }) return ProbeOutcome.BACK to ""
        val appearing = visible.any { b ->
            (kotlin.math.abs(b.t - tabs.t) <= SLOP_PX && kotlin.math.abs(b.b - tabs.b) <= SLOP_PX) || b.intersects(container)
        }
        return if (appearing) ProbeOutcome.APPEARING to "appearing" else ProbeOutcome.NOT_BACK to "noBar"
    }

    private fun near(a: Box, c: Box): Boolean =
        kotlin.math.abs(a.l - c.l) <= SLOP_PX && kotlin.math.abs(a.t - c.t) <= SLOP_PX &&
            kotlin.math.abs(a.r - c.r) <= SLOP_PX && kotlin.math.abs(a.b - c.b) <= SLOP_PX

    /**
     * A probe result that a read started at [startedMs] produced. Before [settleUntilMs] (a re-entry from outside the app, the
     * app's first frames still animate), a back or appearing result is not back. A read started before the target's last
     * screen change ([lastChangeMs]) describes the old screen: not back, and probe again at once ([AcceptedProbe.kick]).
     */
    fun acceptResult(outcome: ProbeOutcome, reason: String, startedMs: Long, settleUntilMs: Long, lastChangeMs: Long): AcceptedProbe {
        if (outcome != ProbeOutcome.BACK && outcome != ProbeOutcome.APPEARING) return AcceptedProbe(outcome, reason, false)
        if (startedMs < settleUntilMs) return AcceptedProbe(ProbeOutcome.NOT_BACK, "settle", false)
        if (startedMs < lastChangeMs) return AcceptedProbe(ProbeOutcome.NOT_BACK, "stale", true)
        return AcceptedProbe(outcome, reason, false)
    }

    /**
     * Whether an accepted result may show the remembered bar provisionally (untouchable, see BlockerService.showProvisional):
     * only an appearing bar, with no keyboard, no bar already shown provisionally, the layout drawable from cache, not for a
     * re-entry from outside the app, and only while the probe is [armed]: started or kicked by a return to the bar's window
     * (not by a bar sliding away, nor by a probe that already showed its one provisional bar).
     */
    fun provisionalFromProbe(
        outcome: ProbeOutcome, ime: Boolean, provisional: Boolean, canRebuild: Boolean, outsideReentry: Boolean, armed: Boolean,
    ): Boolean = outcome == ProbeOutcome.APPEARING && !ime && !provisional && canRebuild && !outsideReentry && armed
}
