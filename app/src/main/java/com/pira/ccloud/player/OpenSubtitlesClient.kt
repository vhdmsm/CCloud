package com.pira.ccloud.player

import android.net.Uri
import com.pira.ccloud.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the OpenSubtitles REST API (https://api.opensubtitles.com).
 * Works with the app's API key only; without a user login the server allows a few downloads
 * per day for each IP address.
 */
object OpenSubtitlesClient {
    private const val BASE_URL = "https://api.opensubtitles.com/api/v1"
    private const val HASH_CHUNK_SIZE = 64 * 1024

    val isConfigured: Boolean get() = BuildConfig.OPENSUBTITLES_API_KEY.isNotEmpty()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Reading the video for its hash is only a bonus: a slow video server mustn't hold up the search
    private val hashClient = client.newBuilder()
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    data class Result(
        val fileId: Int,
        val release: String,
        val downloadCount: Int,
        // The subtitle was made for exactly this video file (same OpenSubtitles hash)
        val matchesFile: Boolean,
        val hearingImpaired: Boolean,
        // The movie's title (for an episode, the show's) and year in the subtitle database
        val title: String = "",
        val year: Int? = null
    )

    data class Download(
        val text: String,
        val fileName: String,
        val remaining: Int?
    )

    class ApiException(message: String) : Exception(message)

    // Title, year and episode read from a release file name like "Julian.2025.720p.WEB-DL.mkv"
    data class ReleaseInfo(
        val fileName: String,
        val title: String,
        val year: Int?,
        val season: Int?,
        val episode: Int?
    )

    fun parseReleaseName(videoUrl: String): ReleaseInfo {
        val fileName = Uri.decode(videoUrl.substringAfterLast('/').substringBefore('?'))
            .substringBeforeLast('.')
        // Only Latin words: some files have Persian words like "دانلود فیلم" in the name
        val words = fileName
            .replace(Regex("[._\\[\\]()\\-]+"), " ")
            .split(' ')
            .filter { it.isNotBlank() && it.all { c -> c.code < 128 } }
        val episodeMatch = words.indexOfFirst { Regex("(?i)S\\d{1,2}E\\d{1,3}").matches(it) }
        val yearIndex = words.indexOfFirst { Regex("(19|20)\\d{2}").matches(it) }
        val titleEnd = listOf(episodeMatch, yearIndex).filter { it > 0 }.minOrNull() ?: words.size
        val episodeWord = words.getOrNull(episodeMatch)
        return ReleaseInfo(
            fileName = fileName,
            title = words.take(titleEnd).joinToString(" "),
            year = words.getOrNull(yearIndex)?.toIntOrNull()?.takeIf { yearIndex > 0 },
            season = episodeWord?.let { Regex("(?i)S(\\d+)").find(it)?.groupValues?.get(1)?.toInt() },
            episode = episodeWord?.let { Regex("(?i)E(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
        )
    }

    suspend fun search(release: ReleaseInfo, movieHash: String?): List<Result> = withContext(Dispatchers.IO) {
        // Release names and the subtitle database often disagree on a movie's year by one (e.g. a
        // file named 2026 for a 2025 movie), and the year is a strict filter, so the years around it
        // are searched too (searching is free)
        val years: List<Int?> = release.year?.takeIf { release.season == null }
            ?.let { listOf(it, it - 1, it + 1) }
            ?: listOf(null)
        val found = coroutineScope {
            years.map { year -> async { searchYear(release, movieHash, year) } }.awaitAll()
        }
        pickResults(release, found.flatten())
    }

    /**
     * Only subtitles of the movie (or show) itself: the search matches any title sharing a word
     * ("the snare" finds "The Couple Across the Street"). Then the same file first, the closest
     * year (two movies can share a title), and the most similar release name.
     */
    fun pickResults(release: ReleaseInfo, found: List<Result>): List<Result> {
        val unique = found.distinctBy { it.fileId }
        val wanted = normalizedTitle(release.title)
        val exact = unique.filter { it.matchesFile || normalizedTitle(it.title) == wanted }
        // Titles written a little differently (e.g. with a subtitle): all of the words are in it
        val wantedWords = wanted.split(' ').filter { it.isNotEmpty() }.toSet()
        val matching = exact.ifEmpty {
            unique.filter { result ->
                wantedWords.isNotEmpty() && normalizedTitle(result.title).split(' ').toSet().containsAll(wantedWords)
            }
        }
        val releaseWords = releaseWords(release.fileName)
        return matching.sortedWith(
            compareByDescending<Result> { it.matchesFile }
                .thenBy { result -> release.year?.let { year -> result.year?.let { kotlin.math.abs(it - year) } } ?: 0 }
                .thenByDescending { (releaseWords(it.release) intersect releaseWords).size }
                .thenBy { it.hearingImpaired }
                .thenByDescending { it.downloadCount }
        )
    }

    private fun searchYear(release: ReleaseInfo, movieHash: String?, year: Int?): List<Result> {
        // The API redirects unless parameters are sorted by name and values are lowercase
        val params = sortedMapOf<String, String>()
        params["languages"] = "en"
        if (release.title.isNotBlank()) params["query"] = release.title.lowercase()
        year?.let { params["year"] = it.toString() }
        release.season?.let { params["season_number"] = it.toString() }
        release.episode?.let { params["episode_number"] = it.toString() }
        movieHash?.let { params["moviehash"] = it }
        val query = params.entries.joinToString("&") { (name, value) ->
            "$name=${URLEncoder.encode(value, "UTF-8")}"
        }

        val json = execute(request("$BASE_URL/subtitles?$query").get().build())
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val attributes = data.getJSONObject(index).optJSONObject("attributes") ?: return@mapNotNull null
            val file = attributes.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
            val feature = attributes.optJSONObject("feature_details")
            Result(
                fileId = file.optInt("file_id"),
                release = attributes.optString("release").ifBlank { file.optString("file_name") },
                downloadCount = attributes.optInt("download_count"),
                matchesFile = attributes.optBoolean("moviehash_match"),
                hearingImpaired = attributes.optBoolean("hearing_impaired"),
                // An episode's own title is the episode's name; the show's is its parent title
                title = feature?.let { if (release.season != null) it.optString("parent_title") else it.optString("title") }.orEmpty(),
                year = feature?.optInt("year")?.takeIf { it > 0 }
            )
        }
    }

    private fun normalizedTitle(title: String): String =
        title.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()

    suspend fun download(result: Result): Download = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("file_id", result.fileId)
            .put("sub_format", "srt")
            .toString()
            .toRequestBody("application/json".toMediaType())
        val json = execute(request("$BASE_URL/download").post(body).build())
        val link = json.optString("link").ifBlank {
            throw ApiException(json.optString("message").ifBlank { "No download link" })
        }
        val text = client.newCall(Request.Builder().url(link).build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("Download failed (${response.code})")
            response.body?.string().orEmpty()
        }
        Download(
            text = text,
            fileName = json.optString("file_name"),
            remaining = if (json.has("remaining")) json.optInt("remaining") else null
        )
    }

    /**
     * OpenSubtitles hash of a remote video: file size plus the 64-bit little-endian sums of the
     * first and last 64 KB, read with HTTP range requests. Null when the server can't do ranges.
     */
    suspend fun movieHash(videoUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val (head, size) = readRange(videoUrl, 0, HASH_CHUNK_SIZE.toLong()) ?: return@withContext null
            if (size == null || size < HASH_CHUNK_SIZE * 2) return@withContext null
            val (tail, _) = readRange(videoUrl, size - HASH_CHUNK_SIZE, HASH_CHUNK_SIZE.toLong())
                ?: return@withContext null
            var hash = size
            for (chunk in listOf(head, tail)) {
                val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                while (buffer.remaining() >= 8) hash += buffer.long
            }
            String.format("%016x", hash)
        } catch (e: Exception) {
            null
        }
    }

    // Returns the bytes and the total file size from Content-Range
    private fun readRange(url: String, start: Long, length: Long): Pair<ByteArray, Long?>? {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-${start + length - 1}")
            .build()
        return hashClient.newCall(request).execute().use { response ->
            if (response.code != 206) return null
            val bytes = response.body?.bytes() ?: return null
            if (bytes.size.toLong() != length) return null
            val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
            bytes to total
        }
    }

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("Api-Key", BuildConfig.OPENSUBTITLES_API_KEY)
        .header("User-Agent", "CCloud v${BuildConfig.VERSION_NAME}")
        .header("Accept", "application/json")

    private fun execute(request: Request): JSONObject =
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            if (!response.isSuccessful) {
                // e.g. the daily download limit: "You have downloaded your allowed 5 subtitles..."
                throw ApiException(json.optString("message").ifBlank { "OpenSubtitles error ${response.code}" })
            }
            json
        }

    // WEB-DL, WEB.DL and WEB DL are the same source
    private fun releaseWords(name: String): Set<String> =
        name.lowercase()
            .replace(Regex("web[ ._-]?dl"), "webdl")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 }
            .toSet()
}
