package com.aurum.musictv.ui.player

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aurum.musictv.data.remote.AurumApi
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.player.AurumTvPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How far a single D-pad left/right press seeks. */
private const val SEEK_STEP_MS = 10_000L

/** UI-facing snapshot of playback state — PlayerActivity observes this to
 *  keep the progress bar and play/pause icon in sync, instead of polling
 *  the MediaController's raw fields directly from the Activity. */
data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isBuffering: Boolean = false,
)

@UnstableApi
class PlayerViewModel : ViewModel() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    durationMs = controller?.duration?.coerceAtLeast(0) ?: it.durationMs,
                )
            }
        }
    }

    fun bindSession(context: Context) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, AurumTvPlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            controller?.addListener(playerListener)
            startPositionPolling()
        }, context.mainExecutor)
    }

    /** Media3's Player has no position-change callback, so we poll on a
     *  cheap 500ms tick while this ViewModel is alive — negligible CPU/RAM
     *  cost, far simpler than a custom Handler-based ticker. */
    private fun startPositionPolling() {
        viewModelScope.launch {
            while (true) {
                controller?.let { c ->
                    _uiState.update {
                        it.copy(
                            positionMs = c.currentPosition.coerceAtLeast(0),
                            durationMs = c.duration.coerceAtLeast(0),
                        )
                    }
                }
                delay(500)
            }
        }
    }

    suspend fun play(song: Song) {
        val streamUrl = AurumApi.resolveStreamUrl(song) ?: return
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(song.id)
            .build()
        controller?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }

    /** D-pad center button. */
    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /** D-pad right. */
    fun seekForward() {
        val c = controller ?: return
        c.seekTo((c.currentPosition + SEEK_STEP_MS).coerceAtMost(c.duration.coerceAtLeast(0)))
    }

    /** D-pad left. */
    fun seekBackward() {
        val c = controller ?: return
        c.seekTo((c.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
    }

    fun unbindSession() {
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
