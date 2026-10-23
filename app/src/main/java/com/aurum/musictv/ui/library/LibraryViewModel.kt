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
)

/** Library = the phone's synced favorites, history and playlists (one sync). */
class LibraryViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    fun selectTab(tab: LibraryTab) {
        _uiState.update { it.copy(tab = tab, openPlaylistId = null) }
    }

    fun openPlaylist(id: String?) {
        _uiState.update { it.copy(openPlaylistId = id) }
    }

    /** Reloads all three lists in parallel; called whenever the screen is shown. */
    fun refresh() {
        val signedIn = AuthRepository.isSignedIn
        _uiState.update { it.copy(isLoading = signedIn, isSignedIn = signedIn) }
        if (!signedIn) {
            _uiState.update { LibraryUiState(isLoading = false, isSignedIn = false, tab = it.tab) }
            return
        }
        viewModelScope.launch {
            val liked = runCatching { SyncRepository.fetchLikedSongs() }.getOrDefault(emptyList())
            _uiState.update { it.copy(likedSongs = liked, isLoading = false) }
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
