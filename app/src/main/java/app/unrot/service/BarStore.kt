package app.unrot.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bar's tab pictures on the phone, in the app's private storage: one PNG per crop under `files/bar-cache/`, named
 * SHA-256(key) hex + ".png", with `index.json` describing them. The index is
 * `{"rulesVersion": n, "files": {filename: {key, pkg, bytes, savedAt}}}` (the key holds the app version).
 *
 * All file IO runs on a single background thread, never on main. The public calls may be made from main: [save] copies
 * the bitmap before handing it over, and [loadAll] delivers its result on main. Files that are corrupt, orphaned, or do
 * not match the index are deleted. The total is capped at [CAP_BYTES], oldest first. Keys are never logged.
 */
class BarStore(context: Context, rulesVersion: Int) {
    private val dir = File(context.applicationContext.filesDir, DIR_NAME)
    private val indexFile = File(dir, INDEX_NAME)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "unrot-bar-store").apply { isDaemon = true } }

    // Executor-only state, loaded from the index on first use.
    private var rules = rulesVersion
    private var loaded = false
    private val entries = LinkedHashMap<String, Entry>()
    private var totalBytes = 0L

    private class Entry(val key: String, val pkg: String, val bytes: Long, val savedAt: Long)

    /** Decodes every saved picture on the background thread, then calls [onLoaded] on main with (key, bitmap) pairs. */
    fun loadAll(onLoaded: (List<Pair<String, Bitmap>>) -> Unit) {
        submit {
            val out = ArrayList<Pair<String, Bitmap>>()
            var decoded = 0L
            // Newest first. Stops before the decoded pictures would exceed the cap; the rest stay on disk for a later load.
            for ((name, e) in entries.entries.sortedByDescending { it.value.savedAt }.map { it.key to it.value }) {
                try {
                    val bmp = BitmapFactory.decodeFile(File(dir, name).path)
                    if (bmp == null) {
                        Log.w(TAG, "saved picture unreadable, deleted")
                        removeEntry(name)
                        continue
                    }
                    val size = bmp.allocationByteCount.toLong()
                    if (decoded + size > CAP_BYTES) { bmp.recycle(); break }
                    decoded += size
                    out += e.key to bmp
                } catch (oom: OutOfMemoryError) {
                    break // memory is short: keep this file and the rest, and load no more now
                } catch (t: Throwable) {
                    Log.w(TAG, "saved picture failed to load, deleted: ${t.javaClass.simpleName}")
                    removeEntry(name)
                }
            }
            writeIndex()
            main.post { onLoaded(out) }
        }
    }

    /** Saves one crop under [key]. Copies the bitmap now, on the caller's thread, then compresses it in the background. */
    fun save(key: String, bitmap: Bitmap) {
        val copy = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        } catch (e: RuntimeException) {
            return
        }
        val queued = submit {
            try {
                val name = fileName(key)
                val target = File(dir, name)
                val tmp = File(dir, "$name.tmp")
                dir.mkdirs()
                try {
                    FileOutputStream(tmp).use { out ->
                        if (!copy.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("compress")
                    }
                    atomicMove(tmp, target)
                } finally {
                    tmp.delete()
                }
                // Forget the old entry only: removeEntry() would also delete the file just written.
                entries.remove(name)?.let { totalBytes -= it.bytes }
                val bytes = target.length()
                entries[name] = Entry(key, key.substringBefore('|'), bytes, System.currentTimeMillis())
                totalBytes += bytes
                enforceCap()
                writeIndex()
            } catch (e: IOException) {
                Log.w(TAG, "save failed: ${e.javaClass.simpleName}")
            } finally {
                copy.recycle()
            }
        }
        if (!queued) copy.recycle()
    }

    /** Deletes the pictures of [keys]. */
    fun delete(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val list = keys.toList()
        submit {
            list.forEach { removeEntry(fileName(it)) }
            writeIndex()
        }
    }

    /** Deletes the pictures of one package (every version). */
    fun deletePkg(pkg: String) {
        submit {
            entries.filter { it.value.pkg == pkg }.keys.toList().forEach { removeEntry(it) }
            writeIndex()
        }
    }

    /** Deletes every picture. The rules version is read on the store's thread, not here. */
    fun deleteAll() {
        submit {
            wipeDir()
            writeIndex()
        }
    }

    /** Deletes every picture and records [newRulesVersion] as the rules the next pictures belong to. */
    fun resetAll(newRulesVersion: Int) {
        submit {
            rules = newRulesVersion
            wipeDir()
            writeIndex()
        }
    }

    /** Stops accepting work. Writes already queued still run. */
    fun shutdown() {
        executor.shutdown()
    }

    /** Runs [block] on the background thread after the index is read. False if the store is shut down. */
    private fun submit(block: () -> Unit): Boolean = try {
        executor.execute {
            try {
                ensureLoaded()
                block()
            } catch (e: RuntimeException) {
                Log.w(TAG, "store task failed: ${e.javaClass.simpleName}")
            }
        }
        true
    } catch (e: RejectedExecutionException) {
        false
    }

    /** Reads the index once. A different rules version, or an unreadable index, empties the store. */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val parsed = try {
            if (indexFile.exists()) JSONObject(indexFile.readText()) else null
        } catch (e: Exception) {
            Log.w(TAG, "index unreadable, cleared")
            null
        }
        if (parsed == null || parsed.optInt("rulesVersion", -1) != rules) {
            wipeDir()
            return
        }
        val files = parsed.optJSONObject("files") ?: JSONObject()
        val found = ArrayList<Pair<String, Entry>>()
        for (name in files.keys()) {
            val o = files.optJSONObject(name) ?: continue
            val key = o.optString("key")
            val file = File(dir, name)
            if (key.isEmpty() || name != fileName(key) || !file.isFile) {
                file.delete()
                continue
            }
            found += name to Entry(key, o.optString("pkg"), file.length(), o.optLong("savedAt"))
        }
        // The index is a JSON object (no order): insert oldest first, so the map's order is the age order for eviction.
        for ((name, e) in found.sortedBy { it.second.savedAt }) {
            entries[name] = e
            totalBytes += e.bytes
        }
        // Pictures no index entry refers to (and stray temp files) are deleted.
        dir.listFiles()?.forEach { f -> if (f.name !in entries && f.name != INDEX_NAME) f.delete() }
        enforceCap()
        writeIndex()
    }

    private fun enforceCap() {
        // The map is in insertion order (a re-save moves the entry to the end), so the first key is the oldest.
        while (totalBytes > CAP_BYTES && entries.isNotEmpty()) {
            val oldest = entries.keys.first()
            removeEntry(oldest)
        }
    }

    private fun removeEntry(name: String) {
        entries.remove(name)?.let { totalBytes -= it.bytes }
        File(dir, name).delete()
    }

    private fun wipeDir() {
        entries.clear()
        totalBytes = 0L
        dir.deleteRecursively()
    }

    private fun writeIndex() {
        dir.mkdirs()
        val files = JSONObject()
        for ((name, e) in entries) {
            files.put(
                name,
                JSONObject()
                    .put("key", e.key)
                    .put("pkg", e.pkg)
                    .put("bytes", e.bytes)
                    .put("savedAt", e.savedAt),
            )
        }
        val doc = JSONObject().put("rulesVersion", rules).put("files", files).toString()
        val tmp = File(dir, "$INDEX_NAME.tmp")
        try {
            tmp.writeText(doc)
            atomicMove(tmp, indexFile)
        } catch (e: IOException) {
            Log.w(TAG, "index write failed: ${e.javaClass.simpleName}")
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "UnrotBarStore"
        private const val DIR_NAME = "bar-cache"
        private const val INDEX_NAME = "index.json"
        /** Total size of the saved pictures on disk. The oldest go first. */
        const val CAP_BYTES = 4L * 1024 * 1024 // also the most decoded picture memory loadAll may take

        /** Hash of the key: the file name without extension. Keys themselves are not file names. */
        fun fileName(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) } + ".png"
        }

        private fun atomicMove(from: File, to: File) {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        /** Deletes the saved pictures from disk, whether or not a service is running. Used by the Home screen. */
        suspend fun wipe(context: Context) = withContext(Dispatchers.IO) {
            File(context.applicationContext.filesDir, DIR_NAME).deleteRecursively()
        }
    }
}
