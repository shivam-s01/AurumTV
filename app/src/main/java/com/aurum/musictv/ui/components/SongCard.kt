package com.aurum.musictv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.ui.theme.AurumColors
import com.aurum.musictv.ui.util.LocalReduceMotion
import com.aurum.musictv.ui.util.adaptiveClickable
import com.aurum.musictv.ui.util.isTouchDevice
import com.aurum.musictv.ui.util.rememberClickFocusRequester

/**
 * The single most-instantiated composable in the app (every row is built
 * from these) — kept deliberately simple: no nested LazyColumn/Row inside
 * it, no per-frame allocations, only one AsyncImage. This is the thing
 * that has to hit 60fps while scrolling on a 1GB RAM box.
 */
@Composable
fun SongCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
) {
    var isFocused by remember { mutableStateOf(false) }
    // ONE zoom only (tv-material's own, which was previously stacked on top
    // of a manual 1.12x -> a 23% jump that overlapped neighbours and cut the
    // focus ring). No zoom at all in Reduce motion.
    val reduce = LocalReduceMotion.current
    val touchFocusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    // size = Dp.Unspecified -> "fluid": the card fills its grid cell exactly
    // (square), so Library/Search grids have no uneven gap on the right.
    val fluid = !size.isSpecified
    val context = LocalContext.current
    // Thumbnails are 300 px at most: decode exactly that, no fade (cheaper
    // while a whole grid of results appears at once).
    val artRequest = remember(song.albumArtUrl) {
        ImageRequest.Builder(context).data(song.albumArtUrl).size(300).crossfade(false).build()
    }

    Column(
        modifier = if (fluid) modifier.fillMaxWidth() else modifier.width(size),
    ) {
        Surface(
            onClick = onClick,
            modifier = (if (fluid) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.size(size))
                .onFocusChanged { isFocused = it.isFocused }
                .adaptiveClickable(touchFocusRequester, isTouch, onClick),
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                shape = RoundedCornerShape(10.dp),
            ),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(
                focusedScale = if (reduce) 1f else 1.07f,
                pressedScale = 0.98f,
            ),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = AurumColors.AmoledBgSurface,
                focusedContainerColor = AurumColors.AmoledBgElevated,
            ),
            border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, AurumColors.Gold),
                    shape = RoundedCornerShape(10.dp),
                ),
            ),
        ) {
            Box {
                AsyncImage(
                    model = artRequest,
                    contentDescription = song.title,
                    modifier = (if (fluid) Modifier.fillMaxSize() else Modifier.size(size)).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
                if (isFocused) {
                    Box(
                        modifier = (if (fluid) Modifier.fillMaxSize() else Modifier.size(size))
                            .background(Color.Black.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
        Text(
            text = song.title,
            color = AurumColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            text = song.artist,
            color = AurumColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp),
        )
    }
}
