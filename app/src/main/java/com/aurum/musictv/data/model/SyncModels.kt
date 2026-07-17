package com.aurum.musictv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors the `playback_state` table — one row per user, last write wins. */
@Serializable
data class PlaybackStateRow(
    @SerialName("user_id") val userId: String,
    @SerialName("song_id") val songId: String? = null,
    @SerialName("song_data") val songData: SongDto? = null,
    @SerialName("position_ms") val positionMs: Long = 0,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    val device: String = "tv",
)

/** Mirrors the `playback_queue` table. */
@Serializable
data class QueueRow(
    @SerialName("user_id") val userId: String,
    val items: List<SongDto> = emptyList(),
    @SerialName("current_index") val currentIndex: Int = 0,
)

/** Mirrors the `profiles` table — TV only ever reads this (see RLS: no
 *  client write policy, only the payment Worker writes it). */
@Serializable
data class ProfileRow(
    @SerialName("user_id") val userId: String,
    @SerialName("is_premium") val isPremium: Boolean = false,
    @SerialName("premium_plan") val premiumPlan: String? = null,
)

/** Lightweight JSON-safe song shape for jsonb columns — separate from the
 *  UI-facing [Song] so player/UI code never has to think about
 *  serialization annotations. */
@Serializable
data class SongDto(
    val id: String,
    val title: String,
    val artist: String,
    val albumArtUrl: String? = null,
    val durationSec: Int = 0,
    val source: String = "SAAVN",
)

fun Song.toDto() = SongDto(id, title, artist, albumArtUrl, durationSec, source.name)
fun SongDto.toSong() = Song(
    id = id,
    title = title,
    artist = artist,
    albumArtUrl = albumArtUrl,
    durationSec = durationSec,
    source = runCatching { SongSource.valueOf(source) }.getOrDefault(SongSource.SAAVN),
)
