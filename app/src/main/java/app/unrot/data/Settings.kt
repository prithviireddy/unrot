package app.unrot.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.store by preferencesDataStore("settings")

data class Settings(
    val onboarded: Boolean = false,
    val instagramEnabled: Boolean = true,
    val youtubeEnabled: Boolean = true,
    val dmException: Boolean = true,
    val caughtUpEnabled: Boolean = true,
    val frictionSeconds: Int = 30,
    val inspectorEnabled: Boolean = false,
    val devUnlocked: Boolean = false,
    val blockedDate: String = "",
    val blockedCount: Int = 0,
    /**
     * 0 = v1 disclosure accepted (or none); 2 = the screenshot disclosure accepted, no capture unless >= 2; 3 = also says the
     * bar's tab pictures are kept on the phone (docs/decisions/0009). Pictures are saved to storage only when >= 3.
     */
    val disclosureVersion: Int = 0,
    /** Plain patch in the rule colour, no screenshots. */
    val simplePatch: Boolean = false,
    /** Last sampled bar colours, keyed "pkg|true" (dark) or "pkg|false" (light), plus "pkg|appearance" and "pkg|night". Not sensitive. */
    val barColors: Map<String, Int> = emptyMap(),
    /** Set to the time when the user deletes the saved bar pictures; the running service clears its crops when it changes. */
    val barCacheResetAt: Long = 0L,
) {
    fun featureEnabled(feature: String) = when (feature) {
        "reels" -> instagramEnabled
        "shorts" -> youtubeEnabled
        "caughtup" -> caughtUpEnabled
        else -> false
    }

    /** Today's count; stale counts from a previous day read as 0. */
    val blockedToday: Int get() = if (blockedDate == LocalDate.now().toString()) blockedCount else 0

    companion object {
        private val ONBOARDED = booleanPreferencesKey("onboarded")
        private val IG = booleanPreferencesKey("ig_enabled")
        private val YT = booleanPreferencesKey("yt_enabled")
        private val DM = booleanPreferencesKey("dm_exception")
        private val CAUGHT_UP = booleanPreferencesKey("caught_up")
        private val FRICTION = intPreferencesKey("friction_seconds")
        private val INSPECTOR = booleanPreferencesKey("inspector")
        private val DEV = booleanPreferencesKey("dev_unlocked")
        private val BLOCKED_DATE = stringPreferencesKey("blocked_date")
        private val BLOCKED_COUNT = intPreferencesKey("blocked_count")
        private val DISCLOSURE_VERSION = intPreferencesKey("disclosure_version")
        private val SIMPLE_PATCH = booleanPreferencesKey("simple_patch")
        private val BAR_COLORS = stringPreferencesKey("bar_colors")
        private val BAR_CACHE_RESET_AT = longPreferencesKey("bar_cache_reset_at")

        fun flow(ctx: Context): Flow<Settings> = ctx.store.data.map { p ->
            Settings(
                onboarded = p[ONBOARDED] ?: false,
                instagramEnabled = p[IG] ?: true,
                youtubeEnabled = p[YT] ?: true,
                dmException = p[DM] ?: true,
                caughtUpEnabled = p[CAUGHT_UP] ?: true,
                frictionSeconds = p[FRICTION] ?: 30,
                inspectorEnabled = p[INSPECTOR] ?: false,
                devUnlocked = p[DEV] ?: false,
                blockedDate = p[BLOCKED_DATE] ?: "",
                blockedCount = p[BLOCKED_COUNT] ?: 0,
                disclosureVersion = p[DISCLOSURE_VERSION] ?: 0,
                simplePatch = p[SIMPLE_PATCH] ?: false,
                barColors = parseColors(p[BAR_COLORS]),
                barCacheResetAt = p[BAR_CACHE_RESET_AT] ?: 0L,
            )
        }

        /** "key=colour;key=colour" (see [Settings.barColors]) to a map. Malformed entries are skipped. */
        internal fun parseColors(s: String?): Map<String, Int> {
            if (s.isNullOrEmpty()) return emptyMap()
            return s.split(';').mapNotNull { entry ->
                val i = entry.lastIndexOf('=')
                if (i <= 0) null else entry.substring(0, i) to (entry.substring(i + 1).toIntOrNull() ?: return@mapNotNull null)
            }.toMap()
        }

        internal fun formatColors(m: Map<String, Int>): String = m.entries.joinToString(";") { "${it.key}=${it.value}" }

        private suspend fun <T> set(ctx: Context, key: Preferences.Key<T>, v: T) {
            ctx.store.edit { it[key] = v }
        }

        suspend fun setOnboarded(ctx: Context, v: Boolean) = set(ctx, ONBOARDED, v)
        suspend fun setInstagram(ctx: Context, v: Boolean) = set(ctx, IG, v)
        suspend fun setYoutube(ctx: Context, v: Boolean) = set(ctx, YT, v)
        suspend fun setDmException(ctx: Context, v: Boolean) = set(ctx, DM, v)
        suspend fun setCaughtUp(ctx: Context, v: Boolean) = set(ctx, CAUGHT_UP, v)
        suspend fun setInspector(ctx: Context, v: Boolean) = set(ctx, INSPECTOR, v)
        suspend fun setDevUnlocked(ctx: Context, v: Boolean) = set(ctx, DEV, v)
        suspend fun setDisclosureVersion(ctx: Context, v: Int) = set(ctx, DISCLOSURE_VERSION, v)
        suspend fun setSimplePatch(ctx: Context, v: Boolean) = set(ctx, SIMPLE_PATCH, v)
        suspend fun setBarColors(ctx: Context, v: Map<String, Int>) = set(ctx, BAR_COLORS, formatColors(v))
        suspend fun setBarCacheResetAt(ctx: Context, v: Long) = set(ctx, BAR_CACHE_RESET_AT, v)

        suspend fun incrementBlocked(ctx: Context) {
            val today = LocalDate.now().toString()
            ctx.store.edit { p ->
                val count = if (p[BLOCKED_DATE] == today) (p[BLOCKED_COUNT] ?: 0) else 0
                p[BLOCKED_DATE] = today
                p[BLOCKED_COUNT] = count + 1
            }
        }
    }
}
