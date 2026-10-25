package com.aurum.musictv.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.aurum.musictv.ui.components.ArtThumb
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import com.aurum.musictv.player.PlayerManager
import com.aurum.musictv.ui.theme.AurumColors
import com.aurum.musictv.ui.util.adaptiveClickable
import com.aurum.musictv.ui.util.isTouchDevice
import com.aurum.musictv.ui.util.rememberClickFocusRequester

/**
 * Persistent mini player (Spotify-style): songs start playing here without
 * hijacking the screen; selecting the left part opens the full player, the
 * three buttons control playback without leaving Home/Search/Library.
 * One AsyncImage + a few Texts — recomposes once a second at most.
 */
@Composable
fun NowPlayingBar(
    playerManager: PlayerManager,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Everything except the ticking position: the bar no longer recomposes
    // every second (only the thin progress line below does).
    val state by remember(playerManager) {
        playerManager.uiState.map { it.copy(positionMs = 0L) }.distinctUntilChanged()
    }.collectAsState(initial = playerManager.uiState.value.copy(positionMs = 0L))
    val song = state.currentSong ?: return
    val openFocusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()

    Column(modifier = modifier.fillMaxWidth().background(AurumColors.AmoledBgCard)) {
        MiniProgress(playerManager)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                onClick = onOpen,
                modifier = Modifier.weight(1f).adaptiveClickable(openFocusRequester, isTouch, onOpen),
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                // A wide row must not zoom (it overlapped the buttons).
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = AurumColors.AmoledBgElevated,
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, AurumColors.Gold),
                        shape = RoundedCornerShape(10.dp),
                    ),
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ArtThumb(url = song.albumArtUrl, size = 48.dp, corner = 8.dp)
                    Column {
                        Text(
                            text = song.title,
                            color = AurumColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (state.isResolving || state.isBuffering) "Loading…" else song.artist,
                            color = AurumColors.TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            BarButton(
                if (state.isCurrentSongLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (state.isCurrentSongLiked) "Remove from Liked Songs" else "Save to Liked Songs",
                tintOverride = if (state.isCurrentSongLiked) AurumColors.Gold else null,
            ) { playerManager.toggleLikeCurrentSong() }
            BarButton(Icons.Filled.SkipPrevious, "Previous") { playerManager.playPrevious() }
            BarButton(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                "Play/Pause",
                filled = true,
            ) { playerManager.togglePlayPause() }
            BarButton(Icons.Filled.SkipNext, "Next") { playerManager.playNext() }
        }
    }
}

/** 2 dp progress line: the only part of the mini player that follows the tick. */
@Composable
private fun MiniProgress(playerManager: PlayerManager) {
    val pos by remember(playerManager) {
        playerManager.uiState.map { it.positionMs to it.durationMs }.distinctUntilChanged()
    }.collectAsState(initial = playerManager.uiState.value.let { it.positionMs to it.durationMs })
    val (p, d) = pos
    val f = if (d > 0) (p.toFloat() / d.toFloat()).coerceIn(0f, 1f) else 0f
    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(AurumColors.AmoledDivider)) {
        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(f).background(AurumColors.Gold))
    }
}

@Composable
private fun BarButton(
    icon: ImageVector,
    contentDescription: String,
    filled: Boolean = false,
    tintOverride: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    val focusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (filled) AurumColors.Gold else AurumColors.AmoledBgSurface,
            focusedContainerColor = if (filled) AurumColors.GoldLight else AurumColors.AmoledBgElevated,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, AurumColors.GoldLight),
                shape = CircleShape,
            ),
        ),
        modifier = Modifier.size(44.dp).adaptiveClickable(focusRequester, isTouch, onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tintOverride ?: if (filled) AurumColors.AmoledBg else AurumColors.TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
