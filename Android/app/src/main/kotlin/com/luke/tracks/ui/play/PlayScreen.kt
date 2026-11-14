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
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.luke.tracks.ui.theme.GoogleSansFlexCondensed
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.graph
import com.luke.tracks.ui.components.Artwork
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
import com.luke.tracks.ui.play.components.SongActionsSheet
import com.luke.tracks.ui.play.components.SongRef
import com.luke.tracks.ui.play.components.SongRow
import com.luke.tracks.ui.play.components.pressable
import com.luke.tracks.ui.play.components.ref
import com.luke.tracks.ui.play.components.rememberPlayActions
import com.luke.tracks.ui.play.components.shareText
import kotlinx.coroutines.launch
import uniffi.tracks_engine.ArtistStats
import uniffi.tracks_engine.Mix
import java.util.Calendar

/**
 * The Play tab: start something in one tap. Today's crate of mixes with Tracks Radio up top,
 * then the mixes made from your listening, your artists and songs to come back to.
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
    var sheetSong by remember { mutableStateOf<SongRef?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val unplayable: () -> Unit = {
        scope.launch { snackbar.showSnackbar("None of these songs are in your music on this phone yet") }
    }
    val hearLess: (SongRef) -> Unit = { song ->
        actions.hearLess(song.identity)
        scope.launch {
            val result = snackbar.showSnackbar("You'll hear less of ${song.title}", actionLabel = "Undo")
            if (result == SnackbarResult.ActionPerformed) actions.undoHearLess(song.identity)
        }
    }
    val openMix: (Mix) -> Unit = { navigator.open(MixRoute(it.id)) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = MiniPlayerClearance - 16.dp)) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(greeting(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = feed?.mixes?.rightNow?.kind?.let { kind ->
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
        val current = feed
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
                    if (current.mixes.mixes.isNotEmpty()) {
                        item(key = "mixes", contentType = "shelf") {
                            MixShelf(current, actions, openMix, unplayable, Modifier.animateItem())
                        }
                    }
                    if (current.topArtists.isNotEmpty()) {
                        item(key = "artists", contentType = "artists") {
                            ArtistRail(current.topArtists, actions, Modifier.animateItem())
                        }
                    }
                    if (current.suggested.isNotEmpty()) {
                        item(key = "suggested", contentType = "grid") {
                            SuggestedSongs(current, actions, onMore = { sheetSong = it }, Modifier.animateItem())
                        }
                    }
                    if (current.explore.isNotEmpty()) {
                        item(key = "explore", contentType = "explore") {
                            Column(Modifier.animateItem()) {
                                SectionTitle("Keep exploring", "Favorites you haven't played in a while")
                                Spacer(Modifier.height(4.dp))
                                current.explore.forEach { stats ->
                                    val song = stats.ref(current.playable[stats.identity])
                                    SongRow(
                                        song = song,
                                        onClick = { actions.play(song) },
                                        onLongClick = { sheetSong = song },
                                        onMore = { sheetSong = song },
                                        onOpenElsewhere = { actions.openElsewhere(song) },
                                        isCurrent = player.current?.identity == song.identity,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    sheetSong?.let { song ->
        SongActionsSheet(song, actions, onDismiss = { sheetSong = null }, onHearLess = hearLess)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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

/** Shapes the artists wear, in turn: Material's expressive set, never two alike side by side. */
private val artistShapes = listOf(
    MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny, MaterialShapes.Cookie6Sided,
    MaterialShapes.SoftBurst, MaterialShapes.Pentagon, MaterialShapes.Cookie4Sided, MaterialShapes.Flower,
)

@Composable
private fun ArtistRail(artists: List<ArtistStats>, actions: PlayActions, modifier: Modifier) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    Column(modifier) {
        SectionTitle("Your artists lately")
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
                    val shape = artistShapes[index % artistShapes.size].toShape()
                    Box {
                        Artwork(artist.artworkUrl, artist.name, Modifier.size(104.dp), shape = shape, showInitials = artist.artworkUrl != null)
                        if (artist.artworkUrl == null) {
                            Text(
                                initials(artist.name),
                                style = MaterialTheme.typography.headlineSmallEmphasized,
                                fontFamily = GoogleSansFlexCondensed,
                                color = Color.White.copy(alpha = 0.92f),
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
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

@Composable
private fun SuggestedSongs(feed: PlayFeed, actions: PlayActions, onMore: (SongRef) -> Unit, modifier: Modifier) {
    val songs = remember(feed) { feed.suggested.map { it.ref(feed.playable[it.identity]) } }
    Column(modifier) {
        SectionTitle("Suggested songs", "Your most played this month")
        Spacer(Modifier.height(8.dp))
        LazyHorizontalGrid(
            rows = GridCells.Fixed(4),
            modifier = Modifier.height(68.dp * 4),
        ) {
            items(songs, key = { it.identity }) { song ->
                SongRow(
                    song = song,
                    onClick = { actions.play(song) },
                    onLongClick = { onMore(song) },
                    onMore = { onMore(song) },
                    onOpenElsewhere = { actions.openElsewhere(song) },
                    modifier = Modifier.width(340.dp).animateItem(),
                    showPlays = false,
                    coverSize = 48.dp,
                )
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
