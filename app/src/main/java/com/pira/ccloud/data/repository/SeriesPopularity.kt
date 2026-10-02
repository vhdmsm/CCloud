package com.pira.ccloud.data.repository

import java.text.Normalizer

/**
 * Watchmode's most popular series (title and start year, the most popular first), to guess which
 * series will rank best before any of their data is looked up: the site's IMDb scores are often
 * high for little-known series, popularity tells the well-known ones apart.
 */
class SeriesPopularity(titles: List<Pair<String, Int>>) {
    private val size = titles.size
    // Normalized title -> (place in the list, start year) of each series with that title
    private val places: Map<String, List<Pair<Int, Int>>> = titles.withIndex()
        .groupBy({ normalize(it.value.first) }, { it.index to it.value.second })

    /**
     * 0..1: 1 for the most popular series down to 0 for the last listed; null when it isn't
     * listed. The site may list a series by a later season's year, Watchmode by its first, so a
     * series that started up to [MAX_YEARS_BEFORE] years earlier (or a year later) matches too.
     */
    fun score(title: String, year: Int): Double? {
        if (size == 0) return null
        val place = places[normalize(title)]
            ?.filter { (_, started) -> started <= 0 || year <= 0 || started in (year - MAX_YEARS_BEFORE)..(year + 1) }
            ?.minOfOrNull { it.first }
            ?: return null
        return 1.0 - place.toDouble() / size
    }

    companion object {
        private const val MAX_YEARS_BEFORE = 5

        /**
         * Lowercase letters and digits only, without accents, apostrophes (the site writes "The
         * Queens Gambit") or a "(2024)" year: "Shōgun" -> "shogun", "The Queen's Gambit" -> "the queens gambit".
         */
        fun normalize(title: String): String {
            val withoutYear = title.replace(Regex("\\((19|20)\\d{2}\\)"), " ")
            return Normalizer.normalize(withoutYear, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("['’ʼ`]"), "")
                .lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
        }
    }
}
