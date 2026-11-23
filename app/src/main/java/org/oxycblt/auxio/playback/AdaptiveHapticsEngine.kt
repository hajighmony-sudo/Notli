/*
 * Copyright (c) 2021 Auxio Project
 * AdaptiveHapticsEngine.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Converts decoded PCM into restrained, tempo-aware haptic pulses.
 *
 * Analysis runs on Notli's existing Media3 PCM path: no microphone, audio capture, network access,
 * or changes to the samples sent to the speaker. The three profiles use the same bounded detector
 * with different trigger rules so sustained bass cannot turn into continuous vibration.
 */
class AdaptiveHapticsEngine
@Inject
constructor(
    @ApplicationContext context: Context,
    private val playbackSettings: PlaybackSettings,
) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private var lowPass = 0f
    private var noiseFloor = 0.010f
    private var slowEnvelope = 0f
    private var lastPulseAt = 0L
    private var estimatedBeatMs = DEFAULT_BEAT_MS

    fun process(input: ByteBuffer, sampleRate: Int, channelCount: Int) {
        val motor = vibrator ?: return
        if (!playbackSettings.adaptiveHapticsEnabled || !motor.hasVibrator()) return
        if (sampleRate <= 0 || channelCount <= 0) return

        val samples = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameSize = channelCount * BYTES_PER_SAMPLE
        val frames = samples.remaining() / frameSize
        if (frames <= 0) return

        val bassAlpha = lowPassAlpha(BASS_CUTOFF_HZ, sampleRate)
        var bassEnergy = 0.0
        var fullEnergy = 0.0
        repeat(frames) {
            var mono = 0f
            repeat(channelCount) {
                mono += samples.getShort().toFloat() / PCM_SCALE
            }
            mono /= channelCount
            lowPass += bassAlpha * (mono - lowPass)
            bassEnergy += lowPass * lowPass
            fullEnergy += mono * mono
        }

        val bassLevel = sqrt(bassEnergy / frames).toFloat()
        val fullLevel = sqrt(fullEnergy / frames).toFloat()
        val now = SystemClock.elapsedRealtime()
        val sensitivity = playbackSettings.adaptiveHapticsSensitivity.coerceIn(1, 5)
        val sensitivityScale = 1.65f - ((sensitivity - 1) * 0.225f)
        val threshold = max(MIN_NOISE_THRESHOLD, noiseFloor * sensitivityScale)
        val onset = fullLevel > max(MIN_ONSET_LEVEL, slowEnvelope * ONSET_RATIO)
        val bassHit = bassLevel > threshold
        val mode = playbackSettings.adaptiveHapticsMode

        // A slow envelope follows overall loudness and adapts the trigger floor to quiet/loud
        // tracks.
        slowEnvelope += ENVELOPE_ALPHA * (fullLevel - slowEnvelope)
        noiseFloor =
            (noiseFloor * NOISE_FLOOR_DECAY + bassLevel * NOISE_FLOOR_ATTACK).coerceIn(
                MIN_NOISE_FLOOR,
                MAX_NOISE_FLOOR,
            )

        val minimumInterval =
            when (mode) {
                MODE_BASS -> BASS_REFRACTORY_MS
                MODE_RHYTHM -> RHYTHM_REFRACTORY_MS
                else -> max(SMART_MIN_INTERVAL_MS, (estimatedBeatMs * 0.42f).toLong())
            }
        if (now - lastPulseAt < minimumInterval) return

        val shouldPulse =
            when (mode) {
                MODE_BASS -> bassHit
                MODE_RHYTHM ->
                    onset && fullLevel > max(MIN_RHYTHM_LEVEL, threshold * RHYTHM_LEVEL_FACTOR)
                else -> bassHit && (onset || fullLevel > slowEnvelope * SMART_SUSTAINED_RATIO)
            }
        if (!shouldPulse) return

        if (lastPulseAt != 0L) {
            val interval = now - lastPulseAt
            if (interval in MIN_BEAT_MS..MAX_BEAT_MS) {
                estimatedBeatMs =
                    (estimatedBeatMs * 0.72f + interval * 0.28f).coerceIn(
                        MIN_BEAT_MS.toFloat(),
                        MAX_BEAT_MS.toFloat(),
                    )
            }
        }

        val signal =
            when (mode) {
                MODE_RHYTHM -> fullLevel
                else -> bassLevel
            }
        val strength =
            ((signal - threshold) / (threshold * STRENGTH_RANGE)).coerceIn(
                MIN_STRENGTH,
                MAX_STRENGTH,
            )
        val intensity = playbackSettings.adaptiveHapticsIntensity.coerceIn(1, 5)
        val userScale = intensity / 5f
        val duration =
            (MIN_PULSE_MS + strength * MAX_EXTRA_PULSE_MS * userScale)
                .toLong()
                .coerceIn(MIN_PULSE_MS, MAX_PULSE_MS)
        val amplitude =
            (MIN_AMPLITUDE + strength * AMPLITUDE_RANGE * userScale)
                .toInt()
                .coerceIn(MIN_AMPLITUDE.toInt(), MAX_AMPLITUDE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val resolvedAmplitude =
                if (motor.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE
            motor.vibrate(VibrationEffect.createOneShot(duration, resolvedAmplitude))
        } else {
            @Suppress("DEPRECATION") motor.vibrate(duration)
        }
        lastPulseAt = now
    }

    fun stop() {
        vibrator?.cancel()
        resetDetector()
    }

    private fun resetDetector() {
        lowPass = 0f
        noiseFloor = 0.010f
        slowEnvelope = 0f
        lastPulseAt = 0L
        estimatedBeatMs = DEFAULT_BEAT_MS
    }

    private fun lowPassAlpha(cutoffHz: Float, sampleRate: Int): Float =
        (1.0 - exp(-2.0 * Math.PI * cutoffHz / sampleRate)).toFloat()

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val PCM_SCALE = 32768f
        const val BASS_CUTOFF_HZ = 125f
        const val MODE_BASS = 0
        const val MODE_RHYTHM = 1
        const val MODE_SMART = 2
        const val DEFAULT_BEAT_MS = 500f
        const val MIN_BEAT_MS = 280L
        const val MAX_BEAT_MS = 1100L
        const val BASS_REFRACTORY_MS = 145L
        const val RHYTHM_REFRACTORY_MS = 105L
        const val SMART_MIN_INTERVAL_MS = 110L
        const val MIN_PULSE_MS = 10L
        const val MAX_PULSE_MS = 34L
        const val MAX_EXTRA_PULSE_MS = 22f
        const val MIN_AMPLITUDE = 34f
        const val AMPLITUDE_RANGE = 150f
        const val MAX_AMPLITUDE = 184
        const val MIN_NOISE_THRESHOLD = 0.018f
        const val MIN_NOISE_FLOOR = 0.006f
        const val MAX_NOISE_FLOOR = 0.14f
        const val NOISE_FLOOR_DECAY = 0.965f
        const val NOISE_FLOOR_ATTACK = 0.035f
        const val ENVELOPE_ALPHA = 0.12f
        const val ONSET_RATIO = 1.24f
        const val MIN_ONSET_LEVEL = 0.018f
        const val MIN_RHYTHM_LEVEL = 0.025f
        const val RHYTHM_LEVEL_FACTOR = 1.25f
        const val SMART_SUSTAINED_RATIO = 1.12f
        const val STRENGTH_RANGE = 3.0f
        const val MIN_STRENGTH = 0.12f
        const val MAX_STRENGTH = 1f
    }
}
