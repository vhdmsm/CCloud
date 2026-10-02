package com.pira.ccloud.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldSeekTest {
    // Seconds of video skipped per second of holding, with the default 10 s seek time
    private fun secondsPerSecond(heldMs: Long) = 10.0 * HoldSeek.multiplier(heldMs) * 1000 / HoldSeek.INTERVAL_MS

    @Test
    fun isFastFromTheStartAndOnlySpeedsUp() {
        // At first 100 s a second (the old speed was about 33)
        assertEquals(100.0, secondsPerSecond(500), 1e-9)
        val speeds = (0L..10_000L step 100).map { secondsPerSecond(it) }
        assertTrue(speeds.zipWithNext().all { (a, b) -> b >= a })
        // At most 20 minutes a second: a two-hour film still takes a few seconds, so it can be stopped on time
        assertEquals(1200.0, speeds.last(), 1e-9)
    }
}
