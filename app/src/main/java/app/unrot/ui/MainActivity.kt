package app.unrot.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.unrot.data.Settings
import app.unrot.service.BlockerService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { UnrotTheme { Root() } }
    }
}

/** Refreshed every time the app comes back to the foreground (e.g. returning from system settings). */
class SystemStatus(val serviceEnabled: Boolean, val batteryExempt: Boolean)

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by remember { Settings.flow(ctx) }.collectAsState(initial = null)
    val status = remember { mutableStateOf(SystemStatus(BlockerService.isEnabled(ctx), Intents.isIgnoringBatteryOptimizations(ctx))) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        status.value = SystemStatus(BlockerService.isEnabled(ctx), Intents.isIgnoringBatteryOptimizations(ctx))
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
