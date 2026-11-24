package app.unrot.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import app.unrot.BuildConfig

/**
 * The service's own record of its last run, in the SharedPreferences file `service_state` (not DataStore: it is written from
 * the service's main thread with commit(), so a clean marker is on disk before the process can die). See [KillCheck].
 *
 * Writes: [connect] (one read-decide-write), [markClean] (onUnbind and ACTION_SHUTDOWN), [heartbeat] and [setBlocking].
 * Main thread only for the writes; the Home screen reads [killNotice].
 */
/** commit() on purpose: the clean marker and the record must be on disk before the process can die (see the class comment). */
@SuppressLint("ApplySharedPref")
object ServiceState {
    private const val FILE = "service_state"
    private const val TAG = "UnrotState"
    private const val K_CLEAN = "clean"
    private const val K_ALIVE = "aliveAt"
    private const val K_CONNECT = "connectAt"
    private const val K_BOOT = "boot"
    private const val K_VERSION = "version"
    private const val K_UPDATE = "lastUpdate"
    private const val K_BLOCKING = "blockingOn"
    private const val K_KILL_AT = "lastKillAt"
    private const val K_KILL_CRASH = "lastKillCrashed"
    private const val K_KILL_DISMISSED = "killDismissed"
    private const val K_NOTICE_AT = "lastNoticeAt"

    /** What the Home card shows: the kill's time and whether it was a crash of ours. */
    class KillNotice(val atMs: Long, val crashed: Boolean)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Connect: reads the previous run, decides (see [KillCheck.decide]), records a kill when there is one, and writes this
     * run's start. Returns the verdict; the caller notifies. [blockingOn] keeps its stored value until the first settings
     * emission sets it (see [setBlocking]).
     */
    fun connect(ctx: Context, nowMs: Long): KillVerdict {
        val p = prefs(ctx)
        val boot = bootCount(ctx)
        val version = BuildConfig.VERSION_CODE.toLong()
        val update = lastUpdateTime(ctx)
        val prev = if (p.contains(K_CONNECT)) PrevRecord(
            clean = p.getBoolean(K_CLEAN, false),
            aliveAtMs = p.getLong(K_ALIVE, 0L),
            connectAtMs = p.getLong(K_CONNECT, 0L),
            boot = p.getLong(K_BOOT, -1L),
            version = p.getLong(K_VERSION, -1L),
            lastUpdateMs = p.getLong(K_UPDATE, -1L),
            blockingOn = p.getBoolean(K_BLOCKING, false),
        ) else null
        val verdict = KillCheck.decide(prev, boot, version, update, exitRecords(ctx))
        if (BuildConfig.DEBUG) Log.i(TAG, "previous run: ${verdict.javaClass.simpleName}")
        val e = p.edit()
        when (verdict) {
            is KillVerdict.Killed -> e.putLong(K_KILL_AT, verdict.atMs).putBoolean(K_KILL_CRASH, false).putBoolean(K_KILL_DISMISSED, false)
            is KillVerdict.Crashed -> e.putLong(K_KILL_AT, verdict.atMs).putBoolean(K_KILL_CRASH, true).putBoolean(K_KILL_DISMISSED, false)
            KillVerdict.None -> Unit
        }
        e.putBoolean(K_CLEAN, false)
            .putLong(K_ALIVE, nowMs)
            .putLong(K_CONNECT, nowMs)
            .putLong(K_BOOT, boot)
            .putLong(K_VERSION, version)
            .putLong(K_UPDATE, update)
            .commit()
        return verdict
    }

    /** The clean marker: written synchronously before the service's teardown, so a normal stop is never a kill. */
    fun markClean(ctx: Context) {
        prefs(ctx).edit().putBoolean(K_CLEAN, true).commit()
    }

    /** Liveness: the run is still going (on connect, screen off, and at most every 10 minutes while a target app is in front). */
    fun heartbeat(ctx: Context, nowMs: Long) {
        prefs(ctx).edit().putLong(K_ALIVE, nowMs).apply()
    }

    /** Whether blocking is on (see [blockingOn]). Written on the first settings emission and whenever it changes. */
    fun setBlocking(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(K_BLOCKING, on).commit()
    }

    /** The kill card to show now, or null (none, dismissed, or older than [KILL_CARD_MS]). */
    fun killNotice(ctx: Context, nowMs: Long): KillNotice? {
        val p = prefs(ctx)
        val at = p.getLong(K_KILL_AT, 0L)
        val dismissed = p.getBoolean(K_KILL_DISMISSED, false)
        if (!killCardVisible(at, nowMs, dismissed)) return null
        return KillNotice(at, p.getBoolean(K_KILL_CRASH, false))
    }

    /** "Dismiss" or "Fix it": the Home card goes away until the next kill. */
    fun dismissKill(ctx: Context) {
        prefs(ctx).edit().putBoolean(K_KILL_DISMISSED, true).commit()
    }

    /** Whether a kill notification may be posted now (rate limit, see [killNoticeDue]). */
    fun noticeDue(ctx: Context, nowMs: Long): Boolean = killNoticeDue(prefs(ctx).getLong(K_NOTICE_AT, 0L), nowMs)

    fun noticeSent(ctx: Context, nowMs: Long) {
        prefs(ctx).edit().putLong(K_NOTICE_AT, nowMs).commit()
    }

    /** Settings.Global.BOOT_COUNT: it changes at each reboot. -1 when unavailable. */
    private fun bootCount(ctx: Context): Long =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong() }.getOrDefault(-1L)

    @Suppress("DEPRECATION")
    private fun lastUpdateTime(ctx: Context): Long =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(-1L)

    /**
     * This app's process exit records (API 30+), or null below. Read on connect only; [KillCheck.decide] keeps the newer ones.
     */
    private fun exitRecords(ctx: Context): List<ExitRecord>? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).map { info ->
                if (BuildConfig.DEBUG) Log.i(TAG, "exit reason=${info.reason} at=${info.timestamp} desc=${info.description}")
                ExitRecord(info.timestamp, info.reason)
            }
        }.getOrDefault(emptyList())
    }
}

/** Whether the accessibility service is switched on and bound (listed among the enabled services), for the Home card and the watchdog. */
object ServiceStatus {
    fun bound(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == ctx.packageName && it.resolveInfo.serviceInfo.name == BlockerService::class.java.name }
    }.getOrDefault(false)

    /** Bound, or running in this process. */
    fun up(ctx: Context): Boolean = BlockerService.running || bound(ctx)
}
