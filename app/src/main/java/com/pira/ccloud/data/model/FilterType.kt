package com.pira.ccloud.data.model

/**
 * Sort options. The first three are sorted by the server; the rest are ranked in the app
 * (movies only) from the server's lists, with data from TMDB and OMDb.
 */
enum class FilterType(
    // Ranked in the app instead of by the server
    val isRanked: Boolean = false,
    // Needs popularity, votes, actors or release dates from TMDB (or Watchmode when TMDB can't be reached)
    val needsMovieData: Boolean = false,
    // Ranks by the lead actors, which costs Watchmode extra credits
    val needsCast: Boolean = false,
    // Needs award data from OMDb (limited daily requests), so it only reads the newest-first list
    val needsOmdb: Boolean = false
) {
    DEFAULT,
    BY_YEAR,
    BY_IMDB,

    // One field each
    MOST_POPULAR(isRanked = true, needsMovieData = true),
    TOP_RATED(isRanked = true, needsMovieData = true),
    STAR_CAST(isRanked = true, needsMovieData = true, needsCast = true),
    MOST_AWARDED(isRanked = true, needsMovieData = true, needsOmdb = true),
    NEWEST(isRanked = true, needsMovieData = true),

    // Combinations
    TOP_PICKS(isRanked = true),
    POPULAR_CAST(isRanked = true, needsMovieData = true),
    BEST_OVERALL(isRanked = true, needsMovieData = true, needsOmdb = true)
}
