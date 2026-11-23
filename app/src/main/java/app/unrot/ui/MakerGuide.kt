package app.unrot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A phone maker's guide to keeping Unrot running: its settings path in words. [settingsButtons] adds the App info and battery
 * settings buttons (the Oppo, Realme and OnePlus guide, decision 0011).
 */
data class OemGuide(val name: String, val match: List<String>, val steps: List<String>, val settingsButtons: Boolean = false)

val OEM_GUIDES = listOf(
    OemGuide(
        "Xiaomi / Redmi / POCO", listOf("xiaomi", "redmi", "poco"),
        listOf(
            "Settings › Apps › Manage apps › Unrot: turn on Autostart, and set Battery saver to \"No restrictions\".",
            "In Recents, long-press Unrot and tap the lock icon so it isn't cleared.",
        ),
    ),
    OemGuide(
        "Samsung", listOf("samsung"),
        listOf(
            "Settings › Battery › Background usage limits: make sure Unrot is not in \"Sleeping apps\" or \"Deep sleeping apps\", " +
                "and add it to \"Never sleeping apps\".",
            "Under Apps › Unrot › Battery, choose \"Unrestricted\".",
        ),
    ),
    OemGuide(
        "Oppo / Realme / OnePlus", listOf("oppo", "realme", "oneplus"),
        listOf(
            "Settings › Apps › Auto launch › turn on Unrot (Realme UI and ColorOS).",
            "Settings › Battery › More settings › App battery management › Unrot: turn on Allow background activity.",
            "If you don't see these, open App info › Battery usage and turn on Allow auto launch and Allow background activity.",
            "If there is a Sleep standby optimisation setting, turn it off for Unrot.",
            "Optional: lock Unrot in Recents.",
        ),
        settingsButtons = true,
    ),
)

/** The guide for this phone's maker, or null when it isn't one of the listed makers. */
fun myOemGuide(): OemGuide? = OEM_GUIDES.firstOrNull { g -> g.match.any { Intents.manufacturer.contains(it) } }

/**
 * One maker's guide as a card, with its settings buttons when it has them. [showBatteryButton] is false where the screen already
 * has a battery button (onboarding), so the same button isn't shown twice.
 */
@Composable
fun GuideCard(g: OemGuide, showBatteryButton: Boolean = true) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(g.name, fontWeight = FontWeight.SemiBold)
            g.steps.forEach { Text("• $it") }
            if (g.settingsButtons) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Intents.openAppInfo(ctx) }) { Text("Open App info") }
                    if (showBatteryButton) {
                        OutlinedButton(onClick = { Intents.openBatterySettings(ctx) }) { Text("Open battery settings") }
                    }
                }
            }
        }
    }
}

/** The "Fix it" guide from the Home screen: this phone's maker's guide, or every guide when the maker isn't listed. */
@Composable
fun MakerGuideDialog(onDismiss: () -> Unit) {
    val mine = myOemGuide()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep Unrot running") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Some phones stop background apps to save battery or when a cleaner runs. Allow Unrot to run in the background:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (mine != null) GuideCard(mine) else OEM_GUIDES.forEach { GuideCard(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
