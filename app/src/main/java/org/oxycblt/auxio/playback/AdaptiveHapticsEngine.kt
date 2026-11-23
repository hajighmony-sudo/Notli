/*
 * Copyright (c) 2026 Notli contributors.
 * This file is part of Notli and is distributed under the GNU General Public License v3.0.
 */

package org.oxycblt.auxio.playback

import android.content.Context
import android.media.VibrationEffect
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Converts the low-frequency envelope of Notli's decoded PCM stream into restrained haptic pulses.
 *
 * It never captures microphone input and does not modify audio samples. A refractory window and
 * adaptive noise floor keep the motor from buzzing continuously on sustained bass.
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
    private var noiseFloor = 0.012f
    private var lastPulseAt = 0L

    fun process(input: ByteBuffer, sampleRate: Int, channelCount: Int) {
        val motor = vibrator ?: return
        if (!playbackSettings.adaptiveHapticsEnabled || !motor.hasVibrator()) return
        if (sampleRate <= 0 || channelCount <= 0) return

        val samples = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameSize = channelCount * Short.SIZE_BYTES
        val frames = samples.remaining() / frameSize
        if (frames <= 0) return

        val alpha = (1.0 - exp(-2.0 * Math.PI * 125.0 / sampleRate)).toFloat()
        var energy = 0.0
        repeat(frames) {
            var mono = 0f
            repeat(channelCount) {
                mono += samples.short.toFloat() / 32768f
            }
            mono /= channelCount
            lowPass += alpha * (mono - lowPass)
            energy += lowPass * lowPass
        }

        val bassLevel = sqrt(energy / frames).toFloat()
        val threshold = max(0.026f, noiseFloor * 1.75f)
        noiseFloor = (noiseFloor * 0.94f + bassLevel * 0.06f).coerceIn(0.008f, 0.18f)

        val now = SystemClock.elapsedRealtime()
        if (bassLevel <= threshold || now - lastPulseAt < MIN_PULSE_INTERVAL_MS) return

        val strength = ((bassLevel - threshold) / (threshold * 2.6f)).coerceIn(0f, 1f)
        val duration = MIN_PULSE_DURATION_MS + (strength * 18f).toLong()
        val amplitude = (72 + strength * 150f).toInt().coerceIn(1, 222)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            motor.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            motor.vibrate(duration)
        }
        lastPulseAt = now
    }

    fun stop() {
        vibrator?.cancel()
        lowPass = 0f
        noiseFloor = 0.012f
        lastPulseAt = 0L
    }

    private companion object {
        const val MIN_PULSE_INTERVAL_MS = 115L
        const val MIN_PULSE_DURATION_MS = 12L
    }
}
