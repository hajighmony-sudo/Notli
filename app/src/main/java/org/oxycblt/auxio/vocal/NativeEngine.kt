/*
 * Copyright (c) 2026 NOTLI contributors
 * Based on jPaauwe/demucs-android NativeEngine.kt (MIT).
 * The upstream copyright and license notices are preserved in third_party/demucs-android.
 */
package org.oxycblt.auxio.vocal

/** JNI bridge for the optional on-device Demucs engine. */
internal object NativeEngine {
    fun interface ProgressListener {
        fun onProgress(fraction: Float, stage: String): Boolean
    }

    init {
        System.loadLibrary("sdengine_jni")
    }

    val sources = listOf("drums", "bass", "other", "vocals", "guitar", "piano")

    @JvmStatic private external fun nativeLoadModel(path: String): String
    @JvmStatic private external fun nativePrepareInput(raw: String, sampleRate: Int, channels: Int, out: String, listener: ProgressListener?): String
    @JvmStatic private external fun nativeSeparate(prepared: String, outDir: String, write: BooleanArray, threads: Int, computeThreads: Int, gpu: Boolean, listener: ProgressListener?): DoubleArray
    @JvmStatic private external fun nativeCancel()

    @Synchronized
    fun loadModel(path: String) {
        val error = nativeLoadModel(path)
        check(error.isEmpty()) { error }
    }

    fun prepareInput(raw: String, sampleRate: Int, channels: Int, output: String, listener: ProgressListener? = null) {
        val error = nativePrepareInput(raw, sampleRate, channels, output, listener)
        check(error.isEmpty()) { error }
    }

    fun separate(
        prepared: String,
        outputDirectory: String,
        selected: Set<String>,
        threads: Int = 1,
        gpu: Boolean = true,
        listener: ProgressListener? = null,
    ) {
        val write = BooleanArray(sources.size) { sources[it] in selected }
        require(write.any { it }) { "Select at least one output stem." }
        val rms = nativeSeparate(prepared, outputDirectory, write, threads, 0, gpu, listener)
        check(rms.size == sources.size && rms.all { it.isFinite() }) {
            "The on-device separation engine returned invalid output statistics."
        }
    }

    fun cancel() = nativeCancel()
}
