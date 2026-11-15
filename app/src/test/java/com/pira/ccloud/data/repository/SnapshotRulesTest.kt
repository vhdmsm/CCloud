package com.pira.ccloud.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotRulesTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    @Test
    fun encodeThenDecodeKeepsEveryAnswer() {
        val snapshot = Snapshot(
            createdAt = now,
            omdb = mapOf("tt0111161" to "${now - day}|{\"Awards\":\"Won 1 Oscar\",\"imdbRating\":\"9.3\"}"),
            watchmode = mapOf("t:inception|2010" to "${now - 2 * day}|{\"id\":1,\"v\":2}", "p:7" to "$now|81.5")
        )
        assertEquals(snapshot, SnapshotRules.decode(SnapshotRules.encode(snapshot)))
    }

    @Test
    fun aSnapshotFromANewerAppOrNotASnapshotIsNotRead() {
        assertNull(SnapshotRules.decode("{\"version\":99,\"omdb\":{}}"))
        assertNull(SnapshotRules.decode("<html>Not Found</html>"))
        assertNull(SnapshotRules.decode("{\"omdb\":{}}"))
    }

    @Test
    fun onlyFreshWellFormedAnswersAreShared() {
        val entries = mapOf(
            "fresh" to "${now - day}|{}",
            "expired" to "${now - 31 * day}|{}",
            "broken" to "not a time|{}",
            "empty" to "${now - day}|",
            "future" to "${now + 3 * day}|{}"
        )
        assertEquals(setOf("fresh"), SnapshotRules.exportable(entries, now).keys)
    }

    @Test
    fun theDeviceTakesOnlyAnswersItLacksOrHasOlder() {
        val local = mapOf(
            "older" to "${now - 10 * day}|{\"old\":1}",
            "newer" to "${now - day}|{\"mine\":1}"
        )
        val incoming = mapOf(
            "older" to "${now - 2 * day}|{\"new\":1}",
            "newer" to "${now - 5 * day}|{\"theirs\":1}",
            "missing" to "${now - 3 * day}|{}",
            "expired" to "${now - 40 * day}|{}"
        )
        val taken = SnapshotRules.newerEntries(local, incoming, now)
        assertEquals(setOf("older", "missing"), taken.keys)
        assertEquals(incoming["older"], taken["older"])
    }

    @Test
    fun newestSaveIsTheLatestFetch() {
        assertEquals(0L, SnapshotRules.newestSave(emptyList()))
        assertEquals(now, SnapshotRules.newestSave(listOf("${now - day}|{}", "$now|{}", "bad")))
        assertTrue(SnapshotRules.newestSave(listOf("${now - day}|{}")) < now)
    }
}
