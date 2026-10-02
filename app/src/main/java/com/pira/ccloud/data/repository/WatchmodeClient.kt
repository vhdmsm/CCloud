package com.pira.ccloud.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.pira.ccloud.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import java.util.Locale
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
    // First billed actors whose popularity is read for the actor sorts (each costs a credit the first time)
    private const val TOP_CAST_COUNT = 2
    // A key that was rejected or used up its monthly quota is tried again after this
    private const val BLOCKED_BACKOFF_MS = 6L * 60 * 60 * 1000
    // Too many requests this minute, or no connection
    private const val SHORT_BACKOFF_MS = 60_000L
    // The free plan allows 120 requests a minute per key; each key is kept a little below that
    private const val REQUESTS_PER_MINUTE = 110
    private const val MINUTE_MS = 60_000L
    // A lookup waits this long at most for a key that hit its per-minute limit, rather than giving up
    private const val MAX_RATE_WAIT_MS = SHORT_BACKOFF_MS + 5_000L
    // Below this share of the month's credits left, only this year's movies get new lookups
    private const val RESERVE_SHARE = 0.2
    // Years a series may run before the year the site lists it by
    private const val MAX_SEASONS_SPAN = 10
    // Series of the same name whose details are read at most, to find the one in the site's language
    private const val MAX_SERIES_TRIES = 3
    // Bumped when the way titles are matched changes, so doubtful older matches are looked up again
    private const val MATCH_VERSION = 2
    // The most popular series list: pages of 250 read (a credit each) and how long it's kept
    private const val POPULAR_SERIES_PAGES = 12
    private const val POPULAR_SERIES_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val keys: List<String> by lazy {
        BuildConfig.WATCHMODE_API_KEYS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    // Monthly quota and credits used per key, from the last response's headers
    private val keyQuota = ConcurrentHashMap<String, Pair<Long, Long>>()

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

    /** Share (0..1) of this month's credits left over all keys, or null before any key has answered. */
    val remainingShare: Double? get() = remainingShare(keys.map { keyQuota[it] })

    /** [quotas] are (monthly quota, used) per key; a key that hasn't answered yet counts as unused. */
    fun remainingShare(quotas: List<Pair<Long, Long>?>): Double? {
        val known = quotas.filterNotNull().filter { it.first > 0 }
        if (known.isEmpty()) return null
        val typicalQuota = known.sumOf { it.first } / known.size
        val unknownKeys = quotas.count { it == null }
        val total = known.sumOf { it.first } + unknownKeys * typicalQuota
        val left = known.sumOf { (it.first - it.second).coerceAtLeast(0) } + unknownKeys * typicalQuota
        return left.toDouble() / total
    }

    /** e.g. "credits left this month: 10,848 of 12,500"; notes keys that haven't answered yet. Null before any has. */
    val creditsText: String?
        get() {
            val known = keys.mapNotNull { keyQuota[it] }.filter { it.first > 0 }
            if (known.isEmpty()) return null
            val left = known.sumOf { (it.first - it.second).coerceAtLeast(0) }
            val total = known.sumOf { it.first }
            val text = String.format(Locale.US, "credits left this month: %,d of %,d", left, total)
            return if (known.size < keys.size) "$text (${known.size} of ${keys.size} keys)" else text
        }

    /** Reads each key's monthly quota and use from Watchmode's status page, which costs no credits. */
    suspend fun refreshQuotas() = withContext(Dispatchers.IO) {
        for (key in keys) {
            try {
                val request = Request.Builder().url("$BASE_URL/status/").header("X-API-Key", key).build()
                client.newCall(request).execute().use { response ->
                    val quota = response.header("X-Account-Quota")?.toLongOrNull()
                    val used = response.header("X-Account-Quota-Used")?.toLongOrNull()
                    if (quota != null && used != null) keyQuota[key] = quota to used
                }
            } catch (e: Exception) {
                // Known after its next lookup instead
            }
        }
    }

    // True when credits are low and only this year's movies get new lookups (not in Unlimited mode)
    val isSavingCredits: Boolean get() = DataUsage.keepsCreditReserve && (remainingShare ?: 1.0) < RESERVE_SHARE

    /**
     * New lookups (which cost credits) for this year's movies always; for older ones only while
     * credits last, unless [keepReserve] is off (Unlimited mode uses the credits to the end).
     */
    fun allowsNewLookup(year: Int, currentYear: Int, remainingShare: Double?, keepReserve: Boolean = true): Boolean =
        !keepReserve || year >= currentYear || (remainingShare ?: 1.0) >= RESERVE_SHARE

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("watchmode", Context.MODE_PRIVATE)
    }

    /**
     * Data for the movie (or with [series], the series) with this title and year, or null when
     * Watchmode doesn't know it or can't answer. [withCast] also reads how popular the lead actors
     * are (more credits). [cachedOnly] answers from the cache alone, without any request.
     * [languages]: the series' likely original languages (from the site's countries; empty when
     * unknown), to tell series of the same name apart.
     */
    suspend fun movie(
        title: String,
        year: Int,
        withCast: Boolean,
        cachedOnly: Boolean = false,
        series: Boolean = false,
        languages: Set<String> = emptySet()
    ): MovieInfo? = withContext(Dispatchers.IO) {
        val query = title.replace(Regex("\\((19|20)\\d{2}\\)"), " ").replace(Regex("\\s+"), " ").trim()
        if (query.isEmpty()) return@withContext null
        val key = "${if (series) "s" else "t"}:${query.lowercase()}|$year"
        // Cached answers are free and still used when the credits are out; new lookups for older
        // movies wait while credits are low. A key at its per-minute limit is waited for.
        val mayLookUp = !cachedOnly && isAvailableSoon && allowsNewLookup(year, Calendar.getInstance().get(Calendar.YEAR), remainingShare, DataUsage.keepsCreditReserve)

        val stored = try {
            readCache(key)?.let { JSONObject(it) }?.takeUnless { series && isDoubtfulSeriesMatch(it, year, languages) } ?: run {
                if (!mayLookUp) return@withContext null
                val found = if (series) findSeries(query, year, languages) else findTitle(query, year)?.let { details(it) }
                (found ?: JSONObject()).put("v", MATCH_VERSION).also { writeCache(key, it.toString()) }
            }
        } catch (e: IOException) {
            offlineUntil = System.currentTimeMillis() + SHORT_BACKOFF_MS
            return@withContext null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext null
        }
        if (!stored.has("id")) return@withContext null

        if (withCast && mayLookUp && !stored.has("cast") &&
            !MovieRanking.isExcludedLanguage(stored.optString("original_language"))
        ) {
            // A failed actors lookup keeps the movie's data; the actors are asked for again next time
            try {
                val actors = leadActors(stored.getInt("id"))
                stored.put("cast", JSONArray(actors.map { JSONObject().put("name", it.name).put("percentile", it.percentile) }))
                writeCache(key, stored.toString())
            } catch (e: IOException) {
                offlineUntil = System.currentTimeMillis() + SHORT_BACKOFF_MS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what we have
            }
        }
        toInfo(stored)
    }

    private val popularSeriesLock = Mutex()

    /**
     * Watchmode's most popular series that started in [sinceYear] or later (about 3,000, the most
     * popular first), or null when it can't be read. Read once a week, for a few credits.
     */
    suspend fun popularSeries(sinceYear: Int): SeriesPopularity? = withContext(Dispatchers.IO) {
        popularSeriesLock.withLock {
            val key = "popular-series:$sinceYear"
            readCache(key, POPULAR_SERIES_TTL_MS)?.let { cached ->
                return@withLock try {
                    SeriesPopularity(parsePopular(JSONArray(cached)))
                } catch (e: Exception) {
                    null
                }
            }
            if (!isAvailableSoon) return@withLock null
            val pages = try {
                coroutineScope {
                    (1..POPULAR_SERIES_PAGES).map { page ->
                        async {
                            call(
                                "/list-titles/",
                                "types" to "tv_series,tv_miniseries",
                                "sort_by" to "popularity_desc",
                                "release_date_start" to "${sinceYear}0101",
                                "limit" to "250",
                                "page" to page.toString()
                            ).optJSONArray("titles") ?: JSONArray()
                        }
                    }.awaitAll()
                }
            } catch (e: IOException) {
                offlineUntil = System.currentTimeMillis() + SHORT_BACKOFF_MS
                return@withLock null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock null
            }
            val titles = JSONArray()
            for (page in pages) {
                for (i in 0 until page.length()) {
                    val title = page.optJSONObject(i) ?: continue
                    titles.put(JSONArray().put(title.optString("title")).put(title.optInt("year")))
                }
            }
            if (titles.length() == 0) return@withLock null
            writeCache(key, titles.toString())
            SeriesPopularity(parsePopular(titles))
        }
    }

    private fun parsePopular(titles: JSONArray): List<Pair<String, Int>> =
        (0 until titles.length()).mapNotNull { i ->
            val row = titles.optJSONArray(i) ?: return@mapNotNull null
            row.optString(0).takeIf { it.isNotBlank() }?.let { it to row.optInt(1) }
        }

    /**
     * Whether a cached series match should be looked up again: matches made before the current
     * rules (closest start year, names without accents or apostrophes, the language checked) that
     * found nothing, or found a series that started more than a year from the site's year, or in
     * another language than the site's countries (Queen of Tears matched its Turkish remake).
     * Each is looked up once; the new answer is kept.
     */
    private fun isDoubtfulSeriesMatch(stored: JSONObject, year: Int, languages: Set<String>): Boolean =
        isDoubtfulSeriesMatch(
            stored.optInt("v"), stored.has("id"), stored.optString("release_date"),
            stored.optString("original_language"), year, languages
        )

    /** [version]: the rules the match was made under; [found]: false when nothing was found. */
    fun isDoubtfulSeriesMatch(
        version: Int,
        found: Boolean,
        releaseDate: String,
        language: String,
        year: Int,
        languages: Set<String>
    ): Boolean {
        if (version >= MATCH_VERSION) return false
        if (!found) return true
        val started = releaseDate.take(4).toIntOrNull()
        if (year > 0 && started != null && kotlin.math.abs(started - year) > 1) return true
        val lang = language.lowercase()
        return languages.isNotEmpty() && lang.isNotEmpty() && lang !in languages
    }

    data class SearchResult(val id: Int, val type: String, val year: Int, val name: String = "")

    private suspend fun search(query: String, series: Boolean): List<SearchResult> {
        val results = call("/search/", "search_field" to "name", "search_value" to query, "types" to if (series) "tv" else "movie")
            .optJSONArray("title_results") ?: return emptyList()
        return (0 until results.length()).map { results.getJSONObject(it) }
            .map { SearchResult(it.optInt("id"), it.optString("type"), it.optInt("year"), it.optString("name")) }
    }

    private suspend fun findTitle(query: String, year: Int): Int? = pickMovie(search(query, series = false), year, query)

    /**
     * The details of the series: of the [seriesCandidates] (at most [MAX_SERIES_TRIES]), the first
     * in one of [languages]; when none is, the closest unless it's in a language the ranked sorts
     * leave out (then none: a Korean series isn't dropped for its Turkish remake).
     */
    private suspend fun findSeries(query: String, year: Int, languages: Set<String>): JSONObject? {
        var first: JSONObject? = null
        for (id in seriesCandidates(search(query, series = true), year, query).take(MAX_SERIES_TRIES)) {
            val found = details(id)
            val language = found.optString("original_language").lowercase()
            if (languages.isEmpty() || language.isEmpty() || language in languages) return found
            if (first == null) first = found
        }
        return first?.takeUnless {
            val language = it.optString("original_language").lowercase()
            MovieRanking.isExcludedLanguage(language) && languages.none { l -> MovieRanking.isExcludedLanguage(l) }
        }
    }

    /** The first of the [seriesCandidates]. */
    fun pickSeries(results: List<SearchResult>, year: Int, query: String): Int? =
        seriesCandidates(results, year, query).firstOrNull()

    /**
     * Series with this name (accents and apostrophes aside: "Shogun" is "Shōgun") that started
     * within [MAX_SEASONS_SPAN] years before [year] or a year after, the closest start year first:
     * the site lists a series by its first year, and the same name is often another series (the
     * 1980 "Shogun", a remake, a Thai "Mouse" and a Korean one). Without a name match, the first
     * that started within a year of [year].
     */
    fun seriesCandidates(results: List<SearchResult>, year: Int, query: String): List<Int> {
        val shows = results.filter { it.id > 0 && (it.type.startsWith("tv_series") || it.type == "tv_miniseries") }
        val name = SeriesPopularity.normalize(query)
        val named = shows.filter { SeriesPopularity.normalize(it.name) == name }
        val byName = if (year > 0) {
            // Stable sort: equally close ones keep Watchmode's order
            named.filter { it.year in (year - MAX_SEASONS_SPAN)..(year + 1) }.sortedBy { kotlin.math.abs(it.year - year) }
        } else {
            named
        }
        if (byName.isNotEmpty()) return byName.map { it.id }.distinct()
        return listOfNotNull(shows.firstOrNull { year > 0 && kotlin.math.abs(it.year - year) <= 1 }?.id)
    }

    /**
     * The search result of the same year (±1, release dates differ between countries), one with
     * the same name first. Watchmode files some films as "tv_movie" or "tv_special" (concerts,
     * documentaries), so any type but a series is accepted, a "movie" first.
     */
    fun pickMovie(results: List<SearchResult>, year: Int, query: String = ""): Int? {
        val films = results.filter { it.id > 0 && !it.type.startsWith("tv_series") && it.type != "tv_miniseries" }
            .sortedBy { if (it.type == "movie") 0 else 1 }
        val inYear = if (year > 0) films.filter { kotlin.math.abs(it.year - year) <= 1 } else films
        val name = SeriesPopularity.normalize(query)
        return (inYear.firstOrNull { name.isNotEmpty() && SeriesPopularity.normalize(it.name) == name } ?: inYear.firstOrNull())?.id
    }

    // Keeps only what the sorts use
    private suspend fun details(id: Int): JSONObject {
        val details = call("/title/$id/details/")
        return JSONObject()
            .put("id", id)
            .put("popularity_percentile", details.optDouble("popularity_percentile", 0.0))
            .put("relevance_percentile", details.optDouble("relevance_percentile", 0.0))
            .put("release_date", details.optString("release_date"))
            .put("imdb_id", details.optString("imdb_id").takeIf { it.startsWith("tt") }.orEmpty())
            .put("original_language", details.optString("original_language"))
    }

    // The first billed actors with how well known they are (asked together); actors' percentiles
    // are cached on their own
    private suspend fun leadActors(titleId: Int): List<MovieInfo.Actor> = coroutineScope {
        val crew = call("/title/$titleId/cast-crew/").optJSONArray("__array") ?: return@coroutineScope emptyList()
        (0 until crew.length()).map { crew.getJSONObject(it) }
            .filter { it.optString("type").equals("Cast", ignoreCase = true) }
            .sortedBy { it.optInt("order", Int.MAX_VALUE) }
            .take(TOP_CAST_COUNT)
            .map { actor ->
                async {
                    val personId = actor.optInt("person_id")
                    val personKey = "p:$personId"
                    val percentile = readCache(personKey)?.toDoubleOrNull() ?: call("/person/$personId/")
                        .optDouble("relevance_percentile", 0.0)
                        .also { writeCache(personKey, it.toString()) }
                    MovieInfo.Actor(actor.optString("full_name"), percentile)
                }
            }
            .awaitAll()
    }

    private fun toInfo(stored: JSONObject): MovieInfo {
        val relevance = stored.optDouble("relevance_percentile", 0.0)
        val popularity = stored.optDouble("popularity_percentile", 0.0)
        val cast = stored.optJSONArray("cast")
        return MovieInfo(
            popularity = MovieRanking.percentileScore(popularity),
            reach = MovieRanking.percentileScore(relevance),
            // Watchmode has no vote count; well-known movies' scores are trusted more
            ratingConfidence = relevance / 100.0,
            // Scored from the cached actors, so a change in scoring needs no new requests
            castPopularity = when {
                cast != null -> MovieRanking.castScore((0 until cast.length()).map { cast.getJSONObject(it).optDouble("percentile", 0.0) })
                stored.has("cast_score") -> stored.getDouble("cast_score")
                else -> null
            },
            releaseDate = stored.optString("release_date"),
            imdbId = stored.optString("imdb_id"),
            originalLanguage = stored.optString("original_language"),
            popularityPercentile = popularity,
            relevancePercentile = relevance,
            actors = if (cast == null) emptyList() else (0 until cast.length()).map {
                val actor = cast.getJSONObject(it)
                MovieInfo.Actor(actor.optString("name"), actor.optDouble("percentile", 0.0))
            }
        )
    }

    /**
     * [get] that waits for a key with room in its per-minute limit (up to a minute) and tries again,
     * instead of failing: a burst of lookups is slowed down, not left without data.
     */
    private suspend fun call(path: String, vararg params: Pair<String, String>): JSONObject {
        // Gives up only when no key will have room soon (rejected, out of quota, or offline)
        while (true) {
            if (!waitForKey()) throw NoUsableKeyException()
            try {
                return get(path, *params)
            } catch (e: NoUsableKeyException) {
                // Other lookups took the free room meanwhile: wait for the next
            }
        }
    }

    // Times of each key's requests in the last minute, to spread requests over the keys and keep
    // each under its per-minute limit
    private val recentRequests = HashMap<String, ArrayDeque<Long>>()

    // When [key] can take a request: after a block ends and once its last minute has room
    private fun readyAt(key: String, now: Long): Long = synchronized(recentRequests) {
        val times = recentRequests.getOrPut(key) { ArrayDeque() }
        while (times.isNotEmpty() && times.first() <= now - MINUTE_MS) times.removeFirst()
        val roomAt = if (times.size < REQUESTS_PER_MINUTE) now else times.first() + MINUTE_MS
        maxOf(roomAt, keyBlockedUntil[key] ?: 0L)
    }

    /** The key with the most room right now (so requests go round the keys), counted as used; null when none has room. */
    private fun takeKey(): String? = synchronized(recentRequests) {
        val now = System.currentTimeMillis()
        val key = keys.filter { readyAt(it, now) <= now }
            .minByOrNull { recentRequests[it]?.size ?: 0 } ?: return null
        recentRequests.getValue(key).addLast(now)
        key
    }

    // True when a key can be used now, after waiting (up to about a minute) for one with room
    private suspend fun waitForKey(): Boolean {
        val now = System.currentTimeMillis()
        if (now < offlineUntil) return false
        val soonest = keys.minOfOrNull { readyAt(it, now) } ?: return false
        if (soonest <= now) return true
        if (soonest - now > MAX_RATE_WAIT_MS) return false
        delay(soonest - now)
        return true
    }

    // A key is usable now or after a per-minute wait (not while every key is rejected or out of
    // quota, or Watchmode can't be reached)
    private val isAvailableSoon: Boolean
        get() {
            val now = System.currentTimeMillis()
            return now >= offlineUntil && keys.any { (keyBlockedUntil[it] ?: 0L) - now <= MAX_RATE_WAIT_MS }
        }

    private class NoUsableKeyException : IllegalStateException("No Watchmode key is usable right now")

    // Sends the request with the key that has the most room, moving to another key when one is
    // rejected, out of quota or at its limit. Arrays (cast-crew) come back wrapped as {"__array": [...]}
    private fun get(path: String, vararg params: Pair<String, String>): JSONObject {
        val url = "$BASE_URL$path".toHttpUrl().newBuilder()
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        repeat(keys.size) {
            val key = takeKey() ?: throw NoUsableKeyException()
            val request = Request.Builder()
                .url(url)
                .header("X-API-Key", key)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val quota = response.header("X-Account-Quota")?.toLongOrNull()
                val used = response.header("X-Account-Quota-Used")?.toLongOrNull()
                if (quota != null && used != null) keyQuota[key] = quota to used
                when (response.code) {
                    200 -> return if (body.trimStart().startsWith("[")) {
                        JSONObject().put("__array", JSONArray(body))
                    } else {
                        JSONObject(body)
                    }
                    404 -> return JSONObject()
                    401, 402, 403 -> blockKey(key, BLOCKED_BACKOFF_MS)
                    429 -> {
                        // Monthly quota used up, or just too many requests this minute (for as
                        // long as Watchmode says, when it does)
                        val retryAfterMs = response.header("Retry-After")?.toLongOrNull()?.times(1000)
                        blockKey(
                            key,
                            if (quota != null && used != null && used >= quota) BLOCKED_BACKOFF_MS
                            else retryAfterMs?.coerceIn(1_000L, SHORT_BACKOFF_MS) ?: SHORT_BACKOFF_MS
                        )
                    }
                    else -> throw IllegalStateException("Watchmode error ${response.code}")
                }
            }
        }
        throw NoUsableKeyException()
    }

    private fun blockKey(key: String, durationMs: Long) {
        keyBlockedUntil[key] = System.currentTimeMillis() + durationMs
    }

    private fun readCache(key: String, ttlMs: Long = CACHE_TTL_MS): String? {
        val entry = memoryCache[key] ?: prefs?.getString(key, null) ?: return null
        val savedAt = entry.substringBefore('|').toLongOrNull() ?: return null
        if (System.currentTimeMillis() - savedAt > ttlMs) {
            memoryCache.remove(key)
            prefs?.edit()?.remove(key)?.apply()
            return null
        }
        memoryCache[key] = entry
        return entry.substringAfter('|')
    }

    private fun writeCache(key: String, value: String) {
        val entry = "${System.currentTimeMillis()}|$value"
        memoryCache[key] = entry
        prefs?.edit()?.putString(key, entry)?.apply()
    }
}
