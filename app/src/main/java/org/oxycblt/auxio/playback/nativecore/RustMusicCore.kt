/*
 * Copyright (c) 2026 Notli contributors.
 * RustMusicCore.kt is part of Notli.
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

    internal external fun nativeCreate(): Long

    internal external fun nativeDestroy(handle: Long)

    internal external fun nativeDispatch(handle: Long, commandJson: String): String

    internal external fun nativeBackendEvent(handle: Long, eventJson: String): String

    internal external fun nativeSnapshot(handle: Long): String

    internal external fun nativeDownloadCommand(handle: Long, commandJson: String): String

    internal external fun nativeDownloadSnapshot(handle: Long): String
}

/**
 * Owns one native engine handle. Close this object when the playback service is destroyed.
 * Calls are synchronized so a command cannot race with handle destruction.
 */
internal class RustMusicEngine private constructor() : AutoCloseable {
    private var handle: Long = RustMusicCore.nativeCreate()

    @Synchronized
    fun dispatch(commandJson: String): String =
        RustMusicCore.nativeDispatch(requireOpenHandle(), commandJson)

    @Synchronized
    fun onBackendEvent(eventJson: String): String =
        RustMusicCore.nativeBackendEvent(requireOpenHandle(), eventJson)

    @Synchronized
    fun snapshot(): String = RustMusicCore.nativeSnapshot(requireOpenHandle())

    @Synchronized
    fun dispatchDownload(commandJson: String): String =
        RustMusicCore.nativeDownloadCommand(requireOpenHandle(), commandJson)

    @Synchronized
    fun downloadSnapshot(): String =
        RustMusicCore.nativeDownloadSnapshot(requireOpenHandle())

    @Synchronized
    override fun close() {
        val currentHandle = handle
        if (currentHandle != 0L) {
            handle = 0L
            RustMusicCore.nativeDestroy(currentHandle)
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
