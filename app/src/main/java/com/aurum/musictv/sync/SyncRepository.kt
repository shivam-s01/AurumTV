package com.aurum.musictv.sync

import com.aurum.musictv.data.model.FavoriteIdRow
import com.aurum.musictv.data.model.FavoriteRow
import com.aurum.musictv.data.model.FavoriteUpsert
import com.aurum.musictv.data.model.HistoryRow
import com.aurum.musictv.data.model.HistoryUpsert
import com.aurum.musictv.data.model.PlaylistRow
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.data.model.TvPlaylist
import com.aurum.musictv.data.model.toPhoneJson
import com.aurum.musictv.data.model.toPlaylistOrNull
import com.aurum.musictv.data.model.toSongOrNull
import com.aurum.musictv.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.Instant

/**
 * The ONE sync TV depends on: the same Supabase tables the phone app syncs
 * (see lib/services/sync_service.dart) — `favorites`, `history`, `playlists`.
 * Sign in with the same Google account and TV shows exactly what the phone
 * has; a like or a play on TV is written in the phone's own row format and
 * appears on the phone at its next sync.
 *
 * Deliberately thin and stateless apart from a tiny liked-id set: no local
 * database on TV, no TV-only tables, no background polling.
 */
object SyncRepository {

    private val userId: String?
        get() = SupabaseClientProvider.auth.currentUserOrNull()?.id

    // Ids of liked songs, loaded once per session/sign-in and kept in sync
    // by like/unlike — so the Player's heart icon is a memory lookup, not a
    // network round-trip on every song change.
    @Volatile
    private var likedIds: Set<String>? = null

    /** Forget everything cached for the previous account (sign-in/out). */
    fun clearSessionCache() {
        likedIds = null
    }

    // ── Favorites ("Liked Songs") ───────────────────────────────────────

    /** Liked songs, newest-looking first. Capped so a huge library can't
     *  stall a 1 GB TV box; the list is lazy in the UI. */
    suspend fun fetchLikedSongs(limit: Int = 500): List<Song> {
        val uid = userId ?: return emptyList()
        return runCatching {
            val rows = SupabaseClientProvider.postgrest.from("favorites")
                .select {
                    filter { eq("user_id", uid) }
                    limit(count = limit.toLong())
                }
                .decodeList<FavoriteRow>()
            likedIds = rows.map { it.songId }.toSet()
            rows.asReversed().mapNotNull { it.data?.toSongOrNull(it.songId) }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    /** Whether [songId] is liked — answered from memory after one cheap
     *  id-only fetch per session. */
    suspend fun isSongLiked(songId: String): Boolean {
        likedIds?.let { return songId in it }
        val uid = userId ?: return false
        return runCatching {
            val ids = SupabaseClientProvider.postgrest.from("favorites")
                .select(columns = Columns.list("song_id")) {
                    filter { eq("user_id", uid) }
                }
                .decodeList<FavoriteIdRow>()
                .map { it.songId }
                .toSet()
            likedIds = ids
            songId in ids
        }.getOrDefault(false)
    }

    /** @return true if the like was stored. */
    suspend fun likeSong(song: Song): Boolean {
        val uid = userId ?: return false
        return runCatching {
            SupabaseClientProvider.postgrest.from("favorites").upsert(
                FavoriteUpsert(songId = song.id, userId = uid, data = song.toPhoneJson())
            )
            likedIds = (likedIds ?: emptySet()) + song.id
            true
        }.getOrDefault(false)
    }

    /** @return true if the like was removed. */
    suspend fun unlikeSong(songId: String): Boolean {
        val uid = userId ?: return false
        return runCatching {
            SupabaseClientProvider.postgrest.from("favorites").delete {
                filter {
                    eq("user_id", uid)
                    eq("song_id", songId)
                }
            }
            likedIds = (likedIds ?: emptySet()) - songId
            true
        }.getOrDefault(false)
    }

    // ── History ("Recently Played" / "Continue Listening") ──────────────

    /** Most recent plays, newest first. Ordered + limited on the server —
     *  the phone's history can hold thousands of rows. */
    suspend fun fetchRecentlyPlayed(limit: Int = 30): List<Song> {
        val uid = userId ?: return emptyList()
        return runCatching {
            SupabaseClientProvider.postgrest.from("history")
                .select {
                    filter { eq("user_id", uid) }
                    order(column = "played_at", order = Order.DESCENDING)
                    limit(count = limit.toLong())
                }
                .decodeList<HistoryRow>()
                .mapNotNull { it.data?.toSongOrNull(it.songId) }
                .distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    /** Records a play in the phone's own `history` table (upsert per song,
     *  newest played_at wins — same as the phone's pushHistoryEntry). */
    suspend fun logRecentlyPlayed(song: Song) {
        val uid = userId ?: return
        runCatching {
            SupabaseClientProvider.postgrest.from("history").upsert(
                HistoryUpsert(
                    songId = song.id,
                    userId = uid,
                    data = song.toPhoneJson(),
                    playedAt = Instant.now().toString(),
                )
            )
        }
    }

    // ── Playlists (read-only on TV) ─────────────────────────────────────

    /** The phone's playlists, pinned first then in the phone's own order. */
    suspend fun fetchPlaylists(): List<TvPlaylist> {
        val uid = userId ?: return emptyList()
        return runCatching {
            SupabaseClientProvider.postgrest.from("playlists")
                .select {
                    filter { eq("user_id", uid) }
                    // Each row carries a whole playlist's songs as JSON — cap
                    // the count so a big library can't bloat a 1 GB TV box.
                    limit(count = 60)
                }
                .decodeList<PlaylistRow>()
                .mapNotNull { it.toPlaylistOrNull() }
                .filter { it.songs.isNotEmpty() }
                .sortedWith(compareByDescending<TvPlaylist> { it.isPinned }.thenBy { it.sortOrder })
        }.getOrDefault(emptyList())
    }
}
