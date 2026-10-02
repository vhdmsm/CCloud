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
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Awards and the current IMDb rating from OMDb (https://www.omdbapi.com). The server's IMDb score
 * is the one from when the movie was added, which is far off for new movies. Each key allows 1000
 * requests a day, so the keys are used in turn and answers are kept on the device: a few days for
 * recent movies (their rating still moves), a month for older ones.
 */
object OmdbClient {
    private const val BASE_URL = "https://www.omdbapi.com/"
    private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val RECENT_CACHE_TTL_MS = 3L * 24 * 60 * 60 * 1000
    // A key that hit its daily limit is tried again after this
    private const val LIMIT_BACKOFF_MS = 3L * 60 * 60 * 1000
    private const val OFFLINE_BACKOFF_MS = 60_000L

    // Any number of keys (OMDB_API_KEY, OMDB_API_KEY2, ...), comma-separated by the build
    private val keys: List<String> by lazy {
        BuildConfig.OMDB_API_KEYS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    val isConfigured: Boolean get() = keys.isNotEmpty()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Awards(
        val oscarWins: Int,
        val oscarNominations: Int,
        // All wins and nominations, Oscars included
        val wins: Int,
        val nominations: Int,
        // OMDb's own summary, e.g. "Won 3 Oscars. 94 wins & 172 nominations total"; empty when not from OMDb
        val summary: String = ""
    )

    // IMDb's current rating and how many people voted
    data class Rating(val imdb: Double, val votes: Int)

    data class Details(val awards: Awards, val rating: Rating?)

    private var prefs: SharedPreferences? = null
    private val memoryCache = ConcurrentHashMap<String, Details>()
    private val keyBlockedUntil = ConcurrentHashMap<String, Long>()

    @Volatile
    private var offlineUntil = 0L

    // False when every key is over its daily limit or OMDb can't be reached
    val isAvailable: Boolean
        get() {
            val now = System.currentTimeMillis()
            return now >= offlineUntil && keys.any { (keyBlockedUntil[it] ?: 0L) <= now }
        }

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("omdb", Context.MODE_PRIVATE)
    }

    /**
     * Awards and rating of the movie with this IMDb id ([year]: its release year, for how long the
     * answer is kept); null when unknown. [cachedOnly]: only a fresh kept answer, no request. When OMDb can't answer (every key
     * at its daily limit, no connection) an older kept answer is used, so hitting a limit loses nothing.
     */
    suspend fun details(imdbId: String, year: Int, cachedOnly: Boolean = false): Details? = withContext(Dispatchers.IO) {
        if (imdbId.isEmpty() || !isConfigured) return@withContext null
        val ttl = if (year >= Calendar.getInstance().get(Calendar.YEAR) - 1) RECENT_CACHE_TTL_MS else CACHE_TTL_MS
        val cached = readCache(imdbId)
        if (cached != null && System.currentTimeMillis() - cached.first <= ttl) {
            return@withContext memoryCache.getOrPut(imdbId) { parseDetails(cached.second) }
        }
        memoryCache.remove(imdbId)
        val stale = cached?.let { parseDetails(it.second) }
        // Only a kept answer that's still fresh counts as cached, so an old rating gets asked for again
        if (cachedOnly) return@withContext null
        if (System.currentTimeMillis() < offlineUntil) return@withContext stale

        for (key in keys) {
            if ((keyBlockedUntil[key] ?: 0L) > System.currentTimeMillis()) continue
            try {
                val json = get(imdbId, key)
                // Not an OMDb answer (e.g. an error page): try again later, cache nothing
                if (!json.has("Response")) return@withContext stale
                if (json.optString("Response") == "True") {
                    val kept = JSONObject()
                        .put("Awards", json.optString("Awards", "N/A"))
                        .put("imdbRating", json.optString("imdbRating", "N/A"))
                        .put("imdbVotes", json.optString("imdbVotes", "N/A"))
                    writeCache(imdbId, kept)
                    return@withContext parseDetails(kept).also { memoryCache[imdbId] = it }
                }
                val error = json.optString("Error")
                if (error.contains("limit", ignoreCase = true) || error.contains("API key", ignoreCase = true)) {
                    // Daily limit or a wrong key: move on to the next key
                    keyBlockedUntil[key] = System.currentTimeMillis() + LIMIT_BACKOFF_MS
                    continue
                }
                // e.g. "Incorrect IMDb ID.": nothing to know about
                val nothing = JSONObject().put("Awards", "N/A")
                writeCache(imdbId, nothing)
                return@withContext parseDetails(nothing).also { memoryCache[imdbId] = it }
            } catch (e: IOException) {
                offlineUntil = System.currentTimeMillis() + OFFLINE_BACKOFF_MS
                return@withContext stale
            } catch (e: Exception) {
                return@withContext stale
            }
        }
        stale
    }

    private fun parseDetails(json: JSONObject): Details =
        parseDetails(json.optString("Awards", "N/A"), json.optString("imdbRating"), json.optString("imdbVotes"))

    /** Awards and rating from OMDb's fields, e.g. imdbRating "8.4" and imdbVotes "477,953" ("N/A" when unknown). */
    fun parseDetails(awards: String, imdbRating: String, imdbVotes: String): Details {
        val imdb = imdbRating.toDoubleOrNull()
        val votes = imdbVotes.replace(",", "").toIntOrNull() ?: 0
        return Details(
            awards = parseAwards(awards),
            rating = imdb?.takeIf { it > 0 }?.let { Rating(it, votes) }
        )
    }

    /**
     * Reads OMDb's award summary, e.g. "Won 3 Oscars. 94 wins & 172 nominations total",
     * "Nominated for 1 Oscar. 5 wins & 20 nominations total" or "N/A".
     */
    fun parseAwards(text: String): Awards {
        fun count(pattern: String) =
            Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val oscarWins = count("Won (\\d+) Oscars?")
        val oscarNominations = count("Nominated for (\\d+) Oscars?")
        return Awards(
            oscarWins = oscarWins,
            oscarNominations = oscarNominations,
            wins = maxOf(count("(\\d+) wins?\\b"), oscarWins),
            nominations = maxOf(count("(\\d+) nominations?\\b"), oscarNominations),
            summary = text.takeIf { it != "N/A" }.orEmpty()
        )
    }

    private fun get(imdbId: String, key: String): JSONObject {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("i", imdbId)
            .addQueryParameter("apikey", key)
            .build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            // The daily limit comes back as 401 with a JSON error, so read the body either way
            val body = response.body?.string().orEmpty()
            try {
                JSONObject(body)
            } catch (e: Exception) {
                throw IllegalStateException("OMDb error ${response.code}")
            }
        }
    }

    // When the answer was kept, and the answer
    private fun readCache(imdbId: String): Pair<Long, JSONObject>? {
        val entry = prefs?.getString(imdbId, null) ?: return null
        val savedAt = entry.substringBefore('|').toLongOrNull() ?: return null
        return try {
            savedAt to JSONObject(entry.substringAfter('|'))
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCache(imdbId: String, json: JSONObject) {
        prefs?.edit()?.putString(imdbId, "${System.currentTimeMillis()}|$json")?.apply()
    }
}
