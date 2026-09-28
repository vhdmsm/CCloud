package com.pira.ccloud.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.pira.ccloud.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Movie data from Watchmode (https://api.watchmode.com): popularity, how well known a movie and
 * its actors are, release dates and IMDb ids. The free plan has a small monthly quota (each request
 * costs a credit), so the keys are used in turn and answers are kept on the device for 30 days, the
 * longest its terms allow.
 */
object WatchmodeClient {
    private const val BASE_URL = "https://api.watchmode.com/v1"
    private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    // Actors whose popularity is read for Famous Actors (each costs a credit the first time)
    private const val TOP_CAST_COUNT = 3
    // A key that was rejected or used up its monthly quota is tried again after this
    private const val BLOCKED_BACKOFF_MS = 6L * 60 * 60 * 1000
    // Too many requests this minute, or no connection
    private const val SHORT_BACKOFF_MS = 60_000L

    private val keys: List<String>
        get() = listOf(BuildConfig.WATCHMODE_API_KEY, BuildConfig.WATCHMODE_API_KEY2).filter { it.isNotEmpty() }

    val isConfigured: Boolean get() = keys.isNotEmpty()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var prefs: SharedPreferences? = null
    private val memoryCache = ConcurrentHashMap<String, String>()

    private val keyBlockedUntil = ConcurrentHashMap<String, Long>()

    @Volatile
    private var offlineUntil = 0L

    // False while every key is rejected or out of quota, or Watchmode can't be reached
    val isAvailable: Boolean
        get() {
            val now = System.currentTimeMillis()
            return now >= offlineUntil && keys.any { (keyBlockedUntil[it] ?: 0L) <= now }
        }

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("watchmode", Context.MODE_PRIVATE)
    }

    /**
     * Data for the movie with this title and year, or null when Watchmode doesn't know it or
     * can't answer. [withCast] also reads how popular the lead actors are (more credits).
     */
    suspend fun movie(title: String, year: Int, withCast: Boolean): MovieInfo? = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext null
        val query = title.replace(Regex("\\((19|20)\\d{2}\\)"), " ").replace(Regex("\\s+"), " ").trim()
        if (query.isEmpty()) return@withContext null
        val key = "t:${query.lowercase()}|$year"

        try {
            var stored = readCache(key)?.let { JSONObject(it) }
            if (stored == null) {
                val id = findMovie(query, year)
                stored = if (id == null) JSONObject() else details(id)
                writeCache(key, stored.toString())
            }
            if (!stored.has("id")) return@withContext null

            if (withCast && !stored.has("cast_score") && !MovieRanking.isExcludedLanguage(stored.optString("original_language"))) {
                stored.put("cast_score", castScore(stored.getInt("id")))
                writeCache(key, stored.toString())
            }
            toInfo(stored)
        } catch (e: IOException) {
            offlineUntil = System.currentTimeMillis() + SHORT_BACKOFF_MS
            null
        } catch (e: Exception) {
            null
        }
    }

    data class SearchResult(val id: Int, val type: String, val year: Int)

    private fun findMovie(query: String, year: Int): Int? {
        val results = get("/search/", "search_field" to "name", "search_value" to query, "types" to "movie")
            .optJSONArray("title_results") ?: return null
        return pickMovie(
            (0 until results.length()).map { results.getJSONObject(it) }
                .map { SearchResult(it.optInt("id"), it.optString("type"), it.optInt("year")) },
            year
        )
    }

    /**
     * The search result of the same year (±1, release dates differ between countries). Watchmode
     * files some films as "tv_movie" or "tv_special" (concerts, documentaries), so any type but a
     * series is accepted, a "movie" first.
     */
    fun pickMovie(results: List<SearchResult>, year: Int): Int? {
        val films = results.filter { it.id > 0 && !it.type.startsWith("tv_series") && it.type != "tv_miniseries" }
            .sortedBy { if (it.type == "movie") 0 else 1 }
        val match = if (year > 0) films.firstOrNull { kotlin.math.abs(it.year - year) <= 1 } else films.firstOrNull()
        return match?.id
    }

    // Keeps only what the sorts use
    private fun details(id: Int): JSONObject {
        val details = get("/title/$id/details/")
        return JSONObject()
            .put("id", id)
            .put("popularity_percentile", details.optDouble("popularity_percentile", 0.0))
            .put("relevance_percentile", details.optDouble("relevance_percentile", 0.0))
            .put("release_date", details.optString("release_date"))
            .put("imdb_id", details.optString("imdb_id").takeIf { it.startsWith("tt") }.orEmpty())
            .put("original_language", details.optString("original_language"))
    }

    // Average fame score (0..1) of the first billed actors; actors' percentiles are cached on their own
    private fun castScore(titleId: Int): Double {
        val crew = get("/title/$titleId/cast-crew/").optJSONArray("__array") ?: return 0.0
        val actors = (0 until crew.length()).map { crew.getJSONObject(it) }
            .filter { it.optString("type").equals("Cast", ignoreCase = true) }
            .sortedBy { it.optInt("order", Int.MAX_VALUE) }
            .take(TOP_CAST_COUNT)
        if (actors.isEmpty()) return 0.0
        return actors.sumOf { actor ->
            val personId = actor.optInt("person_id")
            val personKey = "p:$personId"
            val percentile = readCache(personKey)?.toDoubleOrNull() ?: get("/person/$personId/")
                .optDouble("relevance_percentile", 0.0)
                .also { writeCache(personKey, it.toString()) }
            MovieRanking.percentileScore(percentile)
        } / actors.size
    }

    private fun toInfo(stored: JSONObject): MovieInfo {
        val relevance = stored.optDouble("relevance_percentile", 0.0)
        return MovieInfo(
            popularity = MovieRanking.percentileScore(stored.optDouble("popularity_percentile", 0.0)),
            reach = MovieRanking.percentileScore(relevance),
            // Watchmode has no vote count; well-known movies' scores are trusted more
            ratingConfidence = relevance / 100.0,
            castPopularity = if (stored.has("cast_score")) stored.getDouble("cast_score") else null,
            releaseDate = stored.optString("release_date"),
            imdbId = stored.optString("imdb_id"),
            originalLanguage = stored.optString("original_language")
        )
    }

    // Tries the keys in turn, skipping ones that are rejected or out of quota.
    // Arrays (cast-crew) come back wrapped as {"__array": [...]}
    private fun get(path: String, vararg params: Pair<String, String>): JSONObject {
        val url = "$BASE_URL$path".toHttpUrl().newBuilder()
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        for (key in keys) {
            if ((keyBlockedUntil[key] ?: 0L) > System.currentTimeMillis()) continue
            val request = Request.Builder()
                .url(url)
                .header("X-API-Key", key)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when (response.code) {
                    200 -> return if (body.trimStart().startsWith("[")) {
                        JSONObject().put("__array", org.json.JSONArray(body))
                    } else {
                        JSONObject(body)
                    }
                    404 -> return JSONObject()
                    401, 402, 403 -> blockKey(key, BLOCKED_BACKOFF_MS)
                    429 -> {
                        // Monthly quota used up, or just too many requests this minute
                        val quota = response.header("X-Account-Quota")?.toLongOrNull()
                        val used = response.header("X-Account-Quota-Used")?.toLongOrNull()
                        blockKey(key, if (quota != null && used != null && used >= quota) BLOCKED_BACKOFF_MS else SHORT_BACKOFF_MS)
                    }
                    else -> throw IllegalStateException("Watchmode error ${response.code}")
                }
            }
        }
        throw IllegalStateException("No Watchmode key is usable right now")
    }

    private fun blockKey(key: String, durationMs: Long) {
        keyBlockedUntil[key] = System.currentTimeMillis() + durationMs
    }

    private fun readCache(key: String): String? {
        memoryCache[key]?.let { return it }
        val entry = prefs?.getString(key, null) ?: return null
        val savedAt = entry.substringBefore('|').toLongOrNull() ?: return null
        if (System.currentTimeMillis() - savedAt > CACHE_TTL_MS) {
            prefs?.edit()?.remove(key)?.apply()
            return null
        }
        return entry.substringAfter('|').also { memoryCache[key] = it }
    }

    private fun writeCache(key: String, value: String) {
        memoryCache[key] = value
        prefs?.edit()?.putString(key, "${System.currentTimeMillis()}|$value")?.apply()
    }
}
