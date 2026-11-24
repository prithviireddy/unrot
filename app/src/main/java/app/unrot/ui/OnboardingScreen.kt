package app.unrot.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import app.unrot.data.Settings
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(status: SystemStatus, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Step ${step + 1} of 4", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            when (step) {
                0 -> Disclosure(onAgree = { step = 1 })
                1 -> AccessibilityStep(status, onNext = { step = 2 })
                2 -> BatteryStep(status, onNext = { step = 3 })
                else -> NotificationStep(onDone)
            }
        }
    }
}

@Composable
private fun Title(t: String) = Text(t, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

@Composable
private fun Body(t: String) = Text(t, style = MaterialTheme.typography.bodyLarge)

/** The "prominent disclosure" Google Play requires before asking for the Accessibility permission. */
@Composable
private fun Disclosure(onAgree: () -> Unit) {
    Title("Before you start")
    Body("Unrot blocks Instagram Reels and YouTube Shorts. To do that it needs Android's Accessibility permission.")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What Unrot does with it", fontWeight = FontWeight.SemiBold)
            Text("• Reads the screen only while Instagram or YouTube is open, to recognise Reels and Shorts.")
            Text("• Presses Back, taps the Home tab, or draws a patch over the Reels/Shorts button.")
            Text("• " + BAR_PICTURES_DISCLOSURE)
            Text("What it never does", fontWeight = FontWeight.SemiBold)
            Text(
                "• It never keeps or analyses content from other apps. Apart from the bar's tab pictures described above " +
                    "(and screen dumps you save yourself in developer mode), it never stores screen content, and it never " +
                    "sends your messages, passwords or any screen content anywhere.",
            )
            Text("• It has no account, ads or analytics.")
        }
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Button(onClick = {
        // Move on only after the write, so leaving this step can't cancel it.
        scope.launch { Settings.setDisclosureVersion(ctx, DISCLOSURE_VERSION); onAgree() }
    }, modifier = Modifier.fillMaxWidth()) { Text("I agree, continue") }
}

@Composable
private fun AccessibilityStep(status: SystemStatus, onNext: () -> Unit) {
    val ctx = LocalContext.current
    Title("Turn on Unrot")
    Body("Open Accessibility settings, then go to Installed apps (or Downloaded apps), tap Unrot and turn it on.")
    if (Build.VERSION.SDK_INT >= 33) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Is the switch greyed out?", fontWeight = FontWeight.SemiBold)
                Text("Android blocks this for apps installed outside the Play Store. Open App info, tap ⋮ in the top right, choose \"Allow restricted settings\", then try again.")
                OutlinedButton(onClick = { Intents.openAppInfo(ctx) }) { Text("Open App info") }
            }
        }
    }
    if (status.serviceEnabled) {
        Text("✓ Unrot is on", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Next") }
    } else {
        Button(onClick = { Intents.openAccessibility(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Open Accessibility settings") }
        TextButton(onClick = onNext) { Text("Skip for now") }
    }
}

@Composable
private fun BatteryStep(status: SystemStatus, onNext: () -> Unit) {
    val ctx = LocalContext.current
    val mine = myOemGuide()
    var showAll by remember { mutableStateOf(false) }
    Title("Keep Unrot running")
    Body("Some phones stop background apps to save battery, and blocking stops with them. Let Unrot run unrestricted.")
    if (status.batteryExempt) {
        Text("✓ Battery optimisation is off for Unrot", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    } else {
        Text("In the list, find Unrot and choose \"Don't optimise\" or \"Unrestricted\".")
        OutlinedButton(onClick = { Intents.openBatterySettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open battery settings")
        }
    }
    // The step's own button above opens battery settings, so the guides don't repeat it.
    mine?.let { GuideCard(it, showBatteryButton = false) }
    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Hide other phones" else "Guides for other phones") }
    if (showAll) OEM_GUIDES.filter { it != mine }.forEach { GuideCard(it, showBatteryButton = false) }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Next") }
}

@Composable
private fun NotificationStep(onDone: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }
    Title("Know if it stops")
    Body(
        "While Reels or Shorts blocking is on, Unrot runs in the foreground, which helps stop your phone from closing it. " +
            "Android shows this as a small, silent \"Unrot is blocking\" notification. Allow notifications so you can see it, " +
            "and so Unrot can tell you if Android stops it.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else onDone()
        }) { Text("Allow notifications") }
        TextButton(onClick = onDone) { Text("Not now") }
    }
}
