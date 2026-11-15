package com.pira.ccloud.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class WatchStatus { WATCHING, SEEN }

/**
 * A movie or series the user has started (Watching) or finished (Seen).
 * Keeps the same details as a favorite, so the lists can show it and open its page without the network.
 */
@Serializable
data class WatchHistoryItem(
    val item: FavoriteItem,
    val status: WatchStatus,
    val updatedAt: Long,
    // For a series: the playback key of its last episode; finishing that episode marks the series as Seen
    val finalEpisodeKey: String? = null
)
