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
                _uiState.value = _uiState.value.copy(settings = settings)
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

    fun setAudioQuality(quality: AudioQuality) = viewModelScope.launch {
        SettingsStore.setAudioQuality(getApplication(), quality)
    }

    fun setAutoplay(enabled: Boolean) = viewModelScope.launch {
        SettingsStore.setAutoplay(getApplication(), enabled)
    }

    fun setOpenPlayerOnTap(enabled: Boolean) = viewModelScope.launch {
        SettingsStore.setOpenPlayerOnTap(getApplication(), enabled)
    }

    fun setSkipSilence(enabled: Boolean) = viewModelScope.launch {
        SettingsStore.setSkipSilence(getApplication(), enabled)
    }

    fun setEqPreset(preset: EqPreset) = viewModelScope.launch {
        SettingsStore.setEqPreset(getApplication(), preset)
    }

    fun setBassBoost(level: FxLevel) = viewModelScope.launch {
        SettingsStore.setBassBoost(getApplication(), level)
    }

    fun setVolumeBoost(level: FxLevel) = viewModelScope.launch {
        SettingsStore.setVolumeBoost(getApplication(), level)
    }

    fun setReduceMotion(enabled: Boolean) = viewModelScope.launch {
        SettingsStore.setReduceMotion(getApplication(), enabled)
    }

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
