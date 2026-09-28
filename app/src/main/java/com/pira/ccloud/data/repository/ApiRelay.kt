package com.pira.ccloud.data.repository

import com.pira.ccloud.BuildConfig
import okhttp3.Request

/**
 * The app's own relay server (see relay/ in the repository), used when TMDB or OMDb can't be
 * reached directly (e.g. from Iran). With a relay the API keys stay on the server.
 */
object ApiRelay {
    val url: String get() = BuildConfig.RELAY_URL.trimEnd('/')

    val isEnabled: Boolean get() = url.isNotEmpty()

    // Lets the relay turn away requests that don't come from the app
    fun Request.Builder.relayToken(): Request.Builder = apply {
        if (BuildConfig.RELAY_TOKEN.isNotEmpty()) header("X-App-Token", BuildConfig.RELAY_TOKEN)
    }
}
