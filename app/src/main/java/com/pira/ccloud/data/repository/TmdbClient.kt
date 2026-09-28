package com.pira.ccloud.data.repository

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
 * Minimal client for The Movie Database API (https://api.themoviedb.org), used to rank movies
 * by how popular they are on the internet and how well known their actors are.
 */
object TmdbClient {
    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val TOP_CAST_COUNT = 5
    // After a network error TMDB is skipped for a while, so a blocked connection doesn't
    // make every movie wait for its own timeout
    private const val OFFLINE_BACKOFF_MS = 60_000L

    val isConfigured: Boolean get() = BuildConfig.TMDB_API_KEY.isNotEmpty()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Popularity(
        // TMDB's trending score: views, votes and watchlist adds in the last days
        val popularity: Double,
        // Number of votes on TMDB: how many people have seen it overall
        val voteCount: Int,
        // Average popularity of the first billed actors
        val castPopularity: Double
    )

    // Found movies and movies TMDB doesn't know, by title and year; network errors aren't cached
    private val cache = ConcurrentHashMap<String, Popularity>()
    private val notFound = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var offlineUntil = 0L

    val isReachable: Boolean get() = System.currentTimeMillis() >= offlineUntil

    /** Popularity of the movie with this title and year, or null when TMDB can't find it or can't be reached. */
    suspend fun moviePopularity(title: String, year: Int): Popularity? = withContext(Dispatchers.IO) {
        if (!isConfigured || !isReachable) return@withContext null
        val query = cleanTitle(title)
        if (query.isEmpty()) return@withContext null
        val key = "${query.lowercase()}|$year"
        cache[key]?.let { return@withContext it }
        if (key in notFound) return@withContext null

        try {
            val movieId = findMovie(query, year)
            if (movieId == null) {
                notFound.add(key)
                return@withContext null
            }
            val details = get("/movie/$movieId", "append_to_response" to "credits")
            val cast = details.optJSONObject("credits")?.optJSONArray("cast")
            val castPopularity = if (cast == null || cast.length() == 0) {
                0.0
            } else {
                val count = minOf(TOP_CAST_COUNT, cast.length())
                (0 until count).sumOf { cast.getJSONObject(it).optDouble("popularity", 0.0) } / count
            }
            Popularity(
                popularity = details.optDouble("popularity", 0.0),
                voteCount = details.optInt("vote_count", 0),
                castPopularity = castPopularity
            ).also { cache[key] = it }
        } catch (e: IOException) {
            offlineUntil = System.currentTimeMillis() + OFFLINE_BACKOFF_MS
            null
        } catch (e: Exception) {
            null
        }
    }

    // Best search match: same release year (±1, release dates differ between countries), else the top result
    private fun findMovie(query: String, year: Int): Int? {
        val params = mutableListOf("query" to query, "include_adult" to "false")
        if (year > 0) params.add("year" to year.toString())
        var results = get("/search/movie", *params.toTypedArray()).optJSONArray("results")
        if ((results == null || results.length() == 0) && year > 0) {
            results = get("/search/movie", "query" to query, "include_adult" to "false").optJSONArray("results")
        }
        val found = results?.takeIf { it.length() > 0 } ?: return null

        val candidates = (0 until found.length()).map { found.getJSONObject(it) }
        val match = if (year > 0) {
            candidates.firstOrNull { result ->
                val releaseYear = result.optString("release_date").take(4).toIntOrNull()
                releaseYear != null && kotlin.math.abs(releaseYear - year) <= 1
            }
        } else {
            candidates.first()
        }
        return match?.optInt("id")?.takeIf { it > 0 }
    }

    private fun get(path: String, vararg params: Pair<String, String>): JSONObject {
        val apiKey = BuildConfig.TMDB_API_KEY
        val url = "$BASE_URL$path".toHttpUrl().newBuilder().apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
            // A v3 API key goes in the URL; a v4 read access token (a JWT) goes in the header
            if (!apiKey.startsWith("eyJ")) addQueryParameter("api_key", apiKey)
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { if (apiKey.startsWith("eyJ")) header("Authorization", "Bearer $apiKey") }
            .build()
        return client.newCall(request).execute().use { response ->
            // A rejected key fails every request, so treat it like an unreachable server
            if (response.code == 401) throw IOException("TMDB rejected the API key")
            if (!response.isSuccessful) throw IllegalStateException("TMDB error ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
    }

    // "Inception (2010)" -> "Inception"
    private fun cleanTitle(title: String): String =
        title.replace(Regex("\\((19|20)\\d{2}\\)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
