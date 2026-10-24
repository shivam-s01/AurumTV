package com.aurum.musictv.sync

import com.aurum.musictv.data.model.FavoriteIdRow
import com.aurum.musictv.data.model.FavoriteRow
import com.aurum.musictv.data.model.FavoriteUpsert
import com.aurum.musictv.data.model.HistoryRow
import com.aurum.musictv.data.model.HistoryUpsert
import com.aurum.musictv.data.model.PlaylistMetaRow
import com.aurum.musictv.data.model.PlaylistRow
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.data.model.TvPlaylist
import com.aurum.musictv.data.model.toHeaderOrNull
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

    /** The saved login is restored asynchronously at app start; wait for it
     *  (bounded) so a cold start never reads "signed out" by mistake. */
    private suspend fun awaitUserId(): String? {
        AuthRepository.awaitReady()
        return userId
    }

    // Ids of liked songs, loaded once per session/sign-in (ids only, paged,
    // ~25 bytes each) and kept in sync by like/unlike — so the Player's heart
    // icon is a memory lookup, not a network round-trip on every song change.
    @Volatile
    private var likedIds: Set<String>? = null

    // The phone's `favorites` table is (song_id, user_id, data) with NO
    // timestamp, so by default pages are ordered by song_id (stable, complete,
    // but not by recency). If you add an `added_at timestamptz default now()`
    // column (see supabase_migration_phase3_favorites_order.sql) it is picked
    // up automatically and liked songs come newest-first.
    @Volatile
    private var favOrderColumn: String? = "added_at"

    /** Forget everything cached for the previous account (sign-in/out). */
    fun clearSessionCache() {
        likedIds = null
    }

    fun currentUserId(): String? = userId

    // ── Favorites ("Liked Songs") ───────────────────────────────────────

    data class LikedPage(val songs: List<Song>, val hasMore: Boolean)

    private const val LIKED_PAGE = 120

    private suspend fun favRows(uid: String, from: Long, to: Long, orderCol: String?): List<FavoriteRow> =
        SupabaseClientProvider.postgrest.from("favorites")
            .select {
                filter { eq("user_id", uid) }
                if (orderCol != null) order(column = orderCol, order = Order.DESCENDING)
                order(column = "song_id", order = Order.ASCENDING) // stable paging
                range(from, to)
            }
            .decodeList<FavoriteRow>()

    /** One page of liked songs, newest first. The UI asks for the next page
     *  only when the person scrolls near the end, so a 5,000-song library
     *  costs the same as a 50-song one until it is actually browsed. */
    suspend fun fetchLikedPage(offset: Int, pageSize: Int = LIKED_PAGE): LikedPage {
        val uid = awaitUserId() ?: return LikedPage(emptyList(), false)
        return try {
            val order = favOrderColumn
            val rows = try {
                favRows(uid, offset.toLong(), offset + pageSize - 1L, order)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only a missing column switches to the fallback; a network
                // error just yields an empty page (retried on the next scroll).
                val m = e.message.orEmpty()
                if (m.contains("added_at") || m.contains("42703")) {
                    favOrderColumn = null
                    return fetchLikedPage(offset, pageSize)
                }
                throw e
            }
            LikedPage(
                rows.mapNotNull { it.data?.toSongOrNull(it.songId) }.distinctBy { it.id },
                hasMore = rows.size >= pageSize,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            LikedPage(emptyList(), false)
        }
    }

    /** First [limit] liked songs (Home row). */
    suspend fun fetchLikedSongs(limit: Int = LIKED_PAGE): List<Song> = fetchLikedPage(0, limit).songs

    private suspend fun loadLikedIds(uid: String): Set<String> {
        val out = HashSet<String>()
        var from = 0L
        while (from < 20_000L) {
            val rows = SupabaseClientProvider.postgrest.from("favorites")
                .select(columns = Columns.list("song_id")) {
                    filter { eq("user_id", uid) }
                    order(column = "song_id", order = Order.ASCENDING)
                    range(from, from + 999L)
                }
                .decodeList<FavoriteIdRow>()
            rows.forEach { out += it.songId }
            if (rows.size < 1000) break
            from += 1000L
        }
        return out
    }

    /** Whether [songId] is liked — answered from memory after one cheap
     *  id-only load per session. */
    suspend fun isSongLiked(songId: String): Boolean {
        likedIds?.let { return songId in it }
        val uid = awaitUserId() ?: return false
        return runCatching {
            val ids = loadLikedIds(uid)
            likedIds = ids
            songId in ids
        }.getOrDefault(false)
    }

    /** @return true if the like was stored. */
    suspend fun likeSong(song: Song): Boolean {
        val uid = awaitUserId() ?: return false
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
        val uid = awaitUserId() ?: return false
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
        val uid = awaitUserId() ?: return emptyList()
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
        val uid = awaitUserId() ?: return
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

    /** Playlist HEADERS (name, pin, order, cover) — pinned first, then the
     *  phone's own order. Songs are NOT downloaded here; see
     *  [fetchPlaylistSongs]. If the server can't do JSON-path columns we fall
     *  back to whole rows (capped) so the list still shows. */
    suspend fun fetchPlaylists(): List<TvPlaylist> {
        val uid = awaitUserId() ?: return emptyList()
        val headers = try {
            SupabaseClientProvider.postgrest.from("playlists")
                .select(
                    columns = Columns.raw(
                        "id,name:data->>name,pinned:data->>isPinned,sort:data->>sortOrder,cover:data->songs->0->>artworkUrl"
                    )
                ) {
                    filter { eq("user_id", uid) }
                    limit(count = 300)
                }
                .decodeList<PlaylistMetaRow>()
                .mapNotNull { it.toHeaderOrNull() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            runCatching {
                SupabaseClientProvider.postgrest.from("playlists")
                    .select {
                        filter { eq("user_id", uid) }
                        limit(count = 40)
                    }
                    .decodeList<PlaylistRow>()
                    .mapNotNull { it.toPlaylistOrNull() }
            }.getOrDefault(emptyList())
        }
        return headers.sortedWith(compareByDescending<TvPlaylist> { it.isPinned }.thenBy { it.sortOrder })
    }

    /** The songs of ONE playlist, fetched when the person opens it. */
    suspend fun fetchPlaylistSongs(playlistId: String): List<Song> {
        val uid = awaitUserId() ?: return emptyList()
        return runCatching {
            SupabaseClientProvider.postgrest.from("playlists")
                .select {
                    filter {
                        eq("user_id", uid)
                        eq("id", playlistId)
                    }
                    limit(count = 1)
                }
                .decodeList<PlaylistRow>()
                .firstOrNull()?.toPlaylistOrNull()?.songs.orEmpty()
        }.getOrDefault(emptyList())
    }
}
