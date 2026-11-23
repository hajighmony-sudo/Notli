/*
 * Copyright (c) 2026 NOTLI contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package org.oxycblt.auxio.vocal

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class DecodedPcm(val sampleRate: Int, val channels: Int)

/** Decodes the selected local track to interleaved float32 PCM for the native engine. */
internal object AudioPcmDecoder {
    fun decode(context: Context, uri: Uri, destination: File): DecodedPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var sourceFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = index
                    sourceFormat = format
                    break
                }
            }
            check(track >= 0 && sourceFormat != null) { "No decodable audio track was found." }
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(sourceFormat.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()

            var sampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            BufferedOutputStream(FileOutputStream(destination), 256 * 1024).use { output ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                            inputBuffer.clear()
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = decoder.outputFormat
                            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else AudioFormat.ENCODING_PCM_16BIT
                            require(channels in 1..8) { "Unsupported channel count: $channels" }
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT || encoding == AudioFormat.ENCODING_PCM_8BIT) {
                                "Unsupported decoded PCM format."
                            }
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            val buffer = decoder.getOutputBuffer(outputIndex)!!
                            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val samples = info.size / when (encoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> 4
                                    AudioFormat.ENCODING_PCM_8BIT -> 1
                                    else -> 2
                                }
                                val converted = ByteBuffer.allocate(samples * 4).order(ByteOrder.LITTLE_ENDIAN)
                                when (encoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> {
                                        val pcm = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                                        while (pcm.remaining() >= 4) converted.putFloat(pcm.float.coerceIn(-1f, 1f))
                                    }
                                    AudioFormat.ENCODING_PCM_8BIT -> while (buffer.hasRemaining()) {
                                        converted.putFloat(((buffer.get().toInt() and 0xff) - 128) / 128f)
                                    }
                                    else -> while (buffer.remaining() >= 2) {
                                        converted.putFloat((buffer.short / 32768f).coerceIn(-1f, 1f))
                                    }
                                }
                                output.write(converted.array(), 0, converted.position())
                            }
                            outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            check(destination.length() > 0) { "The selected track decoded to an empty audio stream." }
            return DecodedPcm(sampleRate, channels)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
        }
    }
}
