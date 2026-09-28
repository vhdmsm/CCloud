package com.pira.ccloud.data.repository

import com.pira.ccloud.BuildConfig
import com.pira.ccloud.data.repository.ApiRelay.relayToken
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
 * by popularity, votes, how well known their actors are and release date.
 */
object TmdbClient {
    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val TOP_CAST_COUNT = 5
    // After a network error TMDB is skipped for a while (Watchmode is used meanwhile, if set up),
    // so a blocked connection doesn't make every movie wait for its own timeout
    private const val OFFLINE_BACKOFF_MS = 10 * 60_000L

    val isConfigured: Boolean get() = ApiRelay.isEnabled || BuildConfig.TMDB_API_KEY.isNotEmpty()

    // Through the relay the key is added on the server
    private val baseUrl: String get() = if (ApiRelay.isEnabled) "${ApiRelay.url}/tmdb" else BASE_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class TmdbMovie(
        // TMDB's trending score: views, votes and watchlist adds in the last days
        val popularity: Double,
        // Number of votes on TMDB: how many people have seen it overall
        val voteCount: Int,
        val voteAverage: Double,
        // Average popularity of the first billed actors
        val castPopularity: Double,
        // "2024-03-01", empty when unknown
        val releaseDate: String,
        // "tt1234567", empty when unknown; used to read awards from OMDb
        val imdbId: String,
        val originalLanguage: String,
        val originCountries: List<String>
    )

    // Found movies and movies TMDB doesn't know, by title and year; network errors aren't cached
    private val cache = ConcurrentHashMap<String, TmdbMovie>()
    private val notFound = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var offlineUntil = 0L

    val isReachable: Boolean get() = System.currentTimeMillis() >= offlineUntil

    val isAvailable: Boolean get() = isConfigured && isReachable

    fun TmdbMovie.toInfo() = MovieInfo(
        popularity = MovieRanking.logScale(popularity, 300.0),
        reach = MovieRanking.logScale(voteCount.toDouble(), 30_000.0),
        ratingConfidence = voteCount / (voteCount + MovieRanking.PRIOR_VOTES),
        castPopularity = MovieRanking.logScale(castPopularity, 60.0),
        releaseDate = releaseDate,
        imdbId = imdbId,
        originalLanguage = originalLanguage,
        originCountries = originCountries,
        source = MovieInfo.Source.TMDB
    )

    /** TMDB data for the movie with this title and year, or null when TMDB can't find it or can't be reached. */
    suspend fun movie(title: String, year: Int): TmdbMovie? = withContext(Dispatchers.IO) {
        if (!isConfigured || !isReachable) return@withContext null
        val query = cleanTitle(title)
        if (query.isEmpty()) return@withContext null
        val key = "${query.lowercase()}|$year"
        cache[key]?.let { return@withContext it }
        if (key in notFound) return@withContext null

        try {
            val match = findMovie(query, year)
            if (match == null) {
                notFound.add(key)
                return@withContext null
            }
            val language = match.optString("original_language")
            // Skipped movies (e.g. Indian) don't need the details request
            if (MovieRanking.isExcludedLanguage(language)) {
                return@withContext TmdbMovie(0.0, 0, 0.0, 0.0, "", "", language, emptyList())
                    .also { cache[key] = it }
            }
            val details = get("/movie/${match.optInt("id")}", "append_to_response" to "credits")
            val cast = details.optJSONObject("credits")?.optJSONArray("cast")
            val castPopularity = if (cast == null || cast.length() == 0) {
                0.0
            } else {
                val count = minOf(TOP_CAST_COUNT, cast.length())
                (0 until count).sumOf { cast.getJSONObject(it).optDouble("popularity", 0.0) } / count
            }
            val countries = details.optJSONArray("origin_country")
            TmdbMovie(
                popularity = details.optDouble("popularity", 0.0),
                voteCount = details.optInt("vote_count", 0),
                voteAverage = details.optDouble("vote_average", 0.0),
                castPopularity = castPopularity,
                releaseDate = details.optString("release_date"),
                imdbId = details.optString("imdb_id").takeIf { it.startsWith("tt") }.orEmpty(),
                originalLanguage = details.optString("original_language", language),
                originCountries = if (countries == null) emptyList() else (0 until countries.length()).map { countries.optString(it) }
            ).also { cache[key] = it }
        } catch (e: IOException) {
            offlineUntil = System.currentTimeMillis() + OFFLINE_BACKOFF_MS
            null
        } catch (e: Exception) {
            null
        }
    }

    // Best search match: same release year (±1, release dates differ between countries), else the top result
    private fun findMovie(query: String, year: Int): JSONObject? {
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
        return match?.takeIf { it.optInt("id") > 0 }
    }

    private fun get(path: String, vararg params: Pair<String, String>): JSONObject {
        val apiKey = if (ApiRelay.isEnabled) "" else BuildConfig.TMDB_API_KEY
        val url = "$baseUrl$path".toHttpUrl().newBuilder().apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
            // A v3 API key goes in the URL; a v4 read access token (a JWT) goes in the header
            if (apiKey.isNotEmpty() && !apiKey.startsWith("eyJ")) addQueryParameter("api_key", apiKey)
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { if (apiKey.startsWith("eyJ")) header("Authorization", "Bearer $apiKey") }
            .relayToken()
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
