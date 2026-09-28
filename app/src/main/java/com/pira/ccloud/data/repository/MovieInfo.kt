package com.pira.ccloud.data.repository

/**
 * What the ranked sorts know about a movie, from TMDB or (when TMDB can't be reached) Watchmode.
 * Scores are 0..1 so both sources rank the same way.
 */
data class MovieInfo(
    // Current interest (TMDB popularity, Watchmode popularity percentile)
    val popularity: Double,
    // How widely known it is overall (TMDB vote count, Watchmode relevance percentile)
    val reach: Double,
    // How far to trust the IMDB score: near 1 when many people rated it
    val ratingConfidence: Double,
    // Fame of the lead actors; null when not known
    val castPopularity: Double?,
    // "2024-03-01", empty when unknown
    val releaseDate: String,
    // "tt1234567", empty when unknown; used to read awards from OMDb
    val imdbId: String,
    val originalLanguage: String,
    // ISO codes; Watchmode doesn't say
    val originCountries: List<String>,
    val source: Source
) {
    enum class Source { TMDB, WATCHMODE }
}
