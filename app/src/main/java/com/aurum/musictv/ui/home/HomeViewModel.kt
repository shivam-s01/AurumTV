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
    /** True once the first history fetch finished (even if empty / signed
     *  out). The hero waits for it so "Continue Listening" is slide 1 from
     *  the first frame instead of popping in later. */
    val userDataLoaded: Boolean = false,
    /** Browse rows, in display order; they appear one by one as they load. */
    val sections: List<Pair<String, List<Song>>> = emptyList(),
    val recentlyPlayed: List<Song> = emptyList(),
    val likedSongs: List<Song> = emptyList(),
    /** "Because you played X" / "More like Y" — YouTube radio of what the
     *  person actually listened to (see AurumApi.related). */
    val personalRows: List<Pair<String, List<Song>>> = emptyList(),
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

    /** Retry only: if Home is still empty (server was down at launch) it
     *  tries again every 10 minutes. Once rows are on screen they are left
     *  alone, because reloading replaced every row while the person was
     *  browsing with the remote, which dropped focus and jumped the page. */
    private fun autoRefreshPeriodically() {
        viewModelScope.launch {
            while (true) {
                delay(10 * 60 * 1000L)
                if (isHomeVisible && _uiState.value.sections.isEmpty()) loadHome()
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
                        state.copy(
                            sections = sorted.map { it.title to it.songs },
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
            _uiState.update {
                it.copy(recentlyPlayed = recent, continueListening = recent.firstOrNull(), userDataLoaded = true)
            }
            loadPersonalRows(recent)
        }
        viewModelScope.launch {
            val liked = runCatching { SyncRepository.fetchLikedSongs() }.getOrDefault(emptyList())
            _uiState.update { it.copy(likedSongs = liked.take(40)) }
        }
    }

    /** Up to two rows built from different artists the person played last,
     *  one at a time (one network call each) so a 1 GB box never has two
     *  extractions running just for Home. */
    private fun loadPersonalRows(recent: List<Song>) {
        if (recent.isEmpty()) return
        val seeds = recent.distinctBy { it.artist.lowercase() }.take(2)
        val seen = recent.mapTo(HashSet()) { it.id }
        viewModelScope.launch {
            val rows = ArrayList<Pair<String, List<Song>>>(2)
            seeds.forEachIndexed { i, seed ->
                val songs = runCatching { AurumApi.related(seed, exclude = seen, limit = 20) }
                    .getOrDefault(emptyList())
                if (songs.size >= 4) {
                    val title = if (i == 0) "Because you played ${seed.title}" else "More like ${seed.artist}"
                    rows += title to songs
                    _uiState.update { it.copy(personalRows = rows.toList()) }
                }
            }
        }
    }

    private var localSeedLoaded = false

    /** Not signed in (no synced history yet): the first song played in this
     *  session seeds the same kind of row, so Home still learns right away. */
    fun onSongPlayed(song: Song) {
        val state = _uiState.value
        if (localSeedLoaded || state.personalRows.isNotEmpty() || state.recentlyPlayed.isNotEmpty()) return
        localSeedLoaded = true
        loadPersonalRows(listOf(song))
    }

    fun refresh() {
        loadHome()
        loadUserData()
    }

    /** Called when returning from the Auth screen (sign-in/out/dismiss). */
    fun refreshAuthState() {
        val signedIn = AuthRepository.isSignedIn
        _uiState.update {
            it.copy(
                greetingName = AuthRepository.displayName,
                avatarUrl = AuthRepository.avatarUrl,
                // Signed out: drop the previous account's rows right away.
                continueListening = if (signedIn) it.continueListening else null,
                recentlyPlayed = if (signedIn) it.recentlyPlayed else emptyList(),
                likedSongs = if (signedIn) it.likedSongs else emptyList(),
                personalRows = if (signedIn) it.personalRows else emptyList(),
            )
        }
        localSeedLoaded = false
        loadUserData()
    }
}
