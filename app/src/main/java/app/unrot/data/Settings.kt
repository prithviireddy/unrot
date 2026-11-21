package app.unrot.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
            )
        }

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
