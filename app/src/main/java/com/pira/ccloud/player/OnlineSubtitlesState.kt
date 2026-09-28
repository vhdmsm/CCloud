package com.pira.ccloud.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * UI state for finding an English subtitle on OpenSubtitles for the playing video.
 */
class OnlineSubtitlesState(
    context: Context,
    private val videoUrl: String,
    private val scope: CoroutineScope
) {
    private val store = ExternalSubtitleStore(context, videoUrl)

    // Called when the subtitle (or its timing) changes, so the player can reload it
    var onSubtitleChanged: (ExternalSubtitle?) -> Unit = {}

    // Subtitle downloaded earlier for this video, if any
    var subtitle by mutableStateOf(runCatching { store.load() }.getOrNull())
        private set
    var results by mutableStateOf<List<OpenSubtitlesClient.Result>>(emptyList())
        private set
    var isBusy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun search() {
        if (isBusy) return
        scope.launch {
            isBusy = true
            message = null
            try {
                val release = OpenSubtitlesClient.parseReleaseName(videoUrl)
                val hash = OpenSubtitlesClient.movieHash(videoUrl)
                results = OpenSubtitlesClient.search(release, hash)
                if (results.isEmpty()) {
                    message = "No English subtitles found for \"${release.title}\""
                }
            } catch (e: Exception) {
                message = errorMessage(e)
            } finally {
                isBusy = false
            }
        }
    }

    fun download(result: OpenSubtitlesClient.Result) {
        if (isBusy) return
        scope.launch {
            isBusy = true
            message = null
            try {
                val download = OpenSubtitlesClient.download(result)
                val saved = store.save(download.text, result.release)
                subtitle = saved
                results = emptyList()
                message = "Added." + (download.remaining?.let { " Downloads left today: $it" } ?: "")
                onSubtitleChanged(saved)
            } catch (e: Exception) {
                message = errorMessage(e)
            } finally {
                isBusy = false
            }
        }
    }

    // Positive = subtitles appear later
    fun shift(deltaMs: Long) {
        val current = subtitle ?: return
        val shifted = store.setOffset(current, current.offsetMs + deltaMs)
        subtitle = shifted
        onSubtitleChanged(shifted)
    }

    fun remove() {
        store.clear()
        subtitle = null
        message = null
        onSubtitleChanged(null)
    }

    private fun errorMessage(e: Exception): String = when (e) {
        is OpenSubtitlesClient.ApiException -> e.message ?: "OpenSubtitles error"
        is IOException -> "Couldn't reach OpenSubtitles. Check the internet connection."
        else -> "Something went wrong: ${e.message}"
    }
}
