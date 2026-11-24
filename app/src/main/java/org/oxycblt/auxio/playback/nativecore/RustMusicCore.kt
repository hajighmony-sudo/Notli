/*
 * Copyright (c) 2026 Auxio Project
 * RustMusicCore.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.nativecore

/**
 * Thin JNI transport for the Rust-owned music domain.
 *
 * This class deliberately does not translate Android Song objects or execute Media3 actions yet.
 * Those responsibilities belong to the playback-service adapter and are migrated separately.
 */
internal object RustMusicCore {
    init {
        System.loadLibrary("notli_music_core")
    }

    private external fun nativeCreate(): Long

    private external fun nativeDestroy(handle: Long)

    private external fun nativeDispatch(handle: Long, commandJson: String): String

    private external fun nativeBackendEvent(handle: Long, eventJson: String): String

    private external fun nativeSnapshot(handle: Long): String

    private external fun nativeDownloadCommand(handle: Long, commandJson: String): String

    private external fun nativeDownloadSnapshot(handle: Long): String

    private external fun nativeRestore(handle: Long, snapshotJson: String): String

    private external fun nativeDownloadClearFinished(handle: Long): String

    fun create(): Long = nativeCreate()

    fun destroy(handle: Long) = nativeDestroy(handle)

    fun dispatch(handle: Long, commandJson: String): String = nativeDispatch(handle, commandJson)

    fun backendEvent(handle: Long, eventJson: String): String =
        nativeBackendEvent(handle, eventJson)

    fun snapshot(handle: Long): String = nativeSnapshot(handle)

    fun downloadCommand(handle: Long, commandJson: String): String =
        nativeDownloadCommand(handle, commandJson)

    fun downloadSnapshot(handle: Long): String = nativeDownloadSnapshot(handle)

    fun restore(handle: Long, snapshotJson: String): String = nativeRestore(handle, snapshotJson)

    fun downloadClearFinished(handle: Long): String = nativeDownloadClearFinished(handle)
}

/**
 * Owns one native engine handle. Close this object when the playback service is destroyed. Calls
 * are synchronized so a command cannot race with handle destruction.
 */
internal class RustMusicEngine private constructor() : AutoCloseable {
    private var handle: Long = RustMusicCore.create()

    @Synchronized
    fun dispatch(commandJson: String): String =
        RustMusicCore.dispatch(requireOpenHandle(), commandJson)

    @Synchronized
    fun onBackendEvent(eventJson: String): String =
        RustMusicCore.backendEvent(requireOpenHandle(), eventJson)

    @Synchronized fun snapshot(): String = RustMusicCore.snapshot(requireOpenHandle())

    /**
     * Restores a previously persisted player snapshot. The native side validates the snapshot
     * (including its schema version) and keeps the current state if it is rejected.
     */
    @Synchronized
    fun restore(snapshotJson: String): String =
        RustMusicCore.restore(requireOpenHandle(), snapshotJson)

    /** Removes every finished download task and returns the removed count in the JSON reply. */
    @Synchronized
    fun clearFinishedDownloads(): String = RustMusicCore.downloadClearFinished(requireOpenHandle())

    @Synchronized
    fun dispatchDownload(commandJson: String): String =
        RustMusicCore.downloadCommand(requireOpenHandle(), commandJson)

    @Synchronized
    fun downloadSnapshot(): String = RustMusicCore.downloadSnapshot(requireOpenHandle())

    @Synchronized
    override fun close() {
        val currentHandle = handle
        if (currentHandle != 0L) {
            handle = 0L
            RustMusicCore.destroy(currentHandle)
        }
    }

    private fun requireOpenHandle(): Long {
        check(handle != 0L) { "Rust music engine is closed" }
        return handle
    }

    companion object {
        fun create(): RustMusicEngine = RustMusicEngine()
    }
}
