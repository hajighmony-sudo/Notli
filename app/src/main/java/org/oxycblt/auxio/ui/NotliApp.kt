/*
 * Copyright (c) 2026 Auxio Project
 * NotliApp.kt is part of Auxio.
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

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * The Notli app shell: the four tabs from the reference design (Home, Library, Search, Settings),
 * the mini player and bottom navigation, the hamburger drawer and the full-screen player, with
 * animated transitions between all of them and day / night / system theming.
 *
 * It is stateless with respect to music: the caller passes the current track, the lists and the
 * callbacks, so this layer never reaches into playback or the Rust core.
 */

/** Every user-visible string of the shell, so it can be localised (English / Persian) later. */
internal class NotliAppLabels(
    val home: NotliHomeLabels,
    val player: NotliPlayerLabels,
    val navHome: String,
    val navLibrary: String,
    val navSearch: String,
    val navSettings: String,
    val menu: String,
    val closeMenu: String,
    val menuFooter: String,
    val searchHint: String,
    val searchEmptyTitle: String,
    val searchEmptyBody: String,
    val searchNoResults: String,
    val clear: String,
    val appearance: String,
    val themeSystem: String,
    val themeDay: String,
    val themeNight: String,
    val playbackGroup: String,
    val aboutGroup: String,
    val aboutName: String,
    val aboutVersion: String,
)

/** English strings that match the reference design. */
internal fun notliEnglishLabels(): NotliAppLabels =
    NotliAppLabels(
        home =
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
            ),
        player =
            NotliPlayerLabels(
                collapse = "Collapse player",
                previous = "Previous",
                next = "Next",
                play = "Play",
                pause = "Pause",
                shuffle = "Shuffle",
                repeat = "Repeat",
            ),
        navHome = "Home",
        navLibrary = "Library",
        navSearch = "Search",
        navSettings = "Settings",
        menu = "Menu",
        closeMenu = "Close menu",
        menuFooter = "Notli · Your sound, your space",
        searchHint = "Search songs, albums, artists",
        searchEmptyTitle = "Find your sound",
        searchEmptyBody = "Type a song, album or artist to start.",
        searchNoResults = "Nothing found",
        clear = "Clear",
        appearance = "Appearance",
        themeSystem = "System",
        themeDay = "Day",
        themeNight = "Night",
        playbackGroup = "Playback",
        aboutGroup = "About",
        aboutName = "Notli",
        aboutVersion = "Your sound, your space",
    )

/** Default switches shown in Settings -> Playback. */
internal fun notliDefaultToggles(
    gapless: Boolean = true,
    haptics: Boolean = true,
    animations: Boolean = true,
): List<NotliToggleItem> =
    listOf(
        NotliToggleItem(
            key = "gapless",
            icon = Icons.Filled.Equalizer,
            title = "Gapless playback",
            subtitle = "No silence between tracks",
            checked = gapless,
        ),
        NotliToggleItem(
            key = "haptics",
            icon = Icons.Filled.Vibration,
            title = "Haptic feedback",
            subtitle = "Subtle vibration on controls",
            checked = haptics,
        ),
        NotliToggleItem(
            key = "animations",
            icon = Icons.Filled.Animation,
            title = "Rich animations",
            subtitle = "Equalizer, glow and vinyl motion",
            checked = animations,
        ),
    )

/**
 * Registers an [OnBackPressedCallback] while [enabled]. Written against the activity's own
 * dispatcher so it needs no extra dependency.
 */
@Composable
private fun NotliBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(context, enabled) {
        val callback =
            object : OnBackPressedCallback(enabled) {
                override fun handleOnBackPressed() {
                    currentOnBack()
                }
            }
        context.findDispatcherOwner()?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }
}

private tailrec fun Context.findDispatcherOwner(): OnBackPressedDispatcherOwner? =
    when (this) {
        is OnBackPressedDispatcherOwner -> this
        is ContextWrapper -> baseContext.findDispatcherOwner()
        else -> null
    }

/**
 * The whole app UI.
 *
 * [artwork] receives a key: `"now-playing"` for the current track, otherwise the key of the list
 * item or recent card being drawn. [searchResults] is called with the current query.
 */
@Composable
internal fun NotliApp(
    labels: NotliAppLabels,
    nowPlaying: NotliNowPlaying,
    actions: NotliPlayerActions,
    recent: List<NotliTrackCardItem>,
    libraryItems: List<List<NotliListItem>>,
    searchResults: (String) -> List<NotliListItem>,
    themeMode: NotliThemeMode,
    onThemeMode: (NotliThemeMode) -> Unit,
    toggles: List<NotliToggleItem>,
    onToggle: (String, Boolean) -> Unit,
    onItemClick: (NotliListItem) -> Unit,
    onItemMore: (NotliListItem) -> Unit,
    onRecentClick: (Int) -> Unit,
    onRecentMore: (Int) -> Unit,
    onPlayMix: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (key: String) -> Unit,
) {
    val dark =
        when (themeMode) {
            NotliThemeMode.System -> isSystemInDarkTheme()
            NotliThemeMode.Day -> false
            NotliThemeMode.Night -> true
        }
    NotliThemeHost(darkTheme = dark) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var libraryChip by rememberSaveable { mutableIntStateOf(0) }
        var query by rememberSaveable { mutableStateOf("") }
        var drawerOpen by rememberSaveable { mutableStateOf(false) }
        var playerOpen by rememberSaveable { mutableStateOf(false) }

        NotliBackHandler(enabled = drawerOpen || playerOpen) {
            if (drawerOpen) drawerOpen = false else playerOpen = false
        }

        val navItems =
            notliDefaultNavItems(
                home = labels.navHome,
                library = labels.navLibrary,
                search = labels.navSearch,
                settings = labels.navSettings,
            )
        val chips = notliDefaultChips(labels.home)
        val progress =
            if (nowPlaying.durationMs > 0) {
                nowPlaying.positionMs.toFloat() / nowPlaying.durationMs
            } else {
                0f
            }

        Box(
            modifier
                .fillMaxSize()
                .notliBackdrop()
                // Swipe in from the start edge to open the menu.
                .pointerInput(Unit) {
                    var startX = 0f
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            startX = it.x
                            total = 0f
                        },
                        onHorizontalDrag = { _, delta ->
                            total += delta
                            if (startX < 32.dp.toPx() && total > 60.dp.toPx()) drawerOpen = true
                        },
                    )
                },
        ) {
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(280)) +
                        slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) {
                            direction * it / 6
                        }) togetherWith
                        (fadeOut(tween(160)) +
                            slideOutHorizontally(tween(260)) { -direction * it / 6 })
                },
                label = "notli-screen",
            ) { page ->
                when (page) {
                    0 ->
                        NotliHomeDashboard(
                            labels = labels.home,
                            nowPlaying = nowPlaying,
                            actions = actions,
                            recent = recent,
                            selectedChip = libraryChip,
                            onChipSelected = {
                                libraryChip = it
                                tab = 1
                            },
                            onSearch = { tab = 2 },
                            onSettings = { tab = 3 },
                            onSeeAllLibrary = { tab = 1 },
                            onSeeAllRecent = { tab = 1 },
                            onRecentClick = onRecentClick,
                            onRecentMore = onRecentMore,
                            onPlayMix = onPlayMix,
                            chips = chips,
                            nowPlayingArtwork = { artwork("now-playing") },
                            recentArtwork = { artwork(it.key) },
                        )
                    1 ->
                        NotliLibraryScreen(
                            title = labels.navLibrary,
                            menuLabel = labels.menu,
                            moreLabel = labels.home.more,
                            chips = chips,
                            selectedChip = libraryChip,
                            onChipSelected = { libraryChip = it },
                            items = libraryItems.getOrElse(libraryChip) { emptyList() },
                            onMenu = { drawerOpen = true },
                            onItemClick = onItemClick,
                            onItemMore = onItemMore,
                            artwork = { artwork(it.key) },
                        )
                    2 ->
                        NotliSearchScreen(
                            title = labels.navSearch,
                            menuLabel = labels.menu,
                            moreLabel = labels.home.more,
                            hint = labels.searchHint,
                            clearLabel = labels.clear,
                            emptyTitle = labels.searchEmptyTitle,
                            emptyBody = labels.searchEmptyBody,
                            noResultsTitle = labels.searchNoResults,
                            query = query,
                            onQueryChange = { query = it },
                            results = searchResults(query),
                            onMenu = { drawerOpen = true },
                            onItemClick = onItemClick,
                            onItemMore = onItemMore,
                            artwork = { artwork(it.key) },
                        )
                    else ->
                        NotliSettingsScreen(
                            title = labels.navSettings,
                            menuLabel = labels.menu,
                            appearanceTitle = labels.appearance,
                            themeLabels =
                                listOf(labels.themeSystem, labels.themeDay, labels.themeNight),
                            themeMode = themeMode,
                            onThemeMode = onThemeMode,
                            playbackTitle = labels.playbackGroup,
                            toggles = toggles,
                            onToggle = onToggle,
                            aboutTitle = labels.aboutGroup,
                            aboutName = labels.aboutName,
                            aboutVersion = labels.aboutVersion,
                            onMenu = { drawerOpen = true },
                        )
                }
            }

            // The home header is pixel-matched to the reference and has no menu button, so the
            // waveform logo doubles as the hamburger entry point there.
            if (tab == 0) {
                Box(
                    Modifier.statusBarsPadding()
                        .padding(start = 20.dp, top = 14.dp)
                        .size(width = 46.dp, height = 54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button) { drawerOpen = true }
                        .semantics { contentDescription = labels.menu },
                )
            }

            Column(Modifier.align(Alignment.BottomCenter)) {
                NotliMiniPlayer(
                    title = nowPlaying.title,
                    artist = nowPlaying.artist,
                    isPlaying = nowPlaying.isPlaying,
                    progress = progress,
                    onOpen = { playerOpen = true },
                    onPrevious = actions.onPrevious,
                    onTogglePlaying = actions.onTogglePlaying,
                    onNext = actions.onNext,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    previousDescription = labels.player.previous,
                    nextDescription = labels.player.next,
                    playDescription = labels.player.play,
                    pauseDescription = labels.player.pause,
                    artwork = { artwork("now-playing") },
                )
                NotliBottomNav(items = navItems, selectedIndex = tab, onSelect = { tab = it })
            }

            AnimatedVisibility(
                visible = playerOpen,
                enter =
                    slideInVertically(
                        spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ) {
                        it
                    } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
            ) {
                NotliFullPlayer(
                    state = nowPlaying,
                    actions = actions,
                    labels = labels.home,
                    playerLabels = labels.player,
                    onCollapse = { playerOpen = false },
                    artwork = { artwork("now-playing") },
                )
            }

            NotliDrawer(
                visible = drawerOpen,
                items = navItems,
                selectedIndex = tab,
                closeLabel = labels.closeMenu,
                tagline = labels.home.tagline,
                footer = labels.menuFooter,
                onSelect = {
                    tab = it
                    drawerOpen = false
                },
                onDismiss = { drawerOpen = false },
            )
        }
    }
}
