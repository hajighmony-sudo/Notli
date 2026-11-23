/*
 * Copyright (c) 2026 NOTLI contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package org.oxycblt.auxio.vocal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Stores model weights privately on-device. Network is used only for this optional download;
 * inference never sends audio or other user content off-device.
 *
 * IMPORTANT: the upstream Demucs author describes these weights as research-only. Do not use
 * them in a commercial distribution without obtaining the required rights.
 */
internal class VocalModelStore(context: Context) {
    private val directory = File(context.filesDir, "vocal-studio/models").apply { mkdirs() }
    val modelFile = File(directory, MODEL_NAME)

    @Volatile
    private var verified = false

    /** True only after the complete pinned SHA-256 has been checked in this process. */
    val isReady: Boolean get() = verified

    suspend fun verifyInstalled(): Boolean = withContext(Dispatchers.IO) {
        val valid = modelFile.isFile &&
            modelFile.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES &&
            sha256(modelFile) == MODEL_SHA256
        verified = valid
        valid
    }

    fun markInvalid() {
        verified = false
    }

    /** Removes only the optional model weights; the feature code and user music are untouched. */
    suspend fun deleteInstalledModel() = withContext(Dispatchers.IO) {
        verified = false
        val temporary = File(directory, "$MODEL_NAME.download")
        if (temporary.exists()) temporary.delete()
        if (modelFile.exists()) {
            check(modelFile.delete()) { "Could not delete the downloaded AI model." }
        }
    }

    suspend fun download(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (verifyInstalled()) return@withContext
        verified = false

        val temporary = File(directory, "$MODEL_NAME.download")
        if (temporary.exists()) temporary.delete()

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Model download failed: HTTP ${connection.responseCode}"
            }
            val expectedLength = connection.contentLengthLong
            if (expectedLength > 0) {
                check(expectedLength in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
                    "The model server returned an unexpected file size."
                }
            }

            connection.inputStream.use { input ->
                FileOutputStream(temporary, false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_MODEL_BYTES) {
                            "The model download exceeded the expected size."
                        }
                        output.write(buffer, 0, count)
                        onProgress(((total * 100) / MODEL_BYTES).toInt().coerceIn(0, 99))
                    }
                    output.fd.sync()
                }
            }

            check(temporary.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
                "Downloaded model has an unexpected size."
            }
            check(sha256(temporary) == MODEL_SHA256) {
                "Model checksum mismatch. The file was rejected."
            }
            if (modelFile.exists()) check(modelFile.delete()) {
                "Could not replace the previous model."
            }
            check(temporary.renameTo(modelFile)) {
                "Could not install the verified model."
            }
            verified = true
            onProgress(100)
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODEL_NAME = "htdemucs_6s.safetensors"
        const val MIN_MODEL_BYTES = 50_000_000L
        const val MAX_MODEL_BYTES = 60_000_000L
        const val MODEL_BYTES = 55_000_000L
        const val MODEL_SHA256 = "d2a1745f0744721f6b8ca5bf469b67c651ea5ed1b52998cab033b2158609d411"
        const val MODEL_URL = "https://huggingface.co/adefossez/HTDemucs-6s/resolve/3c5ee475be622df764938de97e4281a7b07ffa58/5c90dfd2.safetensors"
    }
}
