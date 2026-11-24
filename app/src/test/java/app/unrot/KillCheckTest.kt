package app.unrot

import android.app.ApplicationExitInfo
import app.unrot.service.ExitRecord
import app.unrot.service.KillCheck
import app.unrot.service.KillCheck.REASON_ANR
import app.unrot.service.KillCheck.REASON_CRASH
import app.unrot.service.KillCheck.REASON_CRASH_NATIVE
import app.unrot.service.KillCheck.REASON_DEPENDENCY_DIED
import app.unrot.service.KillCheck.REASON_EXIT_SELF
import app.unrot.service.KillCheck.REASON_INITIALIZATION_FAILURE
import app.unrot.service.KillCheck.REASON_LOW_MEMORY
import app.unrot.service.KillCheck.REASON_OTHER
import app.unrot.service.KillCheck.REASON_PACKAGE_STATE_CHANGE
import app.unrot.service.KillCheck.REASON_PACKAGE_UPDATED
import app.unrot.service.KillCheck.REASON_PERMISSION_CHANGE
import app.unrot.service.KillCheck.REASON_SIGNALED
import app.unrot.service.KillCheck.REASON_USER_REQUESTED
import app.unrot.service.KillCheck.REASON_USER_STOPPED
import app.unrot.service.KillVerdict
import app.unrot.service.PrevRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/** The kill detection rules (decision 0011, N3). The record: connect at 1_000, last alive at 5_000, same boot, version and update. */
class KillCheckTest {
    private val boot = 7L
    private val version = 3L
    private val update = 111L

    private fun prev(
        clean: Boolean = false,
        blockingOn: Boolean = true,
        connectAt: Long = 1_000L,
        aliveAt: Long = 5_000L,
        boot: Long = this.boot,
        version: Long = this.version,
        update: Long = this.update,
    ) = PrevRecord(clean, aliveAt, connectAt, boot, version, update, blockingOn)

    private fun decide(prev: PrevRecord?, exits: List<ExitRecord>? = emptyList()) =
        KillCheck.decide(prev, boot = boot, version = version, lastUpdateMs = update, exits = exits)

    private fun exit(at: Long, reason: Int) = ExitRecord(at, reason)

    // ---- no card ----

    @Test fun noPreviousRecordGivesNoCard() {
        assertEquals(KillVerdict.None, decide(null, listOf(exit(2_000, REASON_USER_REQUESTED))))
    }

    @Test fun cleanPreviousRunGivesNoCard() {
        assertEquals(KillVerdict.None, decide(prev(clean = true), listOf(exit(2_000, REASON_USER_REQUESTED))))
    }

    @Test fun blockingOffGivesNoCard() {
        assertEquals(KillVerdict.None, decide(prev(blockingOn = false), listOf(exit(2_000, REASON_USER_REQUESTED))))
    }

    @Test fun rebootGivesNoCard() {
        assertEquals(KillVerdict.None, decide(prev(boot = 6L), listOf(exit(2_000, REASON_USER_REQUESTED))))
    }

    @Test fun versionChangeGivesNoCard() {
        assertEquals(KillVerdict.None, decide(prev(version = 2L), listOf(exit(2_000, REASON_CRASH))))
    }

    @Test fun lastUpdateChangeGivesNoCard() {
        assertEquals(KillVerdict.None, decide(prev(update = 110L), listOf(exit(2_000, REASON_USER_REQUESTED))))
    }

    @Test fun noExitRecordNewerThanTheConnectGivesNoCard() {
        assertEquals(KillVerdict.None, decide(prev(), emptyList()))
        // a record from before the previous connect is an older run's
        assertEquals(KillVerdict.None, decide(prev(), listOf(exit(900, REASON_USER_REQUESTED))))
    }

    @Test fun packageUpdatedRecordGivesNoCard() {
        assertEquals(KillVerdict.None, decide(prev(), listOf(exit(2_000, REASON_PACKAGE_UPDATED))))
        // even alongside a crash, an update explains the exit
        assertEquals(KillVerdict.None, decide(prev(), listOf(exit(2_000, REASON_CRASH), exit(2_100, REASON_PACKAGE_UPDATED))))
    }

    @Test fun ignoredReasonsGiveNoCard() {
        for (r in listOf(REASON_EXIT_SELF, REASON_PERMISSION_CHANGE, REASON_USER_STOPPED, REASON_DEPENDENCY_DIED, REASON_PACKAGE_STATE_CHANGE)) {
            assertEquals("reason $r", KillVerdict.None, decide(prev(), listOf(exit(2_000, r))))
        }
    }

    // ---- killed ----

    @Test fun userRequestedIsKilledAtTheRecordTime() {
        assertEquals(KillVerdict.Killed(2_345), decide(prev(), listOf(exit(2_345, REASON_USER_REQUESTED))))
    }

    @Test fun lowMemoryIsKilled() {
        assertEquals(KillVerdict.Killed(2_000), decide(prev(), listOf(exit(2_000, REASON_LOW_MEMORY))))
    }

    @Test fun signaledIsKilled() {
        assertEquals(KillVerdict.Killed(2_000), decide(prev(), listOf(exit(2_000, REASON_SIGNALED))))
    }

    @Test fun otherIsKilled() {
        assertEquals(KillVerdict.Killed(2_000), decide(prev(), listOf(exit(2_000, REASON_OTHER))))
    }

    @Test fun theEarliestKillRecordGivesTheTime() {
        assertEquals(KillVerdict.Killed(2_000), decide(prev(), listOf(exit(3_000, REASON_USER_REQUESTED), exit(2_000, REASON_LOW_MEMORY))))
    }

    @Test fun belowApi30TheLastAliveTimeIsKilled() {
        assertEquals(KillVerdict.Killed(5_000), decide(prev(), exits = null))
    }

    // ---- crashed ----

    @Test fun crashIsCrashed() {
        assertEquals(KillVerdict.Crashed(2_000), decide(prev(), listOf(exit(2_000, REASON_CRASH))))
    }

    @Test fun nativeCrashIsCrashed() {
        assertEquals(KillVerdict.Crashed(2_000), decide(prev(), listOf(exit(2_000, REASON_CRASH_NATIVE))))
    }

    @Test fun anrIsCrashed() {
        assertEquals(KillVerdict.Crashed(2_000), decide(prev(), listOf(exit(2_000, REASON_ANR))))
    }

    @Test fun initializationFailureIsCrashed() {
        assertEquals(KillVerdict.Crashed(2_000), decide(prev(), listOf(exit(2_000, REASON_INITIALIZATION_FAILURE))))
    }

    @Test fun aCrashBeatsAKillInTheSameWindow() {
        assertEquals(KillVerdict.Crashed(2_000), decide(prev(), listOf(exit(2_500, REASON_USER_REQUESTED), exit(2_000, REASON_CRASH))))
    }

    @Test fun aCrashBeforeTheConnectIsNotCounted() {
        assertEquals(KillVerdict.None, decide(prev(), listOf(exit(900, REASON_CRASH))))
    }

    // ---- reason codes ----

    /** The codes are copied into KillCheck; these are the framework's compile-time constants from android.jar. */
    @Test fun reasonCodesMatchTheFramework() {
        assertEquals(ApplicationExitInfo.REASON_UNKNOWN, KillCheck.REASON_UNKNOWN)
        assertEquals(ApplicationExitInfo.REASON_EXIT_SELF, KillCheck.REASON_EXIT_SELF)
        assertEquals(ApplicationExitInfo.REASON_SIGNALED, KillCheck.REASON_SIGNALED)
        assertEquals(ApplicationExitInfo.REASON_LOW_MEMORY, KillCheck.REASON_LOW_MEMORY)
        assertEquals(ApplicationExitInfo.REASON_CRASH, KillCheck.REASON_CRASH)
        assertEquals(ApplicationExitInfo.REASON_CRASH_NATIVE, KillCheck.REASON_CRASH_NATIVE)
        assertEquals(ApplicationExitInfo.REASON_ANR, KillCheck.REASON_ANR)
        assertEquals(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, KillCheck.REASON_INITIALIZATION_FAILURE)
        assertEquals(ApplicationExitInfo.REASON_PERMISSION_CHANGE, KillCheck.REASON_PERMISSION_CHANGE)
        assertEquals(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, KillCheck.REASON_EXCESSIVE_RESOURCE_USAGE)
        assertEquals(ApplicationExitInfo.REASON_USER_REQUESTED, KillCheck.REASON_USER_REQUESTED)
        assertEquals(ApplicationExitInfo.REASON_USER_STOPPED, KillCheck.REASON_USER_STOPPED)
        assertEquals(ApplicationExitInfo.REASON_DEPENDENCY_DIED, KillCheck.REASON_DEPENDENCY_DIED)
        assertEquals(ApplicationExitInfo.REASON_OTHER, KillCheck.REASON_OTHER)
        assertEquals(ApplicationExitInfo.REASON_FREEZER, KillCheck.REASON_FREEZER)
        assertEquals(ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, KillCheck.REASON_PACKAGE_STATE_CHANGE)
        assertEquals(ApplicationExitInfo.REASON_PACKAGE_UPDATED, KillCheck.REASON_PACKAGE_UPDATED)
    }
}
