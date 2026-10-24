package com.aurum.musictv.ui.player

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.QueueMusic
import com.aurum.musictv.data.model.Song
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import com.aurum.musictv.player.RepeatMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.aurum.musictv.ui.util.adaptiveClickable
import com.aurum.musictv.ui.util.isTouchDevice
import com.aurum.musictv.ui.util.rememberClickFocusRequester
import com.aurum.musictv.player.PlayerManager
import com.aurum.musictv.ui.theme.AurumColors

/**
 * Progress bar and controls are built from plain foundation Box/Row for
 * layout, but every actionable control is a tv-material Surface (not a
 * bare Modifier.clickable) — that's what gives D-pad users a visible
 * focus ring per control. A plain clickable Box has no focus indicator on
 * TV, which made these buttons effectively invisible to remote
 * navigation (you could press the wrong one and not know it).
 */
@Composable
fun PlayerScreen(playerManager: PlayerManager, onBack: () -> Unit) {
    val state by playerManager.uiState.collectAsState()
    val song = state.currentSong
    if (song == null) {
        // Nothing to show (e.g. app restarted): never leave a blank screen.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var showQueue by remember { mutableStateOf(false) }
    // Back closes the Queue panel first, then (next press) the player.
    BackHandler(enabled = showQueue) { showQueue = false }

    // D-pad users land on Play/Pause right away (pause/resume = one click).
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { playFocus.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Ambient background: the same art, dimmed (no blur: cheap on weak GPUs).
        AsyncImage(
            model = com.aurum.musictv.ui.util.bigArt(song.albumArtUrl),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.22f },
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), AurumColors.AmoledBg)),
            ),
        )

        // Way out of the full player (Back on the remote does the same).
        BackPill(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 24.dp),
        )

        Row(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = com.aurum.musictv.ui.util.bigArt(song.albumArtUrl),
                contentDescription = song.title,
                modifier = Modifier.size(360.dp).clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = 480.dp)
                    ) {
                        Text(
                            text = song.title,
                            color = AurumColors.TextPrimary,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                        )
                        Text(
                            text = song.artist,
                            color = AurumColors.TextSecondary,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                    }
                    // Heart / save-to-Library toggle — this is what "save
                    // to Library" from the Player screen means: writes to
                    // the exact same liked_songs row LibraryScreen's
                    // "Liked Songs" tab reads.
                    LikeButton(
                        liked = state.isCurrentSongLiked,
                        onClick = { playerManager.toggleLikeCurrentSong() },
                    )
                }

                val progress = if (state.durationMs > 0) {
                    (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                SeekBar(
                    progress = progress,
                    onSeekFraction = { f -> playerManager.seekTo((f * state.durationMs).toLong()) },
                    onSeekBy = { delta -> playerManager.seekBy(delta) },
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatMs(state.positionMs), color = AurumColors.TextSecondary, fontSize = 13.sp)
                    Text(formatMs(state.durationMs), color = AurumColors.TextSecondary, fontSize = 13.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportButton(
                        icon = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        size = 28.dp,
                        tint = AurumColors.TextPrimary,
                        onClick = { playerManager.playPrevious() },
                    )
                    Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                        TransportButton(
                            icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause",
                            size = 40.dp,
                            tint = AurumColors.AmoledBg,
                            background = AurumColors.Gold,
                            focusRequester = playFocus,
                            onClick = { playerManager.togglePlayPause() },
                        )
                    }
                    TransportButton(
                        icon = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        size = 28.dp,
                        tint = AurumColors.TextPrimary,
                        onClick = { playerManager.playNext() },
                    )
                }

                // Spotify-style mode row: shuffle / repeat / sleep timer.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeButton(
                        icon = Icons.Filled.Shuffle,
                        label = "Shuffle",
                        active = state.shuffleOn,
                        onClick = { playerManager.toggleShuffle() },
                    )
                    Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ModeButton(
                            icon = if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            label = when (state.repeatMode) {
                                RepeatMode.OFF -> "Repeat"
                                RepeatMode.ALL -> "Repeat all"
                                RepeatMode.ONE -> "Repeat one"
                            },
                            active = state.repeatMode != RepeatMode.OFF,
                            onClick = { playerManager.cycleRepeat() },
                        )
                    }
                    ModeButton(
                        icon = Icons.Filled.Timer,
                        label = if (state.sleepMinutes > 0) "Sleep ${state.sleepMinutes}m" else "Sleep",
                        active = state.sleepMinutes > 0,
                        onClick = { playerManager.cycleSleepTimer() },
                    )
                    Box(modifier = Modifier.padding(start = 12.dp)) {
                        ModeButton(
                            icon = Icons.Filled.QueueMusic,
                            label = "Queue",
                            active = showQueue,
                            onClick = { showQueue = !showQueue },
                        )
                    }
                }

                state.queue.getOrNull(state.currentIndex + 1)?.let { next ->
                    Text(
                        text = "Up next · ${next.title} — ${next.artist}",
                        color = AurumColors.TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
        }

        if (showQueue) {
            QueuePanel(
                queue = state.queue,
                currentIndex = state.currentIndex,
                onPlay = { playerManager.playAt(it) },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * Seek bar. Remote: focus it and press Left/Right to jump 10 s. Touch: tap
 * anywhere or drag; the song is seeked once when the finger lifts (one
 * network seek, not one per pixel).
 */
@Composable
private fun SeekBar(
    progress: Float,
    onSeekFraction: (Float) -> Unit,
    onSeekBy: (Long) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var widthPx by remember { mutableStateOf(0) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // pointerInput(Unit) lives across recompositions: always call the newest lambda.
    val seekFraction by rememberUpdatedState(onSeekFraction)
    val shown = dragFraction ?: progress

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = it.width }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    when (ev.key) {
                        Key.DirectionLeft -> { onSeekBy(-10_000L); true }
                        Key.DirectionRight -> { onSeekBy(10_000L); true }
                        else -> false
                    }
                } else false
            }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (widthPx > 0) seekFraction((offset.x / widthPx).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        dragFraction?.let { seekFraction(it) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    change.consume()
                    if (widthPx > 0) dragFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (focused) 8.dp else 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(AurumColors.AmoledDivider),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shown)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (focused) AurumColors.GoldLight else AurumColors.Gold),
            )
        }
    }
}

/** Right-hand "Up next" list (Spotify TV style). Text rows only - no images,
 *  so a long auto-extended queue costs next to nothing on a 1 GB box. */
@Composable
private fun QueuePanel(
    queue: List<Song>,
    currentIndex: Int,
    onPlay: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { currentFocus.requestFocus() }
    }
    Column(
        modifier = modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(AurumColors.AmoledBgCard.copy(alpha = 0.97f))
            .padding(top = 28.dp),
    ) {
        Text(
            "Queue",
            color = AurumColors.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(queue, key = { i, s -> "$i:${s.id}" }) { i, song ->
                QueueRow(
                    song = song,
                    current = i == currentIndex,
                    requester = if (i == currentIndex) currentFocus else null,
                    onClick = { onPlay(i) },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(song: Song, current: Boolean, requester: FocusRequester?, onClick: () -> Unit) {
    val touchRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (current) AurumColors.GoldDark.copy(alpha = 0.22f) else Color.Transparent,
            focusedContainerColor = AurumColors.AmoledBgElevated,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, AurumColors.Gold),
                shape = RoundedCornerShape(10.dp),
            ),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (requester != null && !isTouch) Modifier.focusRequester(requester) else Modifier)
            .adaptiveClickable(touchRequester, isTouch, onClick),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = song.title,
                color = if (current) AurumColors.Gold else AurumColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                color = AurumColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BackPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(22.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AurumColors.AmoledBgSurface,
            focusedContainerColor = AurumColors.AmoledBgElevated,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, AurumColors.Gold),
                shape = RoundedCornerShape(22.dp),
            ),
        ),
        modifier = modifier.adaptiveClickable(focusRequester, isTouch, onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Back",
                tint = AurumColors.TextPrimary,
                modifier = Modifier.size(24.dp),
            )
            Text("Back", color = AurumColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ModeButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val focusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(22.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) AurumColors.GoldDark.copy(alpha = 0.30f) else AurumColors.AmoledBgSurface,
            focusedContainerColor = AurumColors.AmoledBgElevated,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, AurumColors.Gold),
                shape = RoundedCornerShape(22.dp),
            ),
        ),
        modifier = Modifier.adaptiveClickable(focusRequester, isTouch, onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) AurumColors.Gold else AurumColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                label,
                color = if (active) AurumColors.Gold else AurumColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun LikeButton(liked: Boolean, onClick: () -> Unit) {
    val touchFocusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AurumColors.AmoledBgSurface,
            focusedContainerColor = AurumColors.AmoledBgElevated,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, AurumColors.Gold),
                shape = CircleShape,
            ),
        ),
        modifier = Modifier.size(52.dp).adaptiveClickable(touchFocusRequester, isTouch, onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (liked) "Remove from Liked Songs" else "Save to Liked Songs",
                tint = if (liked) AurumColors.Gold else AurumColors.TextSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    background: androidx.compose.ui.graphics.Color? = null,
    focusRequester: FocusRequester? = null,
) {
    val touchFocusRequester = focusRequester ?: rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = background ?: AurumColors.AmoledBgSurface,
            focusedContainerColor = background ?: AurumColors.AmoledBgElevated,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, AurumColors.GoldLight),
                shape = CircleShape,
            ),
        ),
        modifier = Modifier
            .size(if (background != null) size + 32.dp else size + 16.dp)
            // On a TV adaptiveClickable is a no-op, so attach the requester here.
            .then(if (focusRequester != null && !isTouch) Modifier.focusRequester(focusRequester) else Modifier)
            .adaptiveClickable(touchFocusRequester, isTouch, onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(size),
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
