/*
 * Copyright (c) 2026 Auxio Project
 * NotliChrome.kt is part of Auxio.
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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ───────────────────────────── mini player ─────────────────────────────

/**
 * The pill-shaped mini player from the Notli design: artwork, title and artist, previous, a
 * play/pause glyph that morphs between its two states, and next.
 *
 * This is presentation only. Every action is a callback, so the caller keeps owning playback, and
 * the hairline at the bottom edge shows [progress] (0..1).
 */
@Composable
internal fun NotliMiniPlayer(
    title: String,
    artist: String,
    isPlaying: Boolean,
    progress: Float,
    onOpen: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlaying: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    previousDescription: String = "Previous",
    nextDescription: String = "Next",
    playDescription: String = "Play",
    pauseDescription: String = "Pause",
    artwork: @Composable () -> Unit,
) {
    val palette = NotliTheme.palette
    val shape = RoundedCornerShape(28.dp)
    // Position ticks are coarse; a short linear tween turns them into a continuous sweep.
    val smoothProgress by
        animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 140, easing = LinearEasing),
            label = "mini-progress",
        )

    Box(modifier = modifier.fillMaxWidth().notliCard(shape).clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp).height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))) { artwork() }

            // Title and artist glide in when the track changes.
            AnimatedContent(
                targetState = title to artist,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 }) togetherWith
                        (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 3 })
                },
                label = "mini-track-text",
            ) { (currentTitle, currentArtist) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = currentTitle,
                        color = palette.textPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = currentArtist,
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            MiniButton(onClick = onPrevious, contentDescription = previousDescription) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = null,
                    tint = palette.textPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
            MiniButton(
                onClick = onTogglePlaying,
                contentDescription = if (isPlaying) pauseDescription else playDescription,
                pressedScale = 0.86f,
            ) {
                NotliPlayPauseGlyph(
                    isPlaying = isPlaying,
                    tint = palette.textPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
            MiniButton(onClick = onNext, contentDescription = nextDescription) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = null,
                    tint = palette.textPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Box(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth(smoothProgress)
                .height(2.dp)
                .background(palette.accentBrush),
        )
    }
}

@Composable
private fun MiniButton(
    onClick: () -> Unit,
    contentDescription: String,
    pressedScale: Float = 0.9f,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier.size(44.dp)
                .notliPressScale(pressedScale)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ───────────────────────────── bottom navigation ─────────────────────────────

/** One destination in [NotliBottomNav]. */
@Immutable internal data class NotliNavItem(val icon: ImageVector, val label: String)

/** The four destinations from the design: Home, Library, Search and Settings. */
internal fun notliDefaultNavItems(
    home: String = "Home",
    library: String = "Library",
    search: String = "Search",
    settings: String = "Settings",
): List<NotliNavItem> =
    listOf(
        NotliNavItem(Icons.Filled.Home, home),
        NotliNavItem(Icons.Filled.LibraryMusic, library),
        NotliNavItem(Icons.Filled.Search, search),
        NotliNavItem(Icons.Filled.Settings, settings),
    )

/**
 * The glass bottom navigation bar. A gradient indicator slides in above the selected item, its icon
 * lifts on a spring and glows, and the label recolors.
 *
 * It only reports taps through [onSelect]; the caller owns the actual navigation.
 */
@Composable
internal fun NotliBottomNav(
    items: List<NotliNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Row(
        modifier =
            modifier.fillMaxWidth().notliCard(shape).navigationBarsPadding().selectableGroup(),
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val tint by
                animateColorAsState(
                    targetValue = if (selected) palette.accentSoft else palette.textSecondary,
                    animationSpec = tween(durationMillis = 240),
                    label = "nav-tint",
                )
            val indicatorWidth by
                animateDpAsState(
                    targetValue = if (selected) 44.dp else 0.dp,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    label = "nav-indicator",
                )
            val lift by
                animateFloatAsState(
                    targetValue = if (selected) -3f else 0f,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    label = "nav-lift",
                )
            val glow by
                animateFloatAsState(
                    targetValue = if (selected) 0.4f else 0f,
                    animationSpec = tween(durationMillis = 300),
                    label = "nav-glow",
                )

            Column(
                modifier =
                    Modifier.weight(1f)
                        .notliPressScale(0.92f)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        )
                        .padding(bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.width(indicatorWidth)
                        .height(3.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(palette.accentBrush),
                )
                Box(
                    modifier =
                        Modifier.padding(top = 7.dp)
                            .size(44.dp)
                            .drawBehind {
                                drawCircle(
                                    brush =
                                        Brush.radialGradient(
                                            listOf(
                                                palette.accent.copy(alpha = glow),
                                                Color.Transparent,
                                            ),
                                        ),
                                )
                            }
                            .graphicsLayer { translationY = lift * density },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Text(
                    text = item.label,
                    color = tint,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
