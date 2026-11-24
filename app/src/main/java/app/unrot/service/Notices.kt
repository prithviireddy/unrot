package app.unrot.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.unrot.R
import app.unrot.ui.MainActivity

/** The app's notifications. Channels are created right before each post (creating one again is harmless). */
object Notices {
    /** The foreground notification (decision 0011, builds with `-PkeepAliveFgs`). Low importance, silent. */
    const val CHANNEL_BLOCKING = "blocking_status"
    /** Watchdog and kill notices. */
    const val CHANNEL_SERVICE = "service_status"
    private const val STOP_CHANNEL_NAME = "Unrot stopped"
    private const val TEXT_KILLED = "Stopped by the phone or from the task manager, so blocking was off until it restarted. " +
        "Tap to see how to keep it running."
    private const val TEXT_CRASHED = "Blocking was off until it restarted. Tap to open Unrot."
    const val ID_FOREGROUND = 10
    const val ID_WATCHDOG = 1
    const val ID_KILL = 11

    /** Opens the app's main screen when a notice is tapped. */
    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun channel(ctx: Context, id: String, name: String, importance: Int, quiet: Boolean = false) {
        val c = NotificationChannel(id, name, importance)
        if (quiet) {
            c.setSound(null, null)
            c.enableVibration(false)
            c.setShowBadge(false)
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(c)
    }

    /** The foreground notification: "Unrot is blocking", tap to open. Secret on the lock screen, not ongoing (swipeable). */
    fun foreground(ctx: Context): Notification {
        channel(ctx, CHANNEL_BLOCKING, "Blocking status", NotificationManager.IMPORTANCE_LOW, quiet = true)
        return NotificationCompat.Builder(ctx, CHANNEL_BLOCKING)
            .setSmallIcon(R.drawable.ic_stat_unrot)
            .setContentTitle("Unrot is blocking")
            .setContentText("Tap to open Unrot")
            .setContentIntent(openApp(ctx))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setOngoing(false)
            .build()
    }

    /**
     * Starts the service in the foreground with the notification. [ServiceCompat] passes the special-use type on API 34+
     * (the keepAliveFgs manifest declares it). Failures are logged by the caller; blocking goes on without it.
     */
    // Lint can't see the flag: the call is made only when BuildConfig.KEEP_ALIVE_FGS is on, and only that build's manifest
    // declares the service type.
    @SuppressLint("ForegroundServiceType")
    fun startForeground(service: android.app.Service) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(service, ID_FOREGROUND, foreground(service), type)
    }

    fun stopForeground(service: android.app.Service) {
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /** Whether notifications may be posted (Android 13+ needs the permission). */
    fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * The kill notice (ID 11) on the service channel, at most one per [KILL_NOTICE_GAP_MS]. [crashed] picks the text: a stop by
     * the phone, or a crash of ours (which gets no guide line).
     */
    fun postKill(ctx: Context, nowMs: Long, crashed: Boolean) {
        if (!allowed(ctx) || !ServiceState.noticeDue(ctx, nowMs)) return
        channel(ctx, CHANNEL_SERVICE, STOP_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        val n = NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_unrot)
            .setContentTitle(if (crashed) "Unrot stopped unexpectedly" else "Unrot was stopped")
            .setContentText(if (crashed) TEXT_CRASHED else TEXT_KILLED)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(ID_KILL, n)
        ServiceState.noticeSent(ctx, nowMs)
    }

    /** The watchdog's notice (ID 1): [text] is the "turned off" or the "switched on but not running" wording. */
    fun watchdog(ctx: Context, text: String) {
        channel(ctx, CHANNEL_SERVICE, STOP_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        val n = NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Unrot stopped blocking")
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(ID_WATCHDOG, n)
    }

    fun cancelWatchdog(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_WATCHDOG)
}
