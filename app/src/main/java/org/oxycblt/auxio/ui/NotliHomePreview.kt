/*
 * Copyright (c) 2026 Auxio Project
 * NotliHomePreview.kt is part of Auxio.
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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

private val previewLabels =
    NotliHomeLabels(
        tagline = "Your sound, your space",
        nowPlaying = "NOW PLAYING",
        library = "Your library",
        recentlyPlayed = "Recently played",
        seeAll = "See all",
        search = "Search",
        settings = "Settings",
        more = "More",
        madeForTonight = "Made for tonight",
        madeForTonightSubtitle = "A perfect mix for your mood",
        tracksCount = "24 tracks",
        songs = "Songs",
        albums = "Albums",
        artists = "Artists",
        playlists = "Playlists",
    )

private val previewRecent =
    listOf(
        NotliTrackCardItem("1", "Sky Blade", "Void Echo"),
        NotliTrackCardItem("2", "Lost Horizons", "Dreamscape"),
        NotliTrackCardItem("3", "Neon Dreams", "Synthwave Vibes"),
    )

/**
 * Placeholder artwork: a diagonal violet→cyan gradient (real covers are supplied by the caller).
 */
@Composable
private fun PreviewArt(seed: Int = 0) {
    val colors =
        listOf(
            listOf(Color(0xFF2A1466), Color(0xFF7C3AED), Color(0xFF22D3EE)),
            listOf(Color(0xFF0B1B3A), Color(0xFF2563EB), Color(0xFF67E8F9)),
            listOf(Color(0xFF3B0764), Color(0xFFDB2777), Color(0xFF22D3EE)),
        )
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(colors[seed % colors.size])))
}

@Composable
private fun PreviewHome(dark: Boolean) {
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableStateOf(84_000L) }
    var chip by remember { mutableIntStateOf(0) }
    NotliThemeHost(darkTheme = dark) {
        NotliHomeDashboard(
            labels = previewLabels,
            nowPlaying =
                NotliNowPlaying(
                    title = "Midnight Signals",
                    artist = "Nova Drift",
                    positionMs = position,
                    durationMs = 232_000L,
                    isPlaying = playing,
                    shuffle = true,
                    repeat = false,
                ),
            actions =
                NotliPlayerActions(
                    onTogglePlaying = { playing = !playing },
                    onPrevious = {},
                    onNext = {},
                    onShuffle = {},
                    onRepeat = {},
                    onSeekTo = { position = it },
                ),
            recent = previewRecent,
            selectedChip = chip,
            onChipSelected = { chip = it },
            onSearch = {},
            onSettings = {},
            onSeeAllLibrary = {},
            onSeeAllRecent = {},
            onRecentClick = {},
            onRecentMore = {},
            onPlayMix = {},
            nowPlayingArtwork = { PreviewArt(0) },
            recentArtwork = { item -> PreviewArt(item.key.toInt()) },
        )
    }
}

@Preview(name = "Notli home – night", showBackground = true, widthDp = 392, heightDp = 860)
@Composable
private fun NotliHomeNightPreview() = PreviewHome(dark = true)

@Preview(name = "Notli home – day", showBackground = true, widthDp = 392, heightDp = 860)
@Composable
private fun NotliHomeDayPreview() = PreviewHome(dark = false)
