package app.unrot.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.unrot.BuildConfig
import app.unrot.rules.RulesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Periodically downloads the detection rules from static hosting, and checks that the
 * accessibility service is still running (posting a notification if it was killed).
 */
class RulesWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        ServiceWatchdog.check(applicationContext)
        val url = BuildConfig.RULES_URL
        if (url.isBlank()) return Result.success()
        return try {
            val body = withContext(Dispatchers.IO) { fetch(url) }
            val rules = RulesRepository.saveRemote(applicationContext, body)
            Log.i(TAG, "Fetched rules v${rules.version}")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Rules fetch failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }.also {
                check(it.length < 512 * 1024) { "Rules file too large" }
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "UnrotRules"
        private const val PERIODIC = "rules-periodic"
        private const val NOW = "rules-now"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RulesWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun refreshNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<RulesWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
