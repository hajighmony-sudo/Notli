/*
 * Copyright (c) 2021 Auxio Project
 * PlaybackSnapshot.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.state

import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/**
 * Immutable point-in-time playback state.
 *
 * This is the Kotlin-side counterpart to NAVAQ's platform-neutral PlaybackState snapshot. It
 * deliberately carries Notli's existing Musikr Song and MusicParent models so no library
 * conversion, queue replacement, or playback behavior change is required.
 */
data class PlaybackSnapshot(
    val currentSong: Song?,
    val parent: MusicParent?,
    val queue: List<Song>,
    val index: Int,
    val positionMs: Long,
    val isPlaying: Boolean,
    val repeatMode: RepeatMode,
    val isShuffled: Boolean,
) {
    val queueSize: Int
        get() = queue.size

    val hasCurrentSong: Boolean
        get() = currentSong != null
}
