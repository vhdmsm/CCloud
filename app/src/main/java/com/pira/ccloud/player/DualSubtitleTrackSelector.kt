package com.pira.ccloud.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import com.pira.ccloud.data.model.SubtitleMode

/**
 * Track selector that picks subtitle tracks according to a [SubtitleMode] instead of ExoPlayer's
 * default "one text track" logic.
 *
 * In [SubtitleMode.BOTH] the English track is routed to [SecondaryTextRenderer] (see
 * [isSecondaryTrack]) so that Persian and English cues are decoded at the same time.
 */
@OptIn(UnstableApi::class)
class DualSubtitleTrackSelector(
    context: Context,
    initialMode: SubtitleMode
) : DefaultTrackSelector(context) {

    data class SubtitleConfig(
        val mode: SubtitleMode,
        // Manually chosen tracks (see SubtitleTracks.keyOf); null means pick automatically
        val persianTrackKey: String? = null,
        val englishTrackKey: String? = null
    )

    private class Candidate(val rendererIndex: Int, val group: TrackGroup, val trackIndex: Int) {
        val format: Format get() = group.getFormat(trackIndex)
    }

    @Volatile
    var subtitleConfig = SubtitleConfig(initialMode)
        private set

    fun setSubtitleConfig(config: SubtitleConfig) {
        if (config == subtitleConfig) return
        subtitleConfig = config
        // Re-runs track mapping and selection on the playback thread
        invalidate()
    }

    // Called by SecondaryTextRenderer while tracks are mapped to renderers (playback thread)
    fun isSecondaryTrack(format: Format): Boolean {
        val config = subtitleConfig
        if (config.mode != SubtitleMode.BOTH) return false
        val key = SubtitleTracks.keyOf(format)
        if (key == config.persianTrackKey) return false
        return if (config.englishTrackKey != null) {
            key == config.englishTrackKey
        } else {
            SubtitleTracks.classify(format) == SubtitleLanguage.ENGLISH
        }
    }

    override fun selectAllTracks(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: Parameters
    ): Array<ExoTrackSelection.Definition?> {
        val definitions = super.selectAllTracks(
            mappedTrackInfo,
            rendererFormatSupports,
            rendererMixedMimeTypeAdaptationSupports,
            params
        )

        val primaryCandidates = mutableListOf<Candidate>()
        val secondaryCandidates = mutableListOf<Candidate>()
        for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
            if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_TEXT) continue
            // Text selection is decided entirely below
            definitions[rendererIndex] = null
            val isSecondary = mappedTrackInfo.getRendererName(rendererIndex) == SecondaryTextRenderer.NAME
            val candidates = if (isSecondary) secondaryCandidates else primaryCandidates
            val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
            for (groupIndex in 0 until trackGroups.length) {
                val group = trackGroups[groupIndex]
                for (trackIndex in 0 until group.length) {
                    val support = RendererCapabilities.getFormatSupport(
                        rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
                    )
                    if (support == C.FORMAT_HANDLED) {
                        candidates += Candidate(rendererIndex, group, trackIndex)
                    }
                }
            }
        }

        fun select(candidate: Candidate?) {
            if (candidate != null) {
                definitions[candidate.rendererIndex] =
                    ExoTrackSelection.Definition(candidate.group, candidate.trackIndex)
            }
        }

        val config = subtitleConfig
        when (config.mode) {
            SubtitleMode.OFF -> Unit
            SubtitleMode.PERSIAN -> select(pickPersian(primaryCandidates, config))
            SubtitleMode.ENGLISH -> select(pickEnglish(primaryCandidates, config))
            SubtitleMode.BOTH -> {
                select(pickPersian(primaryCandidates, config))
                select(pickEnglish(secondaryCandidates, config))
            }
        }
        return definitions
    }

    private fun pickPersian(candidates: List<Candidate>, config: SubtitleConfig): Candidate? =
        findByKey(candidates, config.persianTrackKey)
            ?: best(candidates, SubtitleLanguage.PERSIAN)
            // Untagged tracks on this Persian-focused service are almost always Persian
            ?: best(candidates, SubtitleLanguage.UNKNOWN)

    private fun pickEnglish(candidates: List<Candidate>, config: SubtitleConfig): Candidate? =
        findByKey(candidates, config.englishTrackKey)
            ?: best(candidates, SubtitleLanguage.ENGLISH)

    private fun findByKey(candidates: List<Candidate>, key: String?): Candidate? =
        key?.let { candidates.firstOrNull { SubtitleTracks.keyOf(it.format) == key } }

    private fun best(candidates: List<Candidate>, language: SubtitleLanguage): Candidate? =
        candidates
            .filter { SubtitleTracks.classify(it.format) == language }
            .minByOrNull { SubtitleTracks.preferenceRank(it.format) }
}
