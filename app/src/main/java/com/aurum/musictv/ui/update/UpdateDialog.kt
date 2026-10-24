package com.aurum.musictv.ui.update

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurum.musictv.ui.theme.AurumColors
import com.aurum.musictv.ui.util.LocalReduceMotion
import com.aurum.musictv.ui.util.adaptiveClickable
import com.aurum.musictv.ui.util.isTouchDevice
import com.aurum.musictv.update.Phase
import com.aurum.musictv.update.UpdateManager
import com.aurum.musictv.update.UpdateUi

private val ButtonSecondary = Color(0xFF232323)

/**
 * App-wide update popup. Lives at the top of the root Box, so it shows on
 * any screen. When hidden it composes nothing at all (zero cost). While
 * open it traps D-pad focus, Back hides it, and the only per-frame work is
 * a draw-phase fade/scale for 220 ms (skipped entirely with Reduce motion).
 */
@Composable
fun UpdateDialogHost() {
    val ui by UpdateManager.ui.collectAsState()
    if (!ui.showDialog || ui.info == null) return
    UpdateDialog(ui)
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun UpdateDialog(ui: UpdateUi) {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val info = ui.info ?: return
    val phase = ui.phase

    val appear = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduce) appear.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
    }

    val primary = remember { FocusRequester() }
    LaunchedEffect(phase) { runCatching { primary.requestFocus() } }

    BackHandler { UpdateManager.dismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color(0xD9000000))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .graphicsLayer {
                    val s = 0.94f + 0.06f * appear.value
                    scaleX = s
                    scaleY = s
                }
                .width(560.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AurumColors.AmoledBgSurface)
                .border(1.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .padding(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(AurumColors.Gold.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = AurumColors.Gold,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column {
                    Text(
                        text = when (phase) {
                            Phase.PERMISSION -> "Allow installing updates"
                            Phase.DOWNLOADING -> "Downloading update…"
                            Phase.INSTALLING -> "Ready to install"
                            Phase.FAILED -> "Update failed"
                            else -> "New update available"
                        },
                        color = AurumColors.TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Astra TV ${info.label} · ${formatMb(info.size)}",
                        color = AurumColors.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            when (phase) {
                Phase.AVAILABLE -> {
                    if (info.notes.isNotBlank()) {
                        Text(
                            "WHAT'S NEW",
                            color = AurumColors.Gold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
                        )
                        Text(
                            info.notes,
                            color = AurumColors.TextPrimary,
                            fontSize = 14.sp,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ui.message?.let { Note(it) }
                }

                Phase.PERMISSION -> Body(
                    "Turn on “Install unknown apps” for Astra TV in the settings page that opens, " +
                        "then come back. The update starts by itself.",
                )

                Phase.DOWNLOADING -> {
                    ProgressBar(percent = ui.percent, reduce = reduce)
                    Text(
                        text = "${ui.percent}%  ·  ${formatMb(info.size * ui.percent / 100)} of ${formatMb(info.size)}",
                        color = AurumColors.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                Phase.INSTALLING -> Body(
                    "Confirm the install when Android asks. Astra TV will close, then open it again " +
                        "from your apps.",
                )

                Phase.FAILED -> Body(ui.message ?: "Something went wrong.")
                else -> Unit
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                when (phase) {
                    Phase.AVAILABLE -> {
                        DialogButton("Update now", primary = true, requester = primary) { UpdateManager.install(context) }
                        DialogButton("Later") { UpdateManager.dismiss() }
                    }
                    Phase.PERMISSION -> {
                        DialogButton("Open settings", primary = true, requester = primary) {
                            UpdateManager.openInstallSettings(context)
                        }
                        DialogButton("Later") { UpdateManager.dismiss() }
                    }
                    Phase.DOWNLOADING -> {
                        DialogButton("Cancel", requester = primary) { UpdateManager.cancelDownload() }
                    }
                    Phase.INSTALLING -> {
                        DialogButton("Close", requester = primary) { UpdateManager.dismiss() }
                    }
                    Phase.FAILED -> {
                        DialogButton("Try again", primary = true, requester = primary) { UpdateManager.install(context) }
                        DialogButton("Close") { UpdateManager.dismiss() }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        color = AurumColors.TextSecondary,
        fontSize = 14.sp,
        modifier = Modifier.padding(top = 20.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        color = AurumColors.GoldLight,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun ProgressBar(percent: Int, reduce: Boolean) {
    val target = percent.coerceIn(0, 100) / 100f
    val shown = if (reduce) target else {
        androidx.compose.animation.core.animateFloatAsState(
            targetValue = target,
            animationSpec = tween(250),
            label = "updateProgress",
        ).value
    }
    Box(
        modifier = Modifier
            .padding(top = 24.dp)
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(AurumColors.AmoledDivider),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(shown.coerceIn(0.02f, 1f))
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(AurumColors.GoldDark, AurumColors.GoldLight))),
        )
    }
}

@Composable
private fun DialogButton(
    label: String,
    primary: Boolean = false,
    requester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val isTouch = isTouchDevice()
    val fr = requester ?: remember { FocusRequester() }
    val mod = if (isTouch) Modifier.adaptiveClickable(fr, true, onClick) else Modifier.focusRequester(fr)
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = mod,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (reduce) 1f else 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) AurumColors.Gold else ButtonSecondary,
            focusedContainerColor = if (primary) AurumColors.GoldLight else AurumColors.AmoledDivider,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, if (primary) AurumColors.TextPrimary else AurumColors.Gold),
                shape = shape,
            ),
        ),
    ) {
        Text(
            label,
            color = if (primary) AurumColors.AmoledBg else AurumColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 12.dp),
        )
    }
}

private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))
