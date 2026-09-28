package com.pira.ccloud.data.repository

import com.pira.ccloud.data.model.FilterType
import com.pira.ccloud.data.model.Movie
import com.pira.ccloud.utils.LanguageUtils
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Calendar
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.log10

/**
 * Movies for the sorts the server can't do (see [FilterType.isRanked]). Each load reads the
 * server's lists, keeps the movies not shown yet, adds Watchmode and OMDb data where the sort needs
 * it and ranks them in the app, so every loaded batch is ordered best first.
 */
class RankedMovieRepository(
    // A page of the server's list: page, genre, server sort
    private val fetchPage: suspend (Int, Int, FilterType) -> List<Movie> = MovieRepository()::getMovies,
    // Whether the first load may rank the two newest years together (it costs more requests)
    private val mayMergeRecentYears: () -> Boolean = { !WatchmodeClient.isSavingCredits },
    // The data a sort ranks a movie by (null: skip the movie); [cachedOnly] makes no requests
    private val lookUpFacts: (suspend (movie: Movie, filterType: FilterType, cachedOnly: Boolean) -> MovieFacts?)? = null
) {
    data class RankedPage(
        val movies: List<Movie>,
        // Last server page read; the next load starts after it
        val lastPage: Int,
        val hasMore: Boolean,
        // Every movie this load dealt with, shown or skipped, so later loads don't take it again
        val handledIds: Set<Int>,
        // Shown above the list when movie or award data is missing
        val notice: String?,
        // Credit for the data source, shown under the sort when Watchmode data is used (its terms ask for it)
        val attribution: String?,
        // While movie data is still coming in, e.g. "Getting movie data: 40 of 180"; null when done
        val progress: String? = null
    )

    // A movie with the data the sorts rank by; info, awards and rating are null when unknown or not needed
    data class MovieFacts(
        val movie: Movie,
        val info: MovieInfo?,
        val awards: OmdbClient.Awards?,
        // IMDb's current rating from OMDb
        val rating: OmdbClient.Rating? = null
    ) {
        // The current IMDb rating, else the server's (from when the movie was added)
        val imdb: Double get() = rating?.imdb ?: movie.imdb
    }

    // Parallel requests allowed to each service, shared by the loads of this list
    private val watchmodePermits = Semaphore(6)
    private val omdbPermits = Semaphore(6)

    /**
     * [handledIds]: movies earlier loads of this list already dealt with (shown or skipped).
     * [onUpdate] gets the list while movie data comes in (about every second), so it never waits
     * for all of it. When two years are ranked together, this year's movies are ranked and shown
     * first; last year's join them as their data arrives.
     */
    suspend fun getRankedMovies(
        page: Int,
        genreId: Int,
        filterType: FilterType,
        handledIds: Set<Int>,
        onUpdate: (RankedPage) -> Unit = {}
    ): RankedPage {
        val seen = handledIds.toMutableSet()
        // Movies of the years (or pages) already ranked
        val ranked = mutableListOf<MovieFacts>()
        var lastPage = page
        var hasMore = true
        fun result(facts: List<MovieFacts>, progress: String?) =
            rankedPage(facts, filterType, lastPage, hasMore, seen.toSet(), progress)

        if (!readsNewestFirst(filterType)) {
            // Skipped and weaker movies leave gaps, so read on until the batch fills
            val candidates = mutableListOf<Movie>()
            var currentPage = page
            while (true) {
                val movies = readServerPage(currentPage, genreId, filterType)
                hasMore = movies.isNotEmpty()
                candidates += movies.filter { seen.add(it.id) && MovieRanking.isCandidate(it, filterType) }
                lastPage = currentPage
                if (candidates.size >= MIN_BATCH_SIZE || !hasMore || currentPage - page + 1 >= MAX_PAGES_PER_LOAD) break
                currentPage++
            }
            ranked += rankGroup(candidates, filterType, emptyList(), year = null) { facts, progress -> onUpdate(result(facts, progress)) }
            return result(ranked, null)
        }

        // One release year at a time: all of a year's movies are ranked together. The first load
        // takes the two newest years while credits last. A page that runs into an older year is
        // read again for the rest.
        val yearsInBatch = if (handledIds.isEmpty() && mayMergeRecentYears()) 2 else 1
        // Pages are read a few ahead, in parallel; a failed page only matters if it's needed
        supervisorScope {
            val pages = HashMap<Int, Deferred<List<Movie>>>()
            suspend fun pageAt(index: Int): List<Movie> {
                for (ahead in index until index + PAGES_READ_AHEAD) {
                    pages.getOrPut(ahead) { async { readServerPage(ahead, genreId, filterType) } }
                }
                return pages.getValue(index).await()
            }
            var currentPage = page
            var yearsRead = 0
            var yearsRanked = 0
            // A year whose movies are all skipped (e.g. only Indian ones) gives nothing to show, so go on
            while (hasMore && yearsRanked < yearsInBatch && yearsRead < MAX_YEARS_PER_LOAD + yearsInBatch - 1) {
                yearsRead++
                val candidates = mutableListOf<Movie>()
                var year: Int? = null
                val firstPage = currentPage
                while (true) {
                    val movies = pageAt(currentPage)
                    hasMore = movies.isNotEmpty()
                    val unseen = movies.filter { it.id !in seen }
                    if (year == null) year = unseen.maxOfOrNull { it.year }
                    val (sameYear, older) = year?.let { y -> unseen.partition { it.year >= y } } ?: (emptyList<Movie>() to emptyList())
                    sameYear.forEach { seen.add(it.id) }
                    candidates += sameYear.filter { MovieRanking.isCandidate(it, filterType) }
                    lastPage = if (older.isNotEmpty()) currentPage - 1 else currentPage
                    if (older.isNotEmpty() || !hasMore || currentPage - firstPage + 1 >= MAX_PAGES_PER_YEAR) break
                    currentPage++
                }
                currentPage = lastPage + 1
                if (candidates.isEmpty()) continue
                yearsRanked++
                val before = ranked.toList()
                ranked += rankGroup(candidates, filterType, before, year) { facts, progress ->
                    onUpdate(result(facts, progress))
                }
                // Shown while the next year's list is read
                if (hasMore && yearsRanked < yearsInBatch) onUpdate(result(ranked, "Reading older movies…"))
            }
            pages.values.forEach { it.cancel() }
        }
        return result(ranked, null)
    }

    /**
     * Gets the data of [candidates] (cached first, then asked for, the best rated first) and returns
     * their facts; skipped movies are left out. [emit] gets [before] plus these movies while their
     * data comes in: all of them when nothing was ranked before, else each once its data is in.
     */
    private suspend fun rankGroup(
        candidates: List<Movie>,
        filterType: FilterType,
        before: List<MovieFacts>,
        // The movies' release year, for the progress line
        year: Int?,
        emit: (List<MovieFacts>, String?) -> Unit
    ): List<MovieFacts> {
        // Each movie's best data so far; empty once the movie is skipped (e.g. Indian)
        val slots = ConcurrentHashMap<Int, Optional<MovieFacts>>()
        val ready = ConcurrentHashMap.newKeySet<Int>()
        coroutineScope {
            candidates.indices.map { i ->
                async {
                    val facts = facts(candidates[i], filterType, cachedOnly = true)
                    slots[i] = Optional.ofNullable(facts)
                    if (facts == null || isComplete(facts, filterType)) ready += i
                }
            }.awaitAll()
        }
        fun group(onlyReady: Boolean) = candidates.indices
            .filter { !onlyReady || it in ready }
            .mapNotNull { slots[it]?.orElse(null) }
        val pending = candidates.indices.filter { it !in ready }
        if (pending.isEmpty()) return group(onlyReady = false)

        val showAll = before.isEmpty()
        val what = if (year != null) "data for $year movies" else "movie data"
        fun progress() = "Getting $what: ${ready.size} of ${candidates.size}…"
        emit(before + group(onlyReady = !showAll), progress())
        coroutineScope {
            val ticker = launch {
                while (true) {
                    delay(UPDATE_INTERVAL_MS)
                    emit(before + group(onlyReady = !showAll), progress())
                }
            }
            pending.sortedByDescending { candidates[it].imdb }.map { i ->
                async {
                    slots[i] = Optional.ofNullable(facts(candidates[i], filterType, cachedOnly = false))
                    ready += i
                }
            }.awaitAll()
            ticker.cancel()
        }
        return group(onlyReady = false)
    }

    // Whether the cache already had everything the sort ranks by, so no request is needed
    private fun isComplete(facts: MovieFacts, filterType: FilterType): Boolean {
        if (!filterType.needsMovieData && !filterType.needsOmdb) return true
        val info = facts.info
        if (filterType.needsMovieData && info == null) return false
        if (filterType.needsCast && info != null && info.castPopularity == null) return false
        if (filterType.needsOmdb) {
            // Most Awards with the awards named in the description needs nothing else
            if (info == null) return facts.awards != null
            if (info.imdbId.isNotEmpty() && facts.rating == null && facts.awards?.summary.isNullOrEmpty()) return false
        }
        return true
    }

    private fun rankedPage(
        batch: List<MovieFacts>,
        filterType: FilterType,
        lastPage: Int,
        hasMore: Boolean,
        handledIds: Set<Int>,
        progress: String?
    ): RankedPage {
        val loading = progress != null
        // Without movie data the sorts fall back to IMDB order
        val dataMissing = !loading && filterType.needsMovieData && batch.isNotEmpty() && batch.none { it.info != null }
        // Only Famous Actors depends on the actors alone; the combined sorts do without them
        val castMissing = !loading && filterType == FilterType.STAR_CAST && !dataMissing &&
            batch.isNotEmpty() && batch.none { it.info?.castPopularity != null }
        val omdbMissing = !loading && filterType.needsOmdb && !OmdbClient.isAvailable && batch.any { it.awards == null }
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val byScore = compareByDescending<MovieFacts> {
            if (dataMissing || castMissing) it.imdb else MovieRanking.score(filterType, it, year)
        }
        // Ties go to the better IMDB score (so movies still waiting for data keep the IMDB order);
        // for Newest the server's order (newest added first) stays
        val ranked = batch.sortedWith(if (filterType == FilterType.NEWEST) byScore else byScore.thenByDescending { it.imdb })
        val notice = when {
            dataMissing -> "Movie data from Watchmode isn't available right now (monthly limit or no connection), showing movies by IMDB score"
            castMissing -> "Actor data isn't available right now, showing movies by IMDB score"
            omdbMissing -> "IMDb ratings and awards from OMDb aren't available right now (daily limit or no connection)"
            filterType.needsMovieData && WatchmodeClient.isSavingCredits ->
                "Watchmode credits are low this month: only this year's movies get new data"
            else -> null
        }
        val attribution = if (batch.any { it.info != null }) "Movie data from Watchmode" else null
        // The list shows the current IMDb rating where OMDb gave one
        val movies = ranked.map { facts -> facts.rating?.let { facts.movie.copy(imdb = it.imdb) } ?: facts.movie }
        return RankedPage(movies, lastPage, hasMore, handledIds, notice, attribution, progress)
    }

    // Sorts that ask Watchmode or OMDb read only the newest-first list: their limited requests go to
    // this year's movies first, then last year's and so on as the list is scrolled (and cached)
    private fun readsNewestFirst(filterType: FilterType) =
        filterType.needsMovieData || filterType.needsOmdb || filterType == FilterType.NEWEST

    private suspend fun readServerPage(page: Int, genreId: Int, filterType: FilterType): List<Movie> =
        if (readsNewestFirst(filterType)) {
            fetchPage(page, genreId, FilterType.BY_YEAR)
        } else {
            coroutineScope {
                val byImdb = async { fetchPage(page, genreId, FilterType.BY_IMDB) }
                val byYear = async { fetchPage(page, genreId, FilterType.BY_YEAR) }
                byImdb.await() + byYear.await()
            }
        }

    private suspend fun facts(movie: Movie, filterType: FilterType, cachedOnly: Boolean): MovieFacts? {
        val custom = lookUpFacts ?: return lookUp(movie, filterType, cachedOnly)
        return custom(movie, filterType, cachedOnly)
    }

    // Watchmode gives the IMDb id for OMDb (Most Awards skips both when the description names the
    // awards). Movies Watchmode says are Indian or Turkish are dropped before any OMDb request.
    private suspend fun lookUp(movie: Movie, filterType: FilterType, cachedOnly: Boolean): MovieFacts? {
        val describedAwards = if (filterType.needsOmdb) MovieDescription.awards(movie) else null
        val needsInfo = filterType.needsMovieData || (filterType.needsOmdb && describedAwards == null)
        val info = when {
            // Answers from the cache even when the credits are out
            needsInfo && WatchmodeClient.isConfigured -> if (cachedOnly) {
                WatchmodeClient.movie(movie.title, movie.year, filterType.needsCast, cachedOnly = true)
            } else {
                watchmodePermits.withPermit { WatchmodeClient.movie(movie.title, movie.year, filterType.needsCast) }
            }
            // Newest uses a release date Watchmode already gave for another sort, at no cost
            filterType == FilterType.NEWEST && WatchmodeClient.isConfigured ->
                WatchmodeClient.movie(movie.title, movie.year, withCast = false, cachedOnly = true)
            else -> null
        }
        if (info != null && MovieRanking.isExcludedOrigin(info)) return null
        val details = if (filterType.needsOmdb && info != null && info.imdbId.isNotEmpty()) {
            if (cachedOnly) {
                OmdbClient.details(info.imdbId, movie.year, cachedOnly = true)
            } else {
                omdbPermits.withPermit { OmdbClient.details(info.imdbId, movie.year) }
            }
        } else {
            null
        }
        return MovieFacts(movie, info, MovieRanking.pickAwards(details?.awards, describedAwards), details?.rating)
    }

    private companion object {
        const val MIN_BATCH_SIZE = 12
        const val MAX_PAGES_PER_LOAD = 3
        // A year with more pages than this is ranked in parts (a limit on the requests of one load)
        const val MAX_PAGES_PER_YEAR = 10
        const val MAX_YEARS_PER_LOAD = 3
        const val UPDATE_INTERVAL_MS = 1_000L
        const val PAGES_READ_AHEAD = 3
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
    // IMDb votes at which the score counts half and the average the other half
    private const val RATING_VOTES_PRIOR = 10_000.0

    // Indian and Turkish movies are left out of the ranked sorts, by the server's country or genre
    private val excludedCountryNames = setOf("india", "هند", "هندوستان", "turkey", "türkiye", "turkiye", "ترکیه")
    private val excludedGenreNames = setOf("هندی", "ترکی")
    // Hindi, Tamil, Telugu, Malayalam, Kannada, Bengali, Marathi, Punjabi, Gujarati, Turkish
    private val excludedLanguages = setOf("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa", "gu", "tr")

    // Checked before any request, so skipped movies cost nothing
    fun isCandidate(movie: Movie, filterType: FilterType): Boolean {
        if (filterType.isRanked) {
            // Persian titles are the site's own posts (app news, ads), not movies
            if (!LanguageUtils.shouldDisplayTitle(movie.title)) return false
            if (movie.country.any { it.title.trim().lowercase() in excludedCountryNames }) return false
            if (movie.genres.any { it.title.trim() in excludedGenreNames }) return false
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
            FilterType.TOP_RATED -> weightedRating(facts.imdb, ratingConfidence(facts))
            FilterType.STAR_CAST -> info?.castPopularity ?: -1.0
            FilterType.MOST_AWARDED -> facts.awards?.let { awardsScore(it) } ?: -1.0
            FilterType.NEWEST -> releaseDateValue(MovieDescription.releaseDate(movie) ?: info?.releaseDate.orEmpty(), movie.year)
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

    /**
     * 0..1: how far the IMDB score can be trusted. From the number of IMDb votes when OMDb gave it
     * (10,000 votes -> 0.5, 100,000 -> 0.91), else from how well known Watchmode says the movie is.
     */
    fun ratingConfidence(facts: RankedMovieRepository.MovieFacts): Double {
        val votes = facts.rating?.votes ?: 0
        if (votes > 0) return votes / (votes + RATING_VOTES_PRIOR)
        return facts.info?.ratingConfidence ?: 0.0
    }

    /** OMDb's awards when it names any, else the ones in the description, else OMDb's "none". */
    fun pickAwards(omdb: OmdbClient.Awards?, described: OmdbClient.Awards?): OmdbClient.Awards? =
        omdb?.takeIf { it.summary.isNotEmpty() } ?: described ?: omdb

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

    // Best Overall weights; they add up to 1
    private const val RATING_WEIGHT = 0.30
    private const val AWARDS_WEIGHT = 0.20
    private const val POPULARITY_WEIGHT = 0.30
    private const val ACTORS_WEIGHT = 0.20

    /**
     * 0..1 mix of rating (IMDB, 30%), awards (20%), popularity (30%) and cast (20%).
     * Missing parts count as zero. The release year isn't weighed: the list is read one year at a
     * time, so the movies ranked together are from the same year already.
     *
     * Recent movies (this year's and last year's, which are ranked together) haven't had time to win
     * all their awards, so the awards' share is spread evenly over the other three, and awards they
     * already have are added on top (at the awards' weight): missing awards cost nothing, early
     * awards still count.
     */
    fun bestOverallScore(facts: RankedMovieRepository.MovieFacts, currentYear: Int): Double =
        bestOverallParts(facts, currentYear).total

    // Each part already weighted, so they add up to the Best Overall score
    data class BestOverallParts(
        val rating: Double,
        val awards: Double,
        val popularity: Double,
        val actors: Double,
        // A recent movie: the awards' share spread over the rest, its awards added on top
        val awardsSpread: Boolean = false
    ) {
        val total: Double get() = rating + awards + popularity + actors
    }

    fun bestOverallParts(facts: RankedMovieRepository.MovieFacts, currentYear: Int): BestOverallParts {
        val info = facts.info
        // 5.0 -> 0, 9.0 -> 1
        val rating = ((weightedRating(facts.imdb, ratingConfidence(facts)) - 5.0) / 4.0).coerceIn(0.0, 1.0)
        val popularity = info?.popularity ?: 0.0
        val actors = info?.castPopularity ?: 0.0
        val withAwards = BestOverallParts(
            rating = RATING_WEIGHT * rating,
            awards = AWARDS_WEIGHT * (facts.awards?.let { awardsScore(it) } ?: 0.0),
            popularity = POPULARITY_WEIGHT * popularity,
            actors = ACTORS_WEIGHT * actors
        )
        if (facts.movie.year < currentYear - 1) return withAwards
        val share = AWARDS_WEIGHT / 3
        return BestOverallParts(
            rating = (RATING_WEIGHT + share) * rating,
            awards = withAwards.awards,
            popularity = (POPULARITY_WEIGHT + share) * popularity,
            actors = (ACTORS_WEIGHT + share) * actors,
            awardsSpread = true
        )
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

    /**
     * 0..1 from a Watchmode percentile, by how rare the rank is: popular movies crowd the top
     * (Shawshank 99.992, Rental Family 99.854), so the last fraction of a percent matters most.
     * Top 0.01% -> ~1, top 0.1% -> 0.75, top 1% -> 0.5, top 10% -> 0.25, 50th percentile -> 0.08.
     */
    fun percentileScore(percentile: Double): Double {
        val topShare = (100.0 - percentile.coerceIn(0.0, 100.0)) + 0.01
        return (log10(100.0 / topShare) / 4.0).coerceIn(0.0, 1.0)
    }

    /**
     * 0..1 star power of the lead actors from their Watchmode percentiles: the best known counts 70%,
     * the next 25% and the third 5%, so a big star isn't pulled down by a little-known co-star (or
     * the dog: "Heart of the Beast" bills Brad Pitt, J.K. Simmons and its dog Uber).
     */
    fun castScore(percentiles: List<Double>): Double {
        val weights = listOf(0.70, 0.25, 0.05)
        val scores = percentiles.map { percentileScore(it) }.sortedDescending().take(weights.size)
        if (scores.isEmpty()) return 0.0
        return scores.indices.sumOf { scores[it] * weights[it] } / weights.take(scores.size).sum()
    }

    // 0 at 0, 1 at max and above
    private fun logScale(value: Double, max: Double): Double =
        (ln(1 + value.coerceAtLeast(0.0)) / ln(1 + max)).coerceAtMost(1.0)
}
