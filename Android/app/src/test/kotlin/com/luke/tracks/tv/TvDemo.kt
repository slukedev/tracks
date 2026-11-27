package com.luke.tracks.tv

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.data.SampleData
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.ui.navigation.AlbumRoute
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.TvCoverCard
import com.luke.tracks.ui.tv.components.TvShelf
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import com.luke.tracks.ui.tv.theme.TvLayout

/** Albums and artists from the sample history, for TV pages in tests. */
object TvDemo {
    data class Album(val title: String, val artist: String)

    val albums: List<Album> by lazy {
        SampleData.plays(days = 60).mapNotNull { p -> p.albumTitle?.let { Album(it, p.artistName) } }.distinctBy { it.title }.take(12)
    }

    val artists: List<String> by lazy {
        SampleData.plays(days = 60).map { it.artistName }.distinct().take(12)
    }

    val song: PlayableSong by lazy {
        val album = albums.first()
        PlayableSong(
            mediaId = "applemusic:1",
            uri = Uri.EMPTY,
            title = "A Song With Quite a Long Title",
            artistName = album.artist,
            albumTitle = album.title,
            artworkUri = null,
            durationMs = 215_000,
            identity = "demo",
        )
    }
}

/** A stand-in home page made of the shared components, the way content screens use them. */
@Composable
fun DemoHome() {
    val navigator = LocalTvNavigator.current
    val memory = rememberFocusMemory()
    val albums = TvDemo.albums
    memory.ClaimFocus(fallback = memory.requester("album:${albums.first().title}"))
    LazyColumn(
        contentPadding = PaddingValues(top = TvLayout.SafeTop, bottom = TvLayout.SafeBottom),
        verticalArrangement = Arrangement.spacedBy(TvLayout.SectionSpacing - 16.dp),
    ) {
        item("greeting") {
            Text(
                "Good Evening",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = TvLayout.ContentStart, top = 8.dp, bottom = 4.dp),
            )
        }
        item("albums") {
            TvShelf("Recently Played", albums, key = { "album:${it.title}" }, focusMemory = memory) { album ->
                TvCoverCard(null, album.title, album.title, onClick = { navigator.open(AlbumRoute(album.title, album.title, album.artist)) }, subtitle = album.artist)
            }
        }
        item("artists") {
            TvShelf("Artists", TvDemo.artists, key = { "artist:$it" }, focusMemory = memory) { name ->
                TvCoverCard(null, name, name, onClick = { navigator.open(ArtistRoute(name, name)) }, circle = true)
            }
        }
    }
}
