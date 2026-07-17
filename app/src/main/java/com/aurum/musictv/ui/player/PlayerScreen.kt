package com.aurum.musictv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.aurum.musictv.player.PlayerManager
import com.aurum.musictv.ui.theme.AurumColors

/**
 * Progress bar and controls are built from plain foundation Box/Row
 * rather than tv-material progress-indicator/icon-button components --
 * androidx.tv:tv-material's exact widget surface varies by version and
 * this stays correct regardless. D-pad focus lands on the Row's
 * clickable Boxes automatically since they're focusable by default on
 * Android TV.
 */
@Composable
fun PlayerScreen(playerManager: PlayerManager) {
    val state by playerManager.uiState.collectAsState()
    val song = state.currentSong ?: return

    Row(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.albumArtUrl,
            contentDescription = song.title,
            modifier = Modifier.size(360.dp).clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop,
        )

        Column(modifier = Modifier.fillMaxWidth()) {
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
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )

            val progress = if (state.durationMs > 0) {
                (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AurumColors.AmoledDivider),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AurumColors.Gold),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatMs(state.positionMs), color = AurumColors.TextSecondary, fontSize = 13.sp)
                Text(formatMs(state.durationMs), color = AurumColors.TextSecondary, fontSize = 13.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
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
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .then(if (background != null) Modifier.background(background) else Modifier)
            .clickable(onClick = onClick)
            .padding(if (background != null) 16.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
