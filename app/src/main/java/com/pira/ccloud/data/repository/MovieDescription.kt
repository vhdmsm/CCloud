package com.pira.ccloud.data.repository

import com.pira.ccloud.data.model.Movie

/**
 * Facts the server already gives in a movie's Persian description and genres, so they need no
 * request: awards ("برنده 6 جایزه اسکار .. برنده 11 جایزه و نامزد 20 جایزه دیگر", the site's
 * "اسکار 2024" / "گلدن گلوب 2025" genres) and the release date ("تاریخ اکران : 28 Sep 2020").
 */
object MovieDescription {
    // Award counts not about the Oscars ("... جایزه اسکار" is counted separately)
    private val winsPattern = Regex("برنده\\s+(\\d+)\\s+جایزه(?![\\s\u200C]*(?:ی[\\s\u200C]*)?اسکار)")
    private val nominationsPattern = Regex("(?:نامزد|کاندید)(?:ای)?(?:\\s+دریافت)?\\s+(\\d+)\\s+جایزه(?![\\s\u200C]*(?:ی[\\s\u200C]*)?اسکار)")
    private val oscarWinsPattern = Regex("برنده\\s+(\\d+)\\s+جایزه[\\s\u200C]*(?:ی[\\s\u200C]*)?اسکار")
    private val oscarNominationsPattern = Regex("(?:نامزد|کاندید)(?:ای)?(?:\\s+دریافت)?\\s+(\\d+)\\s+جایزه[\\s\u200C]*(?:ی[\\s\u200C]*)?اسکار")
    // "اسکار 2024", "گلدن گلوب 2025": the site lists the movie among that year's contenders
    private val oscarGenre = Regex("^اسکار\\s*\\d{4}$")
    private val goldenGlobeGenre = Regex("^گلدن\\s*گلوب\\s*\\d{4}$")
    private val releaseDatePattern = Regex("تاریخ\\s+اکران\\s*:?\\s*(\\d{1,2})\\s+([A-Za-z]{3})[A-Za-z]*\\.?\\s+(\\d{4})")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Awards named in the description or genres, or null when they say nothing about awards. */
    fun awards(movie: Movie): OmdbClient.Awards? {
        val text = normalizeDigits(movie.description)
        val genreTitles = movie.genres.map { it.title.trim() }
        val oscarWins = maxCount(oscarWinsPattern, text)
        var oscarNominations = maxCount(oscarNominationsPattern, text)
        if (oscarNominations == 0 && oscarWins == 0 && genreTitles.any { oscarGenre.matches(it) }) oscarNominations = 1
        val otherWins = maxCount(winsPattern, text)
        var otherNominations = maxCount(nominationsPattern, text)
        if (otherNominations == 0 && otherWins == 0 && genreTitles.any { goldenGlobeGenre.matches(it) }) otherNominations = 1
        if (oscarWins + oscarNominations + otherWins + otherNominations == 0) return null
        return OmdbClient.Awards(
            oscarWins = oscarWins,
            oscarNominations = oscarNominations,
            wins = oscarWins + otherWins,
            nominations = oscarNominations + otherNominations
        )
    }

    /** "2020-09-28" from "تاریخ اکران : 28 Sep 2020", or null when the description has no date. */
    fun releaseDate(movie: Movie): String? {
        val match = releaseDatePattern.find(normalizeDigits(movie.description)) ?: return null
        val (day, month, year) = match.destructured
        val monthNumber = months.indexOf(month.lowercase()) + 1
        if (monthNumber == 0) return null
        return "%s-%02d-%02d".format(year, monthNumber, day.toInt())
    }

    // Persian and Arabic digits to ASCII, so "۱۱" counts as 11
    private fun normalizeDigits(text: String): String = buildString(text.length) {
        for (char in text) {
            append(
                when (char) {
                    in '۰'..'۹' -> '0' + (char - '۰')
                    in '٠'..'٩' -> '0' + (char - '٠')
                    else -> char
                }
            )
        }
    }

    private fun maxCount(pattern: Regex, text: String): Int =
        pattern.findAll(text).maxOfOrNull { it.groupValues[1].toIntOrNull() ?: 0 } ?: 0
}
