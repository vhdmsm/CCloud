package com.pira.ccloud.utils

import com.pira.ccloud.data.model.Source

/**
 * Orders a movie's or episode's qualities best first: by picture height (2160/4K, 1440, 1080, 720,
 * 480...), trailers last; qualities that name no height keep their place among themselves, after
 * those that do.
 */
object QualityOrder {
    private val heightPattern = Regex("""(\d{3,4})\s*[pP]?""")

    /** The picture height [quality] names (e.g. "1080p x265" -> 1080), or null. */
    fun height(quality: String): Int? {
        // The site may write the numbers in Persian (or Arabic) digits
        val text = quality.lowercase().map { c ->
            when (c) {
                in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                else -> c
            }
        }.joinToString("")
        if ("4k" in text || "uhd" in text) return 2160
        if ("2k" in text) return 1440
        return heightPattern.findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it in KNOWN_HEIGHTS }
    }

    fun isTrailer(source: Source): Boolean =
        source.url.contains("/tizer/", ignoreCase = true) ||
            source.quality.contains("تیزر") ||
            source.quality.contains("trailer", ignoreCase = true)

    fun bestFirst(sources: List<Source>): List<Source> =
        sources.sortedWith(
            compareBy<Source> { isTrailer(it) }
                .thenByDescending { height(it.quality) ?: -1 }
        )

    private val KNOWN_HEIGHTS = setOf(240, 360, 480, 540, 576, 720, 900, 1080, 1440, 2160, 4320)
}

/** These qualities, best first (see [QualityOrder]). */
fun List<Source>.bestQualityFirst(): List<Source> = QualityOrder.bestFirst(this)
