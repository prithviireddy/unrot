package app.unrot.service

/*
 * Pure kill detection (decision 0011, plan 0011 step 2). The shell reads the previous run's record and the process exit records,
 * then applies [KillCheck.decide]. Times are wall-clock ms. The reason codes are ApplicationExitInfo.REASON_* (API 30+); they are
 * copied here so the rules run in JVM tests (the values are checked against android.jar).
 */

/** The previous run's record, as written by [ServiceState] at connect, heartbeat and settings changes. */
data class PrevRecord(
    val clean: Boolean,
    val aliveAtMs: Long,
    val connectAtMs: Long,
    val boot: Long,
    val version: Long,
    val lastUpdateMs: Long,
    val blockingOn: Boolean,
)

/** One process exit record of this app: when it happened (wall ms) and its ApplicationExitInfo reason. */
data class ExitRecord(val atMs: Long, val reason: Int)

sealed interface KillVerdict {
    /** Nothing to tell the user. */
    data object None : KillVerdict
    /** The phone (a cleaner, the task manager, or the system) stopped the service while blocking was on. */
    data class Killed(val atMs: Long) : KillVerdict
    /** Unrot itself crashed (an uncaught exception, a native crash, an ANR, or an initialisation failure). */
    data class Crashed(val atMs: Long) : KillVerdict
}

object KillCheck {
    const val REASON_UNKNOWN = 0
    const val REASON_EXIT_SELF = 1
    const val REASON_SIGNALED = 2
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_INITIALIZATION_FAILURE = 7
    const val REASON_PERMISSION_CHANGE = 8
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    const val REASON_USER_REQUESTED = 10
    const val REASON_USER_STOPPED = 11
    const val REASON_DEPENDENCY_DIED = 12
    const val REASON_OTHER = 13
    const val REASON_FREEZER = 14
    const val REASON_PACKAGE_STATE_CHANGE = 15
    const val REASON_PACKAGE_UPDATED = 16

    /** Reasons that are not a kill and not our bug: they never produce a card. */
    private val IGNORED = setOf(
        REASON_EXIT_SELF, REASON_PERMISSION_CHANGE, REASON_USER_STOPPED, REASON_DEPENDENCY_DIED, REASON_PACKAGE_STATE_CHANGE,
    )
    /** Reasons that are our bug (N3). */
    private val CRASHES = setOf(REASON_CRASH, REASON_CRASH_NATIVE, REASON_ANR, REASON_INITIALIZATION_FAILURE)

    /**
     * What to tell the user about the previous run. [prev] is null when there is no previous record. [exits] are this app's
     * exit records (any age), or null below API 30, where the previous heartbeat is the only evidence.
     *
     * No card when: there is no previous record; the previous run ended clean; blocking was off; the device rebooted
     * ([boot] changed); the app was updated ([version] or [lastUpdateMs] changed); or, on API 30+, no exit record is newer
     * than the previous connect, or a newer one says the app was updated (REASON_PACKAGE_UPDATED) or is one of [IGNORED].
     * Crashed when a relevant record is a crash; otherwise Killed. Either way the time is the earliest relevant record's: the
     * first exit after the previous connect is the one that stopped that run.
     */
    fun decide(
        prev: PrevRecord?,
        boot: Long,
        version: Long,
        lastUpdateMs: Long,
        exits: List<ExitRecord>?,
    ): KillVerdict {
        if (prev == null) return KillVerdict.None
        if (prev.clean) return KillVerdict.None
        if (!prev.blockingOn) return KillVerdict.None
        if (boot != prev.boot) return KillVerdict.None
        if (version != prev.version || lastUpdateMs != prev.lastUpdateMs) return KillVerdict.None
        if (exits == null) return KillVerdict.Killed(prev.aliveAtMs)

        val newer = exits.filter { it.atMs > prev.connectAtMs }
        if (newer.any { it.reason == REASON_PACKAGE_UPDATED }) return KillVerdict.None
        val relevant = newer.filter { it.reason !in IGNORED }
        if (relevant.isEmpty()) return KillVerdict.None
        val crash = relevant.filter { it.reason in CRASHES }.minByOrNull { it.atMs }
        if (crash != null) return KillVerdict.Crashed(crash.atMs)
        return KillVerdict.Killed(relevant.minByOrNull { it.atMs }!!.atMs)
    }
}
