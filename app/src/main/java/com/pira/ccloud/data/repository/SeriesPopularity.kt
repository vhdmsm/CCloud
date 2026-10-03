package com.pira.ccloud.data.repository

import java.text.Normalizer

/**
 * Watchmode's most popular series (title and start year, the most popular first), to guess which
 * series will rank best before any of their data is looked up: the site's IMDb scores are often
 * high for little-known series, popularity tells the well-known ones apart.
 */
class SeriesPopularity(titles: List<Pair<String, Int>>) {
    // Series on the list
    val size = titles.size
    // Normalized title -> (place in the list, start year) of each series with that title
    private val places: Map<String, List<Pair<Int, Int>>> = titles.withIndex()
        .groupBy({ normalize(it.value.first) }, { it.index to it.value.second })

    /**
     * 0..1: 1 for the most popular series down to 0 for the last listed; null when it isn't
     * listed. A series that started up to [MAX_YEARS_BEFORE] years earlier (or a year later)
     * matches too; of several with the name (a Korean "Mouse" and a Thai one), the closest start
     * year counts, not the most popular.
     */
    fun score(title: String, year: Int): Double? {
        if (size == 0) return null
        val place = places[normalize(title)]
            ?.filter { (_, started) -> started <= 0 || year <= 0 || started in (year - MAX_YEARS_BEFORE)..(year + 1) }
            ?.minWithOrNull(compareBy({ (_, started) -> distance(started, year) }, { it.first }))
            ?.first
            ?: return null
        return 1.0 - place.toDouble() / size
    }

    companion object {
        private const val MAX_YEARS_BEFORE = 5

        // Years between the start years; unknown years come after the known ones
        private fun distance(started: Int, year: Int): Int = when {
            year <= 0 -> 0
            started <= 0 -> MAX_YEARS_BEFORE + 2
            else -> kotlin.math.abs(started - year)
        }

        /**
         * Lowercase letters and digits only, without accents, apostrophes (the site writes "The
         * Queens Gambit") or a "(2024)" year: "Shōgun" -> "shogun", "The Queen's Gambit" -> "the queens gambit".
         */
        fun normalize(title: String): String {
            val withoutYear = title.replace(YEAR, " ")
            return Normalizer.normalize(withoutYear, Normalizer.Form.NFD)
                .replace(MARKS, "")
                .replace(APOSTROPHES, "")
                .lowercase()
                .replace(NOT_LETTERS, " ")
                .trim()
        }

        // Compiled once: titles are normalized thousands of times while a list is ranked
        private val YEAR = Regex("\\((19|20)\\d{2}\\)")
        private val MARKS = Regex("\\p{M}+")
        private val APOSTROPHES = Regex("['’ʼ`]")
        private val NOT_LETTERS = Regex("[^\\p{L}\\p{N}]+")
    }
}
