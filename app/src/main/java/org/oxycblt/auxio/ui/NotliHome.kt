/*
 * Copyright (c) 2026 Auxio Project
 * NotliHome.kt is part of Auxio.
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The Notli home dashboard, built to match the reference design: logo header, glass "now playing"
 * card, library chips, recently played cards, a "made for tonight" banner.
 *
 * Everything here is stateless. Data, artwork and callbacks come from the caller, so these
 * composables never touch playback logic and can be previewed in isolation (see NotliHomePreview).
 */

// ───────────────────────────── models ─────────────────────────────

internal data class NotliNowPlaying(
    val title: String,
    val artist: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
)

internal class NotliPlayerActions(
    val onTogglePlaying: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onShuffle: () -> Unit,
    val onRepeat: () -> Unit,
    /** Called with the target position in milliseconds. */
    val onSeekTo: (Long) -> Unit,
)

internal data class NotliChipItem(val label: String, val icon: ImageVector)

internal data class NotliTrackCardItem(val key: String, val title: String, val subtitle: String)

/** Strings are supplied by the caller so the design is fully localizable (Persian / English). */
internal class NotliHomeLabels(
    val tagline: String,
    val nowPlaying: String,
    val library: String,
    val recentlyPlayed: String,
    val seeAll: String,
    val search: String,
    val settings: String,
    val more: String,
    val madeForTonight: String,
    val madeForTonightSubtitle: String,
    val tracksCount: String,
    val songs: String,
    val albums: String,
    val artists: String,
    val playlists: String,
)

// ───────────────────────────── helpers ─────────────────────────────

internal fun formatPlaybackTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(total / 60, total % 60)
}

/** The signature card surface: gradient fill with a violet→cyan rim. Day/night aware. */
@Composable
internal fun Modifier.notliCard(shape: Shape): Modifier {
    val palette = NotliTheme.palette
    return this.clip(shape).background(palette.cardBrush, shape).border(1.dp, palette.rimBrush, shape)
}

/**
 * Staggered entrance: fades and rises into place once. The "already shown" flag is saved, so items
 * that scroll out and back in (or survive rotation) do not replay the animation.
 */
@Composable
internal fun Modifier.notliEnter(index: Int, stepMs: Int = 70): Modifier {
    var shown by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (shown) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!shown) {
            delay((index * stepMs).toLong())
            progress.animateTo(1f, tween(durationMillis = 480, easing = FastOutSlowInEasing))
            shown = true
        }
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 28.dp.toPx()
    }
}

// ───────────────────────────── play / pause morph ─────────────────────────────

/**
 * Pause bars that morph into a play triangle (and back) with a springy overshoot. Drawn on a canvas
 * by interpolating the corners of two quads, so there is no drawable resource involved.
 */
@Composable
internal fun NotliPlayPauseGlyph(isPlaying: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val t by
        animateFloatAsState(
            targetValue = if (isPlaying) 0f else 1f,
            animationSpec =
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "play-pause-morph",
        )
    Canvas(modifier) {
        fun x(a: Float, b: Float) = (a + (b - a) * t) * size.width
        fun y(a: Float, b: Float) = (a + (b - a) * t) * size.height
        // Left piece: pause bar → left half of the triangle.
        val left =
            Path().apply {
                moveTo(x(0.18f, 0.28f), y(0.12f, 0.10f))
                lineTo(x(0.40f, 0.52f), y(0.12f, 0.27f))
                lineTo(x(0.40f, 0.52f), y(0.88f, 0.73f))
                lineTo(x(0.18f, 0.28f), y(0.88f, 0.90f))
                close()
            }
        // Right piece: pause bar → right half of the triangle (collapses to the apex).
        val right =
            Path().apply {
                moveTo(x(0.60f, 0.52f), y(0.12f, 0.27f))
                lineTo(x(0.82f, 0.88f), y(0.12f, 0.50f))
                lineTo(x(0.82f, 0.88f), y(0.88f, 0.50f))
                lineTo(x(0.60f, 0.52f), y(0.88f, 0.73f))
                close()
            }
        drawPath(left, tint)
        drawPath(right, tint)
    }
}

// ───────────────────────────── logo, equalizer ─────────────────────────────

/** The waveform logo mark; its bars gently breathe out of phase. */
@Composable
internal fun NotliLogoMark(modifier: Modifier = Modifier, animated: Boolean = true) {
    val palette = NotliTheme.palette
    val base = listOf(0.30f, 0.55f, 0.85f, 1f, 0.7f, 0.9f, 0.5f, 0.28f)
    val factors: List<Float> =
        if (animated) {
            val transition = rememberInfiniteTransition(label = "logo")
            base.indices.map { i ->
                val v by
                    transition.animateFloat(
                        initialValue = 0.82f,
                        targetValue = 1f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = tween(1100 + i * 90, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse,
                                initialStartOffset = StartOffset(i * 110),
                            ),
                        label = "logo-bar-$i",
                    )
                v
            }
        } else {
            base.map { 1f }
        }
    Canvas(modifier) {
        val count = base.size
        val gap = size.width * 0.045f
        val barW = (size.width - gap * (count - 1)) / count
        val brush = Brush.verticalGradient(listOf(palette.accentCyan, palette.accent, palette.accentSoft))
        base.forEachIndexed { i, h ->
            val barH = size.height * h * factors[i]
            drawRoundRect(
                brush = brush,
                topLeft = Offset(i * (barW + gap), (size.height - barH) / 2f),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/** Four bouncing bars; they animate only while [active] so a paused screen never redraws. */
@Composable
internal fun NotliEqualizer(active: Boolean, modifier: Modifier = Modifier) {
    val palette = NotliTheme.palette
    val heights: List<Float> =
        if (active) {
            val transition = rememberInfiniteTransition(label = "eq")
            listOf(520, 690, 450, 610).mapIndexed { i, duration ->
                val v by
                    transition.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 1f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = tween(duration, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse,
                                initialStartOffset = StartOffset(i * 90),
                            ),
                        label = "eq-bar-$i",
                    )
                v
            }
        } else {
            listOf(0.35f, 0.6f, 0.45f, 0.3f)
        }
    Canvas(modifier) {
        val gap = size.width * 0.14f
        val barW = (size.width - gap * 3) / 4f
        heights.forEachIndexed { i, h ->
            val barH = size.height * h
            drawRoundRect(
                color = palette.accentSoft,
                topLeft = Offset(i * (barW + gap), size.height - barH),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

// ───────────────────────────── header ─────────────────────────────

@Composable
internal fun NotliRoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    Box(
        modifier =
            modifier
                .size(48.dp)
                .notliPressScale(0.9f)
                .notliGlass(CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = palette.textPrimary, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun NotliHeader(
    labels: NotliHomeLabels,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotliLogoMark(Modifier.width(46.dp).height(54.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "NOTLI",
                style =
                    TextStyle(
                        brush = Brush.horizontalGradient(listOf(palette.textPrimary, palette.accentSoft)),
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                    ),
                maxLines = 1,
            )
            Text(
                text = labels.tagline,
                color = palette.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NotliRoundButton(Icons.Filled.Search, labels.search, onSearch)
        Spacer(Modifier.width(10.dp))
        NotliRoundButton(Icons.Filled.Settings, labels.settings, onSettings)
    }
}

// ───────────────────────────── now playing ─────────────────────────────

/**
 * A draggable seek bar. While the finger is down the bar follows it instantly and the thumb swells;
 * the seek is committed on release (or on a tap). Otherwise it eases toward the playback position.
 */
@Composable
internal fun NotliSeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val target = if (dragging) dragFraction else fraction
    val shown by
        animateFloatAsState(
            targetValue = target.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = if (dragging) 0 else 160, easing = LinearEasing),
            label = "seek-fraction",
        )
    val thumbScale by
        animateFloatAsState(
            targetValue = if (dragging) 1.55f else 1f,
            animationSpec =
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "seek-thumb",
        )
    Box(
        modifier =
            modifier
                .height(30.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onSeek((offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragging = false
                            onSeek(dragFraction)
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ ->
                            dragFraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        },
                    )
                },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trackH = 4.dp.toPx()
            val cy = size.height / 2f
            val radius = CornerRadius(trackH / 2f, trackH / 2f)
            drawRoundRect(
                color = palette.textSecondary.copy(alpha = 0.28f),
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = radius,
            )
            val filled = size.width * shown
            if (filled > 0f) {
                drawRoundRect(
                    brush = palette.accentBrush,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(filled, trackH),
                    cornerRadius = radius,
                )
            }
            val thumbR = 6.dp.toPx() * thumbScale
            drawCircle(palette.accent.copy(alpha = 0.32f), radius = thumbR * 2.1f, center = Offset(filled, cy))
            drawCircle(Color.White, radius = thumbR, center = Offset(filled, cy))
        }
    }
}

/** An icon button that nudges in [direction] (-1 / +1) and springs back when tapped. */
@Composable
private fun NudgeButton(
    direction: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier =
            modifier
                .size(40.dp)
                .notliPressScale(0.88f)
                .graphicsLayer { translationX = offset.value * density }
                .clip(CircleShape)
                .clickable {
                    scope.launch {
                        offset.animateTo(direction * 7f, tween(90))
                        offset.animateTo(
                            0f,
                            spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
                        )
                    }
                    onClick()
                },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun NotliNowPlayingCard(
    state: NotliNowPlaying,
    actions: NotliPlayerActions,
    labels: NotliHomeLabels,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
) {
    val palette = NotliTheme.palette
    val shape = RoundedCornerShape(28.dp)
    val fraction =
        if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f

    // The big button's outer ring breathes only while playing.
    val ringPulse =
        if (state.isPlaying) {
            val transition = rememberInfiniteTransition(label = "play-ring")
            val v by
                transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec =
                        infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "play-ring-alpha",
                )
            v
        } else {
            0f
        }

    Box(modifier = modifier.fillMaxWidth().notliCard(shape).padding(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(116.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, palette.rimBrush, RoundedCornerShape(22.dp)),
            ) {
                artwork()
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier =
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .background(palette.accent.copy(alpha = 0.18f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NotliLogoMark(Modifier.size(width = 14.dp, height = 14.dp), animated = false)
                        Text(
                            labels.nowPlaying,
                            color = palette.accentSoft,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    NotliEqualizer(state.isPlaying, Modifier.size(width = 20.dp, height = 16.dp))
                }
                Spacer(Modifier.height(6.dp))
                AnimatedContent(
                    targetState = state.title to state.artist,
                    transitionSpec = {
                        (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 3 }) togetherWith
                            (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 3 })
                    },
                    label = "now-playing-text",
                ) { (title, artist) ->
                    Column {
                        Text(
                            title,
                            color = palette.textPrimary,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            artist,
                            color = palette.textSecondary,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                NotliSeekBar(
                    fraction = fraction,
                    onSeek = { actions.onSeekTo((it * state.durationMs).toLong()) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatPlaybackTime(state.positionMs), color = palette.textSecondary, fontSize = 12.sp)
                    Text(formatPlaybackTime(state.durationMs), color = palette.textSecondary, fontSize = 12.sp)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val shuffleTint by
                        animateColorAsState(
                            if (state.shuffle) palette.accentSoft else palette.textSecondary,
                            tween(200),
                            label = "shuffle-tint",
                        )
                    val repeatTint by
                        animateColorAsState(
                            if (state.repeat) palette.accentSoft else palette.textSecondary,
                            tween(200),
                            label = "repeat-tint",
                        )
                    NudgeButton(0, actions.onShuffle) {
                        Icon(Icons.Filled.Shuffle, null, tint = shuffleTint, modifier = Modifier.size(22.dp))
                    }
                    NudgeButton(-1, actions.onPrevious) {
                        Icon(Icons.Filled.SkipPrevious, null, tint = palette.textPrimary, modifier = Modifier.size(28.dp))
                    }
                    Box(
                        modifier =
                            Modifier.size(58.dp)
                                .notliPressScale(0.9f)
                                .clip(CircleShape)
                                .background(palette.accent.copy(alpha = 0.14f))
                                .border(2.dp, palette.accent.copy(alpha = 0.55f + 0.45f * ringPulse), CircleShape)
                                .clickable(onClick = actions.onTogglePlaying),
                        contentAlignment = Alignment.Center,
                    ) {
                        NotliPlayPauseGlyph(state.isPlaying, palette.textPrimary, Modifier.size(26.dp))
                    }
                    NudgeButton(1, actions.onNext) {
                        Icon(Icons.Filled.SkipNext, null, tint = palette.textPrimary, modifier = Modifier.size(28.dp))
                    }
                    NudgeButton(0, actions.onRepeat) {
                        Icon(Icons.Filled.Repeat, null, tint = repeatTint, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

// ───────────────────────────── sections ─────────────────────────────

@Composable
internal fun NotliSectionTitle(
    title: String,
    seeAllLabel: String,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(4.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(palette.accentSoft, palette.accent))),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            color = palette.textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Row(
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onSeeAll).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(seeAllLabel, color = palette.textSecondary, fontSize = 14.sp)
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = palette.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun NotliLibraryChips(
    items: List<NotliChipItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val fill by
                animateColorAsState(
                    if (selected) palette.accent.copy(alpha = 0.26f) else palette.chip,
                    tween(220),
                    label = "chip-fill",
                )
            val tint by
                animateColorAsState(
                    if (selected) palette.accentSoft else palette.textSecondary,
                    tween(220),
                    label = "chip-tint",
                )
            val borderAlpha by
                animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "chip-border")
            val shape = RoundedCornerShape(16.dp)
            Row(
                modifier =
                    Modifier.height(46.dp)
                        .notliPressScale(0.95f)
                        .clip(shape)
                        .background(fill, shape)
                        .border(1.5.dp, palette.accent.copy(alpha = borderAlpha), shape)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(item.icon, null, tint = tint, modifier = Modifier.size(20.dp))
                Text(
                    item.label,
                    color = if (selected) palette.textPrimary else tint,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun NotliRecentRow(
    items: List<NotliTrackCardItem>,
    moreLabel: String,
    onClick: (Int) -> Unit,
    onMore: (Int) -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (NotliTrackCardItem) -> Unit,
) {
    val palette = NotliTheme.palette
    LazyRow(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            val shape = RoundedCornerShape(22.dp)
            Column(
                modifier =
                    Modifier.width(126.dp)
                        .notliEnter(index)
                        .notliPressScale(0.96f)
                        .notliCard(shape)
                        .clickable { onClick(index) },
            ) {
                Box(Modifier.fillMaxWidth().height(100.dp)) { artwork(item) }
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.subtitle,
                            color = palette.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).clickable { onMore(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            moreLabel,
                            tint = palette.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun NotliMadeForTonight(
    labels: NotliHomeLabels,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    val spin by
        rememberInfiniteTransition(label = "vinyl")
            .animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing)),
                label = "vinyl-rotation",
            )
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(112.dp)
                .notliPressScale(0.98f)
                .notliCard(RoundedCornerShape(26.dp))
                .clickable(onClick = onPlay),
    ) {
        Canvas(
            Modifier.size(150.dp)
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    translationX = -34.dp.toPx()
                    rotationZ = spin
                },
        ) {
            val c = center
            val r = size.minDimension / 2f
            drawCircle(Color(0xFF0B0816), radius = r, center = c)
            for (i in 1..6) {
                drawCircle(
                    Color.White.copy(alpha = 0.05f),
                    radius = r * (0.40f + i * 0.09f),
                    center = c,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f),
                )
            }
            // A soft specular streak so the rotation is actually visible.
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, palette.accent.copy(alpha = 0.55f), Color.Transparent), c),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = true,
                alpha = 0.5f,
            )
            drawCircle(Brush.linearGradient(listOf(palette.accent, palette.accentCyan)), radius = r * 0.30f, center = c)
            drawCircle(Color(0xFF0B0816), radius = r * 0.06f, center = c)
        }
        Row(
            Modifier.fillMaxSize().padding(start = 120.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    labels.madeForTonight,
                    color = palette.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    labels.madeForTonightSubtitle,
                    color = palette.textSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        null,
                        tint = palette.accentSoft,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(labels.tracksCount, color = palette.textSecondary, fontSize = 13.sp)
                }
            }
            Box(
                Modifier.size(54.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(palette.accent, Color(0xFF5B3BE0)))),
                contentAlignment = Alignment.Center,
            ) {
                NotliPlayPauseGlyph(isPlaying = false, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ───────────────────────────── dashboard ─────────────────────────────

/** Default library chips in the order shown by the reference design. */
internal fun notliDefaultChips(labels: NotliHomeLabels): List<NotliChipItem> =
    listOf(
        NotliChipItem(labels.songs, Icons.Filled.MusicNote),
        NotliChipItem(labels.albums, Icons.Filled.Album),
        NotliChipItem(labels.artists, Icons.Filled.Person),
        NotliChipItem(labels.playlists, Icons.AutoMirrored.Filled.QueueMusic),
    )

/**
 * The full home screen. The caller owns state; this only lays the sections out on the Notli
 * backdrop with staggered entrances.
 */
@Composable
internal fun NotliHomeDashboard(
    labels: NotliHomeLabels,
    nowPlaying: NotliNowPlaying,
    actions: NotliPlayerActions,
    recent: List<NotliTrackCardItem>,
    selectedChip: Int,
    onChipSelected: (Int) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onSeeAllLibrary: () -> Unit,
    onSeeAllRecent: () -> Unit,
    onRecentClick: (Int) -> Unit,
    onRecentMore: (Int) -> Unit,
    onPlayMix: () -> Unit,
    modifier: Modifier = Modifier,
    chips: List<NotliChipItem> = notliDefaultChips(labels),
    nowPlayingArtwork: @Composable () -> Unit,
    recentArtwork: @Composable (NotliTrackCardItem) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().notliBackdrop().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        NotliHeader(labels, onSearch, onSettings, Modifier.notliEnter(0))
        NotliNowPlayingCard(
            state = nowPlaying,
            actions = actions,
            labels = labels,
            modifier = Modifier.padding(horizontal = 16.dp).notliEnter(1),
            artwork = nowPlayingArtwork,
        )
        NotliSectionTitle(labels.library, labels.seeAll, onSeeAllLibrary, Modifier.notliEnter(2))
        NotliLibraryChips(chips, selectedChip, onChipSelected, Modifier.notliEnter(3))
        NotliSectionTitle(labels.recentlyPlayed, labels.seeAll, onSeeAllRecent, Modifier.notliEnter(4))
        NotliRecentRow(recent, labels.more, onRecentClick, onRecentMore, Modifier.notliEnter(5), recentArtwork)
        NotliMadeForTonight(labels, onPlayMix, Modifier.padding(horizontal = 16.dp).notliEnter(6))
        Spacer(Modifier.height(96.dp)) // room for the mini player and bottom navigation
    }
}
