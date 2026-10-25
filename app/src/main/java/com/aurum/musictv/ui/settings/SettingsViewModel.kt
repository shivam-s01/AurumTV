package com.aurum.musictv.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.musictv.settings.AppSettings
import com.aurum.musictv.settings.AudioQuality
import com.aurum.musictv.settings.DeviceInfo
import com.aurum.musictv.settings.EqPreset
import com.aurum.musictv.settings.FxLevel
import com.aurum.musictv.settings.SettingsStore
import com.aurum.musictv.sync.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val cacheSizeLabel: String = "…",
    val appVersion: String = "",
    val deviceModel: String = "",
    val androidVersion: String = "",
    val isSignedIn: Boolean = false,
    val accountLabel: String? = null,
    val cacheCleared: Boolean = false,
    /** One-line confirmation shown after any change ("Equalizer: Bass"). */
    val notice: String? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshStaticInfo()
        viewModelScope.launch {
            AuthRepository.awaitReady()
            refreshStaticInfo()
        }
        viewModelScope.launch {
            SettingsStore.observe(getApplication()).collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    private fun refreshStaticInfo() {
        val ctx = getApplication<Application>()
        _uiState.value = _uiState.value.copy(
            cacheSizeLabel = DeviceInfo.cacheSizeLabel(ctx),
            appVersion = DeviceInfo.appVersion(ctx),
            deviceModel = DeviceInfo.deviceModel(),
            androidVersion = DeviceInfo.androidVersion(),
            isSignedIn = AuthRepository.isSignedIn,
            accountLabel = AuthRepository.displayName,
        )
    }

    /** Applies a change to the screen at once (so a click is never silent),
     *  then saves it; the DataStore flow confirms the same value afterwards. */
    private fun change(notice: String, edit: (AppSettings) -> AppSettings, save: suspend () -> Unit) {
        _uiState.update { it.copy(settings = edit(it.settings), notice = notice) }
        viewModelScope.launch { runCatching { save() } }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun setAudioQuality(quality: AudioQuality) = change(
        "Audio quality: ${quality.name.lowercase().replaceFirstChar { it.uppercase() }}",
        { it.copy(audioQuality = quality) },
    ) { SettingsStore.setAudioQuality(getApplication(), quality) }

    fun setAutoplay(enabled: Boolean) = change(
        "Autoplay ${if (enabled) "on" else "off"}",
        { it.copy(autoplay = enabled) },
    ) { SettingsStore.setAutoplay(getApplication(), enabled) }

    fun setOpenPlayerOnTap(enabled: Boolean) = change(
        "Full player on tap ${if (enabled) "on" else "off"}",
        { it.copy(openPlayerOnTap = enabled) },
    ) { SettingsStore.setOpenPlayerOnTap(getApplication(), enabled) }

    fun setSkipSilence(enabled: Boolean) = change(
        "Skip silence ${if (enabled) "on" else "off"}",
        { it.copy(skipSilence = enabled) },
    ) { SettingsStore.setSkipSilence(getApplication(), enabled) }

    fun setEqPreset(preset: EqPreset) = change(
        "Equalizer: ${preset.name.lowercase().replaceFirstChar { it.uppercase() }}",
        { it.copy(eqPreset = preset) },
    ) { SettingsStore.setEqPreset(getApplication(), preset) }

    fun setBassBoost(level: FxLevel) = change(
        "Bass boost: ${level.name.lowercase().replaceFirstChar { it.uppercase() }}",
        { it.copy(bassBoost = level) },
    ) { SettingsStore.setBassBoost(getApplication(), level) }

    fun setVolumeBoost(level: FxLevel) = change(
        "Volume boost: ${level.name.lowercase().replaceFirstChar { it.uppercase() }}",
        { it.copy(volumeBoost = level) },
    ) { SettingsStore.setVolumeBoost(getApplication(), level) }

    fun setReduceMotion(enabled: Boolean) = change(
        "Reduce motion ${if (enabled) "on" else "off"}",
        { it.copy(reduceMotion = enabled) },
    ) { SettingsStore.setReduceMotion(getApplication(), enabled) }

    fun clearCache() = viewModelScope.launch {
        DeviceInfo.clearCache(getApplication())
        _uiState.value = _uiState.value.copy(
            cacheSizeLabel = DeviceInfo.cacheSizeLabel(getApplication()),
            cacheCleared = true,
        )
    }

    fun dismissCacheClearedNotice() {
        _uiState.value = _uiState.value.copy(cacheCleared = false)
    }

    fun logout() = viewModelScope.launch {
        AuthRepository.signOut()
        refreshStaticInfo()
    }
}
