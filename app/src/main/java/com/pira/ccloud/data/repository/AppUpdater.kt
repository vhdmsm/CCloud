package com.pira.ccloud.data.repository

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.pira.ccloud.BuildConfig
import com.pira.ccloud.utils.AppRelease
import com.pira.ccloud.utils.AppUpdateRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Updates the app from its GitHub releases: looks for a newer release when the app opens,
 * downloads its APK and hands it to the system's package installer.
 */
object AppUpdater {
    private const val TAG_LOG = "AppUpdater"
    private const val REPO = "vhdmsm/CCloud"
    private const val PREFS = "app_update"
    private const val KEY_SKIPPED = "skipped_tags"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The newer release to offer, or null when there is none or it can't be checked (e.g. offline). */
    suspend fun check(context: Context): AppRelease? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases?per_page=20")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                AppUpdateRules.pickUpdate(body, BuildConfig.VERSION_NAME, skippedTags(context))
            }
        } catch (e: Exception) {
            Log.i(TAG_LOG, "update check skipped: $e")
            null
        }
    }

    /** The result of downloading a release's APK. */
    sealed class Download {
        data class Ready(val file: File) : Download()
        /** The APK is not a newer CCloud (e.g. the tag was ahead of its versionName); it's not offered again. */
        object NotNewer : Download()
        object Failed : Download()
    }

    /** Downloads [release]'s APK into the cache, reporting progress (0 to 1, or -1 while unknown). */
    suspend fun download(context: Context, release: AppRelease, onProgress: (Float) -> Unit): Download =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates")
            // Only the APK being downloaded is kept; older ones are removed
            dir.listFiles()?.forEach { it.delete() }
            dir.mkdirs()
            val file = File(dir, "CCloud-${release.version}.apk")
            val partial = File(dir, file.name + ".part")
            try {
                val request = Request.Builder().url(release.apkUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext Download.Failed
                    val body = response.body ?: return@withContext Download.Failed
                    val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var lastReported = -1
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (total > 0) {
                                    val percent = (done * 100 / total).toInt()
                                    if (percent != lastReported) {
                                        lastReported = percent
                                        onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                                    }
                                } else {
                                    onProgress(-1f)
                                }
                            }
                        }
                    }
                }
                if (!partial.renameTo(file)) return@withContext Download.Failed
                // The installer checks the signature; this checks it is CCloud and really newer
                val info = archiveInfo(context, file) ?: return@withContext Download.Failed
                if (info.packageName != context.packageName) return@withContext Download.Failed
                if (versionCodeOf(info) <= BuildConfig.VERSION_CODE.toLong()) {
                    skip(context, release.tag)
                    file.delete()
                    return@withContext Download.NotNewer
                }
                Download.Ready(file)
            } catch (e: Exception) {
                Log.w(TAG_LOG, "update download failed: $e")
                partial.delete()
                file.delete()
                Download.Failed
            }
        }

    /** Whether the app may start the package installer (Android 8+ asks the user once to allow it). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** The settings page where the user allows CCloud to install apps, or null if the device has none. */
    fun installPermissionIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    /** Opens the package installer for [apk]; false if the device has no installer to open. */
    fun install(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG_LOG, "no package installer: $e")
            false
        }
    }

    private fun skippedTags(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SKIPPED, null).orEmpty()

    private fun skip(context: Context, tag: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_SKIPPED, skippedTags(context) + tag).apply()
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(context: Context, file: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(file.path, 0)

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
}
