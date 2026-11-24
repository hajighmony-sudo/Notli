/*
 * Copyright (c) 2026 Auxio Project
 * NotliScreens.kt is part of Auxio.
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The remaining Notli screens from the reference design: Library, Search, Settings, the hamburger
 * drawer and the full-screen player. Like NotliHome.kt everything here is stateless and
 * presentation-only: data, artwork and callbacks come from the caller, and nothing touches playback.
 */

// ---------------------------------------------------------------- models

internal data class NotliListItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val trailing: String = "",
)

/** How the app picks between the day and night palettes. */
internal enum class NotliThemeMode {
    System,
    Day,
    Night,
}

internal data class NotliToggleItem(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val checked: Boolean,
)

/** Accessibility labels for the player controls. */
internal class NotliPlayerLabels(
    val collapse: String,
    val previous: String,
    val next: String,
    val play: String,
    val pause: String,
    val shuffle: String,
    val repeat: String,
)

/** Stand-in artwork: a diagonal gradient. Real covers are supplied by the caller. */
@Composable
internal fun NotliPlaceholderArt(seed: Int, modifier: Modifier = Modifier) {
    val gradients =
        listOf(
            listOf(Color(0xFF2A1466), Color(0xFF7C3AED), Color(0xFF22D3EE)),
            listOf(Color(0xFF0B1B3A), Color(0xFF2563EB), Color(0xFF67E8F9)),
            listOf(Color(0xFF3B0764), Color(0xFFDB2777), Color(0xFF22D3EE)),
            listOf(Color(0xFF1E1B4B), Color(0xFF6D28D9), Color(0xFFF472B6)),
        )
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.linearGradient(gradients[Math.floorMod(seed, gradients.size)])),
    )
}

// ---------------------------------------------------------------- shared pieces

/** Header for the secondary screens: hamburger button, title and the waveform mark. */
@Composable
internal fun NotliScreenHeader(
    title: String,
    menuLabel: String,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotliRoundButton(Icons.Filled.Menu, menuLabel, onMenu)
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        NotliLogoMark(Modifier.width(30.dp).height(34.dp))
    }
}

@Composable
internal fun NotliListRow(
    item: NotliListItem,
    index: Int,
    moreLabel: String,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (NotliListItem) -> Unit,
) {
    val palette = NotliTheme.palette
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .notliEnter(index.coerceAtMost(8), stepMs = 50)
                .notliPressScale(0.98f)
                .notliCard(shape)
                .clickable(onClick = onClick)
                .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(14.dp))) { artwork(item) }
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = palette.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle,
                color = palette.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.trailing.isNotEmpty()) {
            Text(item.trailing, color = palette.textSecondary, fontSize = 13.sp)
        }
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onMore),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MoreVert,
                moreLabel,
                tint = palette.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A calm placeholder whose icon breathes slowly. */
@Composable
internal fun NotliEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    val breathe by
        rememberInfiniteTransition(label = "empty")
            .animateFloat(
                initialValue = 0.92f,
                targetValue = 1.08f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(1800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "empty-scale",
            )
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp)
                .graphicsLayer {
                    scaleX = breathe
                    scaleY = breathe
                }
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = 0.16f))
                .border(1.5.dp, palette.rimBrush, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = palette.accentSoft, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            color = palette.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                color = palette.textSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------- library

/**
 * The library screen: the Songs / Albums / Artists / Playlists chips from home, and the list for
 * the selected chip. The caller decides which items belong to [selectedChip]; switching chips
 * cross-fades the list.
 */
@Composable
internal fun NotliLibraryScreen(
    title: String,
    menuLabel: String,
    moreLabel: String,
    chips: List<NotliChipItem>,
    selectedChip: Int,
    onChipSelected: (Int) -> Unit,
    items: List<NotliListItem>,
    onMenu: () -> Unit,
    onItemClick: (NotliListItem) -> Unit,
    onItemMore: (NotliListItem) -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (NotliListItem) -> Unit,
) {
    Column(modifier.fillMaxSize().notliBackdrop()) {
        NotliScreenHeader(title, menuLabel, onMenu)
        NotliLibraryChips(chips, selectedChip, onChipSelected)
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            targetState = selectedChip,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(140)) },
            label = "library-section",
        ) { _ ->
            LazyColumn(
                contentPadding = PaddingValues(bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                    NotliListRow(
                        item = item,
                        index = index,
                        moreLabel = moreLabel,
                        onClick = { onItemClick(item) },
                        onMore = { onItemMore(item) },
                        artwork = artwork,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- search

/** A pill search field whose rim lights up while focused and which offers an animated clear. */
@Composable
internal fun NotliSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    var focused by remember { mutableStateOf(false) }
    val rimAlpha by
        animateFloatAsState(
            targetValue = if (focused) 1f else 0.3f,
            animationSpec = tween(220),
            label = "search-rim",
        )
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(56.dp)
                .clip(shape)
                .background(palette.chip, shape)
                .border(1.5.dp, palette.accent.copy(alpha = rimAlpha), shape)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = palette.accentSoft, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(hint, color = palette.textSecondary, fontSize = 16.sp, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = palette.textPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.6f),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.6f),
        ) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Clear,
                    clearLabel,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun NotliSearchScreen(
    title: String,
    menuLabel: String,
    moreLabel: String,
    hint: String,
    clearLabel: String,
    emptyTitle: String,
    emptyBody: String,
    noResultsTitle: String,
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<NotliListItem>,
    onMenu: () -> Unit,
    onItemClick: (NotliListItem) -> Unit,
    onItemMore: (NotliListItem) -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (NotliListItem) -> Unit,
) {
    val state =
        when {
            query.isBlank() -> 0
            results.isEmpty() -> 1
            else -> 2
        }
    Column(modifier.fillMaxSize().notliBackdrop()) {
        NotliScreenHeader(title, menuLabel, onMenu)
        NotliSearchField(query, onQueryChange, hint, clearLabel)
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            targetState = state,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(120)) },
            label = "search-state",
        ) { page ->
            when (page) {
                0 -> NotliEmptyState(Icons.Filled.Search, emptyTitle, emptyBody)
                1 -> NotliEmptyState(Icons.Filled.MusicNote, noResultsTitle, "")
                else ->
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(results, key = { _, item -> item.key }) { index, item ->
                            NotliListRow(
                                item = item,
                                index = index,
                                moreLabel = moreLabel,
                                onClick = { onItemClick(item) },
                                onMore = { onItemMore(item) },
                                artwork = artwork,
                            )
                        }
                    }
            }
        }
    }
}

// ---------------------------------------------------------------- settings

/** A switch whose thumb springs between its two positions. */
@Composable
internal fun NotliSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val progress by
        animateFloatAsState(
            targetValue = if (checked) 1f else 0f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            label = "switch-thumb",
        )
    val track by
        animateColorAsState(
            targetValue =
                if (checked) palette.accent else palette.textSecondary.copy(alpha = 0.32f),
            animationSpec = tween(200),
            label = "switch-track",
        )
    Box(
        modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(CircleShape)
            .background(track)
            .clickable(role = Role.Switch, onClick = { onCheckedChange(!checked) })
            .padding(3.dp),
    ) {
        Box(
            Modifier.size(24.dp)
                .graphicsLayer { translationX = direction * progress * 22.dp.toPx() }
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** Three-way segmented control (system / day / night) with a sliding highlight. */
@Composable
internal fun NotliThemeSelector(
    selected: NotliThemeMode,
    labels: List<String>,
    onSelect: (NotliThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    val modes = NotliThemeMode.entries
    val icons =
        listOf(Icons.Filled.SettingsBrightness, Icons.Filled.LightMode, Icons.Filled.DarkMode)
    val shape = RoundedCornerShape(18.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(palette.chip, shape)
            .padding(4.dp),
    ) {
        val segment: Dp = maxWidth / modes.size
        val indicatorX by
            animateDpAsState(
                targetValue = segment * selected.ordinal,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                label = "theme-indicator",
            )
        Box(
            Modifier.offset(x = indicatorX)
                .width(segment)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.accentBrush),
        )
        Row(Modifier.fillMaxSize()) {
            modes.forEachIndexed { index, mode ->
                val isSelected = mode == selected
                val tint by
                    animateColorAsState(
                        targetValue = if (isSelected) Color.White else palette.textSecondary,
                        animationSpec = tween(200),
                        label = "theme-tint",
                    )
                Row(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelect(mode) },
                        ),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icons[index], null, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        labels[index],
                        color = tint,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun NotliSettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = NotliTheme.palette
    Column(modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier.padding(start = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.verticalGradient(listOf(palette.accentSoft, palette.accent))),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                color = palette.textSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().notliCard(RoundedCornerShape(24.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsIconTile(icon: ImageVector) {
    val palette = NotliTheme.palette
    Box(
        Modifier.size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                palette.accent.copy(alpha = 0.16f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = palette.accentSoft, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun NotliToggleRow(item: NotliToggleItem, onToggle: (Boolean) -> Unit) {
    val palette = NotliTheme.palette
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
            onToggle(!item.checked)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsIconTile(item.icon)
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = palette.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(item.subtitle, color = palette.textSecondary, fontSize = 13.sp)
        }
        NotliSwitch(item.checked, onToggle)
    }
}

@Composable
internal fun NotliSettingsScreen(
    title: String,
    menuLabel: String,
    appearanceTitle: String,
    themeLabels: List<String>,
    themeMode: NotliThemeMode,
    onThemeMode: (NotliThemeMode) -> Unit,
    playbackTitle: String,
    toggles: List<NotliToggleItem>,
    onToggle: (String, Boolean) -> Unit,
    aboutTitle: String,
    aboutName: String,
    aboutVersion: String,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    Column(
        modifier = modifier.fillMaxSize().notliBackdrop().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NotliScreenHeader(title, menuLabel, onMenu)
        NotliSettingsGroup(appearanceTitle, Modifier.notliEnter(1)) {
            NotliThemeSelector(themeMode, themeLabels, onThemeMode)
        }
        NotliSettingsGroup(playbackTitle, Modifier.notliEnter(2)) {
            toggles.forEach { item -> NotliToggleRow(item) { onToggle(item.key, it) } }
        }
        NotliSettingsGroup(aboutTitle, Modifier.notliEnter(3)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsIconTile(Icons.Filled.Info)
                Column(Modifier.weight(1f)) {
                    Text(
                        aboutName,
                        color = palette.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(aboutVersion, color = palette.textSecondary, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(200.dp))
    }
}

// ---------------------------------------------------------------- hamburger drawer

/**
 * The hamburger menu: a glass panel that springs in from the start edge over a dimmed scrim, with
 * its entries rising in one after another. Tapping the scrim or the close button dismisses it.
 */
@Composable
internal fun NotliDrawer(
    visible: Boolean,
    items: List<NotliNavItem>,
    selectedIndex: Int,
    closeLabel: String,
    tagline: String,
    footer: String,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = NotliTheme.palette
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1 else -1
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.CenterStart),
            enter =
                slideInHorizontally(
                    spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ) {
                    sign * it
                } + fadeIn(tween(160)),
            exit = slideOutHorizontally(tween(220)) { sign * it } + fadeOut(tween(160)),
        ) {
            Column(
                Modifier.fillMaxHeight()
                    .width(304.dp)
                    .notliCard(RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp))
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NotliLogoMark(Modifier.width(40.dp).height(46.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "NOTLI",
                            style =
                                TextStyle(
                                    brush =
                                        Brush.horizontalGradient(
                                            listOf(palette.textPrimary, palette.accentSoft),
                                        ),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp,
                                ),
                            maxLines = 1,
                        )
                        Text(
                            tagline,
                            color = palette.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            closeLabel,
                            tint = palette.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                items.forEachIndexed { index, item ->
                    val selected = index == selectedIndex
                    val fill by
                        animateColorAsState(
                            targetValue =
                                if (selected) palette.accent.copy(alpha = 0.22f)
                                else Color.Transparent,
                            animationSpec = tween(200),
                            label = "drawer-fill",
                        )
                    val tint by
                        animateColorAsState(
                            targetValue =
                                if (selected) palette.accentSoft else palette.textSecondary,
                            animationSpec = tween(200),
                            label = "drawer-tint",
                        )
                    Row(
                        Modifier.fillMaxWidth()
                            .notliEnter(index + 1, stepMs = 45)
                            .notliPressScale(0.97f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(fill)
                            .clickable { onSelect(index) }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(item.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                        Text(
                            item.label,
                            color = if (selected) palette.textPrimary else tint,
                            fontSize = 17.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    footer,
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- full-screen player

@Composable
private fun PlayerAction(
    icon: ImageVector,
    description: String,
    tint: Color,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(56.dp)
            .notliPressScale(0.88f)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * The expanded player. Artwork swells and glows while playing and settles back when paused; the
 * seek bar is the same draggable one used on home, so scrubbing forward and back behaves
 * identically everywhere.
 */
@Composable
internal fun NotliFullPlayer(
    state: NotliNowPlaying,
    actions: NotliPlayerActions,
    labels: NotliHomeLabels,
    playerLabels: NotliPlayerLabels,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
) {
    val palette = NotliTheme.palette
    val fraction =
        if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        else 0f
    val artScale by
        animateFloatAsState(
            targetValue = if (state.isPlaying) 1f else 0.88f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            label = "player-art-scale",
        )
    val glow =
        if (state.isPlaying) {
            val transition = rememberInfiniteTransition(label = "player-glow")
            val v by
                transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 0.6f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(1800, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                    label = "player-glow-alpha",
                )
            v
        } else {
            0.12f
        }
    val artShape = RoundedCornerShape(36.dp)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .notliBackdrop()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NotliRoundButton(Icons.Filled.KeyboardArrowDown, playerLabels.collapse, onCollapse)
            Spacer(Modifier.weight(1f))
            Row(
                modifier =
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(palette.accent.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NotliLogoMark(Modifier.size(width = 14.dp, height = 14.dp), animated = false)
                Text(
                    labels.nowPlaying,
                    color = palette.accentSoft,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                )
                NotliEqualizer(state.isPlaying, Modifier.size(width = 16.dp, height = 14.dp))
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp))
        }

        Spacer(Modifier.weight(0.6f))
        Box(
            modifier =
                Modifier.fillMaxWidth().aspectRatio(1f).drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(palette.accent.copy(alpha = glow), Color.Transparent),
                            radius = size.minDimension * 0.75f,
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxSize(0.92f)
                    .graphicsLayer {
                        scaleX = artScale
                        scaleY = artScale
                    }
                    .clip(artShape)
                    .border(1.dp, palette.rimBrush, artShape),
            ) {
                artwork()
            }
        }

        Spacer(Modifier.weight(0.5f))

        AnimatedContent(
            targetState = state.title to state.artist,
            transitionSpec = {
                (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 3 }) togetherWith
                    (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 3 })
            },
            label = "player-text",
        ) { (title, artist) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title,
                    color = palette.textPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    artist,
                    color = palette.textSecondary,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        NotliSeekBar(
            fraction = fraction,
            onSeek = { actions.onSeekTo((it * state.durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatPlaybackTime(state.positionMs),
                color = palette.textSecondary,
                fontSize = 13.sp,
            )
            Text(
                formatPlaybackTime(state.durationMs),
                color = palette.textSecondary,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shuffleTint by
                animateColorAsState(
                    if (state.shuffle) palette.accentSoft else palette.textSecondary,
                    tween(200),
                    label = "player-shuffle",
                )
            val repeatTint by
                animateColorAsState(
                    if (state.repeat) palette.accentSoft else palette.textSecondary,
                    tween(200),
                    label = "player-repeat",
                )
            PlayerAction(
                Icons.Filled.Shuffle,
                playerLabels.shuffle,
                shuffleTint,
                26.dp,
                actions.onShuffle,
            )
            PlayerAction(
                Icons.Filled.SkipPrevious,
                playerLabels.previous,
                palette.textPrimary,
                38.dp,
                actions.onPrevious,
            )
            Box(
                modifier =
                    Modifier.size(84.dp)
                        .notliPressScale(0.92f)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(palette.accent, Color(0xFF5B3BE0))),
                        )
                        .clickable(role = Role.Button, onClick = actions.onTogglePlaying)
                        .semantics {
                            contentDescription =
                                if (state.isPlaying) playerLabels.pause else playerLabels.play
                        },
                contentAlignment = Alignment.Center,
            ) {
                NotliPlayPauseGlyph(state.isPlaying, Color.White, Modifier.size(36.dp))
            }
            PlayerAction(
                Icons.Filled.SkipNext,
                playerLabels.next,
                palette.textPrimary,
                38.dp,
                actions.onNext,
            )
            PlayerAction(
                Icons.Filled.Repeat,
                playerLabels.repeat,
                repeatTint,
                26.dp,
                actions.onRepeat,
            )
        }
        Spacer(Modifier.weight(0.4f))
    }
}
