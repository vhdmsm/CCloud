package com.pira.ccloud.data.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * The site's newest-first lists as read for the ranked sorts, kept on the device: the series
 * ranking reads the whole list (about 240 pages) and a movie ranking the newest years, which took
 * from 20 seconds to a minute every time the app opened.
 *
 * A list is kept as one snapshot, so its pages fit together though new titles shift the site's
 * pages. A snapshot younger than its maximum age is simply used. An older one is still used at
 * once, and the pages read from it are read again in the background into the next snapshot,
 * which replaces it the next time the app opens (not while the list may be being read): the list
 * shows at once, and titles added since show the next time.
 */
object SiteListCache {
    private const val REFRESH_PARALLEL = 8
    private const val LOG_TAG = "CCloudSiteCache"

    private var dir: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshPermits = Semaphore(REFRESH_PARALLEL)

    // Per list: pages read again into the next snapshot (or being), and how many are still going
    private val refreshed = HashMap<String, MutableSet<Int>>()
    private val refreshesLeft = HashMap<String, Int>()
    // Lists used since the app started (a complete next snapshot is taken at the first use)
    private val usedLists = HashSet<String>()

    fun init(context: Context) {
        if (dir == null) dir = File(context.applicationContext.cacheDir, "site-lists")
    }

    /** Keeps the lists in [directory] (unit tests). */
    internal fun useDirectory(directory: File) {
        dir = directory
        synchronized(this) {
            refreshed.clear()
            refreshesLeft.clear()
            usedLists.clear()
        }
    }

    /**
     * The page's text, from the snapshot of [list] when it has the page (read again in the
     * background when the snapshot is older than [maxAgeMs]), else from [fetch]. [list] names the
     * list, e.g. "series-by_year-0".
     */
    suspend fun page(list: String, page: Int, maxAgeMs: Long, fetch: suspend () -> String): String {
        val root = dir?.let { File(it, list) } ?: return fetch()
        val stale = prepare(root, list, maxAgeMs)
        val cached = File(root, "current/$page.json")
        if (cached.exists()) {
            try {
                val text = cached.readText()
                if (stale) refreshInBackground(root, list, page, fetch)
                return text
            } catch (e: Exception) {
                // Read it from the site below
            }
        }
        val text = fetch()
        try {
            // A page the old snapshot doesn't have belongs to the next one
            if (stale) {
                synchronized(this) { refreshed.getOrPut(list) { HashSet() } += page }
                writeAtomically(File(root, "next/$page.json"), text)
            } else {
                writeAtomically(cached, text)
            }
        } catch (e: Exception) {
            // Only a cache
        }
        return text
    }

    /**
     * Gets [root] ready for reading a page; true when its snapshot is too old (its pages are read
     * again in the background). The first use since the app started takes a complete next
     * snapshot (before any page is read: many are read at once) or drops an unfinished one;
     * without any snapshot a new one is started.
     */
    @Synchronized
    private fun prepare(root: File, list: String, maxAgeMs: Long): Boolean {
        val current = File(root, "current")
        val next = File(root, "next")
        try {
            if (usedLists.add(list) && next.exists()) {
                if (File(next, "complete").exists() && File(next, "snapshot").exists()) {
                    current.deleteRecursively()
                    if (next.renameTo(current)) {
                        File(current, "complete").delete()
                        log("$list: refreshed list taken")
                    }
                } else {
                    next.deleteRecursively()
                }
                refreshed.remove(list)
            }
            val takenAt = readTime(File(current, "snapshot"))
            if (takenAt == null) {
                current.deleteRecursively()
                current.mkdirs()
                writeAtomically(File(current, "snapshot"), System.currentTimeMillis().toString())
                next.deleteRecursively()
                refreshed.remove(list)
                return false
            }
            val stale = System.currentTimeMillis() - takenAt > maxAgeMs
            if (stale && !next.exists()) {
                next.mkdirs()
                writeAtomically(File(next, "snapshot"), System.currentTimeMillis().toString())
            }
            return stale
        } catch (e: Exception) {
            return false
        }
    }

    // Reads [page] again into the next snapshot, once; the snapshot is marked complete when every
    // page asked for so far is done
    private fun refreshInBackground(root: File, list: String, page: Int, fetch: suspend () -> String) {
        synchronized(this) {
            if (!refreshed.getOrPut(list) { HashSet() }.add(page)) return
            refreshesLeft[list] = (refreshesLeft[list] ?: 0) + 1
        }
        scope.launch {
            try {
                refreshPermits.withPermit {
                    writeAtomically(File(root, "next/$page.json"), fetch())
                }
            } catch (e: Exception) {
                // Read from the site when asked for, next time
                log("$list page $page: refresh failed ($e)")
            } finally {
                synchronized(this@SiteListCache) {
                    val left = (refreshesLeft[list] ?: 1) - 1
                    refreshesLeft[list] = left
                    if (left == 0) {
                        try {
                            writeAtomically(File(root, "next/complete"), refreshed[list]?.size.toString())
                            log("$list: ${refreshed[list]?.size} pages refreshed")
                        } catch (e: Exception) {
                            // Tried again with the next refresh
                        }
                    }
                }
            }
        }
    }

    private fun readTime(file: File): Long? = try {
        if (file.exists()) file.readText().trim().toLongOrNull() else null
    } catch (e: Exception) {
        null
    }

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun log(message: String) {
        try {
            Log.i(LOG_TAG, message)
        } catch (e: RuntimeException) {
            // Unit tests (no Android logging)
        }
    }
}
