package app.unrot.policy

import app.unrot.data.Settings
import app.unrot.rules.Detection
import app.unrot.rules.Target

sealed interface Decision {
    data object None : Decision
    /** Allowed once because it was opened from a DM. */
    data object AllowDm : Decision
    data class Block(val target: Target) : Decision
}

/**
 * Decides what to do with each detected screen. Pure logic with an injectable clock so it can be unit-tested.
 *
 * DM exception: if the user was in a DM thread within [DM_WINDOW_MS] before a DM-eligible reel viewer
 * opened, that first reel plays. A swipe inside the viewer (a scroll event from one of the target's
 * [Target.swipeViewIds]) ends the pass and blocks.
 */
class Policy(private val clock: () -> Long = System::currentTimeMillis) {
    private var lastDmSeenAt = Long.MIN_VALUE / 2
    private var inBlockedScreen = false
    private var dmPassSince: Long? = null
    private var currentBlocks: List<Target> = emptyList()

    val dmPassActive get() = dmPassSince != null

    fun onScreen(det: Detection, s: Settings): Decision {
        val now = clock()
        if ("dm" in det.markers) lastDmSeenAt = now

        val blocks = det.blocks.filter { s.featureEnabled(it.feature) }
        currentBlocks = blocks
        if (blocks.isEmpty()) {
            inBlockedScreen = false
            dmPassSince = null
            return Decision.None
        }

        val entering = !inBlockedScreen
        inBlockedScreen = true
        val allEligible = blocks.all { it.dmEligible }

        if (entering && s.dmException && allEligible && now - lastDmSeenAt <= DM_WINDOW_MS) {
            dmPassSince = now
            return Decision.AllowDm
        }
        if (dmPassSince != null && allEligible) return Decision.AllowDm

        dmPassSince = null
        return Decision.Block(blocks.first())
    }

    /** A TYPE_VIEW_SCROLLED event arrived from a view with id [sourceViewId]. */
    fun onScroll(sourceViewId: String?): Decision {
        val since = dmPassSince ?: return Decision.None
        if (clock() - since < SWIPE_GRACE_MS) return Decision.None // pager settling on open
        val target = currentBlocks.firstOrNull { t ->
            t.swipeViewIds.isEmpty() || (sourceViewId != null && sourceViewId in t.swipeViewIds)
        } ?: return Decision.None
        dmPassSince = null
        return Decision.Block(target)
    }

    companion object {
        const val DM_WINDOW_MS = 5_000L
        const val SWIPE_GRACE_MS = 1_200L
    }
}
