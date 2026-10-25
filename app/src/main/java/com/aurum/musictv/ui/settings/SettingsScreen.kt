package com.aurum.musictv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurum.musictv.settings.AudioQuality
import com.aurum.musictv.settings.EqPreset
import com.aurum.musictv.settings.FxLevel
import com.aurum.musictv.ui.theme.AurumColors
import com.aurum.musictv.ui.util.adaptiveClickable
import com.aurum.musictv.ui.util.isTouchDevice
import com.aurum.musictv.ui.util.rememberClickFocusRequester
import com.aurum.musictv.update.UpdateManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Full Settings screen — Network + Playback prefs (Audio Quality, Autoplay) + Storage (Clear Cache) + Account
 * (About, Privacy, Logout, Device Info). Every row is a focusable
 * tv-material Surface so it's fully D-pad navigable, same pattern as
 * SongCard/TopBar elsewhere in the app — no new focus-handling approach
 * introduced.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    // Only the label is observed, so download progress doesn't recompose this list.
    val updateLabel by remember {
        UpdateManager.ui.map { UpdateManager.statusLabel(it) }.distinctUntilChanged()
    }.collectAsState(initial = "Tap to check")

    LaunchedEffect(state.cacheCleared) {
        if (state.cacheCleared) {
            kotlinx.coroutines.delay(2000)
            viewModel.dismissCacheClearedNotice()
        }
    }

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(1800)
            viewModel.dismissNotice()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(AurumColors.AmoledBg)) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", color = AurumColors.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                BackChip(onClick = onBack)
            }
        }

        item { SectionHeader("Playback") }
        item {
            SettingsGroup {
                ChoiceRow(
                    title = "Audio Quality",
                    description = "Applies to the current song instantly",
                    options = listOf(
                        AudioQuality.LOW to "Low · saves data",
                        AudioQuality.NORMAL to "Normal",
                        AudioQuality.HIGH to "High · best",
                    ),
                    current = state.settings.audioQuality,
                    onSelect = viewModel::setAudioQuality,
                )
                ToggleRow(
                    label = "Autoplay",
                    description = "Automatically play the next song in queue",
                    checked = state.settings.autoplay,
                    onToggle = viewModel::setAutoplay,
                )
                ToggleRow(
                    label = "Open full player on song tap",
                    description = "Off: song just plays and a mini player shows at the bottom",
                    checked = state.settings.openPlayerOnTap,
                    onToggle = viewModel::setOpenPlayerOnTap,
                )
                ToggleRow(
                    label = "Skip silence",
                    description = "Trims silent gaps in songs, applies instantly",
                    checked = state.settings.skipSilence,
                    onToggle = viewModel::setSkipSilence,
                )
            }
        }

        item { SectionHeader("Sound") }
        item {
            SettingsGroup {
                ChoiceRow(
                    title = "Equalizer",
                    description = "Changes the sound of the current song instantly",
                    options = listOf(
                        EqPreset.FLAT to "Flat",
                        EqPreset.BASS to "Bass",
                        EqPreset.VOCAL to "Vocal",
                        EqPreset.TREBLE to "Treble",
                        EqPreset.LOUD to "Loud",
                    ),
                    current = state.settings.eqPreset,
                    onSelect = viewModel::setEqPreset,
                )
                ChoiceRow(
                    title = "Bass Boost",
                    description = null,
                    options = fxOptions,
                    current = state.settings.bassBoost,
                    onSelect = viewModel::setBassBoost,
                )
                ChoiceRow(
                    title = "Volume Boost",
                    description = "Louder output for quiet songs",
                    options = fxOptions,
                    current = state.settings.volumeBoost,
                    onSelect = viewModel::setVolumeBoost,
                )
            }
        }

        item { SectionHeader("Display") }
        item {
            SettingsGroup {
                ToggleRow(
                    label = "Reduce motion",
                    description = "Stops Home banner auto-rotation and fades (smoother on low-RAM TVs)",
                    checked = state.settings.reduceMotion,
                    onToggle = viewModel::setReduceMotion,
                )
            }
        }

        item { SectionHeader("Software update") }
        item {
            SettingsGroup {
                ActionRow(
                    label = "Check for updates",
                    valueLabel = updateLabel,
                    onClick = { UpdateManager.checkOrReopen(context) },
                )
            }
        }

        item { SectionHeader("Storage") }
        item {
            SettingsGroup {
                ActionRow(
                    label = "Clear Cache",
                    valueLabel = if (state.cacheCleared) "Cleared!" else state.cacheSizeLabel,
                    onClick = viewModel::clearCache,
                )
            }
        }

        item { SectionHeader("Account") }
        item {
            SettingsGroup {
                InfoRow(label = "About", valueLabel = "Astra TV v${state.appVersion}")
                InfoRow(label = "Privacy", valueLabel = "How your data is used")
                if (state.isSignedIn) {
                    ActionRow(
                        label = "Logout",
                        valueLabel = state.accountLabel ?: "Signed in",
                        destructive = true,
                        onClick = viewModel::logout,
                    )
                } else {
                    InfoRow(label = "Account", valueLabel = "Not signed in")
                }
            }
        }

        item { SectionHeader("Device Info") }
        item {
            SettingsGroup {
                InfoRow(label = "Device", valueLabel = state.deviceModel)
                InfoRow(label = "Android Version", valueLabel = state.androidVersion)
                InfoRow(label = "App Version", valueLabel = state.appVersion)
            }
        }
    }
    state.notice?.let { msg ->
        Box(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AurumColors.Gold)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(msg, color = AurumColors.AmoledBg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = AurumColors.Gold,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 48.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AurumColors.AmoledBgCard),
    ) {
        content()
    }
}

@Composable
private fun RowContainer(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun InfoRow(label: String, valueLabel: String) {
    // Focusable on purpose: the list only scrolls with D-pad focus, so
    // plain text rows at the bottom (Device Info) were unreachable.
    ActionRow(label = label, valueLabel = valueLabel, onClick = {})
}

@Composable
private fun ActionRow(label: String, valueLabel: String, destructive: Boolean = false, onClick: () -> Unit) {
    val touchFocusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().adaptiveClickable(touchFocusRequester, isTouch, onClick),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(0.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = AurumColors.AmoledBgElevated,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(
                    2.dp,
                    if (destructive) androidx.compose.ui.graphics.Color(0xFFE05656) else AurumColors.Gold,
                ),
                shape = RoundedCornerShape(0.dp),
            ),
        ),
    ) {
        RowContainer {
            Text(
                label,
                color = if (destructive) androidx.compose.ui.graphics.Color(0xFFE05656) else AurumColors.TextPrimary,
                fontSize = 16.sp,
            )
            Text(valueLabel, color = AurumColors.TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ToggleRow(label: String, description: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val touchFocusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = { onToggle(!checked) },
        modifier = Modifier.fillMaxWidth().adaptiveClickable(touchFocusRequester, isTouch, { onToggle(!checked) }),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(0.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = AurumColors.AmoledBgElevated,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, AurumColors.Gold),
                shape = RoundedCornerShape(0.dp),
            ),
        ),
    ) {
        RowContainer {
            Column {
                Text(label, color = AurumColors.TextPrimary, fontSize = 16.sp)
                Text(description, color = AurumColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            SwitchDot(checked = checked)
        }
    }
}

@Composable
private fun SwitchDot(checked: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (checked) AurumColors.Gold else AurumColors.AmoledDivider)
            .padding(3.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(if (checked) "ON" else "OFF", color = AurumColors.AmoledBg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private val fxOptions = listOf(
    FxLevel.OFF to "Off",
    FxLevel.LOW to "Low",
    FxLevel.MEDIUM to "Medium",
    FxLevel.HIGH to "High",
)

@Composable
private fun <T> ChoiceRow(
    title: String,
    description: String?,
    options: List<Pair<T, String>>,
    current: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp)) {
        Text(title, color = AurumColors.TextPrimary, fontSize = 16.sp)
        if (description != null) {
            Text(description, color = AurumColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Row(
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            options.forEach { (value, label) ->
                ChoiceChip(
                    label = label,
                    selected = value == current,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val touchFocusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        modifier = Modifier.adaptiveClickable(touchFocusRequester, isTouch, onClick),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(20.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AurumColors.Gold else AurumColors.AmoledBgSurface,
            focusedContainerColor = if (selected) AurumColors.GoldLight else AurumColors.AmoledBgElevated,
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
            color = if (selected) AurumColors.AmoledBg else AurumColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BackChip(onClick: () -> Unit) {
    val touchFocusRequester = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        modifier = Modifier.adaptiveClickable(touchFocusRequester, isTouch, onClick),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AurumColors.AmoledBgSurface,
            focusedContainerColor = AurumColors.AmoledBgElevated,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, AurumColors.Gold),
                shape = RoundedCornerShape(8.dp),
            ),
        ),
    ) {
        Text(
            "Back",
            color = AurumColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}
