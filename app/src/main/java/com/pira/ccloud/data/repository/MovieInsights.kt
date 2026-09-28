package com.pira.ccloud.data.repository

import com.pira.ccloud.data.model.FilterType
import com.pira.ccloud.data.model.Movie
import java.util.Calendar

/**
 * Everything the ranked sorts know about one movie, for its page: awards and the value of each
 * criterion. Uses cached answers when there are any; otherwise asks Watchmode (with the actors)
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

    suspend fun load(movie: Movie): Insight {
        val info = if (WatchmodeClient.isAvailable) WatchmodeClient.movie(movie.title, movie.year, withCast = true) else null
        val describedAwards = MovieDescription.awards(movie)
        val awards = describedAwards ?: info?.imdbId?.takeIf { it.isNotEmpty() && OmdbClient.isConfigured }
            ?.let { OmdbClient.awards(it) }
        val facts = RankedMovieRepository.MovieFacts(movie, info, awards)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return Insight(
            facts = facts,
            awardsFromDescription = describedAwards != null,
            releaseDate = MovieDescription.releaseDate(movie) ?: info?.releaseDate?.takeIf { it.isNotEmpty() },
            topRated = MovieRanking.score(FilterType.TOP_RATED, facts, year),
            bestOverall = MovieRanking.bestOverallParts(facts, year),
            incomplete = info == null || info.actors.isEmpty() || (awards == null && describedAwards == null && info.imdbId.isNotEmpty())
        )
    }
}
