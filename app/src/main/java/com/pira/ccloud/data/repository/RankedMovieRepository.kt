package com.pira.ccloud.data.repository

import com.pira.ccloud.data.model.FilterType
import com.pira.ccloud.data.model.Movie
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Calendar
import kotlin.math.ln

/**
 * Movies for the filters the server can't sort by (Top Picks, Popular). Each load reads the
 * server's IMDB-sorted and year-sorted lists, keeps the movies not shown yet and ranks them
 * in the app, so every loaded batch is ordered best first.
 */
class RankedMovieRepository(
    private val movieRepository: MovieRepository = MovieRepository()
) {
    data class RankedPage(
        val movies: List<Movie>,
        // Last server page read; the next load starts after it
        val lastPage: Int,
        val hasMore: Boolean,
        // False when TMDB couldn't be reached, so Popular fell back to IMDB order
        val popularityAvailable: Boolean
    )

    suspend fun getRankedMovies(
        page: Int,
        genreId: Int,
        filterType: FilterType,
        shownIds: Set<Int>
    ): RankedPage = coroutineScope {
        val seen = shownIds.toMutableSet()
        val batch = mutableListOf<Movie>()
        var currentPage = page
        var hasMore = true
        // Top Picks drops weaker movies, so a server page can yield few; read on until the batch fills
        while (true) {
            val byImdb = async { movieRepository.getMovies(currentPage, genreId, FilterType.BY_IMDB) }
            val byYear = async { movieRepository.getMovies(currentPage, genreId, FilterType.BY_YEAR) }
            val candidates = byImdb.await() + byYear.await()
            hasMore = candidates.isNotEmpty()
            candidates.filterTo(batch) { seen.add(it.id) && MovieRanking.isCandidate(it, filterType) }
            if (batch.size >= MIN_BATCH_SIZE || !hasMore || currentPage - page + 1 >= MAX_PAGES_PER_LOAD) break
            currentPage++
        }

        when (filterType) {
            FilterType.POPULAR -> {
                val permits = Semaphore(TMDB_PARALLEL_REQUESTS)
                val popularity = batch.map { movie ->
                    async { permits.withPermit { TmdbClient.moviePopularity(movie.title, movie.year) } }
                }.awaitAll()
                val ranked = batch.zip(popularity)
                    .sortedWith(
                        compareByDescending<Pair<Movie, TmdbClient.Popularity?>> { (_, info) ->
                            info?.let { MovieRanking.popularityScore(it) } ?: -1.0
                        }.thenByDescending { (movie, _) -> movie.imdb }
                    )
                    .map { it.first }
                RankedPage(ranked, currentPage, hasMore, popularityAvailable = TmdbClient.isReachable)
            }
            else -> {
                val year = Calendar.getInstance().get(Calendar.YEAR)
                val ranked = batch.sortedByDescending { MovieRanking.topPicksScore(it.imdb, it.year, year) }
                RankedPage(ranked, currentPage, hasMore, popularityAvailable = true)
            }
        }
    }

    private companion object {
        const val MIN_BATCH_SIZE = 12
        const val MAX_PAGES_PER_LOAD = 3
        const val TMDB_PARALLEL_REQUESTS = 6
    }
}

object MovieRanking {
    // Top Picks only shows movies rated at least this on IMDB
    const val TOP_PICKS_MIN_IMDB = 6.5
    // Movies from the last RECENCY_YEARS get up to RECENCY_BONUS extra points, newest the most
    private const val RECENCY_YEARS = 25
    private const val RECENCY_BONUS = 1.0

    fun isCandidate(movie: Movie, filterType: FilterType): Boolean = when (filterType) {
        FilterType.TOP_PICKS -> movie.imdb in TOP_PICKS_MIN_IMDB..10.0 && movie.year > 0
        else -> true
    }

    /**
     * IMDB score plus a bonus for newer movies: a 7.6 from this year (8.6) ranks above an 8.2 from
     * 25 years ago (8.2), while classics rated 8.7+ stay near the top.
     */
    fun topPicksScore(imdb: Double, year: Int, currentYear: Int): Double {
        val recency = ((year - (currentYear - RECENCY_YEARS)).toDouble() / RECENCY_YEARS).coerceIn(0.0, 1.0)
        return imdb + RECENCY_BONUS * recency
    }

    /**
     * 0..1 score from TMDB data, each part on a log scale so a few blockbusters don't dwarf the rest:
     * current popularity (40%), all-time vote count (35%) and how famous the lead actors are (25%).
     */
    fun popularityScore(info: TmdbClient.Popularity): Double =
        0.40 * logScale(info.popularity, 300.0) +
            0.35 * logScale(info.voteCount.toDouble(), 30_000.0) +
            0.25 * logScale(info.castPopularity, 60.0)

    // 0 at 0, 1 at max and above
    private fun logScale(value: Double, max: Double): Double =
        (ln(1 + value.coerceAtLeast(0.0)) / ln(1 + max)).coerceAtMost(1.0)
}
