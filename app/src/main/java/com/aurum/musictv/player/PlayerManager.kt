package com.aurum.musictv.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.data.remote.AurumApi
import com.aurum.musictv.sync.SyncRepository
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentSong: Song? = null,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isBuffering: Boolean = false,
)

/**
 * Talks to the player through a MediaController connected to
 * AurumTvPlaybackService, NOT a private ExoPlayer instance. This matters:
 * it's what gives us background playback that survives navigating away
 * from PlayerScreen, system media-key handling (play/pause/next/prev on
 * the remote), and a proper notification/session — all the things a
 * bare ExoPlayer in an Activity does not get you for free. One
 * MediaController for the whole app lifecycle, created once here.
 */
class PlayerManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, AurumTvPlaybackService::class.java),
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        this.controllerFuture = controllerFuture
        controllerFuture.addListener({
            controller = controllerFuture.get()
            attachListener()
        }, MoreExecutors.directExecutor())

        // Position ticker — also drives the periodic sync push.
        scope.launch {
            while (true) {
                delay(1000)
                controller?.let { c ->
                    _uiState.value = _uiState.value.copy(positionMs = c.currentPosition.coerceAtLeast(0))
                }
            }
        }
        scope.launch {
            while (true) {
                delay(5000)
                pushStateNow()
            }
        }
    }

    private fun attachListener() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                pushStateNow()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val c = controller ?: return
                _uiState.value = _uiState.value.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    durationMs = c.duration.coerceAtLeast(0),
                )
                if (playbackState == Player.STATE_ENDED) {
                    playNext()
                }
            }
        })
    }

    private fun pushStateNow() {
        val song = _uiState.value.currentSong ?: return
        val c = controller ?: return
        scope.launch {
            SyncRepository.pushPlaybackState(
                song = song,
                positionMs = c.currentPosition,
                isPlaying = c.isPlaying,
            )
        }
    }

    fun playQueue(songs: List<Song>, startIndex: Int) {
        _uiState.value = _uiState.value.copy(queue = songs, currentIndex = startIndex)
        scope.launch { SyncRepository.pushQueue(songs, startIndex) }
        playIndex(startIndex)
    }

    private fun playIndex(index: Int) {
        val queue = _uiState.value.queue
        val song = queue.getOrNull(index) ?: return
        _uiState.value = _uiState.value.copy(currentSong = song, currentIndex = index)

        val c = controller ?: return
        scope.launch {
            val streamUrl = song.streamUrl ?: AurumApi.resolveStreamUrl(song)
            if (streamUrl == null) return@launch
            c.setMediaItem(MediaItem.fromUri(streamUrl))
            c.prepare()
            c.play()
            SyncRepository.logRecentlyPlayed(song)
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun playNext() {
        val state = _uiState.value
        val nextIndex = state.currentIndex + 1
        if (nextIndex < state.queue.size) playIndex(nextIndex)
    }

    fun playPrevious() {
        val state = _uiState.value
        val prevIndex = (state.currentIndex - 1).coerceAtLeast(0)
        playIndex(prevIndex)
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    /** Resumes exactly what mobile was last playing — called from the
     *  "Resume on TV" banner action on Home. */
    fun resumeFrom(song: Song, positionMs: Long) {
        playQueue(listOf(song), 0)
        scope.launch {
            delay(500) // let prepare() land first
            controller?.seekTo(positionMs)
        }
    }

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
    }
}

