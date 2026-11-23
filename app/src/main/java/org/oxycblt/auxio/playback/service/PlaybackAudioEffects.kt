/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackAudioEffects.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback.service

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.preference.PreferenceManager

/**
 * Session-scoped audio processing. Unsupported effects are treated as unavailable instead of
 * failing playback. Spatialization is delegated to Android when a compatible Bluetooth route is
 * active; this does not claim Dolby Atmos certification or synthesize Atmos metadata.
 */
internal class PlaybackAudioEffects(
    private val context: Context,
    private val player: ExoPlayer,
) : android.content.SharedPreferences.OnSharedPreferenceChangeListener {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                applySpatialization()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                applySpatialization()
            }
        }

    init {
        preferences.registerOnSharedPreferenceChangeListener(this)
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        applyAll()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: android.content.SharedPreferences?,
        key: String?,
    ) {
        applyAll()
    }

    fun release() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        releaseEffects()
    }

    private fun applyAll() {
        ensureEffects()
        applyEqualizer()
        applyBassBoost()
        applySpatialization()
        applyPitch()
    }

    private fun ensureEffects() {
        val sessionId = player.audioSessionId
        if (sessionId <= 0 || equalizer != null || bassBoost != null || virtualizer != null) return

        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        if (Build.VERSION.SDK_INT < 32) {
            virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        }
    }

    private fun applyEqualizer() {
        val effect = equalizer ?: return
        val preset = preferences.getInt(KEY_EQ_PRESET, 0).coerceIn(0, 4)
        runCatching {
            val range = effect.bandLevelRange
            for (index in 0 until effect.numberOfBands.toInt()) {
                val band = index.toShort()
                val frequencyHz = effect.getCenterFreq(band) / 1_000
                val target =
                    when (preset) {
                        1 ->
                            when {
                                frequencyHz < 180 -> 650
                                frequencyHz < 1_000 -> 250
                                frequencyHz > 8_000 -> 150
                                else -> 0
                            }
                        2 ->
                            when (frequencyHz) {
                                in 250..4_000 -> 400
                                in 0..180 -> -200
                                else -> 0
                            }
                        3 -> if (frequencyHz > 3_000) 450 else if (frequencyHz < 180) -150 else 0
                        4 ->
                            when {
                                frequencyHz < 180 -> 500
                                frequencyHz < 400 -> 250
                                frequencyHz in 1_000..4_000 -> 180
                                frequencyHz > 8_000 -> 300
                                else -> 0
                            }
                        else -> 0
                    }
                effect.setBandLevel(
                    band,
                    target.coerceIn(range[0].toInt(), range[1].toInt()).toShort(),
                )
            }
            effect.enabled = true
        }
    }

    private fun applyBassBoost() {
        val effect = bassBoost ?: return
        runCatching {
            val strength = preferences.getInt(KEY_BASS_STRENGTH, 60).coerceIn(0, 100) * 10
            effect.setStrength(
                if (preferences.getBoolean(KEY_BASS_BOOST, false)) strength.toShort() else 0.toShort(),
            )
            effect.enabled = preferences.getBoolean(KEY_BASS_BOOST, false)
        }
    }

    private fun applySpatialization() {
        val bluetoothActive = hasBluetoothOutput()
        val requested = preferences.getBoolean(KEY_SPATIAL_AUDIO, true) && bluetoothActive
        val behavior =
            if (requested) {
                C.SPATIALIZATION_BEHAVIOR_AUTO
            } else {
                C.SPATIALIZATION_BEHAVIOR_NEVER
            }
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setSpatializationBehavior(behavior)
                    .build(),
                true,
            )
        }

        // Older Android versions do not expose the platform Spatializer API. Use the legacy
        // session virtualizer only as a fallback, and only for Bluetooth output.
        virtualizer?.let { effect ->
            runCatching {
                val strength = preferences.getInt(KEY_SPATIAL_STRENGTH, 60).coerceIn(0, 100) * 10
                effect.setStrength(if (requested) strength.toShort() else 0.toShort())
                effect.enabled = requested
            }
        }
    }

    private fun hasBluetoothOutput(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 &&
                    (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
    }

    private fun applyPitch() {
        val pitch =
            when (preferences.getInt(KEY_PITCH_EFFECT, 0).coerceIn(0, 2)) {
                1 -> 1.35f
                2 -> 0.85f
                else -> 1.0f
            }
        runCatching {
            player.setPlaybackParameters(PlaybackParameters(player.playbackParameters.speed, pitch))
        }
    }

    private fun releaseEffects() {
        listOfNotNull(equalizer, bassBoost, virtualizer).forEach { effect ->
            runCatching { effect.release() }
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
    }

    private companion object {
        const val KEY_EQ_PRESET = "auxio_audio_eq_preset"
        const val KEY_BASS_BOOST = "auxio_audio_bass_boost"
        const val KEY_BASS_STRENGTH = "auxio_audio_bass_strength"
        const val KEY_SPATIAL_AUDIO = "auxio_audio_spatial"
        const val KEY_SPATIAL_STRENGTH = "auxio_audio_spatial_strength"
        const val KEY_PITCH_EFFECT = "auxio_audio_pitch"
    }
}
