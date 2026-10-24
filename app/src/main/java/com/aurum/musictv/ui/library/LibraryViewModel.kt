package com.aurum.musictv.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.data.model.TvPlaylist
import com.aurum.musictv.sync.AuthRepository
import com.aurum.musictv.sync.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryTab { LIKED_SONGS, RECENTLY_PLAYED, PLAYLISTS }

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.LIKED_SONGS,
    val likedSongs: List<Song> = emptyList(),
    val recentlyPlayed: List<Song> = emptyList(),
    val playlists: List<TvPlaylist> = emptyList(),
    /** Playlist currently opened on the Playlists tab (null = playlist list). */
    val openPlaylistId: String? = null,
    val isLoading: Boolean = true,
    val isSignedIn: Boolean = true,
    val likedHasMore: Boolean = false,
    val likedLoadingMore: Boolean = false,
    val playlistLoading: Boolean = false,
)

/** Library = the phone's synced favorites, history and playlists (one sync). */
class LibraryViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    fun selectTab(tab: LibraryTab) {
        _uiState.update { it.copy(tab = tab, openPlaylistId = null) }
    }

    /** Opening a playlist downloads just that playlist's songs (headers only
     *  were loaded for the list). Already-fetched playlists open instantly. */
    fun openPlaylist(id: String?) {
        _uiState.update { it.copy(openPlaylistId = id, playlistLoading = false) }
        val pl = id?.let { pid -> _uiState.value.playlists.firstOrNull { it.id == pid } } ?: return
        if (pl.songsLoaded) return
        _uiState.update { it.copy(playlistLoading = true) }
        viewModelScope.launch {
            val songs = runCatching { SyncRepository.fetchPlaylistSongs(pl.id) }.getOrDefault(emptyList())
            _uiState.update { st ->
                st.copy(
                    playlists = st.playlists.map { if (it.id == pl.id) it.copy(songs = songs, songsLoaded = true) else it },
                    playlistLoading = false,
                )
            }
        }
    }

    private var loadedUserId: String? = null
    private var loadedAt = 0L

    /** Loads the library. Cheap on revisits: if the same account was loaded
     *  in the last 2 minutes nothing is fetched again. */
    fun refresh() {
        viewModelScope.launch {
            AuthRepository.awaitReady()
            val signedIn = AuthRepository.isSignedIn
            val uid = SyncRepository.currentUserId()
            val fresh = signedIn && uid == loadedUserId &&
                System.currentTimeMillis() - loadedAt < 120_000L && !_uiState.value.isLoading
            if (fresh) return@launch
            if (!signedIn) {
                loadedUserId = null
                _uiState.update { LibraryUiState(isLoading = false, isSignedIn = false, tab = it.tab) }
                return@launch
            }
            if (uid != loadedUserId) {
                _uiState.update { LibraryUiState(tab = it.tab, isLoading = true, isSignedIn = true) }
            }
            loadedUserId = uid
            loadedAt = System.currentTimeMillis()
            loadAll()
        }
    }

    /** Called by the grid when the person scrolls near the end of Liked Songs. */
    fun loadMoreLiked() {
        val st = _uiState.value
        if (!st.likedHasMore || st.likedLoadingMore || st.isLoading) return
        _uiState.update { it.copy(likedLoadingMore = true) }
        viewModelScope.launch {
            val page = runCatching { SyncRepository.fetchLikedPage(offset = _uiState.value.likedSongs.size) }
                .getOrNull()
            _uiState.update { cur ->
                if (page == null) cur.copy(likedLoadingMore = false, likedHasMore = false)
                else {
                    val have = cur.likedSongs.mapTo(HashSet()) { it.id }
                    cur.copy(
                        likedSongs = cur.likedSongs + page.songs.filter { it.id !in have },
                        likedHasMore = page.hasMore && page.songs.isNotEmpty(),
                        likedLoadingMore = false,
                    )
                }
            }
        }
    }

    private fun loadAll() {
        viewModelScope.launch {
            val page = runCatching { SyncRepository.fetchLikedPage(offset = 0) }.getOrNull()
            _uiState.update {
                it.copy(
                    likedSongs = page?.songs ?: it.likedSongs,
                    likedHasMore = page?.hasMore ?: false,
                    isLoading = false,
                )
            }
        }
        viewModelScope.launch {
            val recent = runCatching { SyncRepository.fetchRecentlyPlayed(limit = 50) }.getOrDefault(emptyList())
            _uiState.update { it.copy(recentlyPlayed = recent) }
        }
        viewModelScope.launch {
            val lists = runCatching { SyncRepository.fetchPlaylists() }.getOrDefault(emptyList())
            _uiState.update { it.copy(playlists = lists) }
        }
    }
}
