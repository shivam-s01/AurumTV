package com.aurum.musictv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.aurum.musictv.settings.SettingsStore
import com.aurum.musictv.ui.util.adaptiveClickable
import com.aurum.musictv.ui.util.isTouchDevice
import com.aurum.musictv.ui.util.rememberClickFocusRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.focus.onFocusChanged
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
 * API that may not exist. A manual focus-aware border is added around it
 * (see isFieldFocused below) since BasicTextField has no focus ring of
 * its own — without this a D-pad user has no visual cue the search box
 * is the currently focused element.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = viewModel(),
    onSongClick: (Song, List<Song>) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }
    var isFieldFocused by remember { mutableStateOf(false) }
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val recents by remember { SettingsStore.observeRecentSearches(context) }
        .collectAsState(initial = emptyList())

    // Live search: 250ms after the user stops typing — tight enough to
    // feel instant (this is the "top-level algorithm, ekdam premium"
    // live-search bar), loose enough that a burst of D-pad/on-screen-
    // keyboard input doesn't fire a network call per keystroke. Clearing
    // the box drops results immediately instead of waiting on a debounce
    // that's about to be cancelled anyway.
    LaunchedEffect(query) {
        if (query.isEmpty()) {
            viewModel.clear()
            return@LaunchedEffect
        }
        delay(250)
        viewModel.search(query)
    }

    Column(modifier = Modifier.fillMaxSize().padding(48.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AurumColors.AmoledBgSurface)
                .then(
                    if (isFieldFocused) {
                        Modifier.background(AurumColors.AmoledBgElevated)
                    } else {
                        Modifier
                    }
                ),
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                textStyle = TextStyle(color = AurumColors.TextPrimary, fontSize = 20.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(AurumColors.Gold),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFieldFocused = it.isFocused }
                    .padding(16.dp),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text("Search songs, artists, albums", color = AurumColors.TextSecondary, fontSize = 20.sp)
                    }
                    innerTextField()
                },
            )
        }

        Column(modifier = Modifier.padding(top = 24.dp)) {
            // Empty box: typing on a remote is slow, so offer one-click
            // searches (your last ones first, then popular ones).
            if (query.isEmpty()) {
                if (recents.isNotEmpty()) {
                    ChipSection("Recent searches", recents) { query = it }
                }
                ChipSection("Popular searches", POPULAR_SEARCHES) { query = it }
            }
            if (state.isLoading) {
                Text("Searching…", color = AurumColors.Gold, fontSize = 14.sp)
            } else if (state.results.isEmpty() && query.isNotEmpty()) {
                Text("No results", color = AurumColors.TextSecondary)
                // FIX: previously any failure (dead Worker, timeout, DNS)
                // rendered identically to a genuine "no matches" — same
                // silent-swallow bug as Home. Now shows the actual reason
                // underneath so it's obvious this is a connectivity/server
                // problem, not just an obscure query.
                state.loadError?.let { err ->
                    Text(err, color = AurumColors.TextSecondary, fontSize = 12.sp)
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.results, key = { it.id }) { song ->
                    SongCard(
                        song = song,
                        onClick = {
                            scope.launch { SettingsStore.addRecentSearch(context, query) }
                            onSongClick(song, state.results)
                        },
                        size = androidx.compose.ui.unit.Dp.Unspecified,
                    )
                }
            }
        }
    }
}

private val POPULAR_SEARCHES = listOf(
    "Arijit Singh", "Punjabi hits 2026", "Bollywood party", "Lofi chill",
    "90s romantic", "AP Dhillon", "Sad songs hindi", "Workout hits",
)

@Composable
private fun ChipSection(title: String, items: List<String>, onPick: (String) -> Unit) {
    Text(
        title,
        color = AurumColors.TextSecondary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
    LazyRow(
        contentPadding = PaddingValues(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rowItems(items, key = { it }) { label -> QueryChip(label) { onPick(label) } }
    }
}

@Composable
private fun QueryChip(label: String, onClick: () -> Unit) {
    val focus = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        modifier = Modifier.adaptiveClickable(focus, isTouch, onClick),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AurumColors.AmoledBgSurface,
            focusedContainerColor = AurumColors.AmoledBgElevated,
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
            color = AurumColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}
