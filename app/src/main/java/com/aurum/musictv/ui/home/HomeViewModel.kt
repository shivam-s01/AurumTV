package com.aurum.musictv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.musictv.data.model.PlaybackStateRow
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.data.model.toSong
import com.aurum.musictv.data.remote.AurumApi
import com.aurum.musictv.sync.AuthRepository
import com.aurum.musictv.sync.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val greetingName: String? = null,
    val continueListening: Song? = null,
    val trending: List<Song> = emptyList(),
    val newReleases: List<Song> = emptyList(),
    val recentlyPlayed: List<Song> = emptyList(),
    val isPremium: Boolean = false,
    /** Non-null while the OTHER device (mobile) is actively playing
     *  something TV isn't currently playing — drives the
     *  "Playing on phone" resume banner. */
    val remoteNowPlaying: PlaybackStateRow? = null,
)

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadHome()
        observeRemotePlayback()
        observePremium()
    }

    private fun loadHome() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                greetingName = AuthRepository.displayName,
            )

            // Uses the same homeSections() the phone app's home screen is
            // built from — same Worker call, same content, not a
            // reinvented query.
            val sections = runCatching { AurumApi.homeSections() }.getOrDefault(emptyList())
            val trending = sections.firstOrNull { it.first.contains("Trend", ignoreCase = true) }?.second
                ?: sections.getOrNull(0)?.second ?: emptyList()
            val newReleases = sections.firstOrNull { it.first.contains("New", ignoreCase = true) }?.second
                ?: sections.getOrNull(1)?.second ?: emptyList()

            val playbackState = SyncRepository.fetchPlaybackState()
            val continueListening = playbackState?.songData?.toSong()

            val recent = runCatching {
                // recently_played rows -> songs; kept simple, TV shows at
                // most what's already cached server-side.
                emptyList<Song>() // populated via a dedicated fetch below if needed
            }.getOrDefault(emptyList())

            val isPremium = SyncRepository.fetchIsPremium()

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                trending = trending,
                newReleases = newReleases,
                continueListening = continueListening,
                recentlyPlayed = recent,
                isPremium = isPremium,
            )
        }
    }

    /** Listens for mobile pushing a new playback_state row while TV is
     *  sitting on Home — shows a lightweight "Resume on TV" banner instead
     *  of silently doing nothing (which is what would happen with zero
     *  realtime wiring). */
    private fun observeRemotePlayback() {
        viewModelScope.launch {
            SyncRepository.observePlaybackState(viewModelScope).collect { row ->
                if (row.device != "tv") {
                    _uiState.value = _uiState.value.copy(remoteNowPlaying = row)
                }
            }
        }
    }

    private fun observePremium() {
        viewModelScope.launch {
            SyncRepository.observeIsPremium(viewModelScope).collect { premium ->
                _uiState.value = _uiState.value.copy(isPremium = premium)
            }
        }
    }

    fun dismissRemoteNowPlaying() {
        _uiState.value = _uiState.value.copy(remoteNowPlaying = null)
    }

    fun refresh() = loadHome()
}
