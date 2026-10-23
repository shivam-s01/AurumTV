package com.aurum.musictv.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import com.aurum.musictv.settings.AppSettings
import com.aurum.musictv.settings.EqPreset
import com.aurum.musictv.settings.FxLevel

/**
 * Live DSP (Equalizer / Bass Boost / Volume Boost) bound to the player's
 * audio session. Native effects run inside the audio server, so the cost on
 * a 1 GB box is negligible. Every call is guarded: many cheap TV boxes lack
 * some effects, and a failing effect must never break playback. Band gains
 * are always clamped to the device's own range (an out-of-range gain can
 * corrupt the audio session on some chipsets).
 */
class AudioFx {
    private var sessionId = 0
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var loud: LoudnessEnhancer? = null

    fun apply(newSessionId: Int, s: AppSettings) {
        if (newSessionId <= 0) return
        if (newSessionId != sessionId) {
            release()
            sessionId = newSessionId
        }
        applyEq(s.eqPreset)
        applyBass(s.bassBoost)
        applyLoud(s.volumeBoost)
    }

    private fun applyEq(preset: EqPreset) {
        if (preset == EqPreset.FLAT) {
            runCatching { eq?.setEnabled(false) }
            return
        }
        val e = eq ?: runCatching { Equalizer(0, sessionId) }.getOrNull()?.also { eq = it } ?: return
        runCatching {
            val range = e.bandLevelRange
            val lo = range[0].toInt()
            val hi = range[1].toInt()
            for (band in 0 until e.numberOfBands.toInt()) {
                val hz = e.getCenterFreq(band.toShort()) / 1000
                val mb = (curveDb(preset, hz) * 100f).toInt().coerceIn(lo, hi)
                e.setBandLevel(band.toShort(), mb.toShort())
            }
            e.setEnabled(true)
        }
    }

    private fun curveDb(preset: EqPreset, hz: Int): Float = when (preset) {
        EqPreset.BASS -> when {
            hz < 150 -> 5f
            hz < 500 -> 3f
            else -> 0f
        }
        EqPreset.VOCAL -> when {
            hz < 150 -> -2f
            hz < 500 -> 0f
            hz < 4000 -> 4f
            else -> 1f
        }
        EqPreset.TREBLE -> when {
            hz < 500 -> -1f
            hz < 2500 -> 0f
            hz < 8000 -> 3f
            else -> 5f
        }
        EqPreset.LOUD -> when {
            hz < 150 -> 4f
            hz < 500 -> 1f
            hz < 2500 -> -1f
            hz < 8000 -> 2f
            else -> 4f
        }
        EqPreset.FLAT -> 0f
    }

    private fun applyBass(level: FxLevel) {
        if (level == FxLevel.OFF) {
            runCatching { bass?.setEnabled(false) }
            return
        }
        val b = bass ?: runCatching { BassBoost(0, sessionId) }.getOrNull()?.also { bass = it } ?: return
        runCatching {
            val strength = when (level) {
                FxLevel.LOW -> 300
                FxLevel.MEDIUM -> 600
                else -> 900
            }
            if (b.strengthSupported) b.setStrength(strength.toShort())
            b.setEnabled(true)
        }
    }

    private fun applyLoud(level: FxLevel) {
        if (level == FxLevel.OFF) {
            runCatching { loud?.setEnabled(false) }
            return
        }
        val l = loud ?: runCatching { LoudnessEnhancer(sessionId) }.getOrNull()?.also { loud = it } ?: return
        runCatching {
            val gainMb = when (level) {
                FxLevel.LOW -> 250
                FxLevel.MEDIUM -> 500
                else -> 800
            }
            l.setTargetGain(gainMb)
            l.setEnabled(true)
        }
    }

    fun release() {
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        runCatching { loud?.release() }
        eq = null
        bass = null
        loud = null
        sessionId = 0
    }
}
