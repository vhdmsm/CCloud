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
 * Movies for the sorts the server can't do (see [FilterType.isRanked]). Each load reads the
 * server's lists, keeps the movies not shown yet, adds TMDB and OMDb data where the sort needs
 * it and ranks them in the app, so every loaded batch is ordered best first.
 */
class RankedMovieRepository(
    private val movieRepository: MovieRepository = MovieRepository()
) {
    data class RankedPage(
        val movies: List<Movie>,
        // Last server page read; the next load starts after it
        val lastPage: Int,
        val hasMore: Boolean,
        // Shown above the list when TMDB or OMDb data is missing
        val notice: String?
    )

    // A movie with the data the sorts rank by; tmdb and awards are null when unknown or not needed
    data class MovieFacts(
        val movie: Movie,
        val tmdb: TmdbClient.TmdbMovie?,
        val awards: OmdbClient.Awards?
    )

    suspend fun getRankedMovies(
        page: Int,
        genreId: Int,
        filterType: FilterType,
        shownIds: Set<Int>
    ): RankedPage {
        val seen = shownIds.toMutableSet()
        val batch = mutableListOf<MovieFacts>()
        val tmdbPermits = Semaphore(TMDB_PARALLEL_REQUESTS)
        val omdbPermits = Semaphore(OMDB_PARALLEL_REQUESTS)
        val maxPages = if (filterType.needsOmdb) MAX_PAGES_PER_LOAD_OMDB else MAX_PAGES_PER_LOAD
        var currentPage = page
        var hasMore = true
        // Skipped and weaker movies leave gaps, so read on until the batch fills
        while (true) {
            val candidates = readServerPage(currentPage, genreId, filterType)
            hasMore = candidates.isNotEmpty()
            val fresh = candidates.filter { seen.add(it.id) && MovieRanking.isCandidate(it, filterType) }
            batch += addFacts(fresh, filterType, tmdbPermits, omdbPermits)
            if (batch.size >= MIN_BATCH_SIZE || !hasMore || currentPage - page + 1 >= maxPages) break
            currentPage++
        }

        // Without TMDB, Newest still has the year; the other sorts fall back to IMDB order
        val tmdbMissing = filterType.needsTmdb && !TmdbClient.isReachable && filterType != FilterType.NEWEST
        val omdbMissing = filterType.needsOmdb && !OmdbClient.isAvailable
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val ranked = batch.sortedWith(
            compareByDescending<MovieFacts> {
                if (tmdbMissing) it.movie.imdb else MovieRanking.score(filterType, it, year)
            }.thenByDescending { it.movie.imdb }
        )
        val notice = when {
            tmdbMissing -> "Couldn't reach TMDB, showing movies by IMDB score"
            omdbMissing -> "Award data from OMDb isn't available right now (daily limit or no connection)"
            else -> null
        }
        return RankedPage(ranked.map { it.movie }, currentPage, hasMore, notice)
    }

    // Award and date sorts read only the newest-first list, so OMDb's daily requests go to recent movies
    private suspend fun readServerPage(page: Int, genreId: Int, filterType: FilterType): List<Movie> =
        if (filterType.needsOmdb || filterType == FilterType.NEWEST) {
            movieRepository.getMovies(page, genreId, FilterType.BY_YEAR)
        } else {
            coroutineScope {
                val byImdb = async { movieRepository.getMovies(page, genreId, FilterType.BY_IMDB) }
                val byYear = async { movieRepository.getMovies(page, genreId, FilterType.BY_YEAR) }
                byImdb.await() + byYear.await()
            }
        }

    // Drops movies TMDB says are Indian or Turkish before any OMDb request is spent on them
    private suspend fun addFacts(
        movies: List<Movie>,
        filterType: FilterType,
        tmdbPermits: Semaphore,
        omdbPermits: Semaphore
    ): List<MovieFacts> = coroutineScope {
        movies.map { movie ->
            async {
                val tmdb = if (filterType.needsTmdb) {
                    tmdbPermits.withPermit { TmdbClient.movie(movie.title, movie.year) }
                } else {
                    null
                }
                if (tmdb != null && MovieRanking.isExcludedOrigin(tmdb)) return@async null
                val awards = if (filterType.needsOmdb && tmdb != null && tmdb.imdbId.isNotEmpty()) {
                    omdbPermits.withPermit { OmdbClient.awards(tmdb.imdbId) }
                } else {
                    null
                }
                MovieFacts(movie, tmdb, awards)
            }
        }.awaitAll().filterNotNull()
    }

    private companion object {
        const val MIN_BATCH_SIZE = 12
        const val MAX_PAGES_PER_LOAD = 3
        const val MAX_PAGES_PER_LOAD_OMDB = 2
        const val TMDB_PARALLEL_REQUESTS = 6
        const val OMDB_PARALLEL_REQUESTS = 4
    }
}

object MovieRanking {
    // Top Picks only shows movies rated at least this on IMDB
    const val TOP_PICKS_MIN_IMDB = 6.5
    // Movies from the last RECENCY_YEARS get up to RECENCY_BONUS extra points, newest the most
    private const val RECENCY_YEARS = 25
    private const val RECENCY_BONUS = 1.0
    // Top Rated pulls scores with few votes towards this average, so a 9.0 from 20 votes can't win
    private const val PRIOR_RATING = 6.0
    private const val PRIOR_VOTES = 300

    // Indian and Turkish movies are left out of the ranked sorts
    private val excludedCountryNames = setOf("india", "هند", "هندوستان", "turkey", "türkiye", "turkiye", "ترکیه")
    private val excludedCountryCodes = setOf("IN", "TR")
    // Hindi, Tamil, Telugu, Malayalam, Kannada, Bengali, Marathi, Punjabi, Gujarati, Turkish
    private val excludedLanguages = setOf("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa", "gu", "tr")

    fun isCandidate(movie: Movie, filterType: FilterType): Boolean {
        if (filterType.isRanked && movie.country.any { it.title.trim().lowercase() in excludedCountryNames }) {
            return false
        }
        return when (filterType) {
            FilterType.TOP_PICKS -> movie.imdb in TOP_PICKS_MIN_IMDB..10.0 && movie.year > 0
            else -> true
        }
    }

    fun isExcludedLanguage(language: String): Boolean = language.lowercase() in excludedLanguages

    fun isExcludedOrigin(tmdb: TmdbClient.TmdbMovie): Boolean =
        isExcludedLanguage(tmdb.originalLanguage) || tmdb.originCountries.any { it.uppercase() in excludedCountryCodes }

    /** Higher is better. Missing data scores lowest, so those movies end up at the bottom of the batch. */
    fun score(filterType: FilterType, facts: RankedMovieRepository.MovieFacts, currentYear: Int): Double {
        val movie = facts.movie
        val tmdb = facts.tmdb
        return when (filterType) {
            FilterType.MOST_POPULAR -> tmdb?.popularity ?: -1.0
            FilterType.TOP_RATED -> weightedRating(movie.imdb, tmdb?.voteCount ?: 0)
            FilterType.STAR_CAST -> tmdb?.castPopularity ?: -1.0
            FilterType.MOST_AWARDED -> facts.awards?.let { awardsScore(it) } ?: -1.0
            FilterType.NEWEST -> releaseDateValue(tmdb?.releaseDate.orEmpty(), movie.year)
            FilterType.TOP_PICKS -> topPicksScore(movie.imdb, movie.year, currentYear)
            FilterType.POPULAR_CAST -> tmdb?.let { popularityScore(it) } ?: -1.0
            FilterType.BEST_OVERALL -> bestOverallScore(facts, currentYear)
            FilterType.DEFAULT, FilterType.BY_YEAR, FilterType.BY_IMDB -> 0.0
        }
    }

    /**
     * IMDB score plus a bonus for newer movies: a 7.6 from this year (8.6) ranks above an 8.2 from
     * 25 years ago (8.2), while classics rated 8.7+ stay near the top.
     */
    fun topPicksScore(imdb: Double, year: Int, currentYear: Int): Double = imdb + RECENCY_BONUS * recency(year, currentYear)

    /** IMDB score, trusted more the more people voted on TMDB (a Bayesian average). */
    fun weightedRating(imdb: Double, voteCount: Int): Double {
        if (imdb <= 0.0) return -1.0
        val votes = voteCount.coerceAtLeast(0).toDouble()
        return (votes * imdb + PRIOR_VOTES * PRIOR_RATING) / (votes + PRIOR_VOTES)
    }

    /**
     * 0..1 score from TMDB data, each part on a log scale so a few blockbusters don't dwarf the rest:
     * current popularity (40%), all-time vote count (35%) and how famous the lead actors are (25%).
     */
    fun popularityScore(tmdb: TmdbClient.TmdbMovie): Double =
        0.40 * logScale(tmdb.popularity, 300.0) +
            0.35 * logScale(tmdb.voteCount.toDouble(), 30_000.0) +
            0.25 * logScale(tmdb.castPopularity, 60.0)

    /** 0..1: an Oscar win counts like 10 other wins, an Oscar nomination like 3, other nominations a quarter. */
    fun awardsScore(awards: OmdbClient.Awards): Double {
        val otherWins = (awards.wins - awards.oscarWins).coerceAtLeast(0)
        val otherNominations = (awards.nominations - awards.oscarNominations).coerceAtLeast(0)
        val points = awards.oscarWins * 10.0 + awards.oscarNominations * 3.0 + otherWins + otherNominations * 0.25
        return logScale(points, 200.0)
    }

    /**
     * 0..1 mix of everything: rating (35%), popularity (20%), cast (15%), awards (15%) and how new
     * the movie is (15%). Missing parts count as zero.
     */
    fun bestOverallScore(facts: RankedMovieRepository.MovieFacts, currentYear: Int): Double {
        val tmdb = facts.tmdb
        // 5.0 -> 0, 9.0 -> 1
        val rating = ((weightedRating(facts.movie.imdb, tmdb?.voteCount ?: 0) - 5.0) / 4.0).coerceIn(0.0, 1.0)
        return 0.35 * rating +
            0.20 * logScale(tmdb?.popularity ?: 0.0, 300.0) +
            0.15 * logScale(tmdb?.castPopularity ?: 0.0, 60.0) +
            0.15 * (facts.awards?.let { awardsScore(it) } ?: 0.0) +
            0.15 * recency(facts.movie.year, currentYear)
    }

    // "2024-03-01" -> 20240301; the year alone when TMDB has no date
    fun releaseDateValue(releaseDate: String, year: Int): Double {
        val parts = releaseDate.split('-').mapNotNull { it.toIntOrNull() }
        return if (parts.size == 3) {
            parts[0] * 10_000.0 + parts[1] * 100 + parts[2]
        } else {
            year * 10_000.0
        }
    }

    // 1 for this year, down to 0 for RECENCY_YEARS ago and older
    private fun recency(year: Int, currentYear: Int): Double =
        ((year - (currentYear - RECENCY_YEARS)).toDouble() / RECENCY_YEARS).coerceIn(0.0, 1.0)

    // 0 at 0, 1 at max and above
    private fun logScale(value: Double, max: Double): Double =
        (ln(1 + value.coerceAtLeast(0.0)) / ln(1 + max)).coerceAtMost(1.0)
}
