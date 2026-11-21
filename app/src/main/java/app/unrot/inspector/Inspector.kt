package app.unrot.inspector

import android.content.Context
import android.os.Build
import android.util.Log
import app.unrot.rules.Detection
import app.unrot.rules.NodeSnap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the current screen's accessibility tree to files/dumps/ as JSON, so detection rules can be
 * written and fixed from real data. Dumps stay on the device until the user shares them.
 */
object Inspector {
    private const val TAG = "UnrotInspector"

    fun dir(ctx: Context) = File(ctx.filesDir, "dumps").apply { mkdirs() }

    fun list(ctx: Context): List<File> = dir(ctx).listFiles { f -> f.extension == "json" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun write(
        ctx: Context, pkg: String, versionCode: Long, versionName: String?, screenW: Int, screenH: Int,
        root: NodeSnap, detection: Detection, rulesVersion: Int,
    ): File {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val json = JSONObject().apply {
            put("package", pkg)
            put("versionCode", versionCode)
            put("versionName", versionName ?: "")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
            put("screen", JSONObject().put("w", screenW).put("h", screenH))
            put("rulesVersion", rulesVersion)
            put("matched", JSONObject().apply {
                put("blocks", JSONArray(detection.blocks.map { it.id }))
                put("markers", JSONArray(detection.markers.toList()))
                put("covers", JSONArray(detection.covers.map { it.targetId }))
            })
            put("nodeCount", root.flatten().size)
            put("tree", root.toJson())
        }
        val short = pkg.substringAfterLast('.')
        val f = File(dir(ctx), "dump-$short-$ts.json")
        f.writeText(json.toString(1))
        Log.i(TAG, "Wrote ${f.name} (${f.length()} bytes)")
        return f
    }
}
