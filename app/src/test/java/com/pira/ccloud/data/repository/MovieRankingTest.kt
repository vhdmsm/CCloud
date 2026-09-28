package com.pira.ccloud.data.repository

import com.pira.ccloud.data.model.Country
import com.pira.ccloud.data.model.FilterType
import com.pira.ccloud.data.model.Movie
import com.pira.ccloud.data.repository.RankedMovieRepository.MovieFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieRankingTest {
    private val currentYear = 2026

    private fun movie(id: Int, imdb: Double, year: Int, country: String = "USA") = Movie(
        id = id, type = "movie", title = "Movie $id", description = "", year = year, imdb = imdb,
        rating = 0.0, duration = null, image = "", cover = "", genres = emptyList(), sources = emptyList(),
        country = listOf(Country(1, country, ""))
    )

    // Watchmode data: percentiles (0..100); actors only when asked
    private fun info(
        popularity: Double = 50.0,
        relevance: Double = 50.0,
        cast: Double? = null,
        releaseDate: String = "",
        language: String = "en"
    ) = MovieInfo(
        popularity = popularity / 100, reach = relevance / 100, ratingConfidence = relevance / 100,
        castPopularity = cast, releaseDate = releaseDate, imdbId = "tt0000001", originalLanguage = language
    )

    private fun ranked(filterType: FilterType, vararg facts: MovieFacts): List<Int> =
        facts.sortedByDescending { MovieRanking.score(filterType, it, currentYear) }.map { it.movie.id }

    @Test
    fun parsesOmdbAwardSummaries() {
        assertEquals(
            OmdbClient.Awards(oscarWins = 3, oscarNominations = 0, wins = 94, nominations = 172),
            OmdbClient.parseAwards("Won 3 Oscars. 94 wins & 172 nominations total")
        )
        assertEquals(
            OmdbClient.Awards(oscarWins = 0, oscarNominations = 1, wins = 5, nominations = 20),
            OmdbClient.parseAwards("Nominated for 1 Oscar. 5 wins & 20 nominations total")
        )
        assertEquals(OmdbClient.Awards(0, 0, 1, 0), OmdbClient.parseAwards("1 win"))
        assertEquals(OmdbClient.Awards(0, 0, 0, 0), OmdbClient.parseAwards("N/A"))
    }

    @Test
    fun oscarsOutweighOtherAwards() {
        val oscarWinner = OmdbClient.parseAwards("Won 2 Oscars. 20 wins & 30 nominations total")
        val festivalFavorite = OmdbClient.parseAwards("25 wins & 40 nominations")
        assertTrue(MovieRanking.awardsScore(oscarWinner) > MovieRanking.awardsScore(festivalFavorite))
        assertEquals(0.0, MovieRanking.awardsScore(OmdbClient.parseAwards("N/A")), 0.0)
    }

    @Test
    fun topRatedDistrustsScoresOfLittleKnownMovies() {
        val obscure = MovieFacts(movie(1, 9.1, 2025), info(relevance = 15.0), null)
        val wellKnown = MovieFacts(movie(2, 8.3, 2023), info(relevance = 98.0), null)
        assertEquals(listOf(2, 1), ranked(FilterType.TOP_RATED, obscure, wellKnown))
    }

    @Test
    fun topPicksFavorsNewerMoviesWithSimilarScores() {
        val recent = movie(1, 7.6, currentYear)
        val older = movie(2, 8.2, currentYear - 25)
        assertTrue(MovieRanking.topPicksScore(recent.imdb, recent.year, currentYear) > MovieRanking.topPicksScore(older.imdb, older.year, currentYear))
        assertFalse(MovieRanking.isCandidate(movie(3, 6.4, 2020), FilterType.TOP_PICKS))
    }

    @Test
    fun singleFieldSortsRankByTheirField() {
        val popular = MovieFacts(movie(1, 7.0, 2025), info(popularity = 99.0, cast = 0.1, releaseDate = "2025-01-10"), null)
        val starCast = MovieFacts(movie(2, 7.0, 2024), info(popularity = 40.0, cast = 0.95, releaseDate = "2025-06-01"), null)
        assertEquals(listOf(1, 2), ranked(FilterType.MOST_POPULAR, popular, starCast))
        assertEquals(listOf(2, 1), ranked(FilterType.STAR_CAST, popular, starCast))
        assertEquals(listOf(2, 1), ranked(FilterType.NEWEST, popular, starCast))
    }

    @Test
    fun missingDataRanksLast() {
        val known = MovieFacts(movie(1, 6.0, 2020), info(popularity = 1.0, cast = 0.01), OmdbClient.parseAwards("N/A"))
        val unknown = MovieFacts(movie(2, 9.0, 2025), null, null)
        for (filterType in listOf(FilterType.MOST_POPULAR, FilterType.STAR_CAST, FilterType.MOST_AWARDED, FilterType.POPULAR_CAST)) {
            assertEquals(filterType.name, listOf(1, 2), ranked(filterType, known, unknown))
        }
    }

    @Test
    fun bestOverallWeighsRecencyAboveThePopularity() {
        // Same rating, cast and awards: a new, less popular movie beats an old, very popular one
        val newer = MovieFacts(movie(1, 8.0, currentYear), info(popularity = 10.0), null)
        val older = MovieFacts(movie(2, 8.0, currentYear - 30), info(popularity = 100.0), null)
        assertEquals(listOf(1, 2), ranked(FilterType.BEST_OVERALL, newer, older))
    }

    @Test
    fun leavesOutIndianAndTurkishMovies() {
        for (country in listOf("India", "هند", "Turkey", "ترکیه")) {
            assertFalse(country, MovieRanking.isCandidate(movie(1, 8.0, 2024, country), FilterType.BEST_OVERALL))
        }
        // Server sorts keep them
        assertTrue(MovieRanking.isCandidate(movie(1, 8.0, 2024, "India"), FilterType.BY_IMDB))
        assertTrue(MovieRanking.isExcludedOrigin(info(language = "hi")))
        assertTrue(MovieRanking.isExcludedOrigin(info(language = "tr")))
        assertFalse(MovieRanking.isExcludedOrigin(info()))
    }

    @Test
    fun famousActorsNeedsActorData() {
        val noActors = MovieFacts(movie(1, 8.0, 2024), info(), null)
        val starCast = MovieFacts(movie(2, 7.0, 2024), info(cast = 0.9), null)
        assertEquals(-1.0, MovieRanking.score(FilterType.STAR_CAST, noActors, currentYear), 0.0)
        assertEquals(listOf(2, 1), ranked(FilterType.STAR_CAST, noActors, starCast))
    }
}
