package com.pira.ccloud.player

/**
 * How fast seeking goes while the remote's left/right (or rewind/fast forward) key is held, like
 * YouTube: steps of the seek time (10 s by default), more of them the longer the key is held.
 */
object HoldSeek {
    // How often the shown position moves while the key is held
    const val INTERVAL_MS = 200L

    /** Seek steps per move after the key was held [heldMs]: 100 s a second at first, up to 20 min. */
    fun multiplier(heldMs: Long): Int = when {
        heldMs < 1_000 -> 2
        heldMs < 2_500 -> 6
        heldMs < 4_500 -> 12
        else -> 24
    }
}
