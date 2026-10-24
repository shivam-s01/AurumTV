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
    val shuffleOn: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** Selected sleep-timer length in minutes, 0 = off. */
    val sleepMinutes: Int = 0,
)

enum class RepeatMode { OFF, ALL, ONE }

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
    private var upcomingJob: Job? = null
    private var sleepJob: Job? = null
    private var originalQueue: List<Song>? = null

    // Recovery state for the CURRENT song (reset on every new song).
    private var recoveryAttempts = 0
    private var preferAac = false

    init {
        // Remote / headset / notification Next-Previous keys land here (see
        // QueueAwarePlayer in the playback service), so the queue works even
        // when the app UI is closed.
        MediaKeyBridge.onNext = { playNext() }
        MediaKeyBridge.onPrevious = { playPrevious() }

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
        val repeat = _uiState.value.repeatMode
        when {
            repeat == RepeatMode.ONE -> playIndex(_uiState.value.currentIndex)
            settings.autoplay || repeat == RepeatMode.ALL -> playNext()
        }
    }

    // ── Queue / transport ───────────────────────────────────────────────

    fun playQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        val index = startIndex.coerceIn(0, songs.lastIndex)

        // Same song already loaded and healthy: keep it playing (resume if
        // paused) instead of re-resolving and restarting it from 0:00. A
        // failed / ended / still-resolving song falls through and retries.
        val now = _uiState.value
        val c = controller
        val target = songs[index]
        if (now.currentSong?.id == target.id && !now.isResolving && now.playbackError == null &&
            c != null && c.mediaItemCount > 0 &&
            c.playbackState != Player.STATE_IDLE && c.playbackState != Player.STATE_ENDED
        ) {
            // With shuffle on the (already shuffled) queue is kept as is.
            if (!now.shuffleOn) {
                _uiState.update { it.copy(queue = songs, currentIndex = index) }
            }
            if (!c.isPlaying) c.play()
            ensureUpcoming(_uiState.value.currentIndex)
            return
        }

        if (_uiState.value.shuffleOn) {
            // Shuffle stays on across taps: tapped song first, rest shuffled.
            originalQueue = songs
            val shuffled = listOf(songs[index]) + songs.filterIndexed { i, _ -> i != index }.shuffled()
            _uiState.update { it.copy(queue = shuffled, currentIndex = 0) }
            playIndex(0)
        } else {
            originalQueue = null
            _uiState.update { it.copy(queue = songs, currentIndex = index) }
            playIndex(index)
        }
    }

    /** Jump to a song already in the queue (Queue panel in the full player). */
    fun playAt(index: Int) {
        if (index in _uiState.value.queue.indices) playIndex(index)
    }

    fun toggleShuffle() {
        val s = _uiState.value
        val current = s.currentSong
        if (!s.shuffleOn) {
            if (current == null || s.queue.size < 2) {
                _uiState.update { it.copy(shuffleOn = true) }
                return
            }
            originalQueue = s.queue
            val rest = s.queue.filterIndexed { i, _ -> i != s.currentIndex }.shuffled()
            _uiState.update { it.copy(shuffleOn = true, queue = listOf(current) + rest, currentIndex = 0) }
        } else {
            val orig = originalQueue
            originalQueue = null
            val idx = if (orig != null && current != null) orig.indexOfFirst { it.id == current.id } else -1
            if (orig != null && idx >= 0) {
                _uiState.update { it.copy(shuffleOn = false, queue = orig, currentIndex = idx) }
            } else {
                _uiState.update { it.copy(shuffleOn = false) }
            }
        }
        prefetchNext(_uiState.value.currentIndex)
    }

    fun cycleRepeat() {
        _uiState.update {
            it.copy(
                repeatMode = when (it.repeatMode) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                },
            )
        }
    }

    /** Sleep timer: pauses playback after [minutes] (0 cancels). */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        _uiState.update {
            it.copy(
                sleepMinutes = minutes,
                playbackError = if (minutes > 0) "Sleep timer: $minutes min" else "Sleep timer off",
            )
        }
        if (minutes <= 0) return
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            _uiState.update { it.copy(sleepMinutes = 0, playbackError = "Sleep timer — music paused") }
        }
    }

    fun cycleSleepTimer() {
        setSleepTimer(
            when (_uiState.value.sleepMinutes) {
                0 -> 15
                15 -> 30
                30 -> 60
                else -> 0
            }
        )
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
        ensureUpcoming(index)
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
        when {
            nextIndex < state.queue.size -> playIndex(nextIndex)
            state.repeatMode == RepeatMode.ALL && state.queue.isNotEmpty() -> playIndex(0)
            else -> extendQueueThenPlayNext()
        }
    }

    /** Songs YouTube's radio suggests after [song], minus what is queued. */
    private suspend fun fetchFresh(song: Song): List<Song> {
        val have = _uiState.value.queue.mapTo(HashSet()) { it.id }
        return runCatching { AurumApi.related(song, exclude = have, limit = 25) }
            .getOrDefault(emptyList())
    }

    /** Keeps at least a few songs queued after the current one (Autoplay on):
     *  "Up next" is filled in advance, the next stream is prefetched, and the
     *  end of the queue never waits on a network call. */
    private fun ensureUpcoming(index: Int) {
        if (!settings.autoplay) return
        val s = _uiState.value
        if (s.queue.size - index - 1 >= 3) return
        val song = s.queue.getOrNull(index) ?: return
        // Restart for the newest song: a quick skip must not leave the new
        // song without a topped-up queue because the old job was still waiting.
        upcomingJob?.cancel()
        upcomingJob = scope.launch {
            delay(2_500) // let the current song's stream start first
            val fresh = fetchFresh(song)
            if (fresh.isEmpty()) return@launch
            _uiState.update { st ->
                if (st.currentSong?.id != song.id) st
                else {
                    val have = st.queue.mapTo(HashSet()) { it.id }
                    st.copy(queue = st.queue + fresh.filter { it.id !in have })
                }
            }
            prefetchNext(_uiState.value.currentIndex)
        }
    }

    /** Next pressed (or song ended) at the end of the queue: pull YouTube's
     *  radio for the current song and continue with it, so music never just
     *  stops. Autoplay-off is respected by the callers that advance on their
     *  own; an explicit Next press always works. */
    private fun extendQueueThenPlayNext() {
        val song = _uiState.value.currentSong ?: return
        upcomingJob?.cancel()
        if (extendJob?.isActive == true) return
        extendJob = scope.launch {
            val fresh = fetchFresh(song)
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
        val duration = _uiState.value.durationMs
        val target = if (duration > 0) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        controller?.seekTo(target)
        _uiState.update { it.copy(positionMs = target) }
    }

    /** D-pad left/right on the seek bar: jump relative to the live position. */
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        if (_uiState.value.isResolving) return
        seekTo(c.currentPosition + deltaMs)
    }

    fun release() {
        MediaKeyBridge.onNext = null
        MediaKeyBridge.onPrevious = null
        sleepJob?.cancel()
        upcomingJob?.cancel()
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
