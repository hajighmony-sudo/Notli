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
 * Streaming, three-band haptic analyzer adapted from NAVAQ's HapticAnalyzer design.
 *
 * It classifies low/mid/high transients from Notli's already-decoded PCM stream. It does not
 * capture microphone audio, alter samples, or send audio anywhere. The actuator output is
 * deliberately bounded to short pulses to avoid continuous or excessive vibration.
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

    private var lowState = 0f
    private var highState = 0f
    private var bassFloor = ENERGY_FLOOR
    private var midFloor = ENERGY_FLOOR
    private var highFloor = ENERGY_FLOOR
    private var hopSamples = 0
    private var bassEnergy = 0.0
    private var midEnergy = 0.0
    private var highEnergy = 0.0
    private var lastPulseAt = 0L
    private var sampleRate = 0
    private var channels = 0

    fun process(input: ByteBuffer, sampleRate: Int, channelCount: Int) {
        val motor = vibrator ?: return
        if (!playbackSettings.adaptiveHapticsEnabled || !motor.hasVibrator()) return
        if (sampleRate < MIN_SAMPLE_RATE || channelCount <= 0) return

        if (this.sampleRate != sampleRate || channels != channelCount) {
            this.sampleRate = sampleRate
            channels = channelCount
            resetDetector()
        }

        val samples = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frames = samples.remaining() / (channelCount * BYTES_PER_SAMPLE)
        if (frames <= 0) return

        val lowAlpha = lowPassAlpha(BASS_CUTOFF_HZ, sampleRate)
        val highAlpha = lowPassAlpha(HIGH_CUTOFF_HZ, sampleRate)
        val hopSize = (sampleRate * HOP_MS / 1000).coerceAtLeast(1)
        val mode = playbackSettings.adaptiveHapticsMode
        val sensitivity = playbackSettings.adaptiveHapticsSensitivity.coerceIn(1, 5)
        val onsetRatio = ONSET_RATIO_BASE - (sensitivity - 1) * SENSITIVITY_STEP
        val intensity = playbackSettings.adaptiveHapticsIntensity.coerceIn(1, 5)

        repeat(frames) {
            var mono = 0f
            repeat(channelCount) { mono += samples.getShort().toFloat() / PCM_SCALE }
            mono /= channelCount

            lowState += lowAlpha * (mono - lowState)
            highState += highAlpha * (mono - highState)
            val bass = lowState
            val mid = highState - lowState
            val high = mono - highState

            bassEnergy += bass * bass
            midEnergy += mid * mid
            highEnergy += high * high
            hopSamples++

            if (hopSamples >= hopSize) {
                val count = hopSamples.toDouble()
                val bassRms = sqrt(bassEnergy / count).toFloat()
                val midRms = sqrt(midEnergy / count).toFloat()
                val highRms = sqrt(highEnergy / count).toFloat()
                analyzeHop(bassRms, midRms, highRms, onsetRatio, mode, intensity)
                hopSamples = 0
                bassEnergy = 0.0
                midEnergy = 0.0
                highEnergy = 0.0
            }
        }
    }

    private fun analyzeHop(
        bass: Float,
        mid: Float,
        high: Float,
        onsetRatio: Float,
        mode: Int,
        intensity: Int,
    ) {
        val now = SystemClock.elapsedRealtime()
        val strongest = max(bass, max(mid, high))
        val bassHit = isOnset(bass, bassFloor, onsetRatio)
        val midHit = isOnset(mid, midFloor, onsetRatio)
        val highHit = isOnset(high, highFloor, onsetRatio)

        // Adapt the local noise floor slowly so quiet and loud tracks remain usable.
        bassFloor = adaptFloor(bassFloor, bass)
        midFloor = adaptFloor(midFloor, mid)
        highFloor = adaptFloor(highFloor, high)

        val event =
            when (mode) {
                MODE_BASS -> if (bassHit) Event.BASS else null
                MODE_RHYTHM ->
                    when {
                        midHit -> Event.SNARE
                        highHit -> Event.HIHAT
                        bassHit -> Event.BASS
                        else -> null
                    }
                else ->
                    when {
                        bassHit -> Event.BASS
                        midHit && mid >= strongest * BAND_DOMINANCE -> Event.SNARE
                        highHit && high >= strongest * BAND_DOMINANCE -> Event.HIHAT
                        else -> null
                    }
            } ?: return

        if (now - lastPulseAt < GLOBAL_REFRACTORY_MS) return
        val signal = when (event) {
            Event.BASS -> bass
            Event.SNARE -> mid
            Event.HIHAT -> high
        }
        val floor = when (event) {
            Event.BASS -> bassFloor
            Event.SNARE -> midFloor
            Event.HIHAT -> highFloor
        }.coerceAtLeast(ENERGY_FLOOR)
        val strength = ((signal / floor - onsetRatio) / STRENGTH_RANGE).coerceIn(0.15f, 1f)
        val userScale = intensity / 5f

        // NAVAQ-inspired event envelopes: bass gets a fuller thump, transients stay crisp.
        val baseDuration = when (event) {
            Event.BASS -> BASS_DURATION_MS
            Event.SNARE -> SNARE_DURATION_MS
            Event.HIHAT -> HIHAT_DURATION_MS
        }
        val duration = (baseDuration * (0.55f + 0.45f * userScale) * (0.8f + 0.2f * strength))
            .toLong().coerceIn(MIN_PULSE_MS, MAX_PULSE_MS)
        val amplitude = (MIN_AMPLITUDE + (MAX_AMPLITUDE - MIN_AMPLITUDE) * strength * userScale)
            .toInt().coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)

        val motor = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val resolved = if (motor.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE
            motor.vibrate(VibrationEffect.createOneShot(duration, resolved))
        } else {
            @Suppress("DEPRECATION")
            motor.vibrate(duration)
        }
        lastPulseAt = now
    }

    fun stop() {
        vibrator?.cancel()
        resetDetector()
    }

    private fun isOnset(level: Float, floor: Float, ratio: Float): Boolean =
        level >= MIN_EVENT_LEVEL && level > floor.coerceAtLeast(ENERGY_FLOOR) * ratio

    private fun adaptFloor(previous: Float, current: Float): Float {
        val rate = if (current < previous) FLOOR_FALL_RATE else FLOOR_RISE_RATE
        return (previous + rate * (current - previous)).coerceIn(ENERGY_FLOOR, MAX_FLOOR)
    }

    private fun resetDetector() {
        lowState = 0f
        highState = 0f
        bassFloor = ENERGY_FLOOR
        midFloor = ENERGY_FLOOR
        highFloor = ENERGY_FLOOR
        hopSamples = 0
        bassEnergy = 0.0
        midEnergy = 0.0
        highEnergy = 0.0
        lastPulseAt = 0L
    }

    private fun lowPassAlpha(cutoffHz: Float, sampleRate: Int): Float =
        (1.0 - exp(-2.0 * Math.PI * cutoffHz / sampleRate)).toFloat()

    private enum class Event { BASS, SNARE, HIHAT }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val PCM_SCALE = 32768f
        const val MIN_SAMPLE_RATE = 8_000
        const val BASS_CUTOFF_HZ = 150f
        const val HIGH_CUTOFF_HZ = 3_500f
        const val HOP_MS = 5
        const val MODE_BASS = 0
        const val MODE_RHYTHM = 1
        const val MODE_SMART = 2
        const val ONSET_RATIO_BASE = 2.2f
        const val SENSITIVITY_STEP = 0.22f
        const val STRENGTH_RANGE = 5f
        const val BAND_DOMINANCE = 0.1f
        const val ENERGY_FLOOR = 0.0001f
        const val MAX_FLOOR = 0.5f
        const val MIN_EVENT_LEVEL = 0.006f
        const val FLOOR_FALL_RATE = 0.08f
        const val FLOOR_RISE_RATE = 0.015f
        const val GLOBAL_REFRACTORY_MS = 70L
        const val MIN_PULSE_MS = 15L
        const val MAX_PULSE_MS = 110L
        const val BASS_DURATION_MS = 92f
        const val SNARE_DURATION_MS = 52f
        const val HIHAT_DURATION_MS = 22f
        const val MIN_AMPLITUDE = 12
        const val MAX_AMPLITUDE = 180
    }
}
