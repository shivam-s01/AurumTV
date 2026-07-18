package com.aurum.musictv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.ui.components.SongCard
import com.aurum.musictv.ui.theme.AurumColors

/**
 * Single top-level LazyColumn containing horizontal LazyRows -- NOT
 * nested LazyLists inside LazyLists inside LazyLists (the perf
 * requirement calls this out explicitly). Each row is its own composable
 * function so recomposition of one row (e.g. after a realtime update)
 * never touches the others.
 */
@Composable
fun HomeScreen(
    onSongClick: (Song, List<Song>) -> Unit,
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Hero spotlights the first song of the first non-empty section (or
    // Continue Listening if present) — mirrors Spotify TV's "big banner
    // up top, rows below" layout without a separate curated-hero API.
    val heroSong = state.continueListening ?: state.sections.firstOrNull()?.second?.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AurumColors.AmoledBg),
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        item {
            Box {
                heroSong?.let {
                    HeroBanner(
                        song = it,
                        onPlayClick = {
                            val list = state.continueListening?.let { c -> listOf(c) }
                                ?: state.sections.firstOrNull()?.second ?: listOf(it)
                            onSongClick(it, list)
                        },
                    )
                }
                TopBar(
                    greetingName = state.greetingName,
                    avatarUrl = state.avatarUrl,
                    onSearchClick = onSearchClick,
                    onProfileClick = onProfileClick,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }

        state.remoteNowPlaying?.let { remote ->
            item {
                ResumeOnTvBanner(
                    title = remote.songData?.title ?: "Something",
                    onDismiss = viewModel::dismissRemoteNowPlaying,
                )
            }
        }

        state.continueListening?.let { song ->
            item {
                SongRow(
                    title = "Continue Listening",
                    songs = listOf(song),
                    onSongClick = { onSongClick(it, listOf(song)) },
                )
            }
        }

        if (state.recentlyPlayed.isNotEmpty()) {
            item {
                SongRow(
                    title = "Jump Back In",
                    songs = state.recentlyPlayed,
                    onSongClick = { onSongClick(it, state.recentlyPlayed) },
                )
            }
        }

        if (state.likedSongs.isNotEmpty()) {
            item {
                SongRow(
                    title = "Liked Songs",
                    songs = state.likedSongs,
                    onSongClick = { onSongClick(it, state.likedSongs) },
                )
            }
        }

        // Every section the API returns (Trending, New Releases, Made
        // For You, Top Charts, Bollywood Hits, ...) — driven entirely by
        // AurumApi.homeSections(), so adding a row is a backend change,
        // not a client one.
        items(state.sections, key = { it.first }) { (title, songs) ->
            SongRow(
                title = title,
                songs = songs,
                onSongClick = { onSongClick(it, songs) },
            )
        }
    }
}

/**
 * Full-bleed hero at the top of Home — large backdrop art with a
 * gradient scrim for text legibility, title, and a Play button. This is
 * what makes the landing page feel like a premium streaming service
 * instead of a plain grid of rows.
 */
@Composable
private fun HeroBanner(song: Song, onPlayClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp),
    ) {
        coil.compose.AsyncImage(
            model = song.albumArtUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.15f),
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                            AurumColors.AmoledBg,
                        ),
                        startY = 0f,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            Text(
                text = "PLAYING NOW",
                color = AurumColors.Gold,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = song.title,
                color = AurumColors.TextPrimary,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = song.artist,
                color = AurumColors.TextSecondary,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AurumColors.Gold)
                    .clickable(onClick = onPlayClick)
                    .padding(horizontal = 32.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.tv.material3.Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = AurumColors.AmoledBg,
                    modifier = Modifier.size(22.dp),
                )
                Text("Play", color = AurumColors.AmoledBg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TopBar(
    greetingName: String?,
    avatarUrl: String?,
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = if (greetingName != null) "Hi, $greetingName" else "Aurum",
            color = AurumColors.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AurumColors.AmoledBgSurface)
                    .clickable(onClick = onSearchClick)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text("Search", color = AurumColors.TextPrimary, fontSize = 14.sp)
            }
            ProfileAvatar(avatarUrl = avatarUrl, onClick = onProfileClick)
        }
    }
}

/**
 * Spotify-TV-style profile icon: shows the signed-in user's Google photo
 * when available, otherwise a plain default avatar. Never blocks anything
 * — tapping it is the ONLY way to reach sign-in; browsing/playback never
 * require it.
 */
@Composable
private fun ProfileAvatar(avatarUrl: String?, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(40.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(AurumColors.AmoledBgSurface)
            .clickable(onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        if (avatarUrl != null) {
            coil.compose.AsyncImage(
                model = avatarUrl,
                contentDescription = "Profile",
                modifier = Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            androidx.tv.material3.Text(
                text = "\uD83D\uDC64",
                color = AurumColors.TextSecondary,
                modifier = Modifier,
            )
        }
    }
}

@Composable
private fun ResumeOnTvBanner(title: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Playing on phone: $title",
            color = AurumColors.Gold,
            fontSize = 16.sp,
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("Dismiss", color = AurumColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SongRow(title: String, songs: List<Song>, onSongClick: (Song) -> Unit) {
    Column {
        Text(
            text = title,
            color = AurumColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(songs, key = { it.id }) { song ->
                SongCard(song = song, onClick = { onSongClick(song) })
            }
        }
    }
}
