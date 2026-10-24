package com.aurum.musictv.settings

import android.app.ActivityManager
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "aurum_tv_settings")

/** YouTube audio tier: LOW ~50 kbps, NORMAL ~128 kbps, HIGH best available. */
enum class AudioQuality { LOW, NORMAL, HIGH }

/** Equalizer presets (applied live through android.media.audiofx). */
enum class EqPreset { FLAT, BASS, VOCAL, TREBLE, LOUD }

/** Strength steps for Bass Boost / Volume Boost. */
enum class FxLevel { OFF, LOW, MEDIUM, HIGH }

data class AppSettings(
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val autoplay: Boolean = true,
    /** false = tapping a song only plays it (mini player shows at the bottom,
     *  Spotify-style); true = also jump to the full player. */
    val openPlayerOnTap: Boolean = false,
    val skipSilence: Boolean = false,
    val eqPreset: EqPreset = EqPreset.FLAT,
    val bassBoost: FxLevel = FxLevel.OFF,
    val volumeBoost: FxLevel = FxLevel.OFF,
    /** Stops the Home hero auto-rotation + fades (saves CPU on weak boxes). */
    val reduceMotion: Boolean = false,
)

/**
 * Single source of truth for Settings, backed by DataStore. Every value is a
 * Flow, so the playback service (audio effects, skip-silence) and the
 * PlayerManager (quality) react the moment a setting changes — no restart,
 * no waiting for the next song.
 */
object SettingsStore {
    private val KEY_AUDIO_QUALITY = stringPreferencesKey("audio_quality")
    private val KEY_AUTOPLAY = booleanPreferencesKey("autoplay")
    private val KEY_OPEN_PLAYER = booleanPreferencesKey("open_player_on_tap")
    private val KEY_SKIP_SILENCE = booleanPreferencesKey("skip_silence")
    private val KEY_EQ = stringPreferencesKey("eq_preset")
    private val KEY_BASS = stringPreferencesKey("bass_boost")
    private val KEY_VOLUME = stringPreferencesKey("volume_boost")
    private val KEY_REDUCE_MOTION = booleanPreferencesKey("reduce_motion")

    fun observe(context: Context): Flow<AppSettings> =
        context.dataStore.data.map { prefs ->
            AppSettings(
                audioQuality = parseQuality(prefs[KEY_AUDIO_QUALITY]),
                autoplay = prefs[KEY_AUTOPLAY] ?: true,
                openPlayerOnTap = prefs[KEY_OPEN_PLAYER] ?: false,
                skipSilence = prefs[KEY_SKIP_SILENCE] ?: false,
                eqPreset = parseEnum(prefs[KEY_EQ], EqPreset.FLAT),
                bassBoost = parseEnum(prefs[KEY_BASS], FxLevel.OFF),
                volumeBoost = parseEnum(prefs[KEY_VOLUME], FxLevel.OFF),
                reduceMotion = prefs[KEY_REDUCE_MOTION] ?: isLowRam(context),
            )
        }.distinctUntilChanged()

    private fun parseQuality(raw: String?): AudioQuality = when (raw) {
        "LOW" -> AudioQuality.LOW
        "NORMAL" -> AudioQuality.NORMAL
        else -> AudioQuality.HIGH
    }

    /** 1 GB TV boxes report isLowRamDevice: Reduce motion starts ON there. */
    private fun isLowRam(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }.getOrDefault(false)

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: default

    suspend fun setAudioQuality(context: Context, quality: AudioQuality) {
        context.dataStore.edit { it[KEY_AUDIO_QUALITY] = quality.name }
    }

    suspend fun setAutoplay(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTOPLAY] = enabled }
    }

    suspend fun setOpenPlayerOnTap(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_OPEN_PLAYER] = enabled }
    }

    suspend fun setSkipSilence(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_SKIP_SILENCE] = enabled }
    }

    suspend fun setEqPreset(context: Context, preset: EqPreset) {
        context.dataStore.edit { it[KEY_EQ] = preset.name }
    }

    suspend fun setBassBoost(context: Context, level: FxLevel) {
        context.dataStore.edit { it[KEY_BASS] = level.name }
    }

    suspend fun setVolumeBoost(context: Context, level: FxLevel) {
        context.dataStore.edit { it[KEY_VOLUME] = level.name }
    }

    suspend fun setReduceMotion(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_REDUCE_MOTION] = enabled }
    }

    /** One-off read (callers that don't keep their own collector). */
    suspend fun snapshot(context: Context): AppSettings = observe(context).first()
}
