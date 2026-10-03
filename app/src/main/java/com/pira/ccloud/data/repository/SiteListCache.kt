package com.pira.ccloud.data.repository

import android.content.Context
import java.io.File

/**
 * The site's newest-first lists as read for the ranked sorts, kept on the device for a few hours:
 * the series ranking reads the whole list (about 200 pages) and a movie ranking the newest years,
 * which took most of a minute every time the app opened.
 *
 * A list is kept as one snapshot: once it's too old, a new one is started and every page is read
 * again, so the pages of a snapshot fit together though new titles shift the site's pages.
 */
object SiteListCache {
    private var dir: File? = null

    fun init(context: Context) {
        if (dir == null) dir = File(context.applicationContext.cacheDir, "site-lists")
    }

    /**
     * The page's text: from the snapshot of [list] when it's younger than [maxAgeMs], else from
     * [fetch] (kept for the snapshot). [list] names the list, e.g. "series-year-0".
     */
    suspend fun page(list: String, page: Int, maxAgeMs: Long, fetch: suspend () -> String): String {
        val listDir = dir?.let { File(it, list) } ?: return fetch()
        val file = File(listDir, "$page.json")
        if (useSnapshot(listDir, maxAgeMs) && file.exists()) {
            try {
                return file.readText()
            } catch (e: Exception) {
                // Read it again below
            }
        }
        val text = fetch()
        try {
            writeAtomically(file, text)
        } catch (e: Exception) {
            // Only a cache
        }
        return text
    }

    /**
     * True when [listDir] holds a snapshot young enough to read from; else a new, empty one is
     * started (pages read at the same time all go into it).
     */
    @Synchronized
    private fun useSnapshot(listDir: File, maxAgeMs: Long): Boolean {
        val meta = File(listDir, "snapshot")
        val takenAt = try {
            if (meta.exists()) meta.readText().trim().toLong() else 0L
        } catch (e: Exception) {
            0L
        }
        if (takenAt > 0 && System.currentTimeMillis() - takenAt <= maxAgeMs) return true
        try {
            listDir.deleteRecursively()
            listDir.mkdirs()
            writeAtomically(meta, System.currentTimeMillis().toString())
        } catch (e: Exception) {
            // Read without the cache
        }
        return false
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
