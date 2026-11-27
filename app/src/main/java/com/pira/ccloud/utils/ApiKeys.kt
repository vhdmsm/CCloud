package com.pira.ccloud.utils

import android.content.Context
import com.pira.ccloud.BuildConfig

/**
 * The OMDb, Watchmode and OpenSubtitles API keys.
 *
 * Builds made with keys (the owner's own build) save them on the device. Public releases are built
 * without keys, so when the in-app updater installs one over the owner's build, the saved keys are
 * used and every feature keeps working. Keys never leave the device: the prefs file is excluded
 * from backups, and public APKs hold none.
 */
object ApiKeys {
    private const val PREFS_NAME = "api_keys"
    private const val KEY_OMDB = "omdb"
    private const val KEY_WATCHMODE = "watchmode"
    private const val KEY_OPENSUBTITLES = "opensubtitles"

    @Volatile var omdb: String = BuildConfig.OMDB_API_KEYS; private set
    @Volatile var watchmode: String = BuildConfig.WATCHMODE_API_KEYS; private set
    @Volatile var openSubtitles: String = BuildConfig.OPENSUBTITLES_API_KEY; private set

    @Volatile private var initialized = false

    /** Call before the API clients are used; later calls do nothing. */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val stored = Triple(
                    prefs.getString(KEY_OMDB, "").orEmpty(),
                    prefs.getString(KEY_WATCHMODE, "").orEmpty(),
                    prefs.getString(KEY_OPENSUBTITLES, "").orEmpty()
                )
                val (o, w, s) = resolve(
                    Triple(BuildConfig.OMDB_API_KEYS, BuildConfig.WATCHMODE_API_KEYS, BuildConfig.OPENSUBTITLES_API_KEY),
                    stored
                )
                omdb = o; watchmode = w; openSubtitles = s
                // This build's own keys are saved (newest wins), so a keyless update keeps them
                if (Triple(o, w, s) != stored) {
                    prefs.edit()
                        .putString(KEY_OMDB, o)
                        .putString(KEY_WATCHMODE, w)
                        .putString(KEY_OPENSUBTITLES, s)
                        .apply()
                }
            } catch (e: Exception) {
                // Keep this build's own keys
            }
            initialized = true
        }
    }

    /** Each key set from this build if it has one, else the one saved by an earlier build. */
    internal fun resolve(build: Triple<String, String, String>, stored: Triple<String, String, String>) =
        Triple(
            build.first.ifBlank { stored.first },
            build.second.ifBlank { stored.second },
            build.third.ifBlank { stored.third }
        )
}
