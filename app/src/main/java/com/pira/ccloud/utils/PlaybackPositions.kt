package com.pira.ccloud.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Last playback position of movies and episodes, so playback can continue where it stopped.
 * Keyed by movie / episode, not by URL, so another quality of the same video resumes too.
 */
object PlaybackPositions {
    private const val PREFS_NAME = "playback_positions"
    private const val MAX_ENTRIES = 300
    // Positions this close to the start aren't worth resuming
    const val MIN_RESUME_MS = 10_000L
    // Watched past this share of the video counts as finished: next time starts from the beginning
    private const val FINISHED_FRACTION = 0.95

    fun movieKey(movieId: Int) = "movie:$movieId"

    fun episodeKey(seriesId: Int, seasonId: Int, episodeId: Int) = "episode:$seriesId:$seasonId:$episodeId"

    fun urlKey(videoUrl: String) = "url:$videoUrl"

    // Saved position in milliseconds, or null when there is nothing to resume
    fun get(context: Context, key: String): Long? =
        prefs(context).getString(key, null)
            ?.substringBefore(',')
            ?.toLongOrNull()
            ?.takeIf { it >= MIN_RESUME_MS }

    fun save(context: Context, key: String, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && positionMs >= durationMs * FINISHED_FRACTION
        if (finished || positionMs < MIN_RESUME_MS) {
            remove(context, key)
            return
        }
        val prefs = prefs(context)
        prefs.edit()
            .putString(key, "$positionMs,$durationMs,${System.currentTimeMillis()}")
            .apply()
        pruneOldest(prefs)
    }

    fun remove(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    private fun pruneOldest(prefs: SharedPreferences) {
        val entries = prefs.all
        if (entries.size <= MAX_ENTRIES) return
        val oldest = entries.entries
            .sortedBy { (it.value as? String)?.substringAfterLast(',')?.toLongOrNull() ?: 0L }
            .take(entries.size - MAX_ENTRIES)
        prefs.edit().apply { oldest.forEach { remove(it.key) } }.apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

// Saved position of a movie/episode, read again whenever the screen is shown (e.g. after the player)
@Composable
fun rememberPlaybackPosition(key: String): Long? {
    val context = LocalContext.current
    var position by remember(key) { mutableStateOf(PlaybackPositions.get(context, key)) }
    LifecycleResumeEffect(key) {
        position = PlaybackPositions.get(context, key)
        onPauseOrDispose { }
    }
    return position
}
