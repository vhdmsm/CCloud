package com.pira.ccloud.utils

import com.pira.ccloud.data.model.FavoriteItem
import com.pira.ccloud.data.model.WatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryRulesTest {
    private fun item(id: Int, type: String = "movie") = FavoriteItem(
        id = id, type = type, title = "Title $id", description = "", year = 2024, imdb = 7.5,
        rating = 0.0, duration = null, image = "", cover = "", genres = emptyList(), country = emptyList()
    )

    @Test
    fun finishedPastNinetyFivePercent() {
        assertTrue(WatchHistoryRules.isFinished(95_000, 100_000))
        assertFalse(WatchHistoryRules.isFinished(94_000, 100_000))
        assertFalse(WatchHistoryRules.isFinished(10_000, 0))
    }

    @Test
    fun startingAMovieThenFinishingItMovesItFromWatchingToSeen() {
        var list = WatchHistoryRules.put(emptyList(), item(1), WatchStatus.WATCHING, now = 1, finalEpisodeKey = null)
        assertEquals(listOf(1), WatchHistoryRules.withStatus(list, WatchStatus.WATCHING).map { it.item.id })

        list = WatchHistoryRules.afterFinished(list, PlaybackPositions.movieKey(1), now = 2)
        assertTrue(WatchHistoryRules.withStatus(list, WatchStatus.WATCHING).isEmpty())
        assertEquals(listOf(1), WatchHistoryRules.withStatus(list, WatchStatus.SEEN).map { it.item.id })
        assertEquals(2L, list.single().updatedAt)
    }

    @Test
    fun aSeriesIsSeenOnlyAfterItsLastEpisode() {
        val last = PlaybackPositions.episodeKey(5, 30, 300)
        var list = WatchHistoryRules.put(emptyList(), item(5, "series"), WatchStatus.WATCHING, 1, last)
        // A movie with the same id, or another episode, doesn't finish the series
        list = WatchHistoryRules.afterFinished(list, PlaybackPositions.movieKey(5), 2)
        list = WatchHistoryRules.afterFinished(list, PlaybackPositions.episodeKey(5, 30, 299), 3)
        assertEquals(WatchStatus.WATCHING, list.single().status)

        list = WatchHistoryRules.afterFinished(list, last, 4)
        assertEquals(WatchStatus.SEEN, list.single().status)
    }

    @Test
    fun replayingKeepsOneEntryAndTheSeriesLastEpisode() {
        val last = PlaybackPositions.episodeKey(5, 30, 300)
        var list = WatchHistoryRules.put(emptyList(), item(5, "series"), WatchStatus.SEEN, 1, last)
        list = WatchHistoryRules.put(list, item(5, "series"), WatchStatus.WATCHING, 2, null)
        assertEquals(1, list.size)
        assertEquals(last, list.single().finalEpisodeKey)
        assertEquals(WatchStatus.WATCHING, list.single().status)
    }

    @Test
    fun mostRecentFirstAndMoviesAndSeriesKeptApart() {
        var list = WatchHistoryRules.put(emptyList(), item(1), WatchStatus.WATCHING, 1, null)
        list = WatchHistoryRules.put(list, item(1, "series"), WatchStatus.WATCHING, 2, null)
        list = WatchHistoryRules.put(list, item(2), WatchStatus.WATCHING, 3, null)
        assertEquals(
            listOf("movie:2", "series:1", "movie:1"),
            WatchHistoryRules.withStatus(list, WatchStatus.WATCHING).map { "${it.item.type}:${it.item.id}" }
        )
        assertNull(list.first { it.item.type == "series" }.finalEpisodeKey)
    }
}
