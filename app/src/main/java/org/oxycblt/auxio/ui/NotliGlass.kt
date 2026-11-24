/*
 * Copyright (c) 2026 Auxio Project
 * NotliGlass.kt is part of Auxio.
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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Notli's "liquid glass" surface language: a translucent gradient fill with a specular rim.
 *
 * This is purely visual. It is a layered translucent fill (no real backdrop blur), so it keeps
 * working on every supported API level and costs nothing at draw time.
 */
@Composable
internal fun Modifier.notliGlass(shape: Shape): Modifier {
    val dark = isSystemInDarkTheme()
    val palette = MaterialTheme.colorScheme
    val fill =
        Brush.verticalGradient(
            if (dark) {
                listOf(
                    palette.surface.copy(alpha = 0.92f),
                    palette.surfaceVariant.copy(alpha = 0.78f),
                    palette.surfaceContainerHigh.copy(alpha = 0.84f),
                )
            } else {
                listOf(
                    palette.surface.copy(alpha = 0.84f),
                    palette.surfaceVariant.copy(alpha = 0.58f),
                )
            },
        )
    val rim =
        Brush.linearGradient(
            if (dark) {
                listOf(
                    palette.primary.copy(alpha = 0.54f),
                    palette.secondary.copy(alpha = 0.24f),
                    palette.tertiary.copy(alpha = 0.34f),
                )
            } else {
                listOf(
                    palette.primary.copy(alpha = 0.42f),
                    palette.secondary.copy(alpha = 0.20f),
                    palette.tertiary.copy(alpha = 0.28f),
                )
            },
        )
    return this.clip(shape).background(fill, shape).border(1.dp, rim, shape)
}

/**
 * Springs the element down slightly while a finger is on it, like iOS controls.
 *
 * The gesture is only observed, never consumed, so clicks, long-presses and scrolling on the same
 * element keep working exactly as before.
 */
@Composable
internal fun Modifier.notliPressScale(pressedScale: Float = 0.94f): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by
        animateFloatAsState(
            targetValue = if (pressed) pressedScale else 1f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            label = "press-scale",
        )
    return this.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
}

/**
 * A floating, pill-shaped glass tab bar in the style of iOS 26 navigation.
 *
 * It only displays [labels] and reports taps through [onSelect]; the caller keeps owning the pager
 * and the selected index, so this never changes what any tab does.
 */
@Composable
internal fun NotliGlassTabBar(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier.notliGlass(RoundedCornerShape(percent = 50))
                    .horizontalScroll(rememberScrollState())
                    .padding(4.dp)
                    .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val selectedFill =
                    Brush.linearGradient(
                        listOf(
                            colors.primary,
                            colors.secondary,
                            colors.tertiary,
                        ),
                    )
                val content by
                    animateColorAsState(
                        targetValue = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                        animationSpec = tween(durationMillis = 220),
                        label = "glass-tab-content",
                    )
                val scale by
                    animateFloatAsState(
                        targetValue = if (selected) 1f else 0.96f,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        label = "glass-tab-scale",
                    )
                Box(
                    modifier =
                        Modifier.notliPressScale(pressedScale = 0.92f)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .clip(CircleShape)
                            .then(
                                if (selected) {
                                    Modifier.background(selectedFill, CircleShape)
                                        .border(
                                            1.dp,
                                            Brush.linearGradient(
                                                listOf(
                                                    Color.White.copy(alpha = 0.60f),
                                                    colors.secondary.copy(alpha = 0.48f),
                                                    colors.tertiary.copy(alpha = 0.42f),
                                                ),
                                            ),
                                            CircleShape,
                                        )
                                } else {
                                    Modifier.background(Color.Transparent, CircleShape)
                                },
                            )
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = content,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A compact glass bottom navigation for the library, search and settings destinations. */
@Composable
internal fun NotliGlassBottomBar(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val icons: List<ImageVector> =
        listOf(Icons.Filled.Home, Icons.Filled.Search, Icons.Filled.Settings)
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().notliGlass(RoundedCornerShape(28.dp)).padding(5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.take(icons.size).forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val tint by
                    animateColorAsState(
                        targetValue =
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(180),
                        label = "bottom-nav-tint",
                    )
                Column(
                    modifier =
                        Modifier.weight(1f)
                            .notliPressScale(0.94f)
                            .clip(RoundedCornerShape(22.dp))
                            .clickable { onSelect(index) }
                            .padding(horizontal = 6.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        imageVector = icons[index],
                        contentDescription = label,
                        tint = tint,
                    )
                    Text(
                        text = label,
                        color = tint,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
