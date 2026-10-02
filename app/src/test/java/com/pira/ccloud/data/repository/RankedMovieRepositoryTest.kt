package com.pira.ccloud.data.repository

import com.pira.ccloud.data.model.Country
import com.pira.ccloud.data.model.FilterType
import com.pira.ccloud.data.model.Genre
import com.pira.ccloud.data.model.Movie
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Newest-first loading with fake server pages; Newest needs no Watchmode or OMDb requests
class RankedMovieRepositoryTest {
    private fun movie(id: Int, year: Int, genre: String = "درام") = Movie(
        id = id, type = "movie", title = "Movie $id", description = "", year = year, imdb = 7.0, rating = 0.0,
        duration = null, image = "", cover = "", genres = listOf(Genre(1, genre)), sources = emptyList(),
        country = listOf(Country(1, "امریکا", ""))
    )

    private fun repository(pages: List<List<Movie>>, mergeRecentYears: Boolean = false) =
        RankedMovieRepository({ page, _, _ -> pages.getOrElse(page) { emptyList() } }, { mergeRecentYears })

    @Test
    fun ranksOneReleaseYearPerLoad() = runBlocking {
        // 2026 runs from page 0 into page 1, where 2025 starts
        val pages = listOf(
            (1..5).map { movie(it, 2026) },
            (6..8).map { movie(it, 2026) } + (9..10).map { movie(it, 2025) },
            (11..15).map { movie(it, 2025) }
        )
        val repo = repository(pages)
        val first = repo.getRankedMovies(0, 0, FilterType.NEWEST, emptySet())
        assertEquals((1..8).toSet(), first.movies.map { it.id }.toSet())
        assertTrue(first.movies.all { it.year == 2026 })
        // Page 1 is read again next time for its 2025 movies
        assertEquals(0, first.lastPage)
        assertTrue(first.hasMore)

        val second = repo.getRankedMovies(first.lastPage + 1, 0, FilterType.NEWEST, first.handledIds)
        assertEquals((9..15).toSet(), second.movies.map { it.id }.toSet())
        assertFalse(second.hasMore)
    }

    @Test
    fun ranksTheTwoNewestYearsTogetherWhileCreditsLast() = runBlocking {
        val pages = listOf(
            (1..3).map { movie(it, 2026) } + (4..5).map { movie(it, 2025) },
            (6..7).map { movie(it, 2025) } + (8..9).map { movie(it, 2024) },
            (10..12).map { movie(it, 2024) }
        )
        val repo = repository(pages, mergeRecentYears = true)
        val first = repo.getRankedMovies(0, 0, FilterType.NEWEST, emptySet())
        assertEquals((1..7).toSet(), first.movies.map { it.id }.toSet())
        // Later loads take one year at a time
        val second = repo.getRankedMovies(first.lastPage + 1, 0, FilterType.NEWEST, first.handledIds)
        assertEquals((8..12).toSet(), second.movies.map { it.id }.toSet())
    }

    @Test
    fun movesOnWhenAYearHasOnlySkippedMovies() = runBlocking {
        val pages = listOf(
            listOf(movie(1, 2026, "هندی"), movie(2, 2026, "ترکی")),
            listOf(movie(3, 2025), movie(4, 2025))
        )
        val result = repository(pages).getRankedMovies(0, 0, FilterType.NEWEST, emptySet())
        assertEquals(setOf(3, 4), result.movies.map { it.id }.toSet())
        assertTrue(result.handledIds.containsAll(listOf(1, 2)))
    }

    @Test
    fun givesTheTestAllowanceToTheBestRatedAndStillShowsTheRest() = runBlocking {
        val pages = listOf(listOf(
            movie(1, 2026).copy(imdb = 8.0), movie(2, 2026).copy(imdb = 7.0),
            movie(3, 2026).copy(imdb = 6.5), movie(4, 2026).copy(imdb = 9.0)
        ))
        val requested = mutableListOf<Int>()
        var allowance = 2
        val repo = RankedMovieRepository(
            fetchPage = { page, _, _ -> pages.getOrElse(page) { emptyList() } },
            mayMergeRecentYears = { false },
            lookUpFacts = { movie, _, cachedOnly ->
                if (!cachedOnly) synchronized(requested) { requested += movie.id }
                RankedMovieRepository.MovieFacts(movie, null, null)
            },
            mayFetchNewData = { allowance-- > 0 }
        )
        val result = repo.getRankedMovies(0, 0, FilterType.MOST_POPULAR, emptySet())
        // Only the two best rated cost requests
        assertEquals(setOf(4, 1), requested.toSet())
        // The others are still listed, with the data already on the device
        assertEquals(setOf(1, 2, 3, 4), result.movies.map { it.id }.toSet())
    }

    @Test
    fun newestKeepsTheServersOrderWithinAYear() = runBlocking {
        // Same year, no dates known: the server's newest-added-first order stays (not the IMDB order)
        val pages = listOf(listOf(movie(1, 2026).copy(imdb = 6.0), movie(2, 2026).copy(imdb = 9.0), movie(3, 2026).copy(imdb = 7.5)))
        val result = repository(pages).getRankedMovies(0, 0, FilterType.NEWEST, emptySet())
        assertEquals(listOf(1, 2, 3), result.movies.map { it.id })
    }

    @Test
    fun showsTheBatchAtOnceAndRanksItAgainAsDataComesIn() = runBlocking {
        // Movie 3 is known (from the cache) to be Indian, so it's skipped without any request
        val pages = listOf(listOf(movie(1, 2026).copy(imdb = 8.0), movie(2, 2026).copy(imdb = 6.0), movie(3, 2026)))
        val popularity = mapOf(1 to 0.2, 2 to 0.9)
        val requested = mutableListOf<Int>()
        val repo = RankedMovieRepository(
            { page, _, _ -> pages.getOrElse(page) { emptyList() } },
            { false },
            { movie, _, cachedOnly ->
                when {
                    movie.id == 3 -> null
                    cachedOnly -> RankedMovieRepository.MovieFacts(movie, null, null)
                    else -> {
                        synchronized(requested) { requested += movie.id }
                        val info = MovieInfo(popularity.getValue(movie.id), 0.5, 0.5, null, "", "tt1", "en")
                        RankedMovieRepository.MovieFacts(movie, info, null)
                    }
                }
            }
        )
        val updates = mutableListOf<RankedMovieRepository.RankedPage>()
        val result = repo.getRankedMovies(0, 0, FilterType.MOST_POPULAR, emptySet()) { updates += it }
        // First shown by IMDB (no data yet), with the progress
        assertEquals(listOf(1, 2), updates.first().movies.map { it.id })
        assertEquals("Getting data for 2026 movies: 1 of 3…", updates.first().progress)
        // Then by popularity once the data is in; the skipped movie is never asked about
        assertEquals(listOf(2, 1), result.movies.map { it.id })
        assertEquals(null, result.progress)
        assertEquals(setOf(1, 2), requested.toSet())
    }

    @Test
    fun ranksThisYearFirstThenAddsLastYearsMoviesAsTheirDataComesIn() = runBlocking {
        val pages = listOf(
            listOf(movie(1, 2026), movie(2, 2026), movie(3, 2025)),
            listOf(movie(4, 2025), movie(5, 2024))
        )
        val popularity = mapOf(1 to 0.3, 2 to 0.6, 3 to 0.9, 4 to 0.1)
        val asked = mutableListOf<Int>()
        val repo = RankedMovieRepository(
            { page, _, _ -> pages.getOrElse(page) { emptyList() } },
            { true },
            { movie, _, cachedOnly ->
                if (cachedOnly) {
                    RankedMovieRepository.MovieFacts(movie, null, null)
                } else {
                    synchronized(asked) { asked += movie.id }
                    RankedMovieRepository.MovieFacts(movie, MovieInfo(popularity.getValue(movie.id), 0.5, 0.5, null, "", "tt1", "en"), null)
                }
            }
        )
        val updates = mutableListOf<RankedMovieRepository.RankedPage>()
        val result = repo.getRankedMovies(0, 0, FilterType.MOST_POPULAR, emptySet()) { updates += it }
        // 2026 is shown (and asked about) before any 2025 movie
        assertEquals(setOf(1, 2), updates.first().movies.map { it.id }.toSet())
        assertEquals(listOf(1, 2), asked.take(2).sorted())
        // While 2025 loads, its movies only show once their data is in
        assertTrue(updates.all { update -> update.movies.map { it.id }.containsAll(listOf(1, 2)) })
        // Then both years ranked together; 2024 waits for the next load
        assertEquals(listOf(3, 2, 1, 4), result.movies.map { it.id })
        assertEquals(0, result.lastPage)
    }

    @Test
    fun aYearLongerThanOneLoadIsFinishedBeforeAnOlderOne() = runBlocking {
        // 2026 runs past the page limit of one load (30 pages here); 2025 comes after it
        val pages = (0 until 32).map { listOf(movie(it + 1, 2026)) } + listOf(listOf(movie(100, 2025)))
        val repo = repository(pages, mergeRecentYears = true)
        val first = repo.getRankedMovies(0, 0, FilterType.NEWEST, emptySet())
        // Only 2026, not the next 2026 pages treated as "last year" nor 2025
        assertEquals((1..30).toSet(), first.movies.map { it.id }.toSet())
        val second = repo.getRankedMovies(first.lastPage + 1, 0, FilterType.NEWEST, first.handledIds)
        assertEquals(setOf(31, 32), second.movies.map { it.id }.toSet())
    }
}
