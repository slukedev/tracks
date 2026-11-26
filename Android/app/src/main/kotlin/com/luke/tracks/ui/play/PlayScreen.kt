package com.luke.tracks.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.CloudOff
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
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
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.LibraryAlbum
import com.luke.tracks.library.ServerEntry
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.insights.LocalInsightsSnackbar
import com.luke.tracks.ui.library.LibraryTarget
import com.luke.tracks.ui.library.LocalLibraryActions
import com.luke.tracks.ui.library.WithLibraryActions
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.MixRoute
import com.luke.tracks.ui.navigation.SearchRoute
import com.luke.tracks.ui.navigation.SettingsRoute
import com.luke.tracks.ui.navigation.sharedElement
import com.luke.tracks.ui.play.components.ArtistMenu
import com.luke.tracks.ui.play.components.GoTo
import com.luke.tracks.ui.play.components.SongActionsSheet
import com.luke.tracks.ui.play.components.SongRef
import com.luke.tracks.ui.play.components.artistLink
import com.luke.tracks.ui.play.components.goToActions
import com.luke.tracks.ui.play.components.ref
import com.luke.tracks.ui.play.components.rememberLibraryIndex
import com.luke.tracks.ui.play.components.songLinks
import com.luke.tracks.ui.play.components.MiniPlayerClearance
import com.luke.tracks.ui.play.components.MixArt
import com.luke.tracks.ui.play.components.MixMenu
import com.luke.tracks.ui.play.components.PlayActions
import com.luke.tracks.ui.play.components.pressable
import com.luke.tracks.ui.play.components.rememberPlayActions
import com.luke.tracks.ui.play.components.shareText
import com.luke.tracks.ui.theme.GoogleSansFlexCondensed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import uniffi.tracks_engine.ArtistStats
import uniffi.tracks_engine.ForYouShelf
import uniffi.tracks_engine.Mix
import java.util.Calendar

/**
 * The Play tab: start something in one tap. The iPhone's Play with your own music, in its
 * order: today's crate of mixes with Tracks Radio; songs your servers find by artists new to
 * you; your albums lately, your artists, the mixes made from your listening and your songs
 * waiting to be heard; your servers' picks and shelves; and Keep exploring for as long as
 * you scroll. Each section only when it has something.
 */
@Composable
fun PlayScreen() {
    val graph = LocalContext.current.graph
    val navigator = LocalNavigator.current
    val feed by rememberPlayFeed()
    val player by graph.player.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(null)
    val actions = rememberPlayActions()
    val picks = rememberServerPicks()
    val servers by picks.servers.collectAsState()
    val exploring by picks.exploring.collectAsState()
    val canExploreMore by picks.canExploreMore.collectAsState()
    val exploreNote by picks.exploreNote.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val registry = com.luke.tracks.ui.sources.rememberSources()
    val selection by com.luke.tracks.ui.sources.sourceSelection()
    val current = remember(feed, selection) {
        if (selection is com.luke.tracks.sources.SourceSelection.All) feed
        else feed?.only { selection.includes(registry.sourceOf(it.mediaId)) }
    }
    val sourceCount = com.luke.tracks.ui.sources.sourceList().value.size
    // A server that's down keeps out of the way: its shelves hide until it answers again.
    val shownServers = servers.filter { server ->
        val id = com.luke.tracks.sources.SourceId.Server(server.id)
        selection.includes(id) && registry.source(id)?.status?.canPlay != false
    }
    LaunchedEffect(servers.map { it.id }, shownServers.map { it.id }) {
        val hidden = servers.filter { server -> shownServers.none { it.id == server.id } }
        if (hidden.isNotEmpty()) com.luke.tracks.net.Logs.i("Play: ${hidden.joinToString { it.name }} hidden by the source filter, or because it can't play now")
    }
    // Picks are made again when the artists you play change, not for every song played.
    val taste = remember(current?.topArtists) { current?.topArtists.orEmpty().take(8).map { it.identity }.toSet() }

    val unplayable: () -> Unit = {
        scope.launch { snackbar.showSnackbar("None of these songs are in your library yet") }
    }
    val openMix: (Mix) -> Unit = { navigator.open(MixRoute(it.id)) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = MiniPlayerClearance - 16.dp)) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(greeting(hourOf(com.luke.tracks.ui.components.LocalClock.current())), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = current?.mixes?.rightNow?.kind?.let { kind ->
                    { Text((kind as? uniffi.tracks_engine.MixKind.RightNow)?.let { it.part.moment(it.isWeekend) } ?: "") }
                },
                actions = {
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
        // Adding a song a server found says how it went here.
        CompositionLocalProvider(LocalInsightsSnackbar provides snackbar) {
            WithLibraryActions {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = MiniPlayerClearance + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    if (sourceCount >= 2) item(key = "source", contentType = "source") {
                        com.luke.tracks.ui.sources.SourceFilterChip(Modifier.padding(horizontal = 16.dp))
                    }
                    val crate = crateItems(current, settings?.showTracksRadio != false)
                    if (crate.isNotEmpty()) item(key = "crate", contentType = "crate") {
                        Crate(
                            items = crate,
                            feed = current,
                            player = player,
                            actions = actions,
                            onOpenMix = openMix,
                            onUnplayable = unplayable,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    // Your servers' shelves don't wait for your listening to be read: each settles on its own.
                    if (current == null) {
                        item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                                LoadingIndicator(Modifier.size(56.dp))
                            }
                        }
                    } else if (!current.hasHistory) {
                        item(key = "empty") { EmptyPlay(Modifier.animateItem()) }
                    }
                    shownServers.forEach { server ->
                        item(key = "suggested-${server.id}", contentType = "server-songs") {
                            ServerForYouShelf(server, ForYouShelf.SUGGESTED, taste, Modifier.animateItem())
                        }
                    }
                    if (current != null) {
                        if (current.recentlyPlayed.isNotEmpty()) {
                            item(key = "recent", contentType = "shelf") { RecentlyPlayed(current.recentlyPlayed, Modifier.animateItem()) }
                        }
                        if (current.topArtists.isNotEmpty()) {
                            item(key = "artists", contentType = "artists") { ArtistRail(current.topArtists, actions, Modifier.animateItem()) }
                        }
                        if (current.shelfMixes.isNotEmpty()) {
                            item(key = "mixes", contentType = "shelf") { MixShelf(current, actions, openMix, unplayable, Modifier.animateItem()) }
                        }
                        if (current.waiting.isNotEmpty()) {
                            item(key = "waiting", contentType = "shelf") { WaitingToBeHeard(current.waiting, actions, Modifier.animateItem()) }
                        }
                    }
                    shownServers.forEach { server ->
                        item(key = "picks-${server.id}", contentType = "server-songs") {
                            ServerForYouShelf(server, ForYouShelf.PICKS, taste, Modifier.animateItem())
                        }
                        item(key = "albums-${server.id}", contentType = "server-albums") {
                            ServerAlbumShelves(server, Modifier.animateItem())
                        }
                    }
                    if (shownServers.isNotEmpty() && (exploring.isNotEmpty() || canExploreMore || exploreNote != null)) {
                        keepExploring(exploring, goesOn = canExploreMore, note = exploreNote, onEnd = { picks.exploreMore() })
                    }
                }
            }
        }
    }
}

/** Your albums most lately heard: tap for the album, press and hold for its menu. */
@Composable
private fun RecentlyPlayed(albums: List<LibraryAlbum>, modifier: Modifier) {
    val navigator = LocalNavigator.current
    val actions = LocalLibraryActions.current
    val index = rememberLibraryIndex()
    Column(modifier) {
        SectionTitle("Recently played")
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(albums, key = { it.id }) { album ->
                Column(
                    Modifier
                        .animateItem()
                        .width(148.dp)
                        .goToActions(remember(album, index) { GoTo(artist = index.artistIdOf(album.artistName)?.let(::LibraryArtistRoute)) }, navigator)
                        .pressable(onClick = { navigator.open(LibraryAlbumRoute(album.id)) }, onLongClick = { actions.open(LibraryTarget.Album(album)) }),
                ) {
                    Artwork(album.artworkUri, album.title + album.artistName, Modifier.fillMaxWidth().sharedElement("cover:album:${album.id}"), shape = RoundedCornerShape(20.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(album.title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(album.artistName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Your songs you've never played: tap plays from there, press and hold for the song's menu. */
@Composable
private fun WaitingToBeHeard(songs: List<PlayableSong>, actions: PlayActions, modifier: Modifier) {
    val graph = LocalContext.current.graph
    val navigator = LocalNavigator.current
    val index = rememberLibraryIndex()
    var held by remember { mutableStateOf<SongRef?>(null) }
    val playFrom = { position: Int -> graph.player.play(songs, position, QueueOrigin("Waiting to be heard", QueueOrigin.Kind.Library)) }
    Column(modifier) {
        SectionTitle("Waiting to be heard")
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(songs.size, key = { songs[it].mediaId }) { position ->
                val song = songs[position]
                val ref = remember(song) { song.ref() }
                Column(
                    Modifier
                        .animateItem()
                        .width(148.dp)
                        .goToActions(remember(ref, index) { songLinks(ref, index) }, navigator)
                        .pressable(
                            onClick = { playFrom(position) },
                            onLongClick = { held = ref },
                        ),
                ) {
                    Artwork(song.artworkUri, (song.albumTitle ?: song.title) + song.artistName, Modifier.fillMaxWidth(), shape = MaterialShapes.Cookie12Sided.toShape(), showInitials = false)
                    Spacer(Modifier.height(10.dp))
                    Text(song.title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artistName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    held?.let { song ->
        SongActionsSheet(
            song,
            actions,
            links = songLinks(song, index),
            onDismiss = { held = null },
            onPlay = { playFrom(songs.indexOf(song.playable).coerceAtLeast(0)) },
        )
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
            items(feed.shelfMixes, key = { it.id }) { mix ->
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
    val index = rememberLibraryIndex()
    Column(modifier) {
        SectionTitle("Your artists")
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(artists.size, key = { artists[it].identity }) { position ->
                val artist = artists[position]
                val stats = ArtistRoute(artist.identity, artist.name)
                val page = remember(artist, index) { artistLink(artist.name, artist.identity, index) }
                var menu by remember { mutableStateOf(false) }
                Column(
                    Modifier
                        .animateItem()
                        .width(104.dp)
                        .goToActions(GoTo(artist = page), navigator)
                        .pressable(
                            onClick = { navigator.open(ArtistRoute(artist.identity, artist.name)) },
                            onLongClick = { menu = true },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        ShapedArtistCover(artist.name, artist.artworkUrl, expressiveShapes[position % expressiveShapes.size].toShape())
                        ArtistMenu(menu, { menu = false }, page, stats, actions) {
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
fun hourOf(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)

fun greeting(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}
