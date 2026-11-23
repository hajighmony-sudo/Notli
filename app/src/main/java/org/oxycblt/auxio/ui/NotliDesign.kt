/*
 * Copyright (c) 2026 Auxio Project
 * NotliDesign.kt is part of Auxio.
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Design tokens for the Notli look: a near-black indigo canvas lit by violet and cyan glows, with
 * glass cards that carry a violet-to-cyan rim. [Day] is the light counterpart, so every screen built
 * from these tokens has a proper day mode instead of an inverted night mode.
 */
@Immutable
internal class NotliPalette(
    val isDark: Boolean,
    val canvas: Color,
    val glowViolet: Color,
    val glowCyan: Color,
    val cardTop: Color,
    val cardBottom: Color,
    val rimViolet: Color,
    val rimCyan: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentCyan: Color,
    val chip: Color,
    val onAccent: Color,
) {
    /** Violet → cyan, used for progress fills, rims and the logo. */
    val accentBrush: Brush
        get() = Brush.horizontalGradient(listOf(accent, accentCyan))

    /** The signature card rim: violet at the top-left fading into cyan at the bottom-right. */
    val rimBrush: Brush
        get() = Brush.linearGradient(listOf(rimViolet, rimViolet.copy(alpha = 0.18f), rimCyan))

    val cardBrush: Brush
        get() = Brush.verticalGradient(listOf(cardTop, cardBottom))
}

internal object NotliPalettes {
    val Night =
        NotliPalette(
            isDark = true,
            canvas = Color(0xFF07050F),
            glowViolet = Color(0xFF6D28D9),
            glowCyan = Color(0xFF0891B2),
            cardTop = Color(0xFF1B1433).copy(alpha = 0.92f),
            cardBottom = Color(0xFF0D1228).copy(alpha = 0.92f),
            rimViolet = Color(0xFF9B6BFF),
            rimCyan = Color(0xFF2DD4EE),
            textPrimary = Color(0xFFF5F3FF),
            textSecondary = Color(0xFFA9A3C6),
            accent = Color(0xFF8B5CF6),
            accentSoft = Color(0xFFB8A0FF),
            accentCyan = Color(0xFF22D3EE),
            chip = Color(0xFF181433),
            onAccent = Color.White,
        )

    val Day =
        NotliPalette(
            isDark = false,
            canvas = Color(0xFFF6F3FF),
            glowViolet = Color(0xFFC4B5FD),
            glowCyan = Color(0xFFA5F3FC),
            cardTop = Color.White.copy(alpha = 0.88f),
            cardBottom = Color(0xFFEDE7FF).copy(alpha = 0.86f),
            rimViolet = Color(0xFF8B5CF6).copy(alpha = 0.55f),
            rimCyan = Color(0xFF06B6D4).copy(alpha = 0.45f),
            textPrimary = Color(0xFF1E1B3A),
            textSecondary = Color(0xFF6B6785),
            accent = Color(0xFF6D3BEA),
            accentSoft = Color(0xFF7C5CE0),
            accentCyan = Color(0xFF0891B2),
            chip = Color(0xFFEBE5FF),
            onAccent = Color.White,
        )
}

internal val LocalNotliPalette = staticCompositionLocalOf { NotliPalettes.Night }

/** Shorthand for the active palette: `NotliTheme.palette.accent`. */
internal object NotliTheme {
    val palette: NotliPalette
        @Composable @ReadOnlyComposable get() = LocalNotliPalette.current
}

/**
 * Applies the Notli palette (night or day, following the system) to Material3 and exposes it
 * through [NotliTheme.palette]. Every Notli Compose surface should be wrapped in this.
 */
@Composable
internal fun NotliThemeHost(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) NotliPalettes.Night else NotliPalettes.Day
    val scheme =
        if (darkTheme) {
            darkColorScheme(
                primary = palette.accent,
                onPrimary = palette.onAccent,
                secondary = palette.accentSoft,
                tertiary = palette.accentCyan,
                background = palette.canvas,
                surface = Color(0xFF110E22),
                surfaceContainer = Color(0xFF1A1633),
                onSurface = palette.textPrimary,
                onSurfaceVariant = palette.textSecondary,
                outlineVariant = Color(0xFF3A3360),
            )
        } else {
            lightColorScheme(
                primary = palette.accent,
                onPrimary = palette.onAccent,
                secondary = palette.accentSoft,
                tertiary = palette.accentCyan,
                background = palette.canvas,
                surface = Color(0xFFFAF8FF),
                surfaceContainer = Color(0xFFEDE7FF),
                onSurface = palette.textPrimary,
                onSurfaceVariant = palette.textSecondary,
                outlineVariant = Color(0xFFD4CCF2),
            )
        }
    CompositionLocalProvider(LocalNotliPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** The screen canvas: base colour plus a violet glow top-left and a cyan glow bottom-right. */
@Composable
internal fun Modifier.notliBackdrop(): Modifier {
    val palette = NotliTheme.palette
    val glowAlpha = if (palette.isDark) 0.38f else 0.55f
    return this.background(palette.canvas).drawBehind {
        val radius = size.maxDimension * 0.75f
        drawRect(
            Brush.radialGradient(
                colors = listOf(palette.glowViolet.copy(alpha = glowAlpha), Color.Transparent),
                center = Offset(size.width * 0.05f, size.height * 0.02f),
                radius = radius,
            ),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(palette.glowCyan.copy(alpha = glowAlpha * 0.8f), Color.Transparent),
                center = Offset(size.width * 0.98f, size.height * 0.9f),
                radius = radius,
            ),
        )
    }
}

/** Convenience container that paints [notliBackdrop] behind [content]. */
@Composable
internal fun NotliBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.notliBackdrop()) { content() }
}
