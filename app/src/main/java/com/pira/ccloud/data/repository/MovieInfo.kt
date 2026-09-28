package com.pira.ccloud.data.repository

/** What the ranked sorts know about a movie, from Watchmode. Scores are 0..1. */
data class MovieInfo(
    // Current interest (popularity percentile)
    val popularity: Double,
    // How widely known it is overall (relevance percentile)
    val reach: Double,
    // How far to trust the IMDB score: near 1 for well-known movies that many people rated
    val ratingConfidence: Double,
    // Fame of the lead actors; null when not known
    val castPopularity: Double?,
    // "2024-03-01", empty when unknown
    val releaseDate: String,
    // "tt1234567", empty when unknown; used to read awards from OMDb
    val imdbId: String,
    val originalLanguage: String
)
