package com.pira.ccloud.utils

import android.content.Context
import android.util.Log
import com.pira.ccloud.data.model.FavoriteItem
import com.pira.ccloud.data.model.Movie
import com.pira.ccloud.data.model.Series
import com.pira.ccloud.data.model.WatchHistoryItem
import com.pira.ccloud.data.model.WatchStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Movies and series the user is watching or has seen, shown in the Watching and Seen sections.
 * Playing a movie or an episode puts it under Watching; finishing the movie (or the series' last
 * episode) moves it to Seen. Kept in one JSON file, like the favorites.
 */
object WatchHistory {
    private const val TAG = "WatchHistory"
    private const val FILE_NAME = "watch_history.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): List<WatchHistoryItem> = try {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) json.decodeFromString<List<WatchHistoryItem>>(file.readText()) else emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Error loading watch history", e)
        emptyList()
    }

    // Items with this status, the most recently watched first
    fun load(context: Context, status: WatchStatus): List<WatchHistoryItem> =
        WatchHistoryRules.withStatus(load(context), status)

    fun get(context: Context, id: Int, type: String): WatchHistoryItem? =
        load(context).firstOrNull { it.item.id == id && it.item.type == type }

    fun markWatching(context: Context, item: FavoriteItem, finalEpisodeKey: String? = null) =
        update(context) { WatchHistoryRules.put(it, item, WatchStatus.WATCHING, now(), finalEpisodeKey) }

    fun markSeen(context: Context, item: FavoriteItem) =
        update(context) { WatchHistoryRules.put(it, item, WatchStatus.SEEN, now(), null) }

    fun remove(context: Context, id: Int, type: String) =
        update(context) { list -> list.filterNot { it.item.id == id && it.item.type == type } }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing watch history", e)
        }
    }

    // Called with the player's position: a finished movie, or a series' finished last episode, becomes Seen
    fun onPlaybackProgress(context: Context, playbackKey: String, positionMs: Long, durationMs: Long) {
        if (!WatchHistoryRules.isFinished(positionMs, durationMs)) return
        val list = load(context)
        val updated = WatchHistoryRules.afterFinished(list, playbackKey, now())
        if (updated != list) save(context, updated)
    }

    @Synchronized
    private fun update(context: Context, change: (List<WatchHistoryItem>) -> List<WatchHistoryItem>) {
        save(context, change(load(context)))
    }

    private fun save(context: Context, list: List<WatchHistoryItem>) {
        try {
            File(context.filesDir, FILE_NAME).writeText(json.encodeToString(list))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving watch history", e)
        }
    }

    private fun now() = System.currentTimeMillis()
}

/** The rules of the watch history, apart from storage so they can be tested. */
object WatchHistoryRules {
    // Same share as PlaybackPositions: past it, the video counts as finished
    const val FINISHED_FRACTION = 0.95
    const val MAX_ENTRIES = 500

    fun isFinished(positionMs: Long, durationMs: Long) =
        durationMs > 0 && positionMs >= durationMs * FINISHED_FRACTION

    fun withStatus(list: List<WatchHistoryItem>, status: WatchStatus) =
        list.filter { it.status == status }.sortedByDescending { it.updatedAt }

    // Adds the item or updates its status (a series keeps its last episode when none is given)
    fun put(
        list: List<WatchHistoryItem>,
        item: FavoriteItem,
        status: WatchStatus,
        now: Long,
        finalEpisodeKey: String?
    ): List<WatchHistoryItem> {
        val old = list.firstOrNull { it.item.id == item.id && it.item.type == item.type }
        val entry = WatchHistoryItem(
            item = item,
            status = status,
            updatedAt = now,
            finalEpisodeKey = finalEpisodeKey ?: old?.finalEpisodeKey
        )
        val rest = list.filterNot { it.item.id == item.id && it.item.type == item.type }
        return (listOf(entry) + rest).sortedByDescending { it.updatedAt }.take(MAX_ENTRIES)
    }

    // The list after the video with this playback key was watched to the end
    fun afterFinished(list: List<WatchHistoryItem>, playbackKey: String, now: Long): List<WatchHistoryItem> {
        val movieId = playbackKey.removePrefix("movie:").takeIf { it != playbackKey }?.toIntOrNull()
        return list.map { entry ->
            val finishes = when (entry.item.type) {
                "movie" -> movieId != null && entry.item.id == movieId
                "series" -> entry.finalEpisodeKey == playbackKey
                else -> false
            }
            if (finishes && entry.status != WatchStatus.SEEN) {
                entry.copy(status = WatchStatus.SEEN, updatedAt = now)
            } else {
                entry
            }
        }
    }
}

fun Movie.toWatchItem() = FavoriteItem(
    id = id,
    type = "movie",
    title = title,
    description = description,
    year = year,
    imdb = imdb,
    rating = rating,
    duration = duration,
    image = image,
    cover = cover,
    genres = genres,
    country = country,
    sources = sources
)

fun Series.toWatchItem() = FavoriteItem(
    id = id,
    type = "series",
    title = title,
    description = description,
    year = year,
    imdb = imdb,
    rating = rating,
    duration = duration,
    image = image,
    cover = cover,
    genres = genres,
    country = country
)
