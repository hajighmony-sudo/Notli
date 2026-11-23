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

/** Mixes the non-vocal Demucs stems into a single PCM16 WAV without loading the whole song in RAM. */
internal object InstrumentalMixer {
    private const val WAV_HEADER_BYTES = 44
    private const val CHUNK_BYTES = 16 * 1024

    fun mix(stemsDirectory: File, destination: File) {
        val names = listOf("drums.wav", "bass.wav", "other.wav", "guitar.wav", "piano.wav")
        val files = names.map { File(stemsDirectory, it) }.filter { it.isFile && it.length() > WAV_HEADER_BYTES }
        require(files.size >= 3) { "The separation engine did not produce enough instrumental stems." }

        val inputs = files.map(::FileInputStream)
        try {
            val headers = inputs.map { input ->
                ByteArray(WAV_HEADER_BYTES).also { readFully(input, it) }
            }
            val dataBytes = ByteBuffer.wrap(headers.first()).order(ByteOrder.LITTLE_ENDIAN).getInt(40)
            require(dataBytes > 0 && headers.all {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getInt(40) == dataBytes
            }) { "The generated stems have incompatible WAV lengths." }

            val header = headers.first().copyOf()
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(4, dataBytes + 36)
                .putInt(40, dataBytes)

            FileOutputStream(destination).use { output ->
                output.write(header)
                val sourceBuffers = inputs.map { ByteArray(CHUNK_BYTES) }
                val mixed = ByteArray(CHUNK_BYTES)
                var remaining = dataBytes
                while (remaining > 0) {
                    val count = minOf(CHUNK_BYTES, remaining)
                    sourceBuffers.forEachIndexed { index, bytes -> readFully(inputs[index], bytes, count) }
                    val samples = count / 2
                    val sources = sourceBuffers.map { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
                    val out = ByteBuffer.wrap(mixed).order(ByteOrder.LITTLE_ENDIAN)
                    for (sample in 0 until samples) {
                        var sum = 0
                        sources.forEach { source -> sum += source.short.toInt() }
                        out.putShort(sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                    }
                    output.write(mixed, 0, count)
                    remaining -= count
                }
            }
        } finally {
            inputs.forEach { try { it.close() } catch (_: Exception) {} }
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
}
