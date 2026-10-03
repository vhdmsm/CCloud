package com.pira.ccloud.data.repository

import android.content.Context
import com.pira.ccloud.data.model.Movie
import com.pira.ccloud.data.model.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The first batch of each ranked list (sort and genre) as last shown, kept on the device: when the
 * app opens it shows at once while the list is ranked again in the background, instead of an
 * empty screen until the ranking is done.
 */
object RankedListStore {
    // Older lists aren't shown (the ranking may have changed a lot)
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    @Serializable
    data class SavedMovies(val savedAt: Long, val movies: List<Movie>, val notice: String? = null, val attribution: String? = null)

    @Serializable
    data class SavedSeries(val savedAt: Long, val series: List<Series>, val notice: String? = null, val attribution: String? = null)

    private var dir: File? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun init(context: Context) {
        if (dir == null) dir = File(context.applicationContext.filesDir, "ranked-lists")
    }

    suspend fun movies(key: String): SavedMovies? = read<SavedMovies>("movies-$key")?.takeIf { isRecent(it.savedAt) }

    suspend fun series(key: String): SavedSeries? = read<SavedSeries>("series-$key")?.takeIf { isRecent(it.savedAt) }

    suspend fun saveMovies(key: String, movies: List<Movie>, notice: String?, attribution: String?) =
        write("movies-$key", json.encodeToString(SavedMovies(System.currentTimeMillis(), movies, notice, attribution)))

    suspend fun saveSeries(key: String, series: List<Series>, notice: String?, attribution: String?) =
        write("series-$key", json.encodeToString(SavedSeries(System.currentTimeMillis(), series, notice, attribution)))

    private fun isRecent(savedAt: Long) = System.currentTimeMillis() - savedAt <= MAX_AGE_MS

    private suspend inline fun <reified T> read(name: String): T? = withContext(Dispatchers.IO) {
        val file = dir?.let { File(it, "$name.json") } ?: return@withContext null
        try {
            if (file.exists()) json.decodeFromString<T>(file.readText()) else null
        } catch (e: Exception) {
            // Saved by an older version, or cut short: ranked again anyway
            null
        }
    }

    private suspend fun write(name: String, text: String) = withContext(Dispatchers.IO) {
        val folder = dir ?: return@withContext
        try {
            folder.mkdirs()
            val tmp = File(folder, "$name.json.tmp")
            tmp.writeText(text)
            val file = File(folder, "$name.json")
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            // Only a convenience
        }
    }
}
