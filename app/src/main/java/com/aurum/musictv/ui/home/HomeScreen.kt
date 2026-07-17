package com.aurum.musictv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item { TopBar(greetingName = state.greetingName, onSearchClick = onSearchClick) }

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

        if (state.trending.isNotEmpty()) {
            item {
                SongRow(
                    title = "Trending",
                    songs = state.trending,
                    onSongClick = { onSongClick(it, state.trending) },
                )
            }
        }

        if (state.newReleases.isNotEmpty()) {
            item {
                SongRow(
                    title = "New Releases",
                    songs = state.newReleases,
                    onSongClick = { onSongClick(it, state.newReleases) },
                )
            }
        }
    }
}

@Composable
private fun TopBar(greetingName: String?, onSearchClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (greetingName != null) "Hi, $greetingName" else "Aurum",
            color = AurumColors.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(AurumColors.AmoledBgSurface)
                .clickable(onClick = onSearchClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("Search", color = AurumColors.TextPrimary, fontSize = 14.sp)
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
