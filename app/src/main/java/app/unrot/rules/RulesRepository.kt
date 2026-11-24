package app.unrot.rules

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Holds the active rules: whichever of (bundled asset, last downloaded remote copy) has the higher version.
 */
object RulesRepository {
    private const val TAG = "UnrotRules"
    private const val REMOTE_FILE = "rules_remote.json"

    @Volatile
    var current: RuleSet = RuleSet(1, 0, emptyMap())
        private set

    @Volatile
    var source: String = "none"
        private set

    fun remoteFile(ctx: Context) = File(ctx.filesDir, REMOTE_FILE)

    fun load(ctx: Context) {
        val bundled = runCatching {
            RuleSet.parse(ctx.assets.open("rules.json").bufferedReader().use { it.readText() })
        }.onFailure { Log.e(TAG, "Bundled rules are invalid", it) }.getOrNull()

        val remote = remoteFile(ctx).takeIf { it.exists() }?.let { f ->
            runCatching { RuleSet.parse(f.readText()) }
                .onFailure { Log.w(TAG, "Remote rules invalid, ignoring", it) }.getOrNull()
        }

        val (chosen, src) = when {
            remote != null && (bundled == null || remote.version > bundled.version) -> remote to "remote"
            bundled != null -> bundled to "bundled"
            else -> return
        }
        current = chosen
        source = src
        Log.i(TAG, "Loaded $src rules v${chosen.version}")
    }

    /** Validates and stores downloaded rules. Returns the parsed set, or throws if invalid. */
    fun saveRemote(ctx: Context, json: String): RuleSet {
        val parsed = RuleSet.parse(json) // throws on bad JSON / schema
        val tmp = File(ctx.filesDir, "$REMOTE_FILE.tmp")
        tmp.writeText(json)
        tmp.renameTo(remoteFile(ctx))
        load(ctx)
        return parsed
    }
}
