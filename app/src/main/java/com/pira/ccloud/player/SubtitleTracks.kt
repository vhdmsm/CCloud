package com.pira.ccloud.player

import androidx.media3.common.C
import androidx.media3.common.Format

enum class SubtitleLanguage { PERSIAN, ENGLISH, OTHER, UNKNOWN }

// Helpers for recognising the language of embedded subtitle tracks (MKV/MP4 soft subs)
object SubtitleTracks {
    // Label of the subtitle downloaded from OpenSubtitles (added to the player as an extra track)
    const val EXTERNAL_LABEL = "English (OpenSubtitles)"

    fun isExternal(format: Format): Boolean = format.label == EXTERNAL_LABEL

    private val persianCodes = setOf("fa", "fas", "per", "prs")
    private val englishCodes = setOf("en", "eng")
    private val persianLabelWords = listOf("persian", "farsi", "parsi", "فارسی", "پارسی")
    private val englishLabelWords = listOf("english", "انگلیسی")

    fun classify(format: Format): SubtitleLanguage {
        // The track title is written by whoever muxed the file, so it wins over the language tag
        val label = format.label?.lowercase().orEmpty()
        if (persianLabelWords.any { label.contains(it) }) return SubtitleLanguage.PERSIAN
        if (englishLabelWords.any { label.contains(it) }) return SubtitleLanguage.ENGLISH

        val language = format.language
            ?.lowercase()
            ?.substringBefore('-')
            ?.substringBefore('_')
            .orEmpty()
        return when {
            language in persianCodes -> SubtitleLanguage.PERSIAN
            language in englishCodes -> SubtitleLanguage.ENGLISH
            language.isEmpty() || language == "und" -> SubtitleLanguage.UNKNOWN
            else -> SubtitleLanguage.OTHER
        }
    }

    // True when the track title itself says English (the language tag alone is unreliable: MKV
    // tracks without a language tag are reported as "eng")
    fun isLabeledEnglish(format: Format): Boolean {
        val label = format.label?.lowercase().orEmpty()
        return englishLabelWords.any { label.contains(it) }
    }

    // Language of subtitle text judged by its script, or null when there is too little text
    fun detectLanguage(text: String): SubtitleLanguage? {
        var arabicScript = 0
        var latin = 0
        for (c in text) {
            when {
                c in '؀'..'ۿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿' -> arabicScript++
                c in 'a'..'z' || c in 'A'..'Z' -> latin++
            }
        }
        return when {
            arabicScript >= 3 && arabicScript > latin -> SubtitleLanguage.PERSIAN
            latin >= 5 && arabicScript == 0 -> SubtitleLanguage.ENGLISH
            else -> null
        }
    }

    // Stable identifier for a track, used to remember a manual track choice across re-selections
    fun keyOf(format: Format): String =
        format.id ?: "${format.language}|${format.label}|${format.codecs}|${format.sampleMimeType}"

    fun displayName(format: Format, position: Int, trackLanguage: SubtitleLanguage = classify(format)): String {
        val language = when (trackLanguage) {
            SubtitleLanguage.PERSIAN -> "Persian"
            SubtitleLanguage.ENGLISH -> "English"
            SubtitleLanguage.OTHER -> format.language ?: "Unknown"
            SubtitleLanguage.UNKNOWN -> "Unknown language"
        }
        val label = format.label?.takeIf { it.isNotBlank() }
        // A title that already names the language (e.g. "English (OpenSubtitles)") is shown as is
        val name = when {
            label == null -> language
            label.contains(language, ignoreCase = true) -> label
            else -> "$language ($label)"
        }
        return buildString {
            append("#$position · $name")
            if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) append(" (forced)")
        }
    }

    // Default-flagged tracks first, forced (partial) tracks last
    fun preferenceRank(format: Format): Int = when {
        format.selectionFlags and C.SELECTION_FLAG_FORCED != 0 -> 2
        format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 -> 0
        else -> 1
    }
}
