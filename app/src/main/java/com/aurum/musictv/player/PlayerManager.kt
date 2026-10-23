package com.aurum.musictv.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.data.model.SongSource
import com.aurum.musictv.data.remote.AurumApi
import com.aurum.musictv.data.remote.NetworkResilience
import com.aurum.musictv.settings.AppSettings
import com.aurum.musictv.settings.AudioQuality
import com.aurum.musictv.settings.SettingsStore
import com.aurum.musictv.sync.AuthRepository
import com.aurum.musictv.sync.SyncRepository
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class PlayerUiState(
    val currentSong: Song? = null,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isBuffering: Boolean = false,
    /** Non-null right after a tap fails to produce anything playable — the
     *  UI shows it as a toast so a click always gives feedback. */
    val playbackError: String? = null,
    /** True while the stream URL is being resolved for a song that hasn't
     *  started playing yet (separate from ExoPlayer's own buffering). */
    val isResolving: Boolean = false,
    /** Whether the current song is in the user's Liked Songs (heart icon). */
    val isCurrentSongLiked: Boolean = false,
)

/**
 * Talks to the player through a MediaController connected to
 * AurumTvPlaybackService (not a private ExoPlayer): background playback that
 * survives leaving the player screen, remote media keys, and a real
 * notification/session.
 *
 * Playback pipeline per song:
 *   tap -> resolve stream URL (native NewPipe, Worker hedge — see
 *   YoutubeStreamResolver; usually a cache hit thanks to next-track
 *   prefetch) -> ExoPlayer. If a stream dies mid-song it is re-resolved
 *   once and resumed at the same position, then retried as AAC (decoder
 *   problems), and only then skipped — so one bad link never stalls a queue.
 */
class PlayerManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var playerListener: Player.Listener? = null
    private val controllerReady = CompletableDeferred<MediaController?>()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Latest settings, kept in memory so a tap never waits on disk.
    private var settings = AppSettings()

    private var playJob: Job? = null
    private var prefetchJob: Job? = null
    private var extendJob: Job? = null

    // Recovery state for the CURRENT song (reset on every new song).
    private var recoveryAttempts = 0
    private var preferAac = false

    init {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, AurumTvPlaybackService::class.java),
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try {
                future.get()
            } catch (e: Exception) {
                null
            }
            controller = c
            if (c != null) {
                attachListener(c)
                adoptRunningSession(c)
            }
            controllerReady.complete(c)
        }, MoreExecutors.directExecutor())

        // Settings are live: a changed audio quality is applied to the song
        // that is playing RIGHT NOW (re-resolved + resumed at the same
        // position), not only to the next one.
        scope.launch {
            var first = true
            SettingsStore.observe(context).collect { new ->
                val old = settings
                settings = new
                if (first) {
                    first = false
                } else if (old.audioQuality != new.audioQuality) {
                    applyQualityNow(new.audioQuality)
                }
            }
        }

        // Position ticker — runs a cheap check once a second and only
        // touches state while audio is actually playing.
        scope.launch {
            while (isActive) {
                delay(1000)
                val c = controller ?: continue
                if (c.isPlaying) {
                    val position = c.currentPosition.coerceAtLeast(0)
                    val duration = c.duration.takeIf { it > 0 } ?: _uiState.value.durationMs
                    _uiState.update { it.copy(positionMs = position, durationMs = duration) }
                }
            }
        }
    }

    private fun attachListener(c: MediaController) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val duration = c.duration.takeIf { it > 0 }
                _uiState.update {
                    it.copy(
                        isBuffering = playbackState == Player.STATE_BUFFERING,
                        durationMs = duration ?: it.durationMs,
                    )
                }
                when (playbackState) {
                    // Audio is really flowing — this song's recovery budget is fresh again.
                    Player.STATE_READY -> recoveryAttempts = 0
                    Player.STATE_ENDED -> maybeAutoplayNext()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlayerError(error)
            }
        }
        playerListener = listener
        c.addListener(listener)
    }

    /** App was reopened while the service kept playing: rebuild the UI state
     *  from the live session so the mini player / Now Playing is right away. */
    private fun adoptRunningSession(c: MediaController) {
        if (_uiState.value.currentSong != null) return
        val item = c.currentMediaItem ?: return
        val md = item.mediaMetadata
        val dur = c.duration.takeIf { it > 0 } ?: 0L
        val song = Song(
            id = item.mediaId,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            albumArtUrl = md.artworkUri?.toString(),
            durationSec = (dur / 1000).toInt(),
            source = SongSource.YOUTUBE,
        )
        _uiState.update {
            it.copy(
                currentSong = song,
                queue = listOf(song),
                currentIndex = 0,
                isPlaying = c.isPlaying,
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = dur,
            )
        }
        refreshLikedState(song)
    }

    /** Quality changed in Settings: swap the current song's stream for the
     *  new tier and continue from the same position / play state. */
    private fun applyQualityNow(quality: AudioQuality) {
        val state = _uiState.value
        val song = state.currentSong ?: return
        val c = controller
        if (c != null && !state.isResolving) {
            // Nothing loaded (e.g. last tap failed) or song already finished:
            // nothing to swap, the next play uses the new quality anyway.
            if (c.mediaItemCount == 0 || c.playbackState == Player.STATE_ENDED) return
        }
        val position = if (state.isResolving || c == null) 0L else c.currentPosition.coerceAtLeast(0)
        val keepPlaying = state.isResolving || c?.playWhenReady != false
        val label = when (quality) {
            AudioQuality.LOW -> "Low"
            AudioQuality.NORMAL -> "Normal"
            AudioQuality.HIGH -> "High"
        }
        playJob?.cancel()
        recoveryAttempts = 0
        preferAac = false
        _uiState.update {
            it.copy(isResolving = true, isBuffering = false, playbackError = "Audio quality: $label")
        }
        playJob = scope.launch {
            startPlayback(
                song = song,
                index = state.currentIndex,
                skipAttempt = 0,
                resumeMs = position,
                autoPlay = keepPlaying,
            )
        }
    }

    /** Respects the Autoplay setting: if off, playback simply stops at the
     *  end of the current track. */
    private fun maybeAutoplayNext() {
        if (settings.autoplay) playNext()
    }

    // ── Queue / transport ───────────────────────────────────────────────

    fun playQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        val index = startIndex.coerceIn(0, songs.lastIndex)
        _uiState.update { it.copy(queue = songs, currentIndex = index) }
        playIndex(index)
    }

    /** Clears a shown playback error — call after the UI has displayed it. */
    fun dismissPlaybackError() {
        _uiState.update { it.copy(playbackError = null) }
    }

    private fun playIndex(index: Int, skipAttempt: Int = 0, notice: String? = null) {
        val song = _uiState.value.queue.getOrNull(index) ?: return

        // A newer tap supersedes whatever was still resolving. The old song
        // stops right away so the sound always matches the song shown.
        controller?.pause()
        playJob?.cancel()
        prefetchJob?.cancel()
        recoveryAttempts = 0
        preferAac = false

        _uiState.update {
            it.copy(
                currentSong = song,
                currentIndex = index,
                isResolving = true,
                isBuffering = false,
                playbackError = notice,
                positionMs = 0,
                durationMs = song.durationSec * 1000L,
                isCurrentSongLiked = false,
            )
        }
        refreshLikedState(song)

        playJob = scope.launch { startPlayback(song, index, skipAttempt) }
    }

    /**
     * Resolves and starts [song]. [resumeMs] > 0 continues from that
     * position (stream-recovery); [forceRefresh] throws away any cached URL
     * for the song first.
     */
    private suspend fun startPlayback(
        song: Song,
        index: Int,
        skipAttempt: Int,
        resumeMs: Long = 0L,
        forceRefresh: Boolean = false,
        autoPlay: Boolean = true,
    ) {
        // The controller connects asynchronously; a tap right after launch
        // just waits for it (bounded) instead of failing.
        val c = withTimeoutOrNull(5_000) { controllerReady.await() }
        if (c == null) {
            _uiState.update { it.copy(isResolving = false, playbackError = "Player not ready — try again") }
            return
        }

        if (!NetworkResilience.isOnline(context)) {
            _uiState.update { it.copy(isResolving = false, playbackError = "You're offline — check your connection") }
            return
        }

        val stream = AurumApi.resolveStream(
            song = song,
            quality = settings.audioQuality,
            preferAac = preferAac,
            forceRefresh = forceRefresh,
        )
        if (stream == null) {
            handleUnplayable(song, index, skipAttempt)
            return
        }

        c.setMediaItem(buildMediaItem(song, stream.url), resumeMs)
        c.prepare()
        if (autoPlay) c.play() else c.pause()
        _uiState.update { it.copy(isResolving = false) }

        // Fire-and-forget: history write must never delay or break playback.
        // Only for a fresh start — a quality swap / recovery resume is the
        // same listen, not a new one.
        if (resumeMs == 0L) scope.launch { SyncRepository.logRecentlyPlayed(song) }
        prefetchNext(index)
    }

    private fun buildMediaItem(song: Song, url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaId(song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(song.albumArtUrl?.let { Uri.parse(it) })
                    .build()
            )
            .build()

    /** Resolves the next song's stream in the background so skipping or
     *  auto-advancing starts instantly. */
    private fun prefetchNext(index: Int) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            delay(1_500) // let the current song get its bandwidth first
            _uiState.value.queue.getOrNull(index + 1)?.let { next ->
                AurumApi.prefetch(next, settings.audioQuality)
            }
        }
    }

    /** No playable stream could be resolved: try the next few songs rather
     *  than leaving the UI stuck on a dead one. */
    private fun handleUnplayable(song: Song, index: Int, skipAttempt: Int) {
        _uiState.update { it.copy(isResolving = false) }
        val queue = _uiState.value.queue
        if (skipAttempt < MAX_AUTO_SKIPS && index + 1 < queue.size) {
            playIndex(
                index + 1,
                skipAttempt + 1,
                notice = "Couldn't play \"${song.title}\" — trying next",
            )
        } else {
            _uiState.update {
                it.copy(playbackError = "Couldn't play \"${song.title}\" — check your connection")
            }
        }
    }

    /**
     * A stream failed during playback. Escalating recovery:
     *  1. re-resolve a fresh URL (expired/dead link) and resume in place;
     *  2. same again but force an AAC stream (TV-box decoders that reject Opus);
     *  3. give up on this song and move on.
     */
    private fun handlePlayerError(error: PlaybackException) {
        val song = _uiState.value.currentSong ?: return
        val index = _uiState.value.currentIndex
        val position = controller?.currentPosition?.coerceAtLeast(0) ?: 0L
        recoveryAttempts++

        // Media3 groups codec/container/audio-sink failures in 3xxx-5xxx.
        val codecRelated = error.errorCode in 3000..5999

        if (recoveryAttempts <= 2) {
            if (recoveryAttempts == 2 || codecRelated) preferAac = true
            playJob?.cancel()
            _uiState.update { it.copy(isResolving = true, isBuffering = false) }
            playJob = scope.launch {
                AurumApi.invalidateStream(song)
                startPlayback(song, index, skipAttempt = 0, resumeMs = position, forceRefresh = true)
            }
            return
        }

        _uiState.update { it.copy(isBuffering = false, isResolving = false) }
        val hasNext = index + 1 < _uiState.value.queue.size
        if (hasNext && settings.autoplay) {
            playIndex(index + 1, notice = "Couldn't play \"${song.title}\" — skipping")
        } else {
            _uiState.update { it.copy(playbackError = "Couldn't play \"${song.title}\"") }
        }
    }

    // ── Likes ───────────────────────────────────────────────────────────

    private fun refreshLikedState(song: Song) {
        scope.launch {
            val liked = SyncRepository.isSongLiked(song.id)
            // A slow lookup must not land on a song the user already left.
            if (_uiState.value.currentSong?.id == song.id) {
                _uiState.update { it.copy(isCurrentSongLiked = liked) }
            }
        }
    }

    /** Heart icon on the player: optimistic flip (instant on a remote),
     *  reverted if the write to the shared favorites table fails. */
    fun toggleLikeCurrentSong() {
        val song = _uiState.value.currentSong ?: return
        if (!AuthRepository.isSignedIn) {
            _uiState.update { it.copy(playbackError = "Sign in from the Home screen to like songs") }
            return
        }
        val wasLiked = _uiState.value.isCurrentSongLiked
        _uiState.update { it.copy(isCurrentSongLiked = !wasLiked) }
        scope.launch {
            val ok = if (wasLiked) SyncRepository.unlikeSong(song.id) else SyncRepository.likeSong(song)
            if (!ok && _uiState.value.currentSong?.id == song.id) {
                _uiState.update { it.copy(isCurrentSongLiked = wasLiked) }
            }
        }
    }

    // ── Transport controls ──────────────────────────────────────────────

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun playNext() {
        val state = _uiState.value
        val nextIndex = state.currentIndex + 1
        if (nextIndex < state.queue.size) playIndex(nextIndex) else extendQueueThenPlayNext()
    }

    /** End of queue with Autoplay on: pull more songs by the same artist so
     *  music never just stops (Spotify-style endless queue). */
    private fun extendQueueThenPlayNext() {
        if (!settings.autoplay) return
        val song = _uiState.value.currentSong ?: return
        if (extendJob?.isActive == true) return
        extendJob = scope.launch {
            val artist = song.artist.takeUnless { it.isBlank() || it.equals("Unknown Artist", true) }
            val query = if (artist != null) "$artist songs" else song.title
            val found = runCatching { AurumApi.search(query, 20) }.getOrDefault(emptyList())
            val have = _uiState.value.queue.mapTo(HashSet()) { it.id }
            val fresh = found.filter { it.id !in have }
            // The user may have moved on to another song while this loaded.
            if (fresh.isEmpty() || _uiState.value.currentSong?.id != song.id) return@launch
            _uiState.update { it.copy(queue = it.queue + fresh) }
            playIndex(_uiState.value.currentIndex + 1)
        }
    }

    /** Past the first 3 s (or on the first song) "previous" restarts the
     *  current song, like every other music player. */
    fun playPrevious() {
        val state = _uiState.value
        val c = controller
        if (state.currentIndex == 0 || (c != null && c.currentPosition > 3_000)) {
            seekTo(0)
        } else {
            playIndex(state.currentIndex - 1)
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _uiState.update { it.copy(positionMs = positionMs) }
    }

    fun release() {
        // Cancels the ticker, settings collector and any in-flight resolve.
        scope.cancel()
        playerListener?.let { controller?.removeListener(it) }
        playerListener = null
        controllerReady.complete(null)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
    }

    private companion object {
        /** Songs auto-skipped in a row when none can be resolved. */
        const val MAX_AUTO_SKIPS = 3
    }
}
