package app.unrot.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object Intents {
    fun openAccessibility(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /** App info page, where "Allow restricted settings" lives (⋮ menu) on Android 13+ for sideloaded APKs. */
    fun openAppInfo(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /** The battery optimization list. Doesn't need REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Play restricts. */
    fun openBatterySettings(ctx: Context) = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.recover { openAppInfo(ctx) }

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share dump").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    val manufacturer: String get() = Build.MANUFACTURER.lowercase()
}
