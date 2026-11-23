/*
 * Copyright (c) 2026 Auxio Project
 * NotliCarModeScreen.kt is part of Auxio.
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

package org.oxycblt.auxio.playback.car

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import org.oxycblt.auxio.ui.notliGlass
import org.oxycblt.auxio.ui.notliPressScale

/** A song as shown in car mode. Kept free of library types so the screen stays presentational. */
internal data class CarTrack(val title: String, val artist: String)

/**
 * Notli's large-target "car mode" screen.
 *
 * Left: a rail of big glass controls and a vertical seek line. Right: a queue list that curves and
 * shrinks away from the centre like the Apple Watch list. Bottom: a dome of hanging bars, one per
 * nearby queue entry, with the current song lit. Everything is driven by parameters and callbacks;
 * playback itself is never touched here.
 *
 * Note: this is an in-app screen. Android Auto does not allow media apps to draw their own UI, so
 * Auto keeps using the host's templates and Notli's media session.
 */
@Composable
internal fun NotliCarModeScreen(
    tracks: List<CarTrack>,
    currentIndex: Int,
    isPlaying: Boolean,
    progress: Float,
    isShuffled: Boolean,
    isRepeating: Boolean,
    onTogglePlaying: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onSelectTrack: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(colors.background, colors.surface)))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CarControlRail(
                isPlaying = isPlaying,
                isShuffled = isShuffled,
                isRepeating = isRepeating,
                onTogglePlaying = onTogglePlaying,
                onPrevious = onPrevious,
                onNext = onNext,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
            )
            CarSeekLine(progress = progress, onSeekFraction = onSeekFraction)
            CarWatchList(
                tracks = tracks,
                currentIndex = currentIndex,
                onSelect = onSelectTrack,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        CarSongDome(
            count = tracks.size,
            currentIndex = currentIndex,
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxWidth().height(110.dp),
        )
    }
}

@Composable
private fun CarControlRail(
    isPlaying: Boolean,
    isShuffled: Boolean,
    isRepeating: Boolean,
    onTogglePlaying: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxHeight().width(88.dp).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailButton(
            icon = Icons.Default.Shuffle,
            description = "پخش تصادفی",
            size = 44.dp,
            tint = if (isShuffled) colors.primary else colors.onSurfaceVariant,
            onClick = onToggleShuffle,
        )
        RailButton(
            icon = Icons.Default.SkipPrevious,
            description = "قبلی",
            size = 56.dp,
            tint = colors.onSurface,
            onClick = onPrevious,
        )
        Box(
            modifier =
                Modifier.size(80.dp)
                    .notliPressScale(pressedScale = 0.9f)
                    .notliGlass(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (isPlaying) "مکث" else "پخش",
                        onClick = onTogglePlaying,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (fadeIn(tween(140)) + scaleIn(initialScale = 0.8f)) togetherWith
                        (fadeOut(tween(100)) + scaleOut(targetScale = 0.8f))
                },
                label = "car-play-pause",
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "مکث" else "پخش",
                    tint = colors.primary,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        RailButton(
            icon = Icons.Default.SkipNext,
            description = "بعدی",
            size = 56.dp,
            tint = colors.onSurface,
            onClick = onNext,
        )
        RailButton(
            icon = Icons.Default.Repeat,
            description = "تکرار",
            size = 44.dp,
            tint = if (isRepeating) colors.primary else colors.onSurfaceVariant,
            onClick = onToggleRepeat,
        )
    }
}

@Composable
private fun RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier.size(size)
                .notliPressScale()
                .notliGlass(CircleShape)
                .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** A vertical seek line: tap to jump, drag to scrub. The seek is committed when the drag ends. */
@Composable
private fun CarSeekLine(progress: Float, onSeekFraction: (Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val animated by
        animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 140, easing = LinearEasing),
            label = "car-seek-progress",
        )
    val shown = (dragFraction ?: animated).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier =
            Modifier.fillMaxHeight()
                .width(28.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onSeekFraction((offset.y / size.height).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragFraction = (offset.y / size.height).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragFraction?.let(onSeekFraction)
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.y / size.height).coerceIn(0f, 1f)
                    }
                },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier.width(4.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(colors.onSurface.copy(alpha = 0.18f)),
        )
        Box(
            Modifier.width(4.dp)
                .fillMaxHeight(fraction = shown)
                .clip(CircleShape)
                .background(colors.primary),
        )
        Box(
            Modifier.offset(y = maxHeight * shown - 8.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(colors.primary),
        )
    }
}

/**
 * A snapping list whose rows shrink, fade and bend away from the vertical centre, so the focused
 * song sits large in the middle, like the Apple Watch app list.
 */
@Composable
private fun CarWatchList(
    tracks: List<CarTrack>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    val itemHeight = 68.dp
    val listState = rememberLazyListState()

    BoxWithConstraints(modifier = modifier.notliGlass(shape)) {
        if (tracks.isEmpty()) {
            Text(
                text = "صفی برای نمایش نیست",
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center),
            )
            return@BoxWithConstraints
        }

        // Half-viewport padding lets the first and last rows reach the centre.
        val edgePadding = ((maxHeight - itemHeight) / 2).coerceAtLeast(0.dp)
        LaunchedEffect(currentIndex, tracks.size) {
            if (currentIndex in tracks.indices) listState.animateScrollToItem(currentIndex)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = edgePadding),
            flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Center),
        ) {
            itemsIndexed(tracks) { index, track ->
                val selected = index == currentIndex
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(itemHeight)
                            .graphicsLayer {
                                // Read in the draw phase so scrolling never recomposes rows.
                                val layout = listState.layoutInfo
                                val info = layout.visibleItemsInfo.firstOrNull { it.index == index }
                                if (info != null) {
                                    val half =
                                        (layout.viewportEndOffset - layout.viewportStartOffset) /
                                            2f
                                    val viewportCenter =
                                        (layout.viewportStartOffset + layout.viewportEndOffset) /
                                            2f
                                    val rowCenter = info.offset + info.size / 2f
                                    val distance =
                                        if (half > 0f) {
                                            ((rowCenter - viewportCenter) / half).coerceIn(-1f, 1f)
                                        } else {
                                            0f
                                        }
                                    val away = abs(distance)
                                    val rowScale = 1f - 0.30f * away
                                    scaleX = rowScale
                                    scaleY = rowScale
                                    alpha = 1f - 0.60f * away
                                    translationX = -28.dp.toPx() * away * away
                                }
                            }
                            .clickable(role = Role.Button) { onSelect(index) }
                            .padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = if (selected) colors.primary else colors.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track.artist,
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selected) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(colors.primary))
                    }
                }
            }
        }
    }
}

/**
 * An arc with one hanging bar per nearby queue entry (up to [DOME_WINDOW]). The current entry is lit
 * and the bars sway gently while music plays. The sway is decoration, not audio analysis.
 */
@Composable
private fun CarSongDome(
    count: Int,
    currentIndex: Int,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // The infinite transition only exists while playing, so a paused dome never redraws per frame.
    val phase =
        if (isPlaying) {
            val animatedPhase by
                rememberInfiniteTransition(label = "dome-sway")
                    .animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = tween(durationMillis = 2200, easing = LinearEasing),
                                repeatMode = AnimRepeatMode.Restart,
                            ),
                        label = "dome-sway-phase",
                    )
            animatedPhase
        } else {
            0f
        }

    val barColor = colors.onSurface
    val activeColor = colors.primary
    Canvas(modifier = modifier) {
        if (count <= 0) return@Canvas
        val windowStart =
            (currentIndex - DOME_WINDOW / 2).coerceAtLeast(0).let {
                it.coerceAtMost((count - DOME_WINDOW).coerceAtLeast(0))
            }
        val bars = min(DOME_WINDOW, count - windowStart)
        val baseInset = 8.dp.toPx()
        val radiusX = size.width / 2f * 0.92f
        val radiusY = size.height * 0.45f
        val centerX = size.width / 2f
        val arcTop = baseInset

        drawArc(
            color = barColor.copy(alpha = 0.16f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(centerX - radiusX, arcTop),
            size = Size(radiusX * 2f, radiusY * 2f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )

        for (k in 0 until bars) {
            val songIndex = windowStart + k
            val t = if (bars == 1) 0.5f else k / (bars - 1f)
            val angle = PI * (1.0 - t)
            val x = centerX + radiusX * cos(angle).toFloat()
            val y = arcTop + radiusY - radiusY * sin(angle).toFloat()

            val seed = ((songIndex * 37) % 10) / 10f
            val sway =
                if (isPlaying) {
                    1f + 0.18f * sin(2.0 * PI * (phase + k * 0.13)).toFloat()
                } else {
                    1f
                }
            val length = (18.dp.toPx() + 26.dp.toPx() * seed) * sway
            val current = songIndex == currentIndex
            val color =
                if (current) activeColor
                else barColor.copy(alpha = 0.28f - 0.10f * abs(songIndex - currentIndex).coerceAtMost(2))

            drawLine(
                color = color,
                start = Offset(x, y),
                end = Offset(x, y + length),
                strokeWidth = (if (current) 7.dp else 5.dp).toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = color,
                radius = (if (current) 6.dp else 3.5.dp).toPx(),
                center = Offset(x, y + length),
            )
        }
    }
}

private const val DOME_WINDOW = 15
