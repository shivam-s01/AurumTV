package com.aurum.musictv.data.model

/**
 * TV-app equivalent of lib/models/song.dart — trimmed to only what the TV
 * UI and player actually need. No premium flags, no download metadata,
 * no lyrics-cache fields; keep this thin, it's created constantly while
 * scrolling rows.
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val durationSec: Int,
    val source: SongSource,
    // streamUrl is resolved lazily right before playback (same pattern as
    // your phone app's api_service.dart resolve chain), not stored eagerly
    // for every song in a browse row — keeps memory flat while scrolling.
    var streamUrl: String? = null,
)

enum class SongSource { SAAVN, YOUTUBE, LOCAL }
