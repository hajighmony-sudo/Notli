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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
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
    val fill =
        Brush.verticalGradient(
            if (dark) {
                listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.06f))
            } else {
                listOf(Color.White.copy(alpha = 0.80f), Color.White.copy(alpha = 0.50f))
            },
        )
    val rim =
        Brush.linearGradient(
            if (dark) {
                listOf(
                    Color.White.copy(alpha = 0.42f),
                    Color.White.copy(alpha = 0.05f),
                    Color.White.copy(alpha = 0.22f),
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.95f),
                    Color(0xFF4B367D).copy(alpha = 0.16f),
                    Color.White.copy(alpha = 0.60f),
                )
            },
        )
    return this.clip(shape).background(fill, shape).border(1.dp, rim, shape)
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
                val container by
                    animateColorAsState(
                        targetValue = if (selected) colors.primary else Color.Transparent,
                        animationSpec = tween(durationMillis = 220),
                        label = "glass-tab-container",
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
                        Modifier.graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .clip(CircleShape)
                            .background(container)
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
