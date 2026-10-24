package com.aurum.musictv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.tv.material3.Text
import com.aurum.musictv.settings.AppSettings
import com.aurum.musictv.settings.SettingsStore
import com.aurum.musictv.ui.player.NowPlayingBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.aurum.musictv.ui.util.LocalReduceMotion
import com.aurum.musictv.ui.util.TvScale
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.player.PlayerManager
import com.aurum.musictv.data.remote.SupabaseClientProvider
import com.aurum.musictv.sync.AuthRepository
import com.aurum.musictv.telemetry.DeviceTelemetry
import com.aurum.musictv.ui.update.UpdateDialogHost
import com.aurum.musictv.update.UpdateManager
import com.aurum.musictv.ui.auth.AuthScreen
import com.aurum.musictv.ui.components.BannerData
import com.aurum.musictv.ui.components.TopBanner
import com.aurum.musictv.ui.home.HomeScreen
import com.aurum.musictv.ui.home.HomeViewModel
import com.aurum.musictv.ui.library.LibraryScreen
import com.aurum.musictv.ui.nav.NavDestination
import com.aurum.musictv.ui.nav.SidebarNav
import com.aurum.musictv.ui.player.PlayerScreen
import com.aurum.musictv.ui.search.SearchScreen
import com.aurum.musictv.ui.settings.SettingsScreen
import com.aurum.musictv.ui.theme.AurumColors
import com.aurum.musictv.ui.theme.AurumTvTheme

/**
 * Single-Activity app, one PlayerManager instance for the whole lifecycle
 * (created here, not per-screen — see PlayerManager kdoc). Screen state is
 * a simple sealed enum instead of Navigation-Compose: the screen count is
 * still small enough that pulling in the whole Nav library would be dead
 * weight for this app's actual complexity.
 */
class MainActivity : ComponentActivity() {

    private lateinit var playerManager: PlayerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerManager = (application as AurumTvApp).playerManager

        setContent {
            TvScale {
                AurumTvTheme {
                    MotionProvider {
                        AurumApp(playerManager = playerManager)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // User may have just allowed "Install unknown apps" for the update.
        UpdateManager.onResume(this)
    }

}

private sealed class Screen {
    data object Auth : Screen()
    data object Home : Screen()
    data object Search : Screen()
    data object Library : Screen()
    data object Player : Screen()
    data object Settings : Screen()
}

/** Which sidebar icon should read as "selected" for a given screen — Auth/
 *  Player/Settings aren't sidebar destinations themselves, so they fall
 *  back to whichever main tab the user came from conceptually (Home). */
private fun Screen.toNavDestinationOrNull(): NavDestination? = when (this) {
    Screen.Home -> NavDestination.HOME
    Screen.Search -> NavDestination.SEARCH
    Screen.Library -> NavDestination.LIBRARY
    else -> null
}

@Composable
private fun AurumApp(playerManager: PlayerManager) {
    val context = LocalContext.current

    // Live settings (open-player-on-tap, reduce motion). Changes rarely, so
    // collecting it here does not cause per-second recomposition.
    val settings by remember { SettingsStore.observe(context.applicationContext) }
        .collectAsState(initial = AppSettings())

    // Login is optional — app always starts at Home so playback works
    // without signing in.
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    // Where the full player was opened from, so Back returns there.
    var returnTo by remember { mutableStateOf<Screen>(Screen.Home) }
    val homeViewModel: HomeViewModel = viewModel()
    val homeListState = rememberLazyListState()
    var banner by remember { mutableStateOf<BannerData?>(null) }

    // Only the "is there a current song" bit is observed here — the full
    // PlayerUiState ticks every second and must not recompose the whole app.
    val hasSong by remember(playerManager) {
        playerManager.uiState.map { it.currentSong != null }.distinctUntilChanged()
    }.collectAsState(initial = false)

    LaunchedEffect(screen) {
        homeViewModel.setHomeVisible(screen is Screen.Home)
    }

    // Background housekeeping, deliberately late so it never competes with
    // Home loading on a weak box: (1) look for an app update (max once / 6 h),
    // (2) tell the backend this TV exists + which account is connected
    // (max once / 12 h, again only when the account or version changes).
    LaunchedEffect(Unit) {
        delay(5_000)
        val appCtx = context.applicationContext
        UpdateManager.checkOnLaunch(appCtx)
        AuthRepository.awaitReady()
        AuthRepository.sessionStatus
            .map { SupabaseClientProvider.auth.currentUserOrNull()?.id }
            .distinctUntilChanged()
            .collect { DeviceTelemetry.ping(appCtx) }
    }

    fun openPlayer() {
        if (screen !is Screen.Player) {
            returnTo = screen
            screen = Screen.Player
        }
    }

    /** Spotify behavior: a tap starts playback and shows the mini player;
     *  the full player opens only when asked (or if the setting is on). */
    fun play(song: Song, queue: List<Song>) {
        val startIndex = queue.indexOf(song).coerceAtLeast(0)
        playerManager.playQueue(queue, startIndex)
        homeViewModel.onSongPlayed(song)
        if (settings.openPlayerOnTap) openPlayer()
    }

    val showSidebar = screen is Screen.Home || screen is Screen.Search || screen is Screen.Library
    val showBar = hasSong && (showSidebar || screen is Screen.Settings)

    // Back: player -> wherever it was opened from; everything else -> Home;
    // Home -> system default (leaves the app, music keeps playing).
    BackHandler(enabled = screen != Screen.Home) {
        screen = if (screen is Screen.Player) returnTo else Screen.Home
    }

    Box(modifier = Modifier.fillMaxSize().background(AurumColors.AmoledBg)) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (showSidebar) {
                SidebarNav(
                    current = screen.toNavDestinationOrNull() ?: NavDestination.HOME,
                    onNavigate = { destination ->
                        screen = when (destination) {
                            NavDestination.HOME -> Screen.Home
                            NavDestination.SEARCH -> Screen.Search
                            NavDestination.LIBRARY -> Screen.Library
                        }
                    },
                    showNowPlaying = hasSong,
                    onNowPlaying = { openPlayer() },
                )
            }

            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        is Screen.Auth -> AuthScreen(
                            onSignedIn = {
                                homeViewModel.refreshAuthState()
                                screen = Screen.Home
                                val name = AuthRepository.displayName
                                banner = BannerData(
                                    title = if (name != null) "Signed in as $name" else "Signed in",
                                    subtitle = "Your Astra library is now synced to this TV",
                                    name = name,
                                    avatarUrl = AuthRepository.avatarUrl,
                                )
                            },
                            onSignedOut = {
                                banner = BannerData(
                                    title = "Signed out",
                                    subtitle = "Music still works - sign in anytime from the profile icon",
                                )
                            },
                            onDismiss = {
                                homeViewModel.refreshAuthState()
                                screen = Screen.Home
                            },
                        )

                        is Screen.Home -> HomeScreen(
                            viewModel = homeViewModel,
                            onSongClick = { song, queue -> play(song, queue) },
                            onSearchClick = { screen = Screen.Search },
                            onProfileClick = { screen = Screen.Auth },
                            onSettingsClick = { screen = Screen.Settings },
                            listState = homeListState,
                            reduceMotion = settings.reduceMotion,
                        )

                        is Screen.Search -> SearchScreen(
                            onSongClick = { song, results -> play(song, results) },
                        )

                        is Screen.Library -> LibraryScreen(
                            onSongClick = { song, results -> play(song, results) },
                        )

                        is Screen.Player -> PlayerScreen(
                            playerManager = playerManager,
                            onBack = { screen = returnTo },
                        )

                        is Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
                    }
                }
                if (showBar) {
                    NowPlayingBar(
                        playerManager = playerManager,
                        onOpen = { openPlayer() },
                    )
                }
            }
        }

        PlaybackNoticeHost(playerManager)
        TopBanner(
            data = banner,
            onDone = { banner = null },
            modifier = Modifier.align(Alignment.TopCenter),
        )
        UpdateDialogHost()
    }
}

/** App-wide toast for playback feedback (errors, auto-skip, quality swap) —
 *  global so it shows on every screen, not only the full player. */
@Composable
private fun PlaybackNoticeHost(playerManager: PlayerManager) {
    val message by remember(playerManager) {
        playerManager.uiState.map { it.playbackError }.distinctUntilChanged()
    }.collectAsState(initial = null)
    val text = message ?: return

    LaunchedEffect(text) {
        delay(3000)
        playerManager.dismissPlaybackError()
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(AurumColors.AmoledBgElevated)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(text, color = AurumColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Publishes the "Reduce motion" setting to every card/banner below it. */
@Composable
private fun MotionProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val reduce by remember {
        SettingsStore.observe(context).map { it.reduceMotion }.distinctUntilChanged()
    }.collectAsState(initial = false)
    CompositionLocalProvider(LocalReduceMotion provides reduce, content = content)
}
