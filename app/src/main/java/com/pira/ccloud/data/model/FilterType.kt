package com.pira.ccloud.data.model

enum class FilterType {
    DEFAULT,
    BY_YEAR,
    BY_IMDB,
    // Ranked in the app: IMDB score combined with the release year
    TOP_PICKS,
    // Ranked in the app: popularity on TMDB and the fame of the cast (needs a TMDB API key)
    POPULAR
}
