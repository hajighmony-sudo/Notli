/*
 * Copyright (c) 2026 Auxio Project
 * NotliAppPreview.kt is part of Auxio.
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

package org.oxycblt.auxio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay

private class PreviewTrack(val title: String, val artist: String, val durationMs: Long)

private val previewTracks =
    listOf(
        PreviewTrack("Midnight Signals", "Nova Drift", 232_000L),
        PreviewTrack("Sky Blade", "Void Echo", 201_000L),
        PreviewTrack("Lost Horizons", "Dreamscape", 254_000L),
        PreviewTrack("Neon Dreams", "Synthwave Vibes", 219_000L),
    )

private val previewSongs =
    previewTracks.mapIndexed { i, t ->
        NotliListItem("song-$i", t.title, t.artist, formatPlaybackTime(t.durationMs))
    } +
        listOf(
            NotliListItem("song-4", "Aurora Run", "Nova Drift", "03:12"),
            NotliListItem("song-5", "Echoes of Tomorrow", "Dreamscape", "04:05"),
            NotliListItem("song-6", "Starlight Protocol", "Void Echo", "03:41"),
        )

private val previewAlbums =
    listOf(
        NotliListItem("album-0", "Signals", "Nova Drift", "12 tracks"),
        NotliListItem("album-1", "Void Sessions", "Void Echo", "9 tracks"),
        NotliListItem("album-2", "Horizons", "Dreamscape", "14 tracks"),
    )

private val previewArtists =
    listOf(
        NotliListItem("artist-0", "Nova Drift", "3 albums"),
        NotliListItem("artist-1", "Void Echo", "2 albums"),
        NotliListItem("artist-2", "Dreamscape", "4 albums"),
        NotliListItem("artist-3", "Synthwave Vibes", "1 album"),
    )

private val previewPlaylists =
    listOf(
        NotliListItem("playlist-0", "Made for tonight", "A perfect mix for your mood", "24"),
        NotliListItem("playlist-1", "Night drive", "Synthwave and neon", "18"),
    )

@Composable
private fun PreviewApp(initialMode: NotliThemeMode) {
    var mode by remember { mutableStateOf(initialMode) }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(84_000L) }
    var index by remember { mutableIntStateOf(0) }
    var shuffle by remember { mutableStateOf(true) }
    var repeat by remember { mutableStateOf(false) }
    var gapless by remember { mutableStateOf(true) }
    var haptics by remember { mutableStateOf(true) }
    var animations by remember { mutableStateOf(true) }
    val track = previewTracks[index]

    // Fake playback clock so the progress bar, equalizer and morphing button can be judged.
    LaunchedEffect(playing, index) {
        while (playing) {
            delay(100)
            position += 100
            if (position >= track.durationMs) {
                position = 0
                index = (index + 1) % previewTracks.size
            }
        }
    }

    NotliApp(
        labels = notliEnglishLabels(),
        nowPlaying =
            NotliNowPlaying(
                title = track.title,
                artist = track.artist,
                positionMs = position,
                durationMs = track.durationMs,
                isPlaying = playing,
                shuffle = shuffle,
                repeat = repeat,
            ),
        actions =
            NotliPlayerActions(
                onTogglePlaying = { playing = !playing },
                onPrevious = {
                    position = 0
                    index = (index - 1 + previewTracks.size) % previewTracks.size
                },
                onNext = {
                    position = 0
                    index = (index + 1) % previewTracks.size
                },
                onShuffle = { shuffle = !shuffle },
                onRepeat = { repeat = !repeat },
                onSeekTo = { position = it },
            ),
        recent =
            listOf(
                NotliTrackCardItem("1", "Sky Blade", "Void Echo"),
                NotliTrackCardItem("2", "Lost Horizons", "Dreamscape"),
                NotliTrackCardItem("3", "Neon Dreams", "Synthwave Vibes"),
            ),
        libraryItems = listOf(previewSongs, previewAlbums, previewArtists, previewPlaylists),
        searchResults = { q ->
            (previewSongs + previewAlbums + previewArtists).filter {
                it.title.contains(q.trim(), ignoreCase = true) ||
                    it.subtitle.contains(q.trim(), ignoreCase = true)
            }
        },
        themeMode = mode,
        onThemeMode = { mode = it },
        toggles = notliDefaultToggles(gapless, haptics, animations),
        onToggle = { key, value ->
            when (key) {
                "gapless" -> gapless = value
                "haptics" -> haptics = value
                "animations" -> animations = value
            }
        },
        onItemClick = {},
        onItemMore = {},
        onRecentClick = {},
        onRecentMore = {},
        onPlayMix = {},
        artwork = { key -> NotliPlaceholderArt(seed = key.hashCode()) },
    )
}

@Preview(name = "Notli app - night", showBackground = true, widthDp = 392, heightDp = 860)
@Composable
private fun NotliAppNightPreview() = PreviewApp(NotliThemeMode.Night)

@Preview(name = "Notli app - day", showBackground = true, widthDp = 392, heightDp = 860)
@Composable
private fun NotliAppDayPreview() = PreviewApp(NotliThemeMode.Day)