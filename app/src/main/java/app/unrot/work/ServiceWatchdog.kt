package app.unrot.work

import android.content.Context
import app.unrot.data.Settings
import app.unrot.service.BlockerService
import app.unrot.service.Notices
import app.unrot.service.ServiceStatus
import app.unrot.service.blockingOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Notifies the user when blocking is switched on in the app but the service isn't running (decision 0011, step 3). Called from
 * the rules worker every 12 h.
 *
 * Off in the accessibility settings: notify at once. Switched on but not bound or running: wait 5 s and check again (the
 * service may be binding), then notify with the "switched on but not running" wording. Cancel the notice only when the service
 * is switched on and up, or when blocking is not wanted at all.
 */
object ServiceWatchdog {
    private const val RECHECK_MS = 5_000L
    private const val TEXT_OFF = "The accessibility service was turned off or killed. Tap to turn it back on."
    private const val TEXT_NOT_RUNNING = "Your phone stopped Unrot. Open Unrot to fix it."

    suspend fun check(ctx: Context) {
        val s = Settings.flow(ctx).first()
        if (!blockingOn(s.onboarded, s.instagramEnabled, s.youtubeEnabled)) {
            Notices.cancelWatchdog(ctx)
            return
        }
        if (!BlockerService.isEnabled(ctx)) {
            if (Notices.allowed(ctx)) Notices.watchdog(ctx, TEXT_OFF)
            return
        }
        if (ServiceStatus.up(ctx)) {
            Notices.cancelWatchdog(ctx)
            return
        }
        delay(RECHECK_MS)
        if (ServiceStatus.up(ctx)) {
            Notices.cancelWatchdog(ctx)
        } else if (Notices.allowed(ctx)) {
            Notices.watchdog(ctx, TEXT_NOT_RUNNING)
        }
    }
}
