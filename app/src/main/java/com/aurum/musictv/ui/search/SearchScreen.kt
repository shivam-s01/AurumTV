package com.aurum.musictv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.ui.components.SongCard
import com.aurum.musictv.ui.theme.AurumColors
import kotlinx.coroutines.delay

/**
 * Uses BasicTextField (compose-foundation, always available) rather than
 * a Material3 TextField -- androidx.tv:tv-material intentionally doesn't
 * ship a full text-field component (TV input is D-pad/remote driven, and
 * most search entry happens via the system's built-in voice/keyboard
 * overlay), so this stays in foundation-land instead of guessing at an
 * API that may not exist.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = viewModel(),
    onSongClick: (Song, List<Song>) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }

    // Debounced search -- waits 400ms after the user stops typing on the
    // TV's on-screen keyboard before firing a network call, so every
    // keystroke doesn't trigger a Worker request.
    LaunchedEffect(query) {
        if (query.length < 2) return@LaunchedEffect
        delay(400)
        viewModel.search(query)
    }

    Column(modifier = Modifier.fillMaxSize().padding(48.dp)) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            textStyle = TextStyle(color = AurumColors.TextPrimary, fontSize = 20.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(AurumColors.Gold),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AurumColors.AmoledBgSurface)
                .padding(16.dp),
            decorationBox = { innerTextField ->
                if (query.isEmpty()) {
                    Text("Search songs, artists, albums", color = AurumColors.TextSecondary, fontSize = 20.sp)
                }
                innerTextField()
            },
        )

        Column(modifier = Modifier.padding(top = 24.dp)) {
            if (state.results.isEmpty() && !state.isLoading && query.isNotEmpty()) {
                Text("No results", color = AurumColors.TextSecondary)
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.results, key = { it.id }) { song ->
                    SongCard(song = song, onClick = { onSongClick(song, state.results) })
                }
            }
        }
    }
}
