package app.unrot.ui

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.unrot.data.Settings
import app.unrot.service.BlockerService
import app.unrot.service.RESUME_GRACE_MS
import app.unrot.service.ServiceState
import app.unrot.service.ServiceStatus
import app.unrot.service.notRunningWhileOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { UnrotTheme { Root() } }
    }
}

/**
 * Refreshed every time the app comes back to the foreground (e.g. returning from system settings), and once more after
 * [RESUME_GRACE_MS] for [SystemStatus.notRunning].
 */
class SystemStatus(
    val serviceEnabled: Boolean,
    val batteryExempt: Boolean,
    /** Switched on, but the service is neither bound nor running (see [notRunningWhileOn]). */
    val notRunning: Boolean = false,
    /** A recent kill or crash to report on Home, or null (see [ServiceState.killNotice]). */
    val kill: ServiceState.KillNotice? = null,
)

private fun readStatus(ctx: Context, sinceResumeMs: Long): SystemStatus {
    val enabled = BlockerService.isEnabled(ctx)
    return SystemStatus(
        serviceEnabled = enabled,
        batteryExempt = Intents.isIgnoringBatteryOptimizations(ctx),
        notRunning = notRunningWhileOn(enabled, ServiceStatus.bound(ctx), BlockerService.running, sinceResumeMs),
        kill = ServiceState.killNotice(ctx, System.currentTimeMillis()),
    )
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by remember { Settings.flow(ctx) }.collectAsState(initial = null)
    val status = remember { mutableStateOf(readStatus(ctx, 0L)) }
    val resumeAt = remember { mutableLongStateOf(0L) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        status.value = readStatus(ctx, 0L)
        resumeAt.longValue = SystemClock.elapsedRealtime()
    }
    // The service may still be binding just after the app comes to the front: the "not running" check waits the grace time.
    LaunchedEffect(resumeAt.longValue) {
        if (resumeAt.longValue != 0L) {
            delay(RESUME_GRACE_MS)
            status.value = readStatus(ctx, SystemClock.elapsedRealtime() - resumeAt.longValue)
        }
    }

    val s = settings ?: return
    if (!s.onboarded) {
        OnboardingScreen(status.value, onDone = { scope.launch { Settings.setOnboarded(ctx, true) } })
    } else {
        HomeScreen(s, status.value)
    }
}

@Composable
fun UnrotTheme(content: @Composable () -> Unit) {
    val green = Color(0xFF2E7D5B)
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF7FD1A8), secondary = Color(0xFFFFAB91))
    } else {
        lightColorScheme(primary = green, secondary = Color(0xFFD84315))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
