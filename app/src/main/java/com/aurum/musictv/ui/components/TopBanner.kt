package com.aurum.musictv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.aurum.musictv.ui.theme.AurumColors
import kotlinx.coroutines.delay

data class BannerData(
    val title: String,
    val subtitle: String,
    val name: String? = null,
    val avatarUrl: String? = null,
)

/**
 * Slide-down banner from the top edge (sign-in / sign-out feedback). Shows
 * for ~3.5 s, then slides back up and calls [onDone]. It is not focusable, so
 * it never steals D-pad focus, and the slide is a draw-phase transform (no
 * recomposition per frame).
 */
@Composable
fun TopBanner(data: BannerData?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<BannerData?>(null) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(data) {
        if (data == null) return@LaunchedEffect
        shown = data
        progress.snapTo(0f)
        progress.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        delay(3_500)
        progress.animateTo(0f, tween(300))
        onDone()
    }

    val d = shown ?: return
    if (progress.value <= 0f) return

    Box(
        modifier = modifier.fillMaxWidth().padding(top = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    translationY = -(1f - progress.value) * 140.dp.toPx()
                    alpha = progress.value
                }
                .clip(RoundedCornerShape(18.dp))
                .background(AurumColors.AmoledBgSurface)
                .border(1.dp, AurumColors.Gold.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(url = d.avatarUrl, name = d.name, size = 44.dp)
            Column {
                Text(d.title, color = AurumColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(d.subtitle, color = AurumColors.TextSecondary, fontSize = 13.sp)
            }
        }
    }
}
