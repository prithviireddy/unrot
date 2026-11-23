package app.unrot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.unrot.BuildConfig
import app.unrot.data.Settings
import app.unrot.inspector.Inspector
import app.unrot.rules.RulesRepository
import app.unrot.service.BarStore
import app.unrot.service.ServiceState
import android.os.Build
import app.unrot.work.RulesWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A change that weakens blocking, held behind the countdown. */
private data class Pending(val label: String, val apply: suspend () -> Unit)

@Composable
fun HomeScreen(s: Settings, status: SystemStatus) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pending?>(null) }
    var guideOpen by remember { mutableStateOf(false) }

    /** Strengthening applies at once; weakening waits [Settings.frictionSeconds]. */
    fun change(label: String, weakens: Boolean, apply: suspend () -> Unit) {
        if (weakens && s.frictionSeconds > 0) pending = Pending(label, apply) else scope.launch { apply() }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Unrot", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            if (s.onboarded && s.disclosureVersion < DISCLOSURE_VERSION) DisclosureCard()
            status.kill?.let { KillCard(it, onFix = { guideOpen = true }) }
            StatusCard(status, onFix = { guideOpen = true })

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Blocked today", style = MaterialTheme.typography.labelLarge)
                    Text("${s.blockedToday}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    ToggleRow("Block Instagram Reels", "Hides the Reels tab and closes reels anywhere else.", s.instagramEnabled) { on ->
                        change("turn off Reels blocking", weakens = !on) { Settings.setInstagram(ctx, on) }
                    }
                    HorizontalDivider()
                    ToggleRow("Block YouTube Shorts", "Hides the Shorts tab and closes the Shorts player.", s.youtubeEnabled) { on ->
                        change("turn off Shorts blocking", weakens = !on) { Settings.setYoutube(ctx, on) }
                    }
                    HorizontalDivider()
                    ToggleRow("Stop at \"You're all caught up\"", "Jumps back to the top of the Instagram feed instead of showing suggested posts.", s.caughtUpEnabled) { on ->
                        change("stop jumping back at \"all caught up\"", weakens = !on) { Settings.setCaughtUp(ctx, on) }
                    }
                    HorizontalDivider()
                    ToggleRow("Allow reels from DMs", "Watch a reel a friend sent you. Swiping to the next one is still blocked.", s.dmException) { on ->
                        change("allow reels from DMs", weakens = on) { Settings.setDmException(ctx, on) }
                    }
                    HorizontalDivider()
                    // Not behind the countdown: a plain patch does not weaken blocking.
                    ToggleRow("Simple patch", "Cover the Reels/Shorts tab with a plain patch instead of redrawing the bar. No screenshots.", s.simplePatch) { on ->
                        scope.launch { Settings.setSimplePatch(ctx, on) }
                    }
                    SavedPicturesRow()
                }
            }

            RulesCard()
            if (s.devUnlocked) DeveloperCard(s)
            VersionFooter(s)
        }
    }

    pending?.let { p ->
        FrictionDialog(p.label, s.frictionSeconds, onConfirm = { scope.launch { p.apply() }; pending = null }, onCancel = { pending = null })
    }
    if (guideOpen) MakerGuideDialog(onDismiss = { guideOpen = false })
}

/**
 * The record of a stop by the phone or a crash of ours (see [ServiceState]). "Fix it" opens the maker's guide and hides the card;
 * so does "Dismiss". Hidden for 7 days at most.
 */
@Composable
private fun KillCard(n: ServiceState.KillNotice, onFix: () -> Unit) {
    val ctx = LocalContext.current
    var gone by remember { mutableStateOf(false) }
    if (gone) return
    // The clock time when the stop was today; otherwise the date too.
    val time = if (android.text.format.DateUtils.isToday(n.atMs)) {
        android.text.format.DateUtils.formatDateTime(ctx, n.atMs, android.text.format.DateUtils.FORMAT_SHOW_TIME)
    } else {
        android.text.format.DateUtils.formatDateTime(
            ctx, n.atMs,
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or
                android.text.format.DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
    val hide = {
        gone = true
        ServiceState.dismissKill(ctx) // synchronous write: the card must stay hidden even if this composition leaves
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (n.crashed) {
                Text("Unrot stopped unexpectedly at $time.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            } else {
                val at = if (Build.VERSION.SDK_INT >= 30) "at $time" else "around $time"
                Text(
                    "Unrot was stopped $at (by the phone or from the task manager), so blocking was off until it restarted.",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!n.crashed) Button(onClick = { hide(); onFix() }) { Text("Fix it") }
                TextButton(onClick = hide) { Text("Dismiss") }
            }
        }
    }
}

/**
 * Disclosure version of the screenshots and the saved bar pictures. Must match Settings.disclosureVersion's meaning:
 * 2 = screenshots to redraw the bar; 3 = the bar's tab pictures are also saved on the phone.
 */
const val DISCLOSURE_VERSION = 3

/** The screenshot and saved-picture disclosure, shared by onboarding and the Home card. */
const val BAR_PICTURES_DISCLOSURE =
    "While Instagram or YouTube is open, Unrot may take a screenshot of the screen. It keeps only the bottom bar's tab " +
        "pictures (these can include your profile picture and DM unread count), saved privately on this phone to redraw the bar " +
        "without the Reels/Shorts tab, and discards the rest at once. Nothing is ever sent anywhere."

@Composable
private fun DisclosureCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Unrot now redraws the bar",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            Text(BAR_PICTURES_DISCLOSURE, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { scope.launch { Settings.setDisclosureVersion(ctx, DISCLOSURE_VERSION) } }) { Text("Accept") }
        }
    }
}

/** Deletes the saved bar pictures from the phone and tells the running service to clear its crops. */
@Composable
private fun SavedPicturesRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleted by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            scope.launch {
                BarStore.wipe(ctx)
                Settings.setBarCacheResetAt(ctx, System.currentTimeMillis())
                deleted = true
            }
        }) { Text("Delete saved bar pictures") }
        if (deleted) Text("Deleted. New pictures are taken when needed.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusCard(status: SystemStatus, onFix: () -> Unit) {
    val ctx = LocalContext.current
    val stopped = status.notRunning
    val ok = status.serviceEnabled && !stopped
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    ok -> "Blocking is on"
                    stopped -> "Switched on, but your phone stopped it"
                    else -> "Blocking is off"
                },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            )
            if (!ok) {
                Text(
                    if (stopped) "Unrot is switched on in Accessibility, but it isn't running. Open the guide to keep it running."
                    else "The Unrot accessibility service isn't running. Turn it back on in Accessibility settings.",
                )
                if (stopped) Button(onClick = onFix) { Text("Fix it") }
                OutlinedButton(onClick = { Intents.openAccessibility(ctx) }) { Text("Open Accessibility settings") }
            }
            if (!status.batteryExempt) {
                Text("Battery optimisation may stop Unrot in the background.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { Intents.openBatterySettings(ctx) }) { Text("Fix battery settings") }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FrictionDialog(label: String, seconds: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var left by remember { mutableIntStateOf(seconds) }
    LaunchedEffect(Unit) { while (left > 0) { delay(1_000); left-- } }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Are you sure?") },
        text = { Text("You're about to $label. Take a breath. You can confirm in $left s.") },
        confirmButton = { TextButton(onClick = onConfirm, enabled = left == 0) { Text(if (left == 0) "Confirm" else "Wait $left s") } },
        dismissButton = { Button(onClick = onCancel) { Text("Keep blocking") } },
    )
}

@Composable
private fun RulesCard() {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Detection rules", style = MaterialTheme.typography.titleMedium)
            Text("Version ${RulesRepository.current.version} (${RulesRepository.source})", style = MaterialTheme.typography.bodyMedium)
            if (BuildConfig.RULES_URL.isNotBlank()) {
                TextButton(onClick = { RulesWorker.refreshNow(ctx) }) { Text("Check for updates") }
            }
        }
    }
}

@Composable
private fun DeveloperCard(s: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dumps by remember { mutableStateOf(Inspector.list(ctx)) }
    var hasFiles by remember { mutableStateOf(Inspector.hasFiles(ctx)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            ToggleRow("Inspector", "Shows a DUMP button on screen. Tap it in Instagram or YouTube to save the screen's accessibility tree.", s.inspectorEnabled) { on ->
                scope.launch { Settings.setInspector(ctx, on) }
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Dumps can include text that was on screen (captions, usernames, DM text). Check a dump before you share it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (BuildConfig.DEBUG) OutlinedButton(onClick = { android.os.Process.killProcess(android.os.Process.myPid()) }) {
                        Text("Kill Unrot now")
                    }
                    OutlinedButton(onClick = { dumps = Inspector.list(ctx); hasFiles = Inspector.hasFiles(ctx) }) { Text("Refresh") }
                    if (hasFiles) OutlinedButton(onClick = {
                        Inspector.deleteAll(ctx); dumps = Inspector.list(ctx); hasFiles = Inspector.hasFiles(ctx)
                    }) { Text("Delete all") }
                }
                if (dumps.isEmpty()) Text("No dumps yet.", style = MaterialTheme.typography.bodyMedium)
                dumps.take(20).forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${f.name}  ·  ${f.length() / 1024} KB", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { Intents.share(ctx, f) }) { Text("Share") }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionFooter(s: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var taps by remember { mutableIntStateOf(0) }
    Text(
        "Unrot ${BuildConfig.VERSION_NAME}" + if (s.devUnlocked) " · developer mode" else "",
        Modifier.fillMaxWidth().clickable {
            taps++
            if (taps >= 7) { taps = 0; scope.launch { Settings.setDevUnlocked(ctx, !s.devUnlocked) } }
        }.padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
