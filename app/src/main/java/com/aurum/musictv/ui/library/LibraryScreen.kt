package com.aurum.musictv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.ui.components.SongCard
import com.aurum.musictv.ui.util.adaptiveClickable
import com.aurum.musictv.ui.util.isTouchDevice
import com.aurum.musictv.ui.util.rememberClickFocusRequester
import com.aurum.musictv.ui.theme.AurumColors

/**
 * Dedicated Library screen — Spotify-TV's "Your Library" equivalent.
 * Two tabs (Liked Songs / Recently Played) over the same data the old
 * Home-row sections showed, just given its own permanent home behind the
 * sidebar's Library icon instead of only surfacing as rows mixed in among
 * discovery content.
 */
@Composable
fun LibraryScreen(
    onSongClick: (Song, List<Song>) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    val openPlaylist = state.playlists.firstOrNull { it.id == state.openPlaylistId }
    BackHandler(enabled = openPlaylist != null) { viewModel.openPlaylist(null) }

    val showPlaylistList = state.tab == LibraryTab.PLAYLISTS && openPlaylist == null
    val activeList = when {
        state.tab == LibraryTab.LIKED_SONGS -> state.likedSongs
        state.tab == LibraryTab.RECENTLY_PLAYED -> state.recentlyPlayed
        else -> openPlaylist?.songs ?: emptyList()
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AurumColors.AmoledBg).padding(32.dp),
    ) {
        Text("Your Library", color = AurumColors.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TabChip(
                label = "Liked Songs",
                selected = state.tab == LibraryTab.LIKED_SONGS,
                onClick = { viewModel.selectTab(LibraryTab.LIKED_SONGS) },
            )
            TabChip(
                label = "Recently Played",
                selected = state.tab == LibraryTab.RECENTLY_PLAYED,
                onClick = { viewModel.selectTab(LibraryTab.RECENTLY_PLAYED) },
            )
            TabChip(
                label = "Playlists",
                selected = state.tab == LibraryTab.PLAYLISTS,
                onClick = { viewModel.selectTab(LibraryTab.PLAYLISTS) },
            )
        }

        when {
            !state.isSignedIn -> Box(
                modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Sign in with your phone's account (profile icon on Home) to see your library",
                    color = AurumColors.TextSecondary,
                    fontSize = 16.sp,
                )
            }

            showPlaylistList && state.playlists.isNotEmpty() -> Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.playlists.forEach { pl ->
                    TabChip(
                        label = "${pl.name}  ·  ${pl.songs.size} songs",
                        selected = false,
                        onClick = { viewModel.openPlaylist(pl.id) },
                    )
                }
            }

            state.isLoading -> Box(
                modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading your library…", color = AurumColors.TextSecondary, fontSize = 16.sp)
            }

            activeList.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (state.tab) {
                        LibraryTab.LIKED_SONGS -> "Songs you like will appear here"
                        LibraryTab.RECENTLY_PLAYED -> "Songs you play will appear here"
                        LibraryTab.PLAYLISTS -> "Playlists from your phone will appear here"
                    },
                    color = AurumColors.TextSecondary,
                    fontSize = 16.sp,
                )
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(activeList, key = { it.id }) { song ->
                    SongCard(
                        song = song,
                        onClick = { onSongClick(song, activeList) },
                        size = androidx.compose.ui.unit.Dp.Unspecified,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val touchFocusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        modifier = Modifier.adaptiveClickable(touchFocusRequester, isTouch, onClick),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AurumColors.Gold else AurumColors.AmoledBgSurface,
            focusedContainerColor = if (selected) AurumColors.GoldLight else AurumColors.AmoledBgElevated,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, AurumColors.Gold),
                shape = RoundedCornerShape(20.dp),
            ),
        ),
    ) {
        Text(
            label,
            color = if (selected) AurumColors.AmoledBg else AurumColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}
