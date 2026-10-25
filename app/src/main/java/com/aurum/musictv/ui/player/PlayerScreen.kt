package com.aurum.musictv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.data.remote.AurumApi
import com.aurum.musictv.player.PlayerManager
import com.aurum.musictv.player.RepeatMode
import com.aurum.musictv.ui.components.ArtThumb
import com.aurum.musictv.ui.theme.AurumColors
import com.aurum.musictv.ui.util.LocalReduceMotion
import com.aurum.musictv.ui.util.adaptiveClickable
import com.aurum.musictv.ui.util.bigArt
import com.aurum.musictv.ui.util.heroArt
import com.aurum.musictv.ui.util.isTouchDevice
import com.aurum.musictv.ui.util.rememberArtAccent
import com.aurum.musictv.ui.util.rememberClickFocusRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Full player. Cover art drives the whole look: a dimmed, enlarged copy of
 * the art as backdrop, a soft glow in the art's own accent colour, a big
 * cover card, and the controls. Only the seek area listens to the 1-second
 * position ticks, so the rest of the screen does not recompose every second
 * (cheap on 1 GB boxes). Every control is a tv-material Surface so D-pad
 * users always see a focus ring.
 */
@Composable
fun PlayerScreen(playerManager: PlayerManager, onBack: () -> Unit) {
    // Everything except the ticking position: identical states are skipped.
    val state by remember(playerManager) {
        playerManager.uiState.map { it.copy(positionMs = 0L) }.distinctUntilChanged()
    }.collectAsState(initial = playerManager.uiState.value.copy(positionMs = 0L))
    val song = state.currentSong
    if (song == null) {
        // Nothing to show (e.g. app restarted): never leave a blank screen.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var showQueue by remember { mutableStateOf(false) }
    BackHandler(enabled = showQueue) { showQueue = false }

    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { playFocus.requestFocus() }
    }

    // Low-end mode (Reduce motion, on by default for 1 GB boxes): 600 px art,
    // no crossfade. Everything else about the layout is identical.
    val lite = LocalReduceMotion.current
    val art = remember(song.albumArtUrl, lite) {
        if (lite) bigArt(song.albumArtUrl) else heroArt(song.albumArtUrl)
    }
    val accent = rememberArtAccent(art, AurumColors.Gold)
    val context = LocalContext.current
    val artRequest = remember(art, lite) {
        ImageRequest.Builder(context)
            .data(art)
            .size(if (lite) 600 else 1000)
            .crossfade(!lite)
            .build()
    }
    // Dark page colour with a breath of the cover's accent in it.
    val bg = lerp(Color(0xFF08080A), accent, 0.12f)

    var immersive by remember { mutableStateOf(false) }
    BackHandler(enabled = immersive) { immersive = false }

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
      if (immersive) {
        ImmersiveArt(
            song = song,
            request = artRequest,
            accent = accent,
            bg = bg,
            playerManager = playerManager,
            onExit = { immersive = false },
        )
      } else {
        // The cover itself, sharp and full height on the left, fading into
        // the page colour where the controls begin.
        AsyncImage(
            model = artRequest,
            contentDescription = song.title,
            modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().aspectRatio(1f),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.30f to Color.Transparent,
                    0.56f to bg,
                    1f to bg,
                ),
            ),
        )

        BackPill(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 24.dp),
        )

        Row(
            modifier = Modifier.fillMaxSize().padding(end = 64.dp, top = 40.dp, bottom = 32.dp),
        ) {
            Spacer(modifier = Modifier.weight(0.54f))

            Column(
                modifier = Modifier.weight(0.46f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            text = if (state.isResolving || state.isBuffering) "LOADING…" else "NOW PLAYING",
                            color = accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.sp,
                        )
                        Text(
                            text = song.title,
                            color = AurumColors.TextPrimary,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            text = song.artist,
                            color = AurumColors.TextSecondary,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                        )
                    }
                    LikeButton(
                        liked = state.isCurrentSongLiked,
                        accent = accent,
                        onClick = { playerManager.toggleLikeCurrentSong() },
                    )
                }

                SeekSection(playerManager = playerManager, accent = accent)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
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
                            background = accent,
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

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeButton(
                        icon = Icons.Filled.Shuffle,
                        label = "Shuffle",
                        active = state.shuffleOn,
                        accent = accent,
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
                            accent = accent,
                            onClick = { playerManager.cycleRepeat() },
                        )
                    }
                    ModeButton(
                        icon = Icons.Filled.Timer,
                        label = if (state.sleepMinutes > 0) "Sleep ${state.sleepMinutes}m" else "Sleep",
                        active = state.sleepMinutes > 0,
                        accent = accent,
                        onClick = { playerManager.cycleSleepTimer() },
                    )
                    Box(modifier = Modifier.padding(start = 12.dp)) {
                        ModeButton(
                            icon = Icons.Filled.Fullscreen,
                            label = "Full art",
                            active = false,
                            accent = accent,
                            onClick = { immersive = true },
                        )
                    }
                    Box(modifier = Modifier.padding(start = 12.dp)) {
                        ModeButton(
                            icon = Icons.Filled.QueueMusic,
                            label = "Up next",
                            active = showQueue,
                            accent = accent,
                            onClick = { showQueue = !showQueue },
                        )
                    }
                }

                // One-line peek at what comes next (with its cover).
                state.queue.getOrNull(state.currentIndex + 1)?.let { next ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ArtThumb(url = next.albumArtUrl, size = 40.dp, corner = 8.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("UP NEXT", color = accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
                            Text(
                                text = "${next.title} · ${next.artist}",
                                color = AurumColors.TextSecondary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (showQueue) {
            QueuePanel(
                queue = state.queue,
                currentIndex = state.currentIndex,
                isPlaying = state.isPlaying,
                accent = accent,
                onPlay = { playerManager.playAt(it) },
                onTogglePlay = { playerManager.togglePlayPause() },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
      }
    }
}

/**
 * "Hotel TV" mode: the cover takes the whole screen, every control is gone.
 * Only a slim title strip and a hairline progress bar remain. OK / tap /
 * Back returns to the player. One bitmap + one gradient: cheap on low-end.
 */
@Composable
private fun ImmersiveArt(
    song: Song,
    request: ImageRequest,
    accent: Color,
    bg: Color,
    playerManager: PlayerManager,
    onExit: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focus.requestFocus() }
    }
    val exit by rememberUpdatedState(onExit)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.32f).compositeOver(bg), bg, accent.copy(alpha = 0.32f).compositeOver(bg)),
                ),
            )
            .focusRequester(focus)
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    when (ev.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { exit(); true }
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                } else false
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { exit() } },
    ) {
        AsyncImage(
            model = request,
            contentDescription = song.title,
            modifier = Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(1f),
            contentScale = ContentScale.Crop,
        )
        // Slim title strip over a soft bottom scrim.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(190.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)))),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 30.dp, end = 56.dp),
        ) {
            Text(
                song.title,
                color = AurumColors.TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                color = AurumColors.TextSecondary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        HairlineProgress(
            playerManager = playerManager,
            accent = accent,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/** 3 dp progress line along the bottom edge; follows the position tick itself. */
@Composable
private fun HairlineProgress(playerManager: PlayerManager, accent: Color, modifier: Modifier = Modifier) {
    val pos by remember(playerManager) {
        playerManager.uiState.map { it.positionMs to it.durationMs }.distinctUntilChanged()
    }.collectAsState(initial = playerManager.uiState.value.let { it.positionMs to it.durationMs })
    val (p, d) = pos
    val f = if (d > 0) (p.toFloat() / d.toFloat()).coerceIn(0f, 1f) else 0f
    Box(modifier = modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.12f))) {
        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(f).background(accent))
    }
}

/** Seek bar + times: the only part that follows the 1-second position tick. */
@Composable
private fun SeekSection(playerManager: PlayerManager, accent: Color) {
    val pos by remember(playerManager) {
        playerManager.uiState.map { it.positionMs to it.durationMs }.distinctUntilChanged()
    }.collectAsState(initial = playerManager.uiState.value.let { it.positionMs to it.durationMs })
    val (positionMs, durationMs) = pos
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    SeekBar(
        progress = progress,
        accent = accent,
        onSeekFraction = { f -> playerManager.seekTo((f * durationMs).toLong()) },
        onSeekBy = { delta -> playerManager.seekBy(delta) },
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(formatMs(positionMs), color = AurumColors.TextSecondary, fontSize = 13.sp)
        Text(formatMs(durationMs), color = AurumColors.TextSecondary, fontSize = 13.sp)
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
    accent: Color,
    onSeekFraction: (Float) -> Unit,
    onSeekBy: (Long) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var widthPx by remember { mutableStateOf(0) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val seekFraction by rememberUpdatedState(onSeekFraction)
    val seekBy by rememberUpdatedState(onSeekBy)
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
                        Key.DirectionLeft -> { seekBy(-10_000L); true }
                        Key.DirectionRight -> { seekBy(10_000L); true }
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
                .height(if (focused) 10.dp else 5.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.White.copy(alpha = 0.16f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shown)
                    .clip(RoundedCornerShape(5.dp))
                    .background(accent),
            )
        }
    }
}

/**
 * Right-hand queue. Pinned at the top: the song that is playing now. Below
 * it: only what comes AFTER it, each row with its cover, title, artist and
 * length. Songs whose title already appeared above are hidden, so the same
 * song never shows twice. Thumbnails are decoded at row size.
 */
@Composable
private fun QueuePanel(
    queue: List<Song>,
    currentIndex: Int,
    isPlaying: Boolean,
    accent: Color,
    onPlay: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = queue.getOrNull(currentIndex)
    val upcoming = remember(queue, currentIndex) {
        val seen = HashSet<String>()
        current?.let { seen += AurumApi.titleKey(it) }
        val out = ArrayList<Pair<Int, Song>>()
        for (i in (currentIndex + 1) until queue.size) {
            val s = queue[i]
            if (seen.add(AurumApi.titleKey(s))) out += i to s
        }
        out
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { firstFocus.requestFocus() }
    }

    Column(
        modifier = modifier
            .width(470.dp)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.90f), Color(0xFF070709))))
            .padding(top = 28.dp),
    ) {
        Text(
            "Now playing",
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.8.sp,
            modifier = Modifier.padding(start = 28.dp, bottom = 8.dp),
        )
        if (current != null) {
            NowPlayingRow(song = current, isPlaying = isPlaying, accent = accent, onClick = onTogglePlay)
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 28.dp, vertical = 14.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.10f)),
        )
        Text(
            if (upcoming.isEmpty()) "Up next" else "Up next · ${upcoming.size}",
            color = AurumColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 28.dp, bottom = 6.dp),
        )
        if (upcoming.isEmpty()) {
            Text(
                "Finding songs for you…",
                color = AurumColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 28.dp, top = 8.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(upcoming, key = { _, p -> "${p.first}:${p.second.id}" }) { n, p ->
                QueueRow(
                    song = p.second,
                    requester = if (n == 0) firstFocus else null,
                    accent = accent,
                    onClick = { onPlay(p.first) },
                )
            }
        }
    }
}

@Composable
private fun NowPlayingRow(song: Song, isPlaying: Boolean, accent: Color, onClick: () -> Unit) {
    val touchRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = accent.copy(alpha = 0.16f),
            focusedContainerColor = accent.copy(alpha = 0.26f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, accent),
                shape = RoundedCornerShape(14.dp),
            ),
        ),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .adaptiveClickable(touchRequester, isTouch, onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ArtThumb(url = song.albumArtUrl, size = 64.dp, corner = 10.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = accent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    color = AurumColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.Pause,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun QueueRow(song: Song, requester: FocusRequester?, accent: Color, onClick: () -> Unit) {
    val touchRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.08f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, accent),
                shape = RoundedCornerShape(12.dp),
            ),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (requester != null && !isTouch) Modifier.focusRequester(requester) else Modifier)
            .adaptiveClickable(touchRequester, isTouch, onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ArtThumb(url = song.albumArtUrl, size = 52.dp, corner = 8.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    color = AurumColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
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
            if (song.durationSec > 0) {
                Text(formatMs(song.durationSec * 1000L), color = AurumColors.TextMuted, fontSize = 12.sp)
            }
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
private fun ModeButton(icon: ImageVector, label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    val focusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(22.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f),
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
                tint = if (active) accent else AurumColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                label,
                color = if (active) accent else AurumColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun LikeButton(liked: Boolean, accent: Color, onClick: () -> Unit) {
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
        modifier = Modifier.size(56.dp).adaptiveClickable(touchFocusRequester, isTouch, onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (liked) "Remove from Liked Songs" else "Save to Liked Songs",
                tint = if (liked) accent else AurumColors.TextSecondary,
                modifier = Modifier.size(26.dp),
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
