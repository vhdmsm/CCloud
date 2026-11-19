package com.pira.ccloud.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A published release of the app that can be installed: its tag, version, notes and APK. */
data class AppRelease(
    val tag: String,
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long
)

/**
 * How the app decides from the GitHub releases whether there is a newer version. Releases are
 * tagged "v<versionName>" (e.g. v2.2.3 for versionName 2.2.3) by the release workflow; the shared
 * movie data release ("metadata-snapshot") and any tag without a version are left out.
 */
object AppUpdateRules {
    /** The APK the release workflow attaches; another .apk is taken if a release has no such file. */
    const val APK_NAME = "CCloud-universal.apk"

    private val leadingVersion = Regex("^[vV]?(\\d+(?:\\.\\d+)*)")

    /** The numbers of a tag or version name ("v2.2.3", "2.0.2-dual-subtitles"), or null if it has none. */
    fun parseVersion(text: String): List<Int>? =
        leadingVersion.find(text.trim())?.groupValues?.get(1)
            ?.split('.')
            ?.map { it.toIntOrNull() ?: return null }

    /** Whether [candidate] is a higher version than [installed] (2.2.10 > 2.2.9, 2.3 > 2.2.9, 2.2.0 == 2.2). */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = parseVersion(candidate) ?: return false
        val b = parseVersion(installed) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * From the answer of GET /repos/{owner}/{repo}/releases, the highest-versioned published release
     * (not a draft or prerelease) that has an APK, if it is newer than [installedVersion] and not in
     * [skippedTags]; otherwise null.
     */
    fun pickUpdate(releasesJson: String, installedVersion: String, skippedTags: Set<String> = emptySet()): AppRelease? {
        val releases = try {
            Json.parseToJsonElement(releasesJson).jsonArray
        } catch (e: Exception) {
            return null
        }
        val best = releases.mapNotNull { (it as? JsonObject)?.let(::toRelease) }
            .filter { parseVersion(it.tag) != null }
            .maxWithOrNull { a, b -> if (isNewer(a.tag, b.tag)) 1 else if (isNewer(b.tag, a.tag)) -1 else 0 }
            ?: return null
        if (best.tag in skippedTags) return null
        return if (isNewer(best.tag, installedVersion)) best else null
    }

    private fun toRelease(json: JsonObject): AppRelease? {
        if (json["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
        if (json["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
        val tag = json["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val assets = (json["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        fun name(asset: JsonObject) = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val apk = assets.firstOrNull { name(it) == APK_NAME }
            ?: assets.firstOrNull { name(it).endsWith(".apk", ignoreCase = true) }
            ?: return null
        val url = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
        return AppRelease(
            tag = tag,
            version = parseVersion(tag)!!.joinToString("."),
            notes = json["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
            apkUrl = url,
            apkSize = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L
        )
    }
}
