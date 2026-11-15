package com.luke.tracks.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.graph
import com.luke.tracks.ui.discover.Remote
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.insights.ItemActionsBox
import com.luke.tracks.ui.insights.ItemTarget
import com.luke.tracks.ui.insights.LocalInsightsSnackbar
import com.luke.tracks.ui.navigation.AlbumRoute
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.MixRoute
import com.luke.tracks.ui.navigation.SearchRoute
import com.luke.tracks.ui.navigation.SettingsRoute
import com.luke.tracks.ui.navigation.sharedElement
import com.luke.tracks.ui.play.components.ArtistMenu
import com.luke.tracks.ui.play.components.MiniPlayerClearance
import com.luke.tracks.ui.play.components.MixArt
import com.luke.tracks.ui.play.components.MixMenu
import com.luke.tracks.ui.play.components.PlayActions
import com.luke.tracks.ui.play.components.pressable
import com.luke.tracks.ui.play.components.rememberPlayActions
import com.luke.tracks.ui.play.components.shareText
import com.luke.tracks.ui.theme.GoogleSansFlexCondensed
import kotlinx.coroutines.launch
import uniffi.tracks_engine.ArtistStats
import uniffi.tracks_engine.Mix
import java.util.Calendar

/**
 * The Play tab: start something in one tap. In the order of the iPhone's Play: today's crate
 * of mixes with Tracks Radio, then new music for you (songs and artists you don't have),
 * what you played lately, your artists, the mixes made from your listening and your artists'
 * new albums, with more suggestions for as long as you scroll.
 */
@Composable
fun PlayScreen() {
    val graph = LocalContext.current.graph
    val navigator = LocalNavigator.current
    val feed by rememberPlayFeed()
    val player by graph.player.state.collectAsStateWithLifecycle()
    val actions = rememberPlayActions()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val current = feed
    val topArtists = remember(current?.topArtists) { current?.topArtists.orEmpty().take(8).map { it.identity }.toSet() }
    val suggestions = rememberPlaySuggestions(enabled = current?.hasHistory == true, topArtists = topArtists)

    val unplayable: () -> Unit = {
        scope.launch { snackbar.showSnackbar("None of these songs are in your music on this phone yet") }
    }
    val openMix: (Mix) -> Unit = { navigator.open(MixRoute(it.id)) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = MiniPlayerClearance - 16.dp)) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(greeting(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = current?.mixes?.rightNow?.kind?.let { kind ->
                    { Text((kind as? uniffi.tracks_engine.MixKind.RightNow)?.let { it.part.moment(it.isWeekend) } ?: "") }
                },
                actions = {
                    IconButton(onClick = { navigator.open(SearchRoute) }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { navigator.open(SettingsRoute) }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        // Getting a song from a suggestion says how it went here.
        CompositionLocalProvider(LocalInsightsSnackbar provides snackbar) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = MiniPlayerClearance + 16.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                item(key = "crate", contentType = "crate") {
                    Crate(
                        items = remember(current) { crateItems(current) },
                        feed = current,
                        player = player,
                        actions = actions,
                        onOpenMix = openMix,
                        onUnplayable = unplayable,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                when {
                    current == null -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                            LoadingIndicator(Modifier.size(56.dp))
                        }
                    }
                    !current.hasHistory -> item(key = "empty") { EmptyPlay(Modifier.animateItem()) }
                    else -> {
                        // Each section only when it has something, so no gap is left for one
                        // that doesn't. Offline with nothing found before, they stay away.
                        val found = suggestions.value
                        if (suggestions.state is Remote.Loading || found?.songs?.isNotEmpty() == true) {
                            item(key = "suggested-songs", contentType = "suggested-songs") {
                                SuggestedSongsShelf(suggestions, Modifier.animateItem())
                            }
                        }
                        if (found?.artists?.isNotEmpty() == true) {
                            item(key = "suggested-artists", contentType = "artists") {
                                SuggestedArtistsShelf(suggestions, Modifier.animateItem())
                            }
                        }
                        if (current.recentlyPlayed.isNotEmpty()) {
                            item(key = "recent", contentType = "shelf") {
                                RecentlyPlayed(current.recentlyPlayed, Modifier.animateItem())
                            }
                        }
                        if (current.topArtists.isNotEmpty()) {
                            item(key = "artists", contentType = "artists") {
                                ArtistRail(current.topArtists, actions, Modifier.animateItem())
                            }
                        }
                        if (current.mixes.mixes.isNotEmpty()) {
                            item(key = "mixes", contentType = "shelf") {
                                MixShelf(current, actions, openMix, unplayable, Modifier.animateItem())
                            }
                        }
                        if (found?.releases?.isNotEmpty() == true) {
                            item(key = "new-releases", contentType = "shelf") {
                                NewReleasesShelf(suggestions, Modifier.animateItem())
                            }
                        }
                        if ((found?.songs?.size ?: 0) > SHELF_SONGS) {
                            item(key = "keep-exploring", contentType = "explore") {
                                KeepExploring(suggestions, Modifier.animateItem())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleLargeEmphasized,
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 4.dp),
    )
}

@Composable
private fun MixShelf(feed: PlayFeed, actions: PlayActions, onOpen: (Mix) -> Unit, onUnplayable: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        SectionTitle("Made from your listening")
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(feed.mixes.mixes, key = { it.id }) { mix ->
                var menu by remember { mutableStateOf(false) }
                Column(
                    Modifier
                        .animateItem()
                        .width(168.dp)
                        .pressable(onClick = { onOpen(mix) }, onLongClick = { menu = true }),
                ) {
                    Box {
                        MixArt(mix, Modifier.fillMaxWidth().sharedElement("cover:mix:${mix.id}"))
                        MixMenu(menu, { menu = false }, mix, feed.playable(mix), actions, onOpen = { onOpen(mix) }, onUnplayable = onUnplayable)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(mix.kind.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        mix.kind.tileLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Albums of the latest plays: tap for the album's page, press and hold for its menu. */
@Composable
private fun RecentlyPlayed(albums: List<RecentAlbum>, modifier: Modifier) {
    val navigator = LocalNavigator.current
    Column(modifier) {
        SectionTitle("Recently played")
        Spacer(Modifier.height(12.dp))
        // The ripple's rounded edge sits outside the cover and the text, so it clips neither.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(albums, key = { it.identity }) { album ->
                val target = ItemTarget.Album(album.identity, album.title, album.artistName, album.artworkUrl)
                ItemActionsBox(
                    target,
                    onClick = { navigator.open(AlbumRoute(album.identity, album.title, album.artistName)) },
                    modifier = Modifier.animateItem().width(160.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(Modifier.padding(6.dp)) {
                        Artwork(album.artworkUrl, album.title + album.artistName, Modifier.fillMaxWidth().sharedElement(target.coverKey), shape = RoundedCornerShape(20.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(album.title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            album.artistName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Shapes artists wear, in turn: Material's expressive set, never two alike side by side. */
internal val expressiveShapes = listOf(
    MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny, MaterialShapes.Cookie6Sided,
    MaterialShapes.SoftBurst, MaterialShapes.Pentagon, MaterialShapes.Cookie4Sided, MaterialShapes.Flower,
)

/** An artist's photo in an expressive shape, or their initials on a stand-in color. */
@Composable
internal fun ShapedArtistCover(name: String, url: String?, shape: Shape, size: Dp = 104.dp) {
    Box {
        Artwork(url, name, Modifier.size(size), shape = shape, showInitials = url != null)
        if (url == null) {
            Text(
                initials(name),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                fontFamily = GoogleSansFlexCondensed,
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun ArtistRail(artists: List<ArtistStats>, actions: PlayActions, modifier: Modifier) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    Column(modifier) {
        SectionTitle("Your artists")
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(artists.size, key = { artists[it].identity }) { index ->
                val artist = artists[index]
                var menu by remember { mutableStateOf(false) }
                Column(
                    Modifier
                        .animateItem()
                        .width(104.dp)
                        .pressable(
                            onClick = { navigator.open(ArtistRoute(artist.identity, artist.name)) },
                            onLongClick = { menu = true },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        ShapedArtistCover(artist.name, artist.artworkUrl, expressiveShapes[index % expressiveShapes.size].toShape())
                        ArtistMenu(menu, { menu = false }, artist.name, actions) {
                            shareText(context, artist.name)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        artist.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** No history yet: what Play becomes, and how it gets there. */
@Composable
private fun EmptyPlay(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(MaterialShapes.Clover4Leaf.toShape())
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.LibraryMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("No mixes yet", style = MaterialTheme.typography.titleLargeEmphasized, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Mixes appear here once you've played some music in Tracks or imported your Last.fm history.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun initials(name: String): String =
    name.split(' ', '-', '&').filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2).joinToString("") { it.first().uppercase() }

/** "Good evening", by the hour. */
fun greeting(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}
