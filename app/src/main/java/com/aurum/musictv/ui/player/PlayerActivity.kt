package com.aurum.musictv.ui.player

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.aurum.musictv.R
import com.aurum.musictv.data.Song
import com.aurum.musictv.data.SongSource
import kotlinx.coroutines.launch

/**
 * TV equivalent of full_player_screen.dart, stripped to essentials:
 * artwork, title, artist, play/pause via D-pad center, seek via D-pad
 * left/right. No lyrics tab, no queue panel, no gradient morph background
 * — those are Stage 2 once base playback is confirmed rock-solid on
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

        viewModel.bindSession(this)
        lifecycleScope.launch {
            viewModel.play(song)
        }
    }

    override fun onDestroy() {
        viewModel.unbindSession()
        super.onDestroy()
    }
}
