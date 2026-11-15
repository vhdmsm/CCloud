package com.pira.ccloud.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pira.ccloud.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A shared copy of the Watchmode and OMDb answers kept on the publisher's device, so other devices
 * can rank movies and series without spending the limited API credits.
 *
 * The publisher (whoever has write access to [repo]) uploads it, from Settings with a GitHub token
 * kept only on their device, as a file of a GitHub release ([TAG]). Every device downloads it from
 * the release's public link, with no key or sign-in, at most every few hours, and keeps the answers
 * that are newer than its own. The answers keep the time they were fetched, so they expire on every
 * device just as they do on the publisher's.
 */
object MetadataSnapshot {
    private const val TAG_LOG = "MetadataSnapshot"
    const val TAG = "metadata-snapshot"
    const val ASSET_NAME = "metadata-snapshot.json.gz"
    private const val PREFS_NAME = "metadata_snapshot"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_ETAG = "etag"
    private const val KEY_SNAPSHOT_TIME = "snapshot_time"
    private const val KEY_IMPORTED = "imported"
    private const val KEY_TOKEN = "github_token"
    private const val KEY_LAST_PUBLISH = "last_publish"
    private const val KEY_AUTO_PUBLISH = "auto_publish"
    private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    // The publisher's device uploads at most this often, and only when it fetched new answers
    private const val PUBLISH_INTERVAL_MS = 60L * 60 * 1000

    /** "owner/name" of the GitHub repository whose release holds the snapshot (set by the build). */
    val repo: String get() = BuildConfig.METADATA_SNAPSHOT_REPO
    val isConfigured: Boolean get() = repo.contains('/')
    val downloadUrl: String get() = "https://github.com/$repo/releases/download/$TAG/$ASSET_NAME"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val isUsable: Boolean get() = isConfigured && (WatchmodeClient.isConfigured || OmdbClient.isConfigured)

    /**
     * When the app opens: downloads a newer snapshot when one is due; on the publisher's device,
     * uploads one when it fetched new answers since the last upload.
     */
    fun syncInBackground(context: Context) {
        init(context)
        if (!isUsable) return
        scope.launch {
            try {
                download(force = false)
                publishIfChanged()
            } catch (e: Exception) {
                Log.w(TAG_LOG, "sync failed: $e")
            }
        }
    }

    /** When the app goes to the background: the publisher's device uploads what it fetched meanwhile. */
    fun publishInBackground(context: Context) {
        init(context)
        if (!isUsable || !hasToken) return
        scope.launch {
            try {
                publishIfChanged()
            } catch (e: Exception) {
                Log.w(TAG_LOG, "publish failed: $e")
            }
        }
    }

    private suspend fun publishIfChanged() {
        if (!hasToken || !autoPublish) return
        val last = lastPublish
        if (System.currentTimeMillis() - last < PUBLISH_INTERVAL_MS) return
        val newest = SnapshotRules.newestSave(OmdbClient.snapshotEntries().values + WatchmodeClient.snapshotEntries().values)
        if (newest > last) publish()
    }

    // --- Reading the shared snapshot (every device) ---

    /** When the snapshot last taken was made by the publisher (0: none yet), and how many answers it brought. */
    val snapshotTime: Long get() = prefs?.getLong(KEY_SNAPSHOT_TIME, 0L) ?: 0L
    val importedCount: Int get() = prefs?.getInt(KEY_IMPORTED, 0) ?: 0

    /** Downloads the snapshot (unless checked in the last hours, or [force]) and keeps its newer answers. */
    suspend fun download(force: Boolean): Result<Int> = withContext(Dispatchers.IO) {
        lock.withLock {
            val p = prefs ?: return@withLock Result.failure(IllegalStateException("not set up"))
            val now = System.currentTimeMillis()
            if (!force && now - p.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return@withLock Result.success(0)
            try {
                val request = Request.Builder().url(downloadUrl).apply {
                    if (!force) p.getString(KEY_ETAG, null)?.let { header("If-None-Match", it) }
                }.build()
                client.newCall(request).execute().use { response ->
                    p.edit().putLong(KEY_LAST_CHECK, now).apply()
                    // Not changed, or not published yet
                    if (response.code == 304 || response.code == 404) return@withLock Result.success(0)
                    if (!response.isSuccessful) return@withLock Result.failure(IllegalStateException("HTTP ${response.code}"))
                    val bytes = response.body?.bytes() ?: return@withLock Result.success(0)
                    val snapshot = SnapshotRules.decode(gunzip(bytes))
                        ?: return@withLock Result.failure(IllegalStateException("not a snapshot"))
                    val omdb = SnapshotRules.newerEntries(OmdbClient.snapshotEntries(), snapshot.omdb, now)
                    val watchmode = SnapshotRules.newerEntries(WatchmodeClient.snapshotEntries(), snapshot.watchmode, now)
                    OmdbClient.importEntries(omdb)
                    WatchmodeClient.importEntries(watchmode)
                    p.edit()
                        .putString(KEY_ETAG, response.header("ETag"))
                        .putLong(KEY_SNAPSHOT_TIME, snapshot.createdAt)
                        .putInt(KEY_IMPORTED, omdb.size + watchmode.size)
                        .apply()
                    Log.i(TAG_LOG, "took ${omdb.size} OMDb and ${watchmode.size} Watchmode answers")
                    Result.success(omdb.size + watchmode.size)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // --- Publishing (the publisher's device only) ---

    // The token is kept on this device only; the app as built carries none
    val hasToken: Boolean get() = !prefs?.getString(KEY_TOKEN, null).isNullOrBlank()
    val lastPublish: Long get() = prefs?.getLong(KEY_LAST_PUBLISH, 0L) ?: 0L
    val autoPublish: Boolean get() = prefs?.getBoolean(KEY_AUTO_PUBLISH, true) ?: true

    fun setToken(context: Context, token: String?) {
        init(context)
        prefs?.edit()?.apply { if (token.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, token.trim()) }?.apply()
    }

    fun setAutoPublish(context: Context, enabled: Boolean) {
        init(context)
        prefs?.edit()?.putBoolean(KEY_AUTO_PUBLISH, enabled)?.apply()
    }

    /** Uploads this device's answers as the snapshot; the number of answers uploaded. */
    suspend fun publish(): Result<Int> = withContext(Dispatchers.IO) {
        lock.withLock {
            val p = prefs ?: return@withLock Result.failure(IllegalStateException("not set up"))
            val token = p.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
                ?: return@withLock Result.failure(IllegalStateException("No GitHub token set"))
            try {
                val now = System.currentTimeMillis()
                val omdb = SnapshotRules.exportable(OmdbClient.snapshotEntries(), now)
                val watchmode = SnapshotRules.exportable(WatchmodeClient.snapshotEntries(), now)
                val body = gzip(SnapshotRules.encode(Snapshot(now, omdb, watchmode)))
                val release = findOrCreateRelease(token)
                // A release can't hold two files of the same name: the old one goes first
                release["assets"]?.jsonArray?.forEach { asset ->
                    val a = asset.jsonObject
                    if (a["name"]?.jsonPrimitive?.contentOrNull == ASSET_NAME) {
                        github(token, "DELETE", "https://api.github.com/repos/$repo/releases/assets/${a["id"]!!.jsonPrimitive.content}")
                    }
                }
                val uploadUrl = release["upload_url"]!!.jsonPrimitive.content.substringBefore('{') +
                    "?name=" + URLEncoder.encode(ASSET_NAME, "UTF-8")
                github(token, "POST", uploadUrl, body, "application/gzip")
                p.edit().putLong(KEY_LAST_PUBLISH, now).apply()
                Result.success(omdb.size + watchmode.size)
            } catch (e: Exception) {
                Log.w(TAG_LOG, "publish failed: $e")
                Result.failure(e)
            }
        }
    }

    private fun findOrCreateRelease(token: String): JsonObject {
        val existing = github(token, "GET", "https://api.github.com/repos/$repo/releases/tags/$TAG", allow404 = true)
        if (existing != null) return existing
        // A pre-release, so it isn't shown as the app's latest version
        val create = buildJsonObject {
            put("tag_name", TAG)
            put("name", "Shared movie data")
            put("body", "Movie and series data (Watchmode and OMDb answers) the app downloads, so it needs fewer API requests. Updated from the app; not an app release.")
            put("prerelease", true)
        }.toString().toByteArray()
        return github(token, "POST", "https://api.github.com/repos/$repo/releases", create, "application/json")!!
    }

    // A GitHub API call; its JSON answer (an empty object when there's none), or null for a 404 when [allow404]
    private fun github(
        token: String,
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String = "application/json",
        allow404: Boolean = false
    ): JsonObject? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, body?.toRequestBody(contentType.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404 && allow404) return null
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = try {
                    Json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull
                } catch (e: Exception) {
                    null
                }
                throw IllegalStateException("GitHub ${response.code}${message?.let { ": $it" } ?: ""}")
            }
            return if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
        }
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): String =
        // The release's link serves the file as uploaded; a plain JSON file is read too
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            bytes.toString(Charsets.UTF_8)
        }
}

/** The snapshot's content: when it was made, and the kept answers ("time|value" by key) of each service. */
data class Snapshot(val createdAt: Long, val omdb: Map<String, String>, val watchmode: Map<String, String>)

/** How the snapshot is written, read and taken in, apart from storage and network so it can be tested. */
object SnapshotRules {
    const val FORMAT_VERSION = 1
    // The longest any answer is kept (Watchmode's terms allow 30 days); older ones aren't shared
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    // A publisher's clock a little ahead is fine; an answer from far in the future is not trusted
    private const val CLOCK_SLACK_MS = 24L * 60 * 60 * 1000

    fun savedAt(entry: String): Long? = entry.substringBefore('|', "").toLongOrNull()

    /** When the newest of these answers was fetched (0 for none). */
    fun newestSave(entries: Collection<String>): Long = entries.maxOfOrNull { savedAt(it) ?: 0L } ?: 0L

    /** The answers worth sharing: well formed and not older than any device would keep them. */
    fun exportable(entries: Map<String, String>, now: Long): Map<String, String> =
        entries.filter { (_, entry) -> isUsable(entry, now) }

    /** The snapshot's answers this device should take: ones it doesn't have, or has older. */
    fun newerEntries(local: Map<String, String>, incoming: Map<String, String>, now: Long): Map<String, String> =
        incoming.filter { (key, entry) ->
            if (key.isEmpty() || !isUsable(entry, now)) return@filter false
            val mine = local[key]?.let { savedAt(it) } ?: return@filter true
            savedAt(entry)!! > mine
        }

    private fun isUsable(entry: String, now: Long): Boolean {
        val at = savedAt(entry) ?: return false
        return now - at <= MAX_AGE_MS && at <= now + CLOCK_SLACK_MS && entry.length > entry.indexOf('|') + 1
    }

    fun encode(snapshot: Snapshot): String = buildJsonObject {
        put("version", FORMAT_VERSION)
        put("createdAt", snapshot.createdAt)
        put("omdb", JsonObject(snapshot.omdb.mapValues { JsonPrimitive(it.value) }))
        put("watchmode", JsonObject(snapshot.watchmode.mapValues { JsonPrimitive(it.value) }))
    }.toString()

    /** The snapshot in [text], or null when it isn't one this version of the app can read. */
    fun decode(text: String): Snapshot? = try {
        val root = Json.parseToJsonElement(text).jsonObject
        val version = root["version"]?.jsonPrimitive?.longOrNull
        if (version == null || version > FORMAT_VERSION) {
            null
        } else {
            fun section(name: String): Map<String, String> =
                root[name]?.jsonObject?.mapNotNull { (key, value) ->
                    (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
                }?.toMap().orEmpty()
            Snapshot(root["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L, section("omdb"), section("watchmode"))
        }
    } catch (e: Exception) {
        null
    }
}
