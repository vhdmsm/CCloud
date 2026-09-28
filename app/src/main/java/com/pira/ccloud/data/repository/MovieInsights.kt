package com.pira.ccloud.data.repository

import com.pira.ccloud.data.model.FilterType
import com.pira.ccloud.data.model.Movie
import java.util.Calendar

/**
 * Everything the ranked sorts know about one movie, for its page: awards, the current IMDb rating
 * and the value of each criterion. Uses cached answers when there are any; otherwise asks Watchmode (with the actors)
 * and OMDb under the same limits as the sorts.
 */
object MovieInsights {
    data class Insight(
        val facts: RankedMovieRepository.MovieFacts,
        // Awards read from the server's description (no OMDb request)
        val awardsFromDescription: Boolean,
        val releaseDate: String?,
        val topRated: Double,
        val bestOverall: MovieRanking.BestOverallParts,
        // Some data wasn't available (limits, no connection or no key)
        val incomplete: Boolean
    )

    /** [series]: [movie] is a series (as a movie), looked up on Watchmode as one. */
    suspend fun load(movie: Movie, series: Boolean = false): Insight {
        val info = if (WatchmodeClient.isConfigured) WatchmodeClient.movie(movie.title, movie.year, withCast = true, series = series) else null
        val describedAwards = MovieDescription.awards(movie)
        val details = info?.imdbId?.takeIf { it.isNotEmpty() && OmdbClient.isConfigured }
            ?.let { OmdbClient.details(it, movie.year) }
        val awards = MovieRanking.pickAwards(details?.awards, describedAwards)
        val facts = RankedMovieRepository.MovieFacts(movie, info, awards, details?.rating)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return Insight(
            facts = facts,
            awardsFromDescription = describedAwards != null && awards === describedAwards,
            releaseDate = MovieDescription.releaseDate(movie) ?: info?.releaseDate?.takeIf { it.isNotEmpty() },
            topRated = MovieRanking.score(FilterType.TOP_RATED, facts, year),
            bestOverall = MovieRanking.bestOverallParts(facts, year),
            incomplete = info == null || info.actors.isEmpty() || (details == null && info.imdbId.isNotEmpty())
        )
    }
}
