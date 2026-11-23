/*
 * Copyright (c) 2021 Auxio Project
 * NotliPlaybackBar.kt is part of Auxio.
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

package org.oxycblt.auxio.playback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.musikr.Song

/**
 * Compose implementation of the compact playback surface.
 *
 * This is deliberately a presentation-only layer: all playback commands continue to use the
 * existing playback ViewModel and media service.
 */
@Composable
internal fun NotliPlaybackBar(
    song: Song?,
    title: String,
    artist: String,
    isPlaying: Boolean,
    positionDs: Long,
    actionMode: ActionMode,
    repeatMode: RepeatMode,
    isShuffled: Boolean,
    onOpen: () -> Unit,
    onShowAlbum: () -> Unit,
    onTogglePlaying: () -> Unit,
    onNext: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val durationDs = (song?.durationMs ?: 0L) / 100L
    val progress = if (durationDs > 0L) {
        (positionDs.toFloat() / durationDs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onShowAlbum),
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.35f)),
        tonalElevation = 3.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            brush = Brush.linearGradient(
                                listOf(Color(0xFF3A246A), Color(0xFFB7D94A)),
                            ),
                            shape = RoundedCornerShape(16.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "♫",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = title.ifBlank { "Notli" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = artist.ifBlank { "آمادهٔ پخش موسیقی" },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = {
                        when (actionMode) {
                            ActionMode.NEXT -> onNext()
                            ActionMode.REPEAT -> onToggleRepeat()
                            ActionMode.SHUFFLE -> onToggleShuffle()
                        }
                    },
                ) {
                    when (actionMode) {
                        ActionMode.NEXT -> Icon(Icons.Default.SkipNext, contentDescription = "بعدی")
                        ActionMode.REPEAT -> Text(
                            text = if (repeatMode == RepeatMode.TRACK) "1" else "↻",
                            color = if (repeatMode != RepeatMode.NONE) colors.primary else colors.onSurfaceVariant,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        ActionMode.SHUFFLE -> Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "پخش تصادفی",
                            tint = if (isShuffled) colors.primary else colors.onSurfaceVariant,
                        )
                    }
                }

                IconButton(onClick = onTogglePlaying) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "مکث" else "پخش",
                        tint = colors.primary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = colors.primary,
                trackColor = Color.Transparent,
            )
        }
    }
}

@Composable
internal fun NotliComposeTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFFC7F24E),
            onPrimary = Color(0xFF1A2200),
            secondary = Color(0xFFB9A6FF),
            background = Color(0xFF0B0A10),
            surface = Color(0xFF14121B),
            surfaceContainer = Color(0xFF201D29),
            onSurface = Color(0xFFF0ECF7),
            onSurfaceVariant = Color(0xFFAAA3B8),
            outlineVariant = Color(0xFF3A3546),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF4B367D),
            onPrimary = Color.White,
            secondary = Color(0xFF59468A),
            background = Color(0xFFF6F5F0),
            surface = Color(0xFFF6F5F0),
            surfaceContainer = Color(0xFFEAE8E0),
            onSurface = Color(0xFF19171F),
            onSurfaceVariant = Color(0xFF5D5868),
            outlineVariant = Color(0xFFD0CDD5),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
