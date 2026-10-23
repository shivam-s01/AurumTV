package com.aurum.musictv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * ONE SYNC: these rows mirror the tables the PHONE app already syncs
 * (lib/services/sync_service.dart) — favorites, history, playlists — and
 * nothing else. Same Supabase project, same user, same row/JSON shapes, so
 * whatever is on the phone is on the TV and anything the TV writes shows up
 * on the phone at its next sync. The old TV-only tables (liked_songs,
 * recently_played, playback_state, playback_queue, profiles) were never
 * written by the phone, which is why TV always looked empty/out of sync.
 */

/** `favorites` — PK (song_id, user_id); `data` is the phone's Song.toJson(). */
@Serializable
data class FavoriteRow(
    @SerialName("song_id") val songId: String,
    val data: JsonObject? = null,
)

/** Same table, id column only — cheap "which songs are liked" lookup. */
@Serializable
data class FavoriteIdRow(
    @SerialName("song_id") val songId: String,
)

/** `history` — PK (song_id, user_id); one row per song, newest played_at wins. */
@Serializable
data class HistoryRow(
    @SerialName("song_id") val songId: String,
    val data: JsonObject? = null,
    @SerialName("played_at") val playedAt: String? = null,
)

/** `playlists` — PK (id, user_id); `data` is the phone's AurumPlaylist.toJson(). */
@Serializable
data class PlaylistRow(
    val id: String,
    val data: JsonObject? = null,
)

@Serializable
data class FavoriteUpsert(
    @SerialName("song_id") val songId: String,
    @SerialName("user_id") val userId: String,
    val data: JsonObject,
)

@Serializable
data class HistoryUpsert(
    @SerialName("song_id") val songId: String,
    @SerialName("user_id") val userId: String,
    val data: JsonObject,
    @SerialName("played_at") val playedAt: String,
)

/** A playlist as the phone saved it (read-only on TV). */
data class TvPlaylist(
    val id: String,
    val name: String,
    val songs: List<Song>,
    val isPinned: Boolean,
    val sortOrder: Int,
)

// ── JSON <-> Song, matching lib/models/song.dart ────────────────────────

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.intOrNull ?: p.doubleOrNull?.toInt()
}

private fun cleanText(s: String): String = s
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#039;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .trim()

/** Mirrors Song._resolveArtwork: saved songs carry `artworkUrl`; older/raw
 *  shapes may carry `artworkUrl100`, an `image` list/string, `artwork` or
 *  `thumbnail`. */
private fun JsonObject.artwork(): String? {
    str("artworkUrl")?.let { return it }
    str("artworkUrl100")?.let { return it.replace("100x100bb", "600x600bb").replace("100x100", "600x600") }
    val image: JsonElement? = this["image"]
    if (image is JsonArray && image.isNotEmpty()) {
        val last = image.last() as? JsonObject
        (last?.str("link") ?: last?.str("url"))?.let { return it }
    }
    val raw = (image as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: str("artwork") ?: str("thumbnail")
    return raw?.replace("150x150", "500x500")?.replace("50x50", "500x500")
}

/**
 * Parses one phone-saved song. Returns null for songs TV can't play: no id,
 * or a local file that only exists on the phone.
 */
fun JsonObject.toSongOrNull(fallbackId: String? = null): Song? {
    val sourceStr = str("source")
    if (sourceStr == "local" || str("localPath") != null) return null

    val id = str("trackId") ?: str("id") ?: str("song_id") ?: fallbackId ?: return null
    val title = cleanText(str("trackName") ?: str("title") ?: str("song") ?: "Unknown")
    val artist = cleanText(
        str("artistName") ?: str("primary_artists") ?: str("artist") ?: str("singers") ?: "Unknown Artist"
    )
    val durationSec = (int("trackTimeMillis")?.let { it / 1000 }) ?: int("duration") ?: 0

    return Song(
        id = id,
        title = title,
        artist = artist,
        albumArtUrl = artwork(),
        durationSec = durationSec.coerceAtLeast(0),
        // streamUrl in synced JSON is a stale CDN link — never reuse it;
        // TV always resolves a fresh stream.
        source = if (sourceStr == "youtube") SongSource.YOUTUBE else SongSource.SAAVN,
    )
}

/** Writes a song in the phone's Song.toJson() shape so the phone reads it
 *  back without any conversion. */
fun Song.toPhoneJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("title", title)
    put("artist", artist)
    put("album", "")
    put("artworkUrl", albumArtUrl ?: "")
    put("streamUrl", JsonNull)
    if (durationSec > 0) put("duration", durationSec) else put("duration", JsonNull)
    put("language", JsonNull)
    put("year", JsonNull)
    put("localPath", JsonNull)
    put(
        "source",
        when (source) {
            SongSource.YOUTUBE -> "youtube"
            SongSource.LOCAL -> "local"
            SongSource.SAAVN -> "saavn"
        },
    )
    put("viewCount", JsonNull)
    put("artistChannelId", JsonNull)
}

/** Parses a playlist row; tolerates a corrupt row by returning null. */
fun PlaylistRow.toPlaylistOrNull(): TvPlaylist? {
    val d = data ?: return null
    val name = d.str("name") ?: return null
    val songs = (d["songs"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.toSongOrNull() }
        .orEmpty()
    return TvPlaylist(
        id = id,
        name = name,
        songs = songs,
        isPinned = (d["isPinned"] as? JsonPrimitive)?.booleanOrNull ?: false,
        sortOrder = d.int("sortOrder") ?: 0,
    )
}
