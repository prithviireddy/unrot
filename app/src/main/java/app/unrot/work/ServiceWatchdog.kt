package app.unrot.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.unrot.data.Settings
import app.unrot.service.BlockerService
import app.unrot.ui.MainActivity
import kotlinx.coroutines.flow.first

/** Notifies the user when blocking is switched on in the app but the accessibility service isn't running. */
object ServiceWatchdog {
    private const val CHANNEL = "service_status"
    private const val ID = 1

    suspend fun check(ctx: Context) {
        val s = Settings.flow(ctx).first()
        val wantsBlocking = s.onboarded && (s.instagramEnabled || s.youtubeEnabled)
        if (!wantsBlocking || BlockerService.isEnabled(ctx)) {
            NotificationManagerCompat.from(ctx).cancel(ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Blocker status", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Unrot stopped blocking")
            .setContentText("The accessibility service was turned off or killed. Tap to turn it back on.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(ID, n)
    }
}
