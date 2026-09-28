package com.pira.ccloud.data.model

/**
 * Sort options. The first three are sorted by the server; the rest are ranked in the app
 * (movies only) from the server's lists, with data from TMDB and OMDb.
 */
enum class FilterType(
    // Ranked in the app instead of by the server
    val isRanked: Boolean = false,
    // Needs popularity, votes, cast or release dates from TMDB
    val needsTmdb: Boolean = false,
    // Needs award data from OMDb (limited daily requests), so it only reads the newest-first list
    val needsOmdb: Boolean = false
) {
    DEFAULT,
    BY_YEAR,
    BY_IMDB,

    // One field each
    MOST_POPULAR(isRanked = true, needsTmdb = true),
    TOP_RATED(isRanked = true, needsTmdb = true),
    STAR_CAST(isRanked = true, needsTmdb = true),
    MOST_AWARDED(isRanked = true, needsTmdb = true, needsOmdb = true),
    NEWEST(isRanked = true, needsTmdb = true),

    // Combinations
    TOP_PICKS(isRanked = true),
    POPULAR_CAST(isRanked = true, needsTmdb = true),
    BEST_OVERALL(isRanked = true, needsTmdb = true, needsOmdb = true)
}
