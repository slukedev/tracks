package com.luke.tracks.ui.tv.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.luke.tracks.graph
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.ui.navigation.AlbumRoute
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.CatalogAlbumRoute
import com.luke.tracks.ui.navigation.CatalogArtistRoute
import com.luke.tracks.ui.navigation.ChartsRoute
import com.luke.tracks.ui.navigation.HistoryRoute
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.LibraryRoute
import com.luke.tracks.ui.navigation.MixRoute
import com.luke.tracks.ui.navigation.PlayRoute
import com.luke.tracks.ui.navigation.Route
import com.luke.tracks.ui.navigation.SearchRoute
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.SettingsRoute
import com.luke.tracks.ui.navigation.SongRoute
import com.luke.tracks.ui.navigation.SummaryRoute
import com.luke.tracks.ui.tv.components.LocalTvFocusStore
import com.luke.tracks.ui.tv.components.LocalTvRouteKey
import com.luke.tracks.ui.tv.theme.LocalReducedMotion
import com.luke.tracks.ui.tv.theme.TvLayout
import com.luke.tracks.ui.tv.theme.TvMotion
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class NowPlaying(val song: PlayableSong?, val playing: Boolean)

internal val RailItemCollapsedWidth = NavigationDrawerItemDefaults.CollapsedDrawerItemWidth
internal val RailItemExpandedWidth = NavigationDrawerItemDefaults.ExpandedDrawerItemWidth

/**
 * The TV app's frame: a rail of destinations down the left that opens with labels while it
 * has focus and lies over the page, each destination's own pages, and the full-screen player
 * over everything. Back walks out of a page, then to the rail, then to Play, then out.
 */
@Composable
fun TvShell(navigator: TvNavigator, playerState: StateFlow<PlayerState> = LocalContext.current.graph.player.state) {
    // Only what the rail shows, so playback ticks don't recompose the frame.
    val nowPlaying by remember(playerState) {
        playerState.map { NowPlaying(it.current, it.isPlaying) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(NowPlaying(playerState.value.current, playerState.value.isPlaying))
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val railFocused = drawer.currentValue == DrawerValue.Open
    val railItems = remember { TvDestination.entries.associateWith { FocusRequester() } }
    val pages = remember { FocusRequester() }
    val back = {
        when (navigator.back(railFocused = drawer.currentValue == DrawerValue.Open)) {
            TvBack.FocusRail, TvBack.WentToPlay -> railItems.getValue(navigator.destination).requestFocus()
            TvBack.ClosedNowPlaying, TvBack.Popped, TvBack.Exit -> Unit
        }
        Unit
    }

    BackHandler(enabled = navigator.canGoBack(railFocused), onBack = back)

    // A page without focus memory can't take the focus it's handed, which would leave the
    // remote pointing nowhere, so the rail takes it.
    val claim = navigator.focus.claim
    LaunchedEffect(claim) {
        if (claim == null || claim == TvNavigator.NowPlayingKey) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }
        if (navigator.focus.claim == claim && !navigator.focus.isOnScreen(claim)) {
            navigator.focus.dropClaim()
            railItems.getValue(navigator.destination).requestFocus()
        }
    }

    CompositionLocalProvider(LocalTvNavigator provides navigator, LocalTvFocusStore provides navigator.focus) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Nothing behind the player takes focus while it's open.
                    .focusProperties { onEnter = { if (navigator.nowPlayingOpen) cancelFocusChange() } }
                    .focusGroup(),
            ) {
                ModalNavigationDrawer(
                    drawerState = drawer,
                    scrimBrush = Brush.horizontalGradient(
                        0f to MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                        0.45f to MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                        1f to Color.Transparent,
                    ),
                    drawerContent = { value ->
                        Rail(
                            navigator = navigator,
                            nowPlaying = nowPlaying,
                            open = value == DrawerValue.Open,
                            requesters = railItems,
                            pages = pages,
                        )
                    },
                ) {
                    Pages(navigator, back, Modifier.focusRequester(pages).focusGroup())
                }
            }
            NowPlayingOverlay(navigator)
        }
    }
}

@Composable
private fun NavigationDrawerScope.Rail(
    navigator: TvNavigator,
    nowPlaying: NowPlaying,
    open: Boolean,
    requesters: Map<TvDestination, FocusRequester>,
    pages: FocusRequester,
) {
    val song = nowPlaying.song
    Column(
        Modifier
            .fillMaxHeight()
            .then(if (open) Modifier.background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background.copy(alpha = 0f)))) else Modifier)
            .padding(start = 12.dp, end = 12.dp, top = TvLayout.SafeTop, bottom = TvLayout.SafeBottom)
            .onFocusChanged { if (it.hasFocus) navigator.focus.dropClaim() }
            .focusProperties {
                // Into the rail lands on the page you're on, not the nearest item.
                onEnter = { requesters.getValue(navigator.destination).requestFocus() }
            }
            // Right goes back to the item you left. Pages bleed under the rail, so the default
            // search finds nothing to its right: their rows and columns start at the screen's edge.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionRight) return@onPreviewKeyEvent false
                navigator.focus.restore(navigator.topKey) || pages.requestFocus()
                true
            }
            .focusGroup()
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (song != null) {
            NowPlayingChip(
                song = song,
                playing = nowPlaying.playing,
                expanded = hasFocus,
                onClick = navigator::openNowPlaying,
            )
            Spacer(Modifier.height(12.dp))
        }
        TvDestination.entries.filter { it != TvDestination.Settings }.forEach { destination ->
            RailItem(destination, navigator, requesters.getValue(destination))
        }
        Spacer(Modifier.weight(1f))
        RailItem(TvDestination.Settings, navigator, requesters.getValue(TvDestination.Settings))
    }
}

@Composable
private fun NavigationDrawerScope.RailItem(destination: TvDestination, navigator: TvNavigator, requester: FocusRequester) {
    val selected = navigator.destination == destination
    val colors = MaterialTheme.colorScheme
    NavigationDrawerItem(
        selected = selected,
        onClick = { navigator.select(destination) },
        leadingContent = {
            Icon(if (selected) destination.selectedIcon else destination.icon, contentDescription = null)
        },
        modifier = Modifier.focusRequester(requester),
        colors = NavigationDrawerItemDefaults.colors(
            focusedContainerColor = colors.primary,
            focusedContentColor = colors.onPrimary,
            pressedContainerColor = colors.primary,
            pressedContentColor = colors.onPrimary,
            selectedContainerColor = colors.secondaryContainer.copy(alpha = if (hasFocus) 1f else 0.7f),
            selectedContentColor = colors.onSecondaryContainer,
            focusedSelectedContainerColor = colors.primary,
            focusedSelectedContentColor = colors.onPrimary,
            inactiveContentColor = colors.onSurfaceVariant,
        ),
    ) {
        Text(destination.label, maxLines = 1)
    }
}

@Composable
private fun Pages(navigator: TvNavigator, onBack: () -> Unit, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val holder = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = navigator.destination,
        transitionSpec = {
            fadeIn(tween(TvMotion.ScreenMs, delayMillis = 60, easing = TvMotion.EaseOut))
                .togetherWith(fadeOut(tween(90, easing = TvMotion.EaseOut)))
        },
        label = "destination",
        modifier = modifier,
    ) { destination ->
        holder.SaveableStateProvider(destination.name) {
            val slide = with(LocalDensity.current) { 32.dp.roundToPx() }
            NavDisplay(
                backStack = navigator.entries(destination),
                onBack = onBack,
                transitionSpec = { push(slide, reduced) },
                popTransitionSpec = { pop(slide, reduced) },
                predictivePopTransitionSpec = { pop(slide, reduced) },
                entryProvider = { entry ->
                    NavEntry(entry) {
                        CompositionLocalProvider(LocalTvRouteKey provides navigator.entryKey(destination, entry.depth)) {
                            Screen(entry.route)
                        }
                    }
                },
            )
        }
    }
}

private fun push(slide: Int, reduced: Boolean): ContentTransform =
    if (reduced) {
        fadeIn(tween(TvMotion.ScreenMs)) togetherWith fadeOut(tween(90))
    } else {
        (slideInHorizontally(tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut)) { slide } + fadeIn(tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut)))
            .togetherWith(slideOutHorizontally(tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut)) { -slide / 2 } + fadeOut(tween(90)))
    }

private fun pop(slide: Int, reduced: Boolean): ContentTransform =
    if (reduced) {
        fadeIn(tween(TvMotion.ScreenMs)) togetherWith fadeOut(tween(90))
    } else {
        (slideInHorizontally(tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut)) { -slide / 2 } + fadeIn(tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut)))
            .togetherWith(slideOutHorizontally(tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut)) { slide } + fadeOut(tween(90)))
    }

@Composable
private fun Screen(route: Route) {
    when (route) {
        PlayRoute -> TvScreens.play()
        LibraryRoute -> TvScreens.library()
        SummaryRoute -> TvScreens.summary()
        ChartsRoute -> TvScreens.charts()
        HistoryRoute -> TvScreens.history()
        SearchRoute -> TvScreens.search()
        SettingsRoute -> TvScreens.settings()
        is SettingsPageRoute -> TvScreens.settingsPage(route)
        is AlbumRoute -> TvScreens.album(route)
        is ArtistRoute -> TvScreens.artist(route)
        is SongRoute -> TvScreens.song(route)
        is MixRoute -> TvScreens.mix(route)
        is LibraryAlbumRoute -> TvScreens.libraryAlbum(route)
        is LibraryArtistRoute -> TvScreens.libraryArtist(route)
        is CatalogAlbumRoute -> TvScreens.catalogAlbum(route)
        is CatalogArtistRoute -> TvScreens.catalogArtist(route)
        // Sources that never show on TV.
        else -> Unit
    }
}

@Composable
private fun NowPlayingOverlay(navigator: TvNavigator) {
    val reduced = LocalReducedMotion.current
    val ms = TvMotion.NowPlayingMs
    AnimatedVisibility(
        visible = navigator.nowPlayingOpen,
        enter = if (reduced) fadeIn(tween(ms)) else scaleIn(tween(ms, easing = TvMotion.EaseOut), initialScale = 0.94f) + fadeIn(tween(ms, easing = TvMotion.EaseOut)),
        exit = if (reduced) fadeOut(tween(200)) else scaleOut(tween(200, easing = TvMotion.EaseOut), targetScale = 0.96f) + fadeOut(tween(200, easing = TvMotion.EaseOut)),
    ) {
        val requester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            // The player claims its own focus; if nothing inside took it, its first control does.
            repeat(3) { withFrameNanos { } }
            if (navigator.focus.claim == TvNavigator.NowPlayingKey) {
                requester.requestFocus()
                navigator.focus.dropClaim()
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .focusRequester(requester)
                .focusProperties { onExit = { if (navigator.nowPlayingOpen) cancelFocusChange() } }
                .focusGroup(),
        ) {
            CompositionLocalProvider(LocalTvRouteKey provides TvNavigator.NowPlayingKey) {
                TvScreens.nowPlaying(navigator::closeNowPlaying)
            }
        }
    }
}
