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

    private fun repository(pages: List<List<Movie>>) =
        RankedMovieRepository { page, _, _ -> pages.getOrElse(page) { emptyList() } }

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
    fun newestKeepsTheServersOrderWithinAYear() = runBlocking {
        // Same year, no dates known: the server's newest-added-first order stays (not the IMDB order)
        val pages = listOf(listOf(movie(1, 2026).copy(imdb = 6.0), movie(2, 2026).copy(imdb = 9.0), movie(3, 2026).copy(imdb = 7.5)))
        val result = repository(pages).getRankedMovies(0, 0, FilterType.NEWEST, emptySet())
        assertEquals(listOf(1, 2, 3), result.movies.map { it.id })
    }
}
