package com.aurum.musictv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.player.PlayerManager
import com.aurum.musictv.sync.AuthRepository
import com.aurum.musictv.ui.auth.AuthScreen
import com.aurum.musictv.ui.home.HomeScreen
import com.aurum.musictv.ui.player.PlayerScreen
import com.aurum.musictv.ui.search.SearchScreen
import com.aurum.musictv.ui.theme.AurumTvTheme

/**
 * Single-Activity app, one PlayerManager instance for the whole lifecycle
 * (created here, not per-screen — see PlayerManager kdoc). Screen state is
 * a simple sealed enum instead of Navigation-Compose: only 4 screens,
 * pulling in the whole Nav library would be dead weight for this app's
 * actual complexity.
 */
class MainActivity : ComponentActivity() {

    private lateinit var playerManager: PlayerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerManager = PlayerManager(applicationContext)

        setContent {
            AurumTvTheme {
                AurumApp(playerManager = playerManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playerManager.release()
    }
}

private sealed class Screen {
    data object Auth : Screen()
    data object Home : Screen()
    data object Search : Screen()
    data object Player : Screen()
}

@Composable
private fun AurumApp(playerManager: PlayerManager) {
    var screen by remember {
        mutableStateOf<Screen>(if (AuthRepository.isSignedIn) Screen.Home else Screen.Auth)
    }

    when (val current = screen) {
        is Screen.Auth -> AuthScreen(onSignedIn = { screen = Screen.Home })

        is Screen.Home -> HomeScreen(
            onSongClick = { song, queue ->
                val startIndex = queue.indexOf(song).coerceAtLeast(0)
                playerManager.playQueue(queue, startIndex)
                screen = Screen.Player
            },
            onSearchClick = { screen = Screen.Search },
        )

        is Screen.Search -> SearchScreen(
            onSongClick = { song, results ->
                val startIndex = results.indexOf(song).coerceAtLeast(0)
                playerManager.playQueue(results, startIndex)
                screen = Screen.Player
            },
        )

        is Screen.Player -> PlayerScreen(playerManager = playerManager)
    }
}
