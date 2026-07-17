package com.aurum.musictv.ui.player

import android.os.Bundle
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import coil.load
import com.aurum.musictv.R
import com.aurum.musictv.data.Song
import com.aurum.musictv.data.SongSource
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * TV equivalent of full_player_screen.dart, stripped to essentials:
 * artwork, title, artist, play/pause via D-pad center, seek via D-pad
 * left/right. No lyrics tab, no queue panel, no gradient morph background
 * — those are later stages once base playback is confirmed rock-solid on
 * real 1GB-RAM hardware.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SONG_ID = "song_id"
        const val EXTRA_SONG_TITLE = "song_title"
        const val EXTRA_SONG_ARTIST = "song_artist"
        const val EXTRA_SONG_ART = "song_art"
        const val EXTRA_SONG_SOURCE = "song_source"
    }

    private val viewModel: PlayerViewModel by viewModels()

    private lateinit var playPauseIcon: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val song = Song(
            id = intent.getStringExtra(EXTRA_SONG_ID) ?: return finish(),
            title = intent.getStringExtra(EXTRA_SONG_TITLE) ?: "",
            artist = intent.getStringExtra(EXTRA_SONG_ARTIST) ?: "",
            albumArtUrl = intent.getStringExtra(EXTRA_SONG_ART),
            durationSec = 0,
            source = SongSource.valueOf(
                intent.getStringExtra(EXTRA_SONG_SOURCE) ?: SongSource.SAAVN.name
            ),
        )

        findViewById<TextView>(R.id.player_title).text = song.title
        findViewById<TextView>(R.id.player_artist).text = song.artist
        findViewById<ImageView>(R.id.player_art).load(song.albumArtUrl) {
            crossfade(true)
        }

        playPauseIcon = findViewById(R.id.player_play_pause_icon)
        progressBar = findViewById(R.id.player_progress)
        positionText = findViewById(R.id.player_position)
        durationText = findViewById(R.id.player_duration)

        viewModel.bindSession(this)
        lifecycleScope.launch {
            viewModel.play(song)
        }
        observePlaybackState()
    }

    private fun observePlaybackState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    playPauseIcon.setImageResource(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    if (state.durationMs > 0) {
                        progressBar.progress =
                            ((state.positionMs * 1000) / state.durationMs).toInt()
                    }
                    positionText.text = formatMs(state.positionMs)
                    durationText.text = formatMs(state.durationMs)
                }
            }
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    /** TV remotes send D-pad presses as key events, not touch — this is
     *  the standard way to handle remote input on Android TV when you're
     *  not using Leanback's playback UI widgets. DPAD_CENTER and
     *  ENTER both map to the remote's "OK" button depending on the box. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                viewModel.togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                viewModel.seekForward()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                viewModel.seekBackward()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                viewModel.togglePlayPause()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        viewModel.unbindSession()
        super.onDestroy()
    }
}
