package com.aurum.musictv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.data.remote.AurumApi
import com.aurum.musictv.sync.AuthRepository
import com.aurum.musictv.sync.SyncRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val greetingName: String? = null,
    val avatarUrl: String? = null,
    /** Last song played on any device (phone or TV) — from the shared history. */
    val continueListening: Song? = null,
    /** Hero carousel: one random pick per section, filled as rows arrive. */
    val heroSongs: List<Song> = emptyList(),
    /** Browse rows, in display order; they appear one by one as they load. */
    val sections: List<Pair<String, List<Song>>> = emptyList(),
    val recentlyPlayed: List<Song> = emptyList(),
    val likedSongs: List<Song> = emptyList(),
    /** Set only when a load finished with zero rows because the calls failed. */
    val loadError: String? = null,
)

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var isHomeVisible = true

    fun setHomeVisible(visible: Boolean) {
        isHomeVisible = visible
    }

    init {
        loadHome()
        loadUserData()
        autoRefreshPeriodically()
    }

    /** TV has no pull-to-refresh, so Home reloads its browse rows itself
     *  every 10 minutes while visible (query pools are randomized, so it
     *  surfaces different songs). */
    private fun autoRefreshPeriodically() {
        viewModelScope.launch {
            while (true) {
                delay(10 * 60 * 1000L)
                if (isHomeVisible) loadHome()
            }
        }
    }

    /** Browse rows stream in: the first row is on screen after one search
     *  round-trip instead of waiting for every row. User data loads in
     *  parallel (see [loadUserData]) and never blocks them. */
    private fun loadHome() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    loadError = null,
                    greetingName = AuthRepository.displayName,
                    avatarUrl = AuthRepository.avatarUrl,
                )
            }

            val rows = mutableListOf<com.aurum.musictv.data.remote.HomeRow>()
            val failure = runCatching {
                AurumApi.homeRows().collect { row ->
                    rows.add(row)
                    val sorted = rows.sortedBy { it.index }
                    _uiState.update { state ->
                        // Hero: keep what's already shown, add one pick from each new row.
                        val hero = if (state.heroSongs.size < 6 && row.songs.isNotEmpty()) {
                            state.heroSongs + row.songs.random()
                        } else state.heroSongs
                        state.copy(
                            sections = sorted.map { it.title to it.songs },
                            heroSongs = hero,
                            isLoading = false,
                        )
                    }
                }
            }.exceptionOrNull()

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    loadError = when {
                        rows.isNotEmpty() -> null
                        failure != null -> "Couldn't reach server: ${failure.message ?: "unknown error"}"
                        else -> "No songs loaded. ${AurumApi.lastDiagnostic}"
                    },
                )
            }
        }
    }

    /** Favorites + history from the phone's own sync tables, both at once. */
    private fun loadUserData() {
        viewModelScope.launch {
            AuthRepository.awaitReady()
            _uiState.update {
                it.copy(greetingName = AuthRepository.displayName, avatarUrl = AuthRepository.avatarUrl)
            }
            val recent = runCatching { SyncRepository.fetchRecentlyPlayed(limit = 30) }.getOrDefault(emptyList())
            _uiState.update { it.copy(recentlyPlayed = recent, continueListening = recent.firstOrNull()) }
        }
        viewModelScope.launch {
            val liked = runCatching { SyncRepository.fetchLikedSongs() }.getOrDefault(emptyList())
            _uiState.update { it.copy(likedSongs = liked.take(40)) }
        }
    }

    fun refresh() {
        loadHome()
        loadUserData()
    }

    /** Called when returning from the Auth screen (sign-in/out/dismiss). */
    fun refreshAuthState() {
        _uiState.update {
            it.copy(
                greetingName = AuthRepository.displayName,
                avatarUrl = AuthRepository.avatarUrl,
            )
        }
        loadUserData()
    }
}
