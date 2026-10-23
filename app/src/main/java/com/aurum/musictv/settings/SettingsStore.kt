package com.aurum.musictv.settings

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

/**
 * Streaming quality for YouTube audio. Maps onto real YouTube audio tiers
 * (see YoutubeStreamResolver):
 *   LOW    — Opus ~50 kbps  (data saver, fastest start on weak Wi-Fi)
 *   NORMAL — ~128 kbps
 *   HIGH   — best stream YouTube offers (~160 kbps+)
 */
enum class AudioQuality { LOW, NORMAL, HIGH }

data class AppSettings(
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val autoplay: Boolean = true,
)

/**
 * Single source of truth for the Settings screen, backed by Jetpack
 * DataStore. Deliberately tiny: only settings that actually change what the
 * app does. The old Theme / Crossfade / Normalize Volume / Lossless options
 * were never wired to anything (and cost CPU/RAM on a TV box if they had
 * been), so they are gone.
 */
object SettingsStore {
    private val KEY_AUDIO_QUALITY = stringPreferencesKey("audio_quality")
    private val KEY_AUTOPLAY = booleanPreferencesKey("autoplay")

    fun observe(context: Context): Flow<AppSettings> =
        context.dataStore.data.map { prefs ->
            AppSettings(
                audioQuality = parseQuality(prefs[KEY_AUDIO_QUALITY]),
                autoplay = prefs[KEY_AUTOPLAY] ?: true,
            )
        }.distinctUntilChanged()

    /** Tolerates values saved by older builds (e.g. LOSSLESS_IF_AVAILABLE
     *  maps to HIGH) so an upgrade never resets or crashes the setting. */
    private fun parseQuality(raw: String?): AudioQuality = when (raw) {
        "LOW" -> AudioQuality.LOW
        "NORMAL" -> AudioQuality.NORMAL
        else -> AudioQuality.HIGH
    }

    suspend fun setAudioQuality(context: Context, quality: AudioQuality) {
        context.dataStore.edit { it[KEY_AUDIO_QUALITY] = quality.name }
    }

    suspend fun setAutoplay(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTOPLAY] = enabled }
    }

    /** One-off read (callers that don't keep their own collector). */
    suspend fun snapshot(context: Context): AppSettings = observe(context).first()
}
