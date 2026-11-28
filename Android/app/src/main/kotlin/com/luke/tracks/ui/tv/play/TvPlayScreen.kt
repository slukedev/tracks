package com.luke.tracks.ui.tv.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.data.AppSettings
import com.luke.tracks.discover.ServerAlbum
import com.luke.tracks.discover.ServerSong
import com.luke.tracks.discover.isNew
import com.luke.tracks.graph
import com.luke.tracks.library.LibraryAlbum
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceSelection
import com.luke.tracks.ui.components.LocalClock
import com.luke.tracks.ui.sources.rememberSources
import com.luke.tracks.ui.sources.sourceSelection
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.MixRoute
import com.luke.tracks.ui.play.PlayFeed
import com.luke.tracks.ui.play.RadioArt
import com.luke.tracks.ui.play.hourOf
import com.luke.tracks.ui.play.hue
import com.luke.tracks.ui.play.icon
import com.luke.tracks.ui.play.rememberPlayFeed
import com.luke.tracks.ui.play.rememberServerPicks
import com.luke.tracks.ui.play.tileLine
import com.luke.tracks.ui.play.title
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.FocusMemory
import com.luke.tracks.ui.tv.components.TvCard
import com.luke.tracks.ui.tv.components.TvCoverCard
import com.luke.tracks.ui.tv.components.TvShelf
import com.luke.tracks.ui.tv.components.TvShelfSkeleton
import com.luke.tracks.ui.tv.components.TvSkeletonBlock
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.detail.MixColors
import com.luke.tracks.ui.tv.detail.PendingPlay
import com.luke.tracks.ui.tv.detail.TvMixArt
import com.luke.tracks.ui.tv.detail.TvPulse
import com.luke.tracks.ui.tv.detail.rememberPendingPlay
import com.luke.tracks.ui.tv.detail.TvRowScroll
import com.luke.tracks.ui.tv.detail.TvShelfScroll
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import com.luke.tracks.ui.tv.theme.TvLayout
import uniffi.tracks_engine.Mix
import uniffi.tracks_engine.MixKind
import androidx.compose.material3.MaterialTheme as M3Theme

/** Tracks Radio and this hour's mix, side by side at the top of Play. */
private val HeroWidth = 392.dp
private const val HeroAspect = 2f

/** One of Trackside's shelves for a server: its songs or albums, ready to show. */
@Immutable
private data class ServerShelves(
    val id: String,
    val picks: List<ServerSong>,
    val suggested: List<ServerSong>,
    val albums: List<ServerAlbum>,
)

/**
 * Play on TV: start something in one press. Tracks Radio and this hour's mix up top, washed in
 * the mix's color, then shelves of your mixes, your server's picks, your albums lately, the
 * other times of day, your artists and songs waiting to be heard. Each only when it has
 * something.
 */
@Composable
fun TvPlayScreen() {
    val graph = LocalContext.current.graph
    val feed by rememberPlayFeed()
    val registry = rememberSources()
    val selection by sourceSelection()
    // Narrowed to one server, as Settings > Sources says, the way the phone's Play is.
    val shownFeed = remember(feed, selection) {
        if (selection is SourceSelection.All) feed else feed?.only { selection.includes(registry.sourceOf(it.mediaId)) }
    }
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val picks = rememberServerPicks()
    val servers by picks.servers.collectAsState()
    val forYou by picks.forYou.collectAsState()
    val albums by picks.albums.collectAsState()
    LaunchedEffect(servers.map { it.id }) {
        servers.forEach { picks.load(it.id); picks.loadAlbums(it.id) }
    }
    // Trackside's songs that play here; new ones it would have to get first stay off TV.
    val shelves = remember(servers, forYou, albums, selection) {
        servers.filter { selection.includes(SourceId.Server(it.id)) }.map { server ->
            val state = forYou[server.id]?.shelves
            fun playable(songs: List<uniffi.tracks_engine.FoundSong>?) = songs.orEmpty().filter { !it.isNew }
                .map { ServerSong(server.id, it) }.filter { picks.playable(it) != null }
            ServerShelves(server.id, playable(state?.picks), playable(state?.suggested), albums[server.id]?.random.orEmpty())
        }
    }
    TvPlayContent(shownFeed, shelves, showsRadio = settings.showTracksRadio, greeting = tvGreeting(hourOf(LocalClock.current())))
}

/** "Good Evening", by the hour. */
fun tvGreeting(hour: Int): String = when (hour) {
    in 5..11 -> "Good Morning"
    in 12..16 -> "Good Afternoon"
    in 17..21 -> "Good Evening"
    else -> "Good Night"
}

@Composable
private fun TvPlayContent(feed: PlayFeed?, servers: List<ServerShelves>, showsRadio: Boolean, greeting: String) {
    val memory = rememberFocusMemory()
    val pending = rememberPendingPlay()
    val navigator = LocalTvNavigator.current
    val player = LocalContext.current.graph.player
    val picks = rememberServerPicks()
    val rightNow = feed?.mixes?.rightNow?.takeIf { feed.playable(it).isNotEmpty() }
    val firstKey = when {
        showsRadio -> "hero:radio"
        rightNow != null -> "hero:mix"
        else -> null
    }
    memory.ClaimFocus(fallback = (firstKey ?: firstShelfKey(feed, servers))?.let(memory::requester))

    if (feed == null) {
        PlaySkeleton(greeting)
        return
    }
    Box(Modifier.fillMaxSize()) {
        // A soft wash of this hour's color behind the top of the page.
        val wash = rightNow?.kind?.hue ?: com.luke.tracks.ui.play.RadioHue
        Box(
            Modifier.fillMaxWidth().height(420.dp).background(
                Brush.verticalGradient(listOf(wash.copy(alpha = 0.16f), Color.Transparent)),
            ),
        )
        TvShelfScroll { LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(top = TvLayout.SafeTop, bottom = TvLayout.SafeBottom + 16.dp),
            verticalArrangement = Arrangement.spacedBy(TvLayout.SectionSpacing - 16.dp),
        ) {
            item(key = "greeting", contentType = "greeting") { Greeting(greeting) }
            val hero = listOfNotNull("radio".takeIf { showsRadio }, "mix".takeIf { rightNow != null })
            if (hero.isNotEmpty()) item(key = "hero", contentType = "hero") {
                TvRowScroll { TvShelf(null, hero, key = { "hero:$it" }, focusMemory = memory) { which ->
                    // Alone before there's any history, the radio takes the room of both.
                    val width = if (hero.size == 1) HeroWidth * 1.5f else HeroWidth
                    when (which) {
                        "radio" -> TvCard(
                            onClick = { pending.run("hero:radio") { player.startRadio() } },
                            title = "Tracks Radio",
                            width = width,
                            aspectRatio = if (hero.size == 1) 2.6f else HeroAspect,
                            pending = pending.isPending("hero:radio"),
                        ) { RadioArt() }
                        else -> rightNow?.let { mix ->
                            TvCard(
                                onClick = { pending.play("hero:mix", feed.playable(mix), 0, QueueOrigin(mix.kind.title, QueueOrigin.Kind.Mix)) },
                                onLongClick = { navigator.open(MixRoute(mix.id)) },
                                title = mix.kind.title,
                                subtitle = mix.kind.tileLine,
                                width = width,
                                aspectRatio = HeroAspect,
                                pending = pending.isPending("hero:mix"),
                            ) { WideMixArt(mix) }
                        }
                    }
                } }
            }
            val mixes = feed.shelfMixes
            if (mixes.isNotEmpty()) item(key = "mixes", contentType = "shelf") {
                MixShelf("Your Mixes", "mixes", mixes, memory)
            }
            servers.forEach { server ->
                if (server.picks.isNotEmpty()) item(key = "picks-${server.id}", contentType = "shelf") {
                    SongShelf("Picked for You", "picks-${server.id}", server.picks.mapNotNull { s -> picks.playable(s)?.let { it to picks.coverUrl(s) } }, memory, pending)
                }
            }
            if (feed.recentlyPlayed.isNotEmpty()) item(key = "recent", contentType = "shelf") {
                AlbumShelf("Recently Played", "recent", feed.recentlyPlayed, memory)
            }
            val otherTimes = feed.mixes.otherTimes.filter { feed.playable(it).isNotEmpty() }
            if (otherTimes.isNotEmpty()) item(key = "times", contentType = "shelf") {
                MixShelf("Other Times", "times", otherTimes, memory)
            }
            servers.forEach { server ->
                if (server.suggested.isNotEmpty()) item(key = "suggested-${server.id}", contentType = "shelf") {
                    SongShelf("Suggested Songs", "suggested-${server.id}", server.suggested.mapNotNull { s -> picks.playable(s)?.let { it to picks.coverUrl(s) } }, memory, pending)
                }
            }
            if (feed.topArtists.isNotEmpty()) item(key = "artists", contentType = "shelf") {
                TvRowScroll { TvShelf("Your Artists", feed.topArtists, key = { "artists:${it.identity}" }, focusMemory = memory) { artist ->
                    TvCoverCard(artist.artworkUrl, artist.name, artist.name, onClick = { navigator.open(ArtistRoute(artist.identity, artist.name)) }, width = 156.dp, circle = true)
                } }
            }
            servers.forEach { server ->
                if (server.albums.isNotEmpty()) item(key = "different-${server.id}", contentType = "shelf") {
                    TvRowScroll { TvShelf("Something Different", server.albums, key = { "different:${it.libraryId}" }, focusMemory = memory) { album ->
                        TvCoverCard(album.coverUrl, album.title + album.artist, album.title, onClick = { navigator.open(LibraryAlbumRoute(album.libraryId)) }, subtitle = album.artist)
                    } }
                }
            }
            if (feed.waiting.isNotEmpty()) item(key = "waiting", contentType = "shelf") {
                SongShelf("Waiting to Be Heard", "waiting", feed.waiting.map { it to it.artworkUri }, memory, pending)
            }
        } }
    }
}

private fun firstShelfKey(feed: PlayFeed?, servers: List<ServerShelves>): String? {
    feed ?: return null
    feed.shelfMixes.firstOrNull()?.let { return "mixes:${it.id}" }
    servers.firstNotNullOfOrNull { s -> s.picks.firstOrNull()?.let { "picks-${s.id}:${it.song.identity}" } }?.let { return it }
    feed.recentlyPlayed.firstOrNull()?.let { return "recent:${it.id}" }
    return feed.waiting.firstOrNull()?.let { "waiting:${it.identity}" }
}

@Composable
private fun Greeting(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier.padding(start = TvLayout.ContentStart, end = TvLayout.SafeEnd, top = 4.dp),
    )
}

@Composable
private fun MixShelf(title: String, prefix: String, mixes: List<Mix>, memory: FocusMemory) {
    val navigator = LocalTvNavigator.current
    TvRowScroll { TvShelf(title, mixes, key = { "$prefix:${it.id}" }, focusMemory = memory) { mix ->
        TvCard(onClick = { navigator.open(MixRoute(mix.id)) }, title = mix.kind.title, subtitle = mix.kind.tileLine) {
            TvMixArt(mix, Modifier.fillMaxSize())
        }
    } }
}

@Composable
private fun AlbumShelf(title: String, prefix: String, albums: List<LibraryAlbum>, memory: FocusMemory) {
    val navigator = LocalTvNavigator.current
    TvRowScroll { TvShelf(title, albums, key = { "$prefix:${it.id}" }, focusMemory = memory) { album ->
        TvCoverCard(album.artworkUri, album.title + album.artistName, album.title, onClick = { navigator.open(LibraryAlbumRoute(album.id)) }, subtitle = album.artistName)
    } }
}

/** Songs as cards: pressing one plays it and the rest of the shelf after it. */
@Composable
private fun SongShelf(title: String, prefix: String, songs: List<Pair<PlayableSong, Any?>>, memory: FocusMemory, pending: PendingPlay) {
    val queue = remember(songs) { songs.map { it.first } }
    TvRowScroll { TvShelf(title, songs, key = { "$prefix:${it.first.identity}" }, focusMemory = memory) { (song, cover) ->
        val key = "$prefix:${song.identity}"
        TvCoverCard(
            cover,
            (song.albumTitle ?: song.title) + song.artistName,
            song.title,
            onClick = { pending.play(key, queue, queue.indexOf(song), QueueOrigin(title, QueueOrigin.Kind.Other)) },
            subtitle = song.artistName,
            pending = pending.isPending(key),
        )
    } }
}

/** This hour's mix, wide: its name big on its own color, with its covers beside. */
@Composable
private fun WideMixArt(mix: Mix) {
    MixColors(mix) {
        val scheme = M3Theme.colorScheme
        Row(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)))) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(20.dp)) {
                Row(
                    Modifier.clip(CircleShape).background(scheme.primary).padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(mix.kind.icon, null, Modifier.size(18.dp), tint = scheme.onPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text("Right Now", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    (mix.kind as? MixKind.RightNow)?.let { mix.kind.title.removeSuffix(" Mix") } ?: mix.kind.title,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TvMixArt(mix, Modifier.fillMaxHeight(), shape = RoundedCornerShape(0.dp), badge = false)
        }
    }
}

/** Play while it first reads your listening: the greeting, the two big cards and a shelf. */
@Composable
private fun PlaySkeleton(greeting: String) {
    Column(Modifier.fillMaxSize().padding(top = TvLayout.SafeTop), verticalArrangement = Arrangement.spacedBy(TvLayout.SectionSpacing - 16.dp)) {
        Greeting(greeting)
        TvPulse {
            Row(Modifier.padding(start = TvLayout.ContentStart, top = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(TvLayout.ShelfSpacing)) {
                repeat(2) { HeroBlock(HeroWidth) }
            }
        }
        TvShelfSkeleton(cards = 5)
    }
}

@Composable
private fun HeroBlock(width: Dp) {
    Column(Modifier.width(width)) {
        TvSkeletonBlock(Modifier.fillMaxWidth().height(width / HeroAspect), RoundedCornerShape(16.dp))
        Spacer(Modifier.height(12.dp))
        TvSkeletonBlock(Modifier.width(width * 0.4f).height(12.dp).padding(top = 0.dp), RoundedCornerShape(4.dp))
        Spacer(Modifier.height(10.dp))
        TvSkeletonBlock(Modifier.width(width * 0.25f).height(10.dp), RoundedCornerShape(4.dp))
    }
}
