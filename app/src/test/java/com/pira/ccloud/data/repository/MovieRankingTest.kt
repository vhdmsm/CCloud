package com.pira.ccloud.data.repository

import com.pira.ccloud.data.model.Country
import com.pira.ccloud.data.model.FilterType
import com.pira.ccloud.data.model.Movie
import com.pira.ccloud.data.repository.RankedMovieRepository.MovieFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            OmdbClient.parseAwards("Won 3 Oscars. 94 wins & 172 nominations total").copy(summary = "")
        )
        assertEquals(
            OmdbClient.Awards(oscarWins = 0, oscarNominations = 1, wins = 5, nominations = 20),
            OmdbClient.parseAwards("Nominated for 1 Oscar. 5 wins & 20 nominations total").copy(summary = "")
        )
        assertEquals(OmdbClient.Awards(0, 0, 1, 0, "1 win"), OmdbClient.parseAwards("1 win"))
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
    fun bestOverallWeighsAwardsAndPopularity() {
        // Same rating and actors: an unknown movie loses to an award winner that everyone knows
        val newer = MovieFacts(movie(1, 8.0, currentYear), info(popularity = 50.0, cast = 0.5), null)
        val older = MovieFacts(movie(2, 8.0, currentYear - 20), info(popularity = 99.9, cast = 0.5), OmdbClient.parseAwards("Won 2 Oscars. 30 wins & 40 nominations total"))
        assertEquals(listOf(2, 1), ranked(FilterType.BEST_OVERALL, newer, older))
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

    @Test
    fun percentileScoreSpreadsOutTheTop() {
        // Real Watchmode popularity percentiles
        val shawshank = MovieRanking.percentileScore(99.992)
        val rentalFamily = MovieRanking.percentileScore(99.854)
        val theBox = MovieRanking.percentileScore(87.762)
        assertTrue(shawshank > 0.9)
        assertTrue(shawshank - rentalFamily > 0.2)
        assertTrue(rentalFamily - theBox > 0.4)
        assertEquals(0.0, MovieRanking.percentileScore(0.0), 0.01)
    }

    @Test
    fun bestOverallPartsAddUpToTheScore() {
        val facts = MovieFacts(movie(1, 7.9, currentYear), info(popularity = 99.996, relevance = 99.873, cast = 0.5), null)
        val parts = MovieRanking.bestOverallParts(facts, currentYear)
        assertEquals(MovieRanking.bestOverallScore(facts, currentYear), parts.total, 1e-9)
        assertEquals(0.0, parts.awards, 1e-9)
    }

    @Test
    fun picksTheFilmOfTheRightYearOfAnyNonSeriesType() {
        fun result(id: Int, type: String, year: Int) = WatchmodeClient.SearchResult(id, type, year)
        // "Rental Family": the 2025 film, not the 2018 one or the one without a year
        assertEquals(1888121, WatchmodeClient.pickMovie(listOf(result(1888121, "movie", 2025), result(1892527, "movie", 2018), result(11006853, "movie", 0)), 2025))
        // Concerts and documentaries are filed as tv_movie / tv_special
        assertEquals(1158231, WatchmodeClient.pickMovie(listOf(result(1158231, "tv_movie", 2017)), 2017))
        assertEquals(551522, WatchmodeClient.pickMovie(listOf(result(551522, "tv_special", 2022)), 2022))
        // A series of the same name is skipped, and a movie beats a TV movie
        assertEquals(2, WatchmodeClient.pickMovie(listOf(result(1, "tv_series", 2021), result(2, "movie", 2021)), 2021))
        assertEquals(3, WatchmodeClient.pickMovie(listOf(result(4, "tv_movie", 2021), result(3, "movie", 2021)), 2021))
        assertEquals(null, WatchmodeClient.pickMovie(listOf(result(1, "tv_series", 2021)), 2021))
    }

    @Test
    fun savesCreditsForThisYearsMovies() {
        // Plenty left (or not known yet): every year gets new lookups
        assertTrue(WatchmodeClient.allowsNewLookup(2019, 2026, 0.6))
        assertTrue(WatchmodeClient.allowsNewLookup(2019, 2026, null))
        // Under 20% left: only this year's movies
        assertTrue(WatchmodeClient.allowsNewLookup(2026, 2026, 0.1))
        assertFalse(WatchmodeClient.allowsNewLookup(2025, 2026, 0.1))
        // Unlimited mode drops the reserve: older movies too, until the credits run out
        assertTrue(WatchmodeClient.allowsNewLookup(2025, 2026, 0.1, keepReserve = false))
    }

    @Test
    fun keysThatHaveNotAnsweredCountAsUnused() {
        // Key 1 used up, keys 2 and 3 not asked yet: two thirds left, not "credits low"
        assertEquals(2.0 / 3, WatchmodeClient.remainingShare(listOf(2500L to 2500L, null, null))!!, 0.001)
        assertEquals(0.1, WatchmodeClient.remainingShare(listOf(2500L to 2250L))!!, 0.001)
        assertNull(WatchmodeClient.remainingShare(listOf(null, null)))
    }

    @Test
    fun thisYearsMoviesSpreadTheAwardsShare() {
        // Forgotten Island's real data: 2026, IMDB 7.9, popularity top 0.004%, actors 0.5, no awards yet
        val thisYear = MovieFacts(movie(1, 7.9, currentYear), info(popularity = 99.996, relevance = 99.873, cast = 0.5), null)
        val parts = MovieRanking.bestOverallParts(thisYear, currentYear)
        assertTrue(parts.awardsSpread)
        assertEquals(0.0, parts.awards, 1e-9)
        assertEquals((0.30 + 0.20 / 3) * (7.9 - 5.0) / 4.0, parts.rating, 0.01)
        // Last year's movies are recent too (they're ranked together); older ones get no such help
        val lastYear = MovieFacts(movie(2, 7.9, currentYear - 1), info(popularity = 99.996, relevance = 99.873, cast = 0.5), null)
        assertTrue(MovieRanking.bestOverallParts(lastYear, currentYear).awardsSpread)
        val older = MovieFacts(movie(3, 7.9, currentYear - 2), info(popularity = 99.996, relevance = 99.873, cast = 0.5), null)
        assertFalse(MovieRanking.bestOverallParts(older, currentYear).awardsSpread)
        assertTrue(parts.total > MovieRanking.bestOverallScore(older, currentYear))
    }

    @Test
    fun anEarlyAwardNeverLowersThisYearsScore() {
        val none = MovieFacts(movie(1, 7.5, currentYear), info(popularity = 99.0, cast = 0.3), null)
        val oneNomination = none.copy(awards = OmdbClient.parseAwards("1 nomination"))
        val bigWinner = none.copy(awards = OmdbClient.parseAwards("Won 3 Oscars. 60 wins & 150 nominations total"))
        val scoreNone = MovieRanking.bestOverallScore(none, currentYear)
        assertTrue(MovieRanking.bestOverallScore(oneNomination, currentYear) >= scoreNone)
        // Awards add on top of the spread weights
        val winnerParts = MovieRanking.bestOverallParts(bigWinner, currentYear)
        assertTrue(winnerParts.awardsSpread)
        assertTrue(winnerParts.awards > 0.1)
        assertEquals(scoreNone + winnerParts.awards, winnerParts.total, 1e-9)
    }

    @Test
    fun thisYearsAwardsCountOnTopOfTheSpreadWeights() {
        // Real data: Obsession (IMDB 7.9, 7 wins & 15 nominations, lesser-known young cast) vs Forgotten Island (7.7, no awards)
        val obsession = MovieFacts(movie(1, 7.9, currentYear), info(popularity = 99.998, relevance = 99.999, cast = 0.464), OmdbClient.parseAwards("7 wins & 15 nominations total"))
        val forgottenIsland = MovieFacts(movie(2, 7.7, currentYear), info(popularity = 99.996, relevance = 99.873, cast = 0.786), OmdbClient.parseAwards("N/A"))
        assertEquals(listOf(1, 2), ranked(FilterType.BEST_OVERALL, obsession, forgottenIsland))
    }

    @Test
    fun aLittleKnownCoStarDoesNotPullDownTheStar() {
        // Real Watchmode percentiles of the two lead actors
        val heartOfTheBeast = MovieRanking.castScore(listOf(100.0, 100.0)) // Brad Pitt, J.K. Simmons
        val forgottenIsland = MovieRanking.castScore(listOf(83.1, 94.1))   // Liza Soberano, H.E.R.
        val starAndUnknown = MovieRanking.castScore(listOf(100.0, 26.8))
        assertEquals(1.0, heartOfTheBeast, 1e-9)
        assertTrue(heartOfTheBeast > forgottenIsland)
        // The star counts about 74%, so an unknown co-star costs little
        assertTrue(starAndUnknown > 0.74)
        assertTrue(starAndUnknown > forgottenIsland)
        // A third actor cached by an older version doesn't count
        assertEquals(heartOfTheBeast, MovieRanking.castScore(listOf(100.0, 100.0, 26.8)), 1e-9)
        assertEquals(1.0, MovieRanking.castScore(listOf(100.0)), 1e-9)
        assertEquals(0.0, MovieRanking.castScore(emptyList()), 1e-9)
    }

    @Test
    fun readsOmdbRatingAndVotes() {
        val details = OmdbClient.parseDetails("Won 1 Oscar. 5 wins & 20 nominations total", "8.4", "477,953")
        assertEquals(OmdbClient.Rating(8.4, 477953), details.rating)
        assertEquals(1, details.awards.oscarWins)
        assertNull(OmdbClient.parseDetails("N/A", "N/A", "N/A").rating)
    }

    @Test
    fun theCurrentImdbRatingReplacesTheSitesOldOne() {
        // Real data: the site has The Odyssey at 7.6 and The Invite at 7.9; IMDb now says 8.4 and 7.5
        val odyssey = MovieFacts(movie(1, 7.6, currentYear), info(relevance = 99.9), null, OmdbClient.Rating(8.4, 477953))
        val invite = MovieFacts(movie(2, 7.9, currentYear), info(relevance = 99.9), null, OmdbClient.Rating(7.5, 68555))
        assertEquals(8.4, odyssey.imdb, 1e-9)
        assertEquals(listOf(1, 2), ranked(FilterType.TOP_RATED, invite, odyssey))
        assertEquals(listOf(1, 2), ranked(FilterType.BEST_OVERALL, invite, odyssey))
        // Without OMDb the site's rating is used
        assertEquals(7.9, invite.copy(rating = null).imdb, 1e-9)
    }

    @Test
    fun aRatingFromFewVotesCountsLittle() {
        // Real data: Heart of the Beast 7.5 from 542 votes, The Invite 7.5 from 68,555
        val heartOfTheBeast = MovieFacts(movie(1, 7.5, currentYear), info(relevance = 99.9), null, OmdbClient.Rating(7.5, 542))
        val invite = MovieFacts(movie(2, 7.5, currentYear), info(relevance = 99.9), null, OmdbClient.Rating(7.5, 68555))
        assertTrue(MovieRanking.ratingConfidence(heartOfTheBeast) < 0.1)
        assertTrue(MovieRanking.ratingConfidence(invite) > 0.85)
        assertEquals(listOf(2, 1), ranked(FilterType.TOP_RATED, heartOfTheBeast, invite))
        // No votes known: Watchmode's measure of how well known the movie is
        assertEquals(0.999, MovieRanking.ratingConfidence(invite.copy(rating = null)), 1e-9)
    }

    @Test
    fun omdbAwardsComeFirstWhenItNamesAny() {
        val omdb = OmdbClient.parseAwards("7 wins & 15 nominations total")
        val described = OmdbClient.Awards(0, 0, 3, 4)
        assertEquals(omdb, MovieRanking.pickAwards(omdb, described))
        assertEquals(described, MovieRanking.pickAwards(OmdbClient.parseAwards("N/A"), described))
        assertEquals(OmdbClient.parseAwards("N/A"), MovieRanking.pickAwards(OmdbClient.parseAwards("N/A"), null))
        assertNull(MovieRanking.pickAwards(null, null))
    }

    @Test
    fun leavesOutMoviesRatedBelowSix() {
        assertFalse(MovieRanking.isCandidate(movie(1, 5.9, 2026), FilterType.BEST_OVERALL))
        assertFalse(MovieRanking.isCandidate(movie(1, 3.0, 2026), FilterType.NEWEST))
        assertTrue(MovieRanking.isCandidate(movie(1, 6.0, 2026), FilterType.BEST_OVERALL))
        // Not rated yet: kept
        assertTrue(MovieRanking.isCandidate(movie(1, 0.0, 2026), FilterType.BEST_OVERALL))
        // The server's own sorts show everything
        assertTrue(MovieRanking.isCandidate(movie(1, 4.0, 2026), FilterType.BY_YEAR))
    }

    @Test
    fun picksTheSeriesByNameThatStartedByTheSitesYear() {
        // Watchmode lists a series by its first year; the site may list it by a later season's
        val results = listOf(
            WatchmodeClient.SearchResult(3184679, "tv_series", 2022, "The Bear"),
            WatchmodeClient.SearchResult(3166070, "tv_series", 1962, "The Beary Family"),
            WatchmodeClient.SearchResult(900, "movie", 2026, "The Bear"),
            WatchmodeClient.SearchResult(901, "tv_series", 2030, "The Bear")
        )
        assertEquals(3184679, WatchmodeClient.pickSeries(results, 2026, "The Bear"))
        assertEquals(3184679, WatchmodeClient.pickSeries(results, 2022, "the bear"))
        // No name match: one that started within a year
        assertEquals(3166070, WatchmodeClient.pickSeries(results, 1963, "Beary"))
        assertNull(WatchmodeClient.pickSeries(results, 2010, "Unknown"))
    }

    @Test
    fun picksTheSeriesWhateverItsAccentsAndNotAnOldOneOfTheSameName() {
        val old = WatchmodeClient.SearchResult(1, "tv_miniseries", 1980, "Shogun")
        val new = WatchmodeClient.SearchResult(2, "tv_series", 2024, "Shōgun")
        assertEquals(2, WatchmodeClient.pickSeries(listOf(old, new), 2024, "Shogun"))
        // The site lists it by its second season's year
        assertEquals(2, WatchmodeClient.pickSeries(listOf(old, new), 2026, "Shogun"))
        // Only the 1980 one: not that series
        assertNull(WatchmodeClient.pickSeries(listOf(old), 2024, "Shogun"))
        val gambit = WatchmodeClient.SearchResult(3, "tv_miniseries", 2020, "The Queen's Gambit")
        assertEquals(3, WatchmodeClient.pickSeries(listOf(gambit), 2025, "The Queens Gambit"))
    }

    @Test
    fun aRecentSeriesDoesntGetItsAwardsOnTopOfTheSpreadShare() {
        val awards = OmdbClient.Awards(oscarWins = 0, oscarNominations = 0, wins = 100, nominations = 200)
        val facts = MovieFacts(
            movie(1, 9.0, 2025), info(popularity = 100.0, relevance = 100.0, cast = 1.0), awards,
            OmdbClient.Rating(9.0, 1_000_000)
        )
        // Movies: awards on top of the spread share, above 1
        assertTrue(MovieRanking.bestOverallParts(facts, currentYear).total > 1.0)
        // Series: the better of spread (no awards) and with awards, at most 1
        assertEquals(1.0, MovieRanking.seriesQualityParts(facts, currentYear).total, 0.02)
        assertTrue(MovieRanking.seriesQualityParts(facts, currentYear).total <= 1.0 + 1e-9)
        // An older series keeps the movies' score
        val older = facts.copy(movie = movie(1, 9.0, 2022))
        assertEquals(MovieRanking.bestOverallParts(older, currentYear), MovieRanking.seriesQualityParts(older, currentYear))
    }

    @Test
    fun aSeriesWithoutAnImdbRatingYetDoesntKeepTheSitesScore() {
        // Well known on Watchmode, rated 8.8 on the site, but IMDb has no votes for it yet
        val facts = MovieFacts(movie(1, 8.8, 2026), info(relevance = 99.0), null)
        val unrated = facts.copy(unrated = true)
        assertEquals(0.0, MovieRanking.ratingConfidence(unrated), 1e-9)
        assertTrue(MovieRanking.seriesBestOverallScore(unrated, currentYear) < MovieRanking.seriesBestOverallScore(facts, currentYear))
    }

    @Test
    fun startYearScoreGoesFromTheOldestListedYearToThisYear() {
        val year = 2026
        assertEquals(1.0, MovieRanking.startYearScore(year, year), 1e-9)
        assertEquals(0.0, MovieRanking.startYearScore(MovieRanking.SERIES_MIN_START_YEAR, year), 1e-9)
        assertEquals(0.0, MovieRanking.startYearScore(2010, year), 1e-9)
        // Unknown year
        assertEquals(0.0, MovieRanking.startYearScore(0, year), 1e-9)
        assertEquals(6.0 / 7.0, MovieRanking.startYearScore(2025, year), 1e-9)
    }

    @Test
    fun popularityLiftsAWellKnownSeriesAboveLittleKnownOnesTheSiteRatesHigher() {
        val year = 2026
        fun show(imdb: Double, startYear: Int) = Movie(
            id = 1, type = "serie", title = "Show", description = "", year = startYear, imdb = imdb, rating = 0.0,
            duration = null, image = "", cover = "", genres = emptyList(), sources = emptyList(), country = emptyList()
        )
        val littleKnownNew = MovieRanking.seriesPreScore(show(8.8, 2026), year, popularity = 0.0)
        val famousOlder = MovieRanking.seriesPreScore(show(8.6, 2022), year, popularity = 0.97)
        assertTrue(famousOlder > littleKnownNew)
        // Without the list, the site's score alone
        assertEquals(
            (1 - MovieRanking.SERIES_START_YEAR_WEIGHT) * ((8.8 - 5.0) / 4.0) + MovieRanking.SERIES_START_YEAR_WEIGHT,
            MovieRanking.seriesPreScore(show(8.8, 2026), year, popularity = null),
            1e-9
        )
        // Never above the best a series of its start year can have (the series list is read that far)
        assertTrue(MovieRanking.seriesPreScore(show(10.0, 2022), year, 1.0) <= MovieRanking.seriesBestPreScore(2022, year) + 1e-9)
    }

    @Test
    fun matchesPopularSeriesByTitleWithoutAccentsAndByStartYear() {
        val popularity = SeriesPopularity(listOf("Shōgun" to 2024, "The White Lotus" to 2021, "Task" to 2025, "Task" to 2010))
        assertEquals(1.0, popularity.score("Shogun", 2024)!!, 1e-9)
        // The site may list a series by a later season's year
        assertEquals(0.75, popularity.score("The White Lotus (2021)", 2025)!!, 1e-9)
        assertEquals(0.5, popularity.score("TASK", 2025)!!, 1e-9)
        // Too far from the start year: another series of that name
        assertEquals(null, popularity.score("The White Lotus", 2030))
        assertEquals(null, popularity.score("Unknown Show", 2024))
        assertEquals("the white lotus", SeriesPopularity.normalize("The White Lotus (2021)"))
        // The site leaves out apostrophes
        assertEquals(SeriesPopularity.normalize("The Queen’s Gambit"), SeriesPopularity.normalize("The Queens Gambit"))
        assertEquals(0.5, SeriesPopularity(listOf("Ted" to 2020, "The Queen's Gambit" to 2020)).score("The Queens Gambit", 2020)!!, 1e-9)
    }

    @Test
    fun picksTheSeriesOfTheSameNameThatStartedClosestToTheSitesYear() {
        // Queen of Tears (Korean, 2024) and its Turkish remake (2025)
        val korean = WatchmodeClient.SearchResult(10, "tv_series", 2024, "Queen of Tears")
        val turkish = WatchmodeClient.SearchResult(11, "tv_series", 2025, "Queen of Tears")
        assertEquals(listOf(10, 11), WatchmodeClient.seriesCandidates(listOf(turkish, korean), 2024, "Queen of Tears"))
        // A Thai "Mouse" from 2025, not the Korean one from 2021
        val koreanMouse = WatchmodeClient.SearchResult(20, "tv_series", 2021, "Mouse")
        val thaiMouse = WatchmodeClient.SearchResult(21, "tv_series", 2025, "Mouse")
        assertEquals(21, WatchmodeClient.pickSeries(listOf(koreanMouse, thaiMouse), 2025, "Mouse"))
        assertEquals(20, WatchmodeClient.pickSeries(listOf(koreanMouse, thaiMouse), 2021, "Mouse"))
    }

    @Test
    fun picksTheMovieOfTheSameNameFirst() {
        val other = WatchmodeClient.SearchResult(1, "movie", 2024, "Heat Wave")
        val named = WatchmodeClient.SearchResult(2, "movie", 2024, "Heat")
        assertEquals(2, WatchmodeClient.pickMovie(listOf(other, named), 2024, "Heat"))
        // No name match: the first of the year, as before
        assertEquals(1, WatchmodeClient.pickMovie(listOf(other, named), 2024, "Something Else"))
        assertEquals(1, WatchmodeClient.pickMovie(listOf(other, named), 2024))
    }

    @Test
    fun tellsTheLanguagesOfASeriesFromTheSitesCountries() {
        fun countries(vararg names: String) = names.mapIndexed { i, name -> Country(i, name, "") }
        assertEquals(setOf("ko"), MovieRanking.languagesOf(countries("کره جنوبی")))
        assertEquals(setOf("en", "ko"), MovieRanking.languagesOf(countries("امریکا", "کره جنوبی")))
        // A country it doesn't know: no check
        assertEquals(emptySet<String>(), MovieRanking.languagesOf(countries("امریکا", "مغولستان")))
        assertEquals(emptySet<String>(), MovieRanking.languagesOf(emptyList()))
    }

    @Test
    fun looksUpDoubtfulSeriesMatchesMadeBeforeTheCurrentRulesOnce() {
        fun doubtful(release: String, language: String, year: Int, languages: Set<String>, version: Int = 0, found: Boolean = true) =
            WatchmodeClient.isDoubtfulSeriesMatch(version, found, release, language, year, languages)
        // The Turkish remake cached for the Korean series
        assertTrue(doubtful("2025-01-01", "tr", 2024, setOf("ko")))
        // The 1980 Shogun for the 2024 one
        assertTrue(doubtful("1980-09-15", "en", 2024, setOf("en")))
        // Nothing found before (e.g. an apostrophe in the name)
        assertTrue(doubtful("", "", 2020, emptySet(), found = false))
        // A good match stays, and a match made under the current rules is never looked up again
        assertFalse(doubtful("2022-02-18", "en", 2022, setOf("en")))
        assertFalse(doubtful("2025-01-01", "tr", 2024, setOf("ko"), version = 2))
    }

    @Test
    fun popularityGoesToTheSeriesOfTheSameNameWithTheClosestStartYear() {
        // The Korean "Mouse" (2021) is more popular than the Thai one (2025)
        val popularity = SeriesPopularity(listOf("Mouse" to 2021, "Other" to 2024, "Mouse" to 2025, "Last" to 2020))
        assertEquals(0.5, popularity.score("Mouse", 2025)!!, 1e-9)
        assertEquals(1.0, popularity.score("Mouse", 2021)!!, 1e-9)
    }
}
