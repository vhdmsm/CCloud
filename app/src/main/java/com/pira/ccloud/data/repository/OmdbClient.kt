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
 * Award data from OMDb (https://www.omdbapi.com). Each key allows 1000 requests a day, so the
 * keys are used in turn, answers are kept on the device for a month, and only the award sorts ask.
 */
object OmdbClient {
    private const val BASE_URL = "https://www.omdbapi.com/"
    private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    // A key that hit its daily limit is tried again after this
    private const val LIMIT_BACKOFF_MS = 3L * 60 * 60 * 1000
    private const val OFFLINE_BACKOFF_MS = 60_000L

    private val keys: List<String>
        get() = listOf(BuildConfig.OMDB_API_KEY, BuildConfig.OMDB_API_KEY2, BuildConfig.OMDB_API_KEY3, BuildConfig.OMDB_API_KEY4).filter { it.isNotEmpty() }

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

    private var prefs: SharedPreferences? = null
    private val memoryCache = ConcurrentHashMap<String, Awards>()
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
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("omdb_awards", Context.MODE_PRIVATE)
    }

    /** Awards of the movie with this IMDb id; null when unknown or OMDb can't answer right now. */
    suspend fun awards(imdbId: String): Awards? = withContext(Dispatchers.IO) {
        if (imdbId.isEmpty() || !isConfigured) return@withContext null
        memoryCache[imdbId]?.let { return@withContext it }
        readCache(imdbId)?.let { text ->
            return@withContext parseAwards(text).also { memoryCache[imdbId] = it }
        }
        if (System.currentTimeMillis() < offlineUntil) return@withContext null

        for (key in keys) {
            if ((keyBlockedUntil[key] ?: 0L) > System.currentTimeMillis()) continue
            try {
                val json = get(imdbId, key)
                // Not an OMDb answer (e.g. an error page): try again later, cache nothing
                if (!json.has("Response")) return@withContext null
                if (json.optString("Response") == "True") {
                    val text = json.optString("Awards", "N/A")
                    writeCache(imdbId, text)
                    return@withContext parseAwards(text).also { memoryCache[imdbId] = it }
                }
                val error = json.optString("Error")
                if (error.contains("limit", ignoreCase = true) || error.contains("API key", ignoreCase = true)) {
                    // Daily limit or a wrong key: move on to the next key
                    keyBlockedUntil[key] = System.currentTimeMillis() + LIMIT_BACKOFF_MS
                    continue
                }
                // e.g. "Incorrect IMDb ID.": no awards to know about
                writeCache(imdbId, "N/A")
                return@withContext parseAwards("N/A")
            } catch (e: IOException) {
                offlineUntil = System.currentTimeMillis() + OFFLINE_BACKOFF_MS
                return@withContext null
            } catch (e: Exception) {
                return@withContext null
            }
        }
        null
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

    private fun readCache(imdbId: String): String? {
        val entry = prefs?.getString(imdbId, null) ?: return null
        val savedAt = entry.substringBefore('|').toLongOrNull() ?: return null
        if (System.currentTimeMillis() - savedAt > CACHE_TTL_MS) return null
        return entry.substringAfter('|')
    }

    private fun writeCache(imdbId: String, text: String) {
        prefs?.edit()?.putString(imdbId, "${System.currentTimeMillis()}|$text")?.apply()
    }
}
