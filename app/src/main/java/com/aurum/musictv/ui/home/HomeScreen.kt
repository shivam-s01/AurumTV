package com.aurum.musictv.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlin.math.abs
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.aurum.musictv.ui.util.adaptiveClickable
import com.aurum.musictv.ui.util.isTouchDevice
import com.aurum.musictv.ui.util.rememberClickFocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import coil.imageLoader
import coil.request.ImageRequest
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
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
    // Hoisted so the scroll position survives opening the player/Settings
    // and coming back (Spotify-like: you land where you left).
    listState: LazyListState = rememberLazyListState(),
    reduceMotion: Boolean = false,
) {
    val state by viewModel.uiState.collectAsState()

    // Hero: built by HeroPicker (stable priority order, de-duplicated, real
    // songs with art only). It waits for the history fetch (or 1.8 s) so
    // "Continue Listening" is slide 1 from the very first frame.
    var heroWaited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1_800); heroWaited = true }
    val heroReady = state.userDataLoaded || heroWaited
    val heroItems = remember(state.continueListening, state.recentlyPlayed, state.personalRows, state.sections) {
        HeroPicker.build(state.continueListening, state.recentlyPlayed, state.personalRows, state.sections)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(AurumColors.AmoledBg),
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Box {
                if (heroReady && heroItems.isNotEmpty()) {
                    HeroCarousel(
                        items = heroItems,
                        reduceMotion = reduceMotion,
                        // Play starts the slide's own row (queue) at that song.
                        onPlayClick = { item -> onSongClick(item.song, item.queue) },
                    )
                } else if (state.isLoading || !heroReady) {
                    // Reserve the hero's height while loading so the rows
                    // below do not jump down when it appears.
                    Box(modifier = Modifier.fillMaxWidth().height(HERO_HEIGHT))
                }
                TopBar(
                    greetingName = state.greetingName,
                    avatarUrl = state.avatarUrl,
                    onSearchClick = onSearchClick,
                    onProfileClick = onProfileClick,
                    onSettingsClick = onSettingsClick,
                    modifier = Modifier.padding(top = 20.dp),
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

        items(state.personalRows, key = { "p:" + it.first }) { (title, songs) ->
            SongRow(
                title = title,
                songs = songs,
                onSongClick = { onSongClick(it, songs) },
            )
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

        // FIX: Home previously had no fallback UI when sections came back
        // empty — the LazyColumn just rendered nothing below the top bar,
        // which was indistinguishable from "still loading" or "stuck".
        // Now: while loading, show a spinner; if loading finished with
        // zero sections, show the actual reason (network/server) plus a
        // Retry button instead of a permanently blank page.
        if (state.isLoading && state.sections.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Loading songs…", color = AurumColors.TextSecondary, fontSize = 16.sp)
                }
            }
        } else if (!state.isLoading && state.sections.isEmpty()) {
            item {
                HomeErrorState(message = state.loadError, onRetry = viewModel::refresh)
            }
        }
    }
}

@Composable
private fun HomeErrorState(message: String?, onRetry: () -> Unit) {
    val retryFocusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Couldn't load songs",
            color = AurumColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message ?: "Check your internet connection and try again.",
            color = AurumColors.TextSecondary,
            fontSize = 13.sp,
        )
        androidx.tv.material3.Surface(
            onClick = onRetry,
            modifier = Modifier.adaptiveClickable(retryFocusRequester, isTouch, onRetry),
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = AurumColors.Gold,
                focusedContainerColor = AurumColors.GoldLight,
            ),
        ) {
            Text(
                "Retry",
                color = AurumColors.AmoledBg,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

private val HERO_HEIGHT = 400.dp

/** Set once the hero Play button got the very first D-pad focus, so coming
 *  back to Home later does not steal focus from the sidebar again. */
private var heroInitialFocusDone = false

/**
 * Horizontal hero: caption + title + artist + Play on the left, album art
 * card on the right over a dimmed copy of the same art. Slides sideways
 * every 7 s; remote Right/Left or a swipe moves it by hand and restarts the
 * timer. Only ONE slide is composed at a time and the slide animation is a
 * draw-phase transform, which keeps it smooth on a 1 GB box.
 *
 * The current slide is tracked by SONG ID, not position: when rows finish
 * loading and the list changes, the person keeps seeing the same song.
 */
@Composable
private fun HeroCarousel(
    items: List<HeroItem>,
    reduceMotion: Boolean,
    onPlayClick: (HeroItem) -> Unit,
) {
    val count = items.size
    if (count == 0) return

    var currentId by remember { mutableStateOf<String?>(null) }
    var direction by remember { mutableIntStateOf(1) }
    val safeIndex = items.indexOfFirst { it.song.id == currentId }.let { if (it < 0) 0 else it }
    val item = items[safeIndex]
    val song = item.song

    fun go(delta: Int) {
        if (count <= 1) return
        direction = if (delta >= 0) 1 else -1
        currentId = items[(safeIndex + delta + count) % count].song.id
    }

    // Auto-advance. Keyed on the song, so any manual move restarts the 7 s.
    LaunchedEffect(song.id, count, reduceMotion) {
        if (count <= 1 || reduceMotion) return@LaunchedEffect
        delay(7_000)
        direction = 1
        currentId = items[(safeIndex + 1) % count].song.id
    }

    // Warm the next slide's art so it is already decoded when the slide
    // arrives (no empty art box while the image loads).
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(song.id, count) {
        if (count <= 1) return@LaunchedEffect
        val next = items[(safeIndex + 1) % count].song
        val url = com.aurum.musictv.ui.util.bigArt(next.albumArtUrl) ?: return@LaunchedEffect
        context.imageLoader.enqueue(
            ImageRequest.Builder(context).data(url).build()
        )
    }

    // A fresh Animatable per slide starts at +-1 (off to the side) on the very
    // first frame, so there is no one-frame flash of the new slide in place.
    val slide = remember(song.id) { Animatable(if (reduceMotion) 0f else direction.toFloat()) }
    LaunchedEffect(slide) {
        if (slide.value != 0f) slide.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
    }

    val isTouch = isTouchDevice()
    val playFocus = rememberClickFocusRequester()
    LaunchedEffect(Unit) {
        if (!isTouch && !heroInitialFocusDone) {
            heroInitialFocusDone = true
            delay(250)
            runCatching { playFocus.requestFocus() }
        }
    }

    val artUrl = remember(song.id) { com.aurum.musictv.ui.util.bigArt(song.albumArtUrl) }
    val artRequest = remember(artUrl) {
        ImageRequest.Builder(context).data(artUrl).crossfade(if (reduceMotion) 0 else 250).build()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HERO_HEIGHT)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || count <= 1) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionRight -> { go(1); true }
                    // Left on the first slide falls through to the sidebar.
                    Key.DirectionLeft -> if (safeIndex > 0) { go(-1); true } else false
                    else -> false
                }
            }
            .pointerInput(count, song.id) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragCancel = { total = 0f },
                    onDragEnd = {
                        if (total < -80f) go(1) else if (total > 80f) go(-1)
                        total = 0f
                    },
                ) { _, amount -> total += amount }
            },
    ) {
        // Ambient background: same art, dimmed. Upscaling a 600px image is
        // the "blur" - free, unlike Modifier.blur.
        coil.compose.AsyncImage(
            model = artRequest,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.30f * (1f - 0.5f * abs(slide.value)) },
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            AurumColors.AmoledBg.copy(alpha = 0.95f),
                            AurumColors.AmoledBg.copy(alpha = 0.60f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, AurumColors.AmoledBg),
                        startY = 160f,
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 48.dp, top = 76.dp, bottom = 28.dp)
                .graphicsLayer {
                    translationX = slide.value * 120.dp.toPx()
                    alpha = 1f - abs(slide.value) * 0.9f
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    color = AurumColors.Gold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = HeroPicker.displayTitle(song.title),
                    color = AurumColors.TextPrimary,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = HeroPicker.displayArtist(song.artist),
                    color = AurumColors.TextSecondary,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, bottom = 22.dp),
                )
                androidx.tv.material3.Surface(
                    onClick = { onPlayClick(item) },
                    modifier = Modifier
                        .then(if (!isTouch) Modifier.focusRequester(playFocus) else Modifier)
                        .adaptiveClickable(playFocus, isTouch) { onPlayClick(item) },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                        shape = RoundedCornerShape(10.dp),
                    ),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = AurumColors.Gold,
                        focusedContainerColor = AurumColors.GoldLight,
                    ),
                    border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                        focusedBorder = androidx.tv.material3.Border(
                            border = androidx.compose.foundation.BorderStroke(2.dp, AurumColors.TextPrimary),
                            shape = RoundedCornerShape(10.dp),
                        ),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 34.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        androidx.tv.material3.Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = AurumColors.AmoledBg,
                            modifier = Modifier.size(24.dp),
                        )
                        Text("Play", color = AurumColors.AmoledBg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (count > 1) {
                    Row(
                        modifier = Modifier.padding(top = 22.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        repeat(count) { i ->
                            Box(
                                modifier = Modifier
                                    .size(if (i == safeIndex) 22.dp else 6.dp, 6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (i == safeIndex) AurumColors.Gold else AurumColors.TextMuted),
                            )
                        }
                    }
                }
            }
            coil.compose.AsyncImage(
                model = artRequest,
                contentDescription = song.title,
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(AurumColors.AmoledBgSurface),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun TopBar(
    greetingName: String?,
    avatarUrl: String?,
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = if (greetingName != null) "Hi, $greetingName" else "Astra",
            color = AurumColors.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val searchFocusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
            androidx.tv.material3.Surface(
                onClick = onSearchClick,
                modifier = Modifier.adaptiveClickable(searchFocusRequester, isTouch, onSearchClick),
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                    shape = RoundedCornerShape(6.dp),
                ),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = AurumColors.AmoledBgSurface,
                    focusedContainerColor = AurumColors.AmoledBgElevated,
                ),
                border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                    focusedBorder = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, AurumColors.Gold),
                        shape = RoundedCornerShape(6.dp),
                    ),
                ),
            ) {
                Text(
                    "Search",
                    color = AurumColors.TextPrimary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            val settingsFocusRequester = rememberClickFocusRequester()
            androidx.tv.material3.Surface(
                onClick = onSettingsClick,
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                    shape = androidx.compose.foundation.shape.CircleShape,
                ),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = AurumColors.AmoledBgSurface,
                    focusedContainerColor = AurumColors.AmoledBgElevated,
                ),
                border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                    focusedBorder = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, AurumColors.Gold),
                        shape = androidx.compose.foundation.shape.CircleShape,
                    ),
                ),
                modifier = Modifier.size(40.dp).adaptiveClickable(settingsFocusRequester, isTouch, onSettingsClick),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("\u2699", color = AurumColors.TextSecondary, fontSize = 18.sp)
                }
            }
            ProfileAvatar(avatarUrl = avatarUrl, name = greetingName, onClick = onProfileClick)
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
private fun ProfileAvatar(avatarUrl: String?, name: String?, onClick: () -> Unit) {
    val avatarFocusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            shape = androidx.compose.foundation.shape.CircleShape,
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = AurumColors.AmoledBgSurface,
            focusedContainerColor = AurumColors.AmoledBgElevated,
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, AurumColors.Gold),
                shape = androidx.compose.foundation.shape.CircleShape,
            ),
        ),
        modifier = Modifier.size(44.dp).adaptiveClickable(avatarFocusRequester, isTouch, onClick),
    ) {
        com.aurum.musictv.ui.components.Avatar(url = avatarUrl, name = name, size = 44.dp)
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
        )
        // Vertical padding: the focused card zooms 12% and LazyRow clips to
        // its bounds, which used to cut the gold focus ring at top/bottom.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(songs, key = { it.id }) { song ->
                SongCard(song = song, onClick = { onSongClick(song) })
            }
        }
    }
}
