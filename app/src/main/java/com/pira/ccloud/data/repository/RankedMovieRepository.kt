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
 * server's lists, keeps the movies not shown yet, adds Watchmode and OMDb data where the sort needs
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
        // Shown above the list when movie or award data is missing
        val notice: String?,
        // Credit for the data source, shown under the sort when Watchmode data is used (its terms ask for it)
        val attribution: String?
    )

    // A movie with the data the sorts rank by; info and awards are null when unknown or not needed
    data class MovieFacts(
        val movie: Movie,
        val info: MovieInfo?,
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
        val permits = Permits()
        val maxPages = if (filterType.needsOmdb) MAX_PAGES_PER_LOAD_OMDB else MAX_PAGES_PER_LOAD
        var currentPage = page
        var hasMore = true
        // Skipped and weaker movies leave gaps, so read on until the batch fills
        while (true) {
            val candidates = readServerPage(currentPage, genreId, filterType)
            hasMore = candidates.isNotEmpty()
            val fresh = candidates.filter { seen.add(it.id) && MovieRanking.isCandidate(it, filterType) }
            batch += addFacts(fresh, filterType, permits)
            if (batch.size >= MIN_BATCH_SIZE || !hasMore || currentPage - page + 1 >= maxPages) break
            currentPage++
        }

        // Without movie data, Newest still has the year; the other sorts fall back to IMDB order
        val dataMissing = filterType.needsMovieData && filterType != FilterType.NEWEST &&
            batch.isNotEmpty() && batch.none { it.info != null }
        val castMissing = filterType.needsCast && !dataMissing &&
            batch.isNotEmpty() && batch.none { it.info?.castPopularity != null }
        val omdbMissing = filterType.needsOmdb && !OmdbClient.isAvailable
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val ranked = batch.sortedWith(
            compareByDescending<MovieFacts> {
                if (dataMissing || castMissing) it.movie.imdb else MovieRanking.score(filterType, it, year)
            }.thenByDescending { it.movie.imdb }
        )
        val notice = when {
            dataMissing -> "Movie data from Watchmode isn't available right now (monthly limit or no connection), showing movies by IMDB score"
            castMissing -> "Actor data isn't available right now, showing movies by IMDB score"
            omdbMissing -> "Award data from OMDb isn't available right now (daily limit or no connection)"
            else -> null
        }
        val attribution = if (batch.any { it.info != null }) "Movie data from Watchmode" else null
        return RankedPage(ranked.map { it.movie }, currentPage, hasMore, notice, attribution)
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

    // Parallel requests allowed to each service
    private class Permits {
        val watchmode = Semaphore(3)
        val omdb = Semaphore(4)
    }

    // Drops movies Watchmode says are Indian or Turkish before any OMDb request is spent on them
    private suspend fun addFacts(
        movies: List<Movie>,
        filterType: FilterType,
        permits: Permits
    ): List<MovieFacts> = coroutineScope {
        movies.map { movie ->
            async {
                val info = if (filterType.needsMovieData && WatchmodeClient.isAvailable) {
                    permits.watchmode.withPermit { WatchmodeClient.movie(movie.title, movie.year, filterType.needsCast) }
                } else {
                    null
                }
                if (info != null && MovieRanking.isExcludedOrigin(info)) return@async null
                val awards = if (filterType.needsOmdb && info != null && info.imdbId.isNotEmpty()) {
                    permits.omdb.withPermit { OmdbClient.awards(info.imdbId) }
                } else {
                    null
                }
                MovieFacts(movie, info, awards)
            }
        }.awaitAll().filterNotNull()
    }

    private companion object {
        const val MIN_BATCH_SIZE = 12
        const val MAX_PAGES_PER_LOAD = 3
        const val MAX_PAGES_PER_LOAD_OMDB = 2
    }
}

object MovieRanking {
    // Top Picks only shows movies rated at least this on IMDB
    const val TOP_PICKS_MIN_IMDB = 6.5
    // Movies from the last RECENCY_YEARS get up to RECENCY_BONUS extra points, newest the most
    private const val RECENCY_YEARS = 25
    private const val RECENCY_BONUS = 1.0
    // Top Rated pulls scores of little-known movies towards this average, so an obscure 9.0 can't win
    private const val PRIOR_RATING = 6.0

    // Indian and Turkish movies are left out of the ranked sorts
    private val excludedCountryNames = setOf("india", "هند", "هندوستان", "turkey", "türkiye", "turkiye", "ترکیه")
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

    fun isExcludedOrigin(info: MovieInfo): Boolean = isExcludedLanguage(info.originalLanguage)

    /** Higher is better. Missing data scores lowest, so those movies end up at the bottom of the batch. */
    fun score(filterType: FilterType, facts: RankedMovieRepository.MovieFacts, currentYear: Int): Double {
        val movie = facts.movie
        val info = facts.info
        return when (filterType) {
            FilterType.MOST_POPULAR -> info?.popularity ?: -1.0
            FilterType.TOP_RATED -> weightedRating(movie.imdb, info?.ratingConfidence ?: 0.0)
            FilterType.STAR_CAST -> info?.castPopularity ?: -1.0
            FilterType.MOST_AWARDED -> facts.awards?.let { awardsScore(it) } ?: -1.0
            FilterType.NEWEST -> releaseDateValue(info?.releaseDate.orEmpty(), movie.year)
            FilterType.TOP_PICKS -> topPicksScore(movie.imdb, movie.year, currentYear)
            FilterType.POPULAR_CAST -> info?.let { popularityScore(it) } ?: -1.0
            FilterType.BEST_OVERALL -> bestOverallScore(facts, currentYear)
            FilterType.DEFAULT, FilterType.BY_YEAR, FilterType.BY_IMDB -> 0.0
        }
    }

    /**
     * IMDB score plus a bonus for newer movies: a 7.6 from this year (8.6) ranks above an 8.2 from
     * 25 years ago (8.2), while classics rated 8.7+ stay near the top.
     */
    fun topPicksScore(imdb: Double, year: Int, currentYear: Int): Double = imdb + RECENCY_BONUS * recency(year, currentYear)

    /** IMDB score pulled towards an average for little-known movies (a Bayesian average). */
    fun weightedRating(imdb: Double, confidence: Double): Double {
        if (imdb <= 0.0) return -1.0
        val weight = confidence.coerceIn(0.0, 1.0)
        return weight * imdb + (1 - weight) * PRIOR_RATING
    }

    /**
     * 0..1: current popularity (40%), how widely known the movie is (35%) and how famous the lead
     * actors are (25%, when known).
     */
    fun popularityScore(info: MovieInfo): Double =
        0.40 * info.popularity + 0.35 * info.reach + 0.25 * (info.castPopularity ?: 0.0)

    /** 0..1: an Oscar win counts like 10 other wins, an Oscar nomination like 3, other nominations a quarter. */
    fun awardsScore(awards: OmdbClient.Awards): Double {
        val otherWins = (awards.wins - awards.oscarWins).coerceAtLeast(0)
        val otherNominations = (awards.nominations - awards.oscarNominations).coerceAtLeast(0)
        val points = awards.oscarWins * 10.0 + awards.oscarNominations * 3.0 + otherWins + otherNominations * 0.25
        return logScale(points, 200.0)
    }

    /**
     * 0..1 mix of everything: rating (35%), how new the movie is (25%), cast (15%), awards (15%) and
     * popularity (10%). Missing parts count as zero.
     */
    fun bestOverallScore(facts: RankedMovieRepository.MovieFacts, currentYear: Int): Double {
        val info = facts.info
        // 5.0 -> 0, 9.0 -> 1
        val rating = ((weightedRating(facts.movie.imdb, info?.ratingConfidence ?: 0.0) - 5.0) / 4.0).coerceIn(0.0, 1.0)
        return 0.35 * rating +
            0.25 * recency(facts.movie.year, currentYear) +
            0.15 * (info?.castPopularity ?: 0.0) +
            0.15 * (facts.awards?.let { awardsScore(it) } ?: 0.0) +
            0.10 * (info?.popularity ?: 0.0)
    }

    // "2024-03-01" -> 20240301; the year alone when the date is unknown
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
