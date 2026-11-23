/*
 * Copyright (c) 2026 NOTLI contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package org.oxycblt.auxio.vocal

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** Mixes the non-vocal Demucs stems into one stereo PCM16 WAV without loading the song into RAM. */
internal object InstrumentalMixer {
    private const val WAV_HEADER_BYTES = 44
    private const val CHUNK_BYTES = 16 * 1024

    fun mix(
        stemsDirectory: File,
        destination: File,
        shouldContinue: () -> Boolean = { true },
    ) {
        val required = listOf("drums.wav", "bass.wav", "other.wav")
        val optional = listOf("guitar.wav", "piano.wav")
        val files = (required + optional)
            .map { File(stemsDirectory, it) }
            .filter { it.isFile && it.length() > WAV_HEADER_BYTES }

        require(required.all { name -> files.any { it.name == name } }) {
            "The separation engine did not produce the required instrumental stems."
        }

        val headers = files.map { file ->
            FileInputStream(file).use { input ->
                ByteArray(WAV_HEADER_BYTES).also { readFully(input, it) }
            }
        }
        val first = headers.first()
        val firstFormat = ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN)
        require(first.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            first.copyOfRange(8, 12).decodeToString() == "WAVE" &&
            firstFormat.getShort(20).toInt() == 1 &&
            firstFormat.getShort(22).toInt() == 2 &&
            firstFormat.getInt(24) == 44_100 &&
            firstFormat.getShort(34).toInt() == 16
        ) { "The separation engine returned an unsupported WAV format." }

        val dataBytes = firstFormat.getInt(40)
        require(dataBytes > 0 && dataBytes % 4 == 0 && headers.all { header ->
            val format = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            format.getInt(40) == dataBytes &&
                format.getShort(22).toInt() == 2 &&
                format.getInt(24) == 44_100 &&
                format.getShort(34).toInt() == 16
        }) { "The generated stems have incompatible WAV formats or lengths." }

        // First pass finds the peak of the summed stems. A single global gain prevents
        // hard clipping while preserving the relative dynamics of the instrumental mix.
        val peak = scanPeak(files, dataBytes, shouldContinue)
        val gain = if (peak > Short.MAX_VALUE) Short.MAX_VALUE.toDouble() / peak else 1.0

        val outputHeader = first.copyOf()
        ByteBuffer.wrap(outputHeader).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4, dataBytes + 36)
            .putInt(40, dataBytes)

        openInputs(files).use { inputs ->
            FileOutputStream(destination).use { output ->
                output.write(outputHeader)
                mixPass(inputs, output, dataBytes, gain, shouldContinue)
            }
        }
    }

    private fun scanPeak(
        files: List<File>,
        dataBytes: Int,
        shouldContinue: () -> Boolean,
    ): Double {
        var peak = 0.0
        openInputs(files).use { inputs ->
            val buffers = inputs.map { ByteArray(CHUNK_BYTES) }
            var remaining = dataBytes
            while (remaining > 0) {
                if (!shouldContinue()) throw CancellationException("Instrumental mixing cancelled.")
                val count = minOf(CHUNK_BYTES, remaining)
                buffers.forEachIndexed { index, bytes -> readFully(inputs[index], bytes, count) }
                val sources = buffers.map { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
                repeat(count / 2) {
                    var sum = 0
                    sources.forEach { sum += it.short.toInt() }
                    peak = maxOf(peak, kotlin.math.abs(sum.toDouble()))
                }
                remaining -= count
            }
        }
        return peak
    }

    private fun mixPass(
        inputs: List<FileInputStream>,
        output: FileOutputStream,
        dataBytes: Int,
        gain: Double,
        shouldContinue: () -> Boolean,
    ) {
        val buffers = inputs.map { ByteArray(CHUNK_BYTES) }
        val mixed = ByteArray(CHUNK_BYTES)
        var remaining = dataBytes
        while (remaining > 0) {
            if (!shouldContinue()) throw CancellationException("Instrumental mixing cancelled.")
            val count = minOf(CHUNK_BYTES, remaining)
            buffers.forEachIndexed { index, bytes -> readFully(inputs[index], bytes, count) }
            val sources = buffers.map { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
            val destinationBuffer = ByteBuffer.wrap(mixed).order(ByteOrder.LITTLE_ENDIAN)
            repeat(count / 2) {
                var sum = 0
                sources.forEach { sum += it.short.toInt() }
                destinationBuffer.putShort((sum * gain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            output.write(mixed, 0, count)
            remaining -= count
        }
    }

    private fun openInputs(files: List<File>): List<FileInputStream> =
        files.map { FileInputStream(it).apply { skipFully(WAV_HEADER_BYTES) } }

    private fun FileInputStream.skipFully(bytes: Int) {
        var remaining = bytes.toLong()
        while (remaining > 0) {
            val skipped = skip(remaining)
            check(skipped > 0) { "Could not skip the WAV header." }
            remaining -= skipped
        }
    }

    private fun readFully(input: FileInputStream, buffer: ByteArray, length: Int = buffer.size) {
        var offset = 0
        while (offset < length) {
            val count = input.read(buffer, offset, length - offset)
            check(count > 0) { "Unexpected end of WAV stem." }
            offset += count
        }
    }

    private inline fun <T> List<FileInputStream>.use(block: (List<FileInputStream>) -> T): T {
        try {
            return block(this)
        } finally {
            forEach { runCatching { it.close() } }
        }
    }
}
