package com.luke.tracks.tv

import com.luke.tracks.ui.navigation.AlbumRoute
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.ChartsRoute
import com.luke.tracks.ui.navigation.MixRoute
import com.luke.tracks.ui.navigation.PlayRoute
import com.luke.tracks.ui.navigation.SearchRoute
import com.luke.tracks.ui.tv.navigation.TvBack
import com.luke.tracks.ui.tv.navigation.TvDestination
import com.luke.tracks.ui.tv.navigation.TvNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvNavigatorTest {
    private val album = AlbumRoute("a", "Blue", "Joni Mitchell")
    private val artist = ArtistRoute("j", "Joni Mitchell")

    @Test fun eachDestinationKeepsItsOwnStack() {
        val nav = TvNavigator()
        nav.open(MixRoute("on-repeat"))
        nav.open(album)
        nav.select(TvDestination.Library)
        nav.open(artist)
        nav.select(TvDestination.Play)

        assertEquals(listOf(PlayRoute, MixRoute("on-repeat"), album), nav.backStack)
        nav.select(TvDestination.Library)
        assertEquals(artist, nav.backStack.last())
    }

    @Test fun pickingTheCurrentDestinationGoesBackToItsTop() {
        val nav = TvNavigator()
        nav.open(album)
        nav.open(artist)
        nav.select(TvDestination.Play)
        assertEquals(listOf(PlayRoute), nav.backStack)
    }

    @Test fun openingARootRouteSwitchesDestinationInsteadOfPushing() {
        val nav = TvNavigator()
        nav.open(ChartsRoute)
        assertEquals(TvDestination.Charts, nav.destination)
        assertEquals(listOf(ChartsRoute), nav.backStack)
    }

    @Test fun openingTheSamePageTwiceInARowPushesOnce() {
        val nav = TvNavigator()
        nav.open(album)
        nav.open(album)
        assertEquals(2, nav.backStack.size)
    }

    @Test fun theSameRouteDeeperIsItsOwnEntry() {
        val nav = TvNavigator()
        nav.open(album)
        nav.open(artist)
        nav.open(album)
        val entries = nav.entries(TvDestination.Play)
        assertEquals(listOf(0, 1, 2, 3), entries.map { it.depth })
        assertEquals(entries.size, entries.toSet().size)
    }

    @Test fun backWalksOutInThePlannedOrder() {
        val nav = TvNavigator()
        nav.select(TvDestination.Library)
        nav.open(album)
        nav.openNowPlaying()

        assertEquals(TvBack.ClosedNowPlaying, nav.back(railFocused = false))
        assertFalse(nav.nowPlayingOpen)
        assertEquals(TvBack.Popped, nav.back(railFocused = false))
        assertEquals(TvBack.FocusRail, nav.back(railFocused = false))
        assertEquals(TvDestination.Library, nav.destination)
        assertEquals(TvBack.WentToPlay, nav.back(railFocused = true))
        assertEquals(TvDestination.Play, nav.destination)
        assertFalse(nav.canGoBack(railFocused = true))
        assertEquals(TvBack.Exit, nav.back(railFocused = true))
    }

    @Test fun backFromTheRailStillPopsADeeperPageWithoutTakingFocus() {
        val nav = TvNavigator()
        nav.open(album)
        assertTrue(nav.canGoBack(railFocused = true))
        assertEquals(TvBack.Popped, nav.back(railFocused = true))
        assertNull(nav.focus.claim)
    }

    @Test fun focusGoesToTheRightPageAtEachStep() {
        val nav = TvNavigator()
        assertEquals("Play/0", nav.focus.claim)

        nav.open(album)
        assertEquals("Play/1", nav.focus.claim)

        nav.focus.dropClaim()
        nav.back(railFocused = false)
        assertEquals("Play/0", nav.focus.claim)

        nav.openNowPlaying()
        assertEquals(TvNavigator.NowPlayingKey, nav.focus.claim)
        nav.closeNowPlaying()
        assertEquals("Play/0", nav.focus.claim)

        nav.back(railFocused = false)
        assertNull(nav.focus.claim)
    }

    @Test fun aPoppedPageForgetsItsFocusButItsParentRemembers() {
        val nav = TvNavigator()
        val store = nav.focus
        // As a page's FocusMemory records it.
        store.record("Play/0", "card:blue")
        nav.open(album)
        store.record("Play/1", "track:3")
        nav.back(railFocused = false)

        assertEquals("card:blue", store.lastKey("Play/0"))
        assertNull(store.lastKey("Play/1"))

        nav.open(artist)
        assertNull(store.lastKey("Play/1"))
    }

    @Test fun searchFromOutsideOpensAFreshSearchWithTheQueryOnce() {
        val nav = TvNavigator()
        nav.select(TvDestination.Search)
        nav.open(album)
        nav.select(TvDestination.Play)
        nav.openNowPlaying()

        nav.openSearch("  joni\u0000 mitchell ")
        assertEquals(TvDestination.Search, nav.destination)
        assertEquals(listOf(SearchRoute), nav.backStack)
        assertFalse(nav.nowPlayingOpen)
        assertEquals("joni mitchell", nav.consumeSearchQuery())
        assertNull(nav.consumeSearchQuery())
    }

    @Test fun aHugeOrBlankSearchIsCappedOrDropped() {
        val nav = TvNavigator()
        nav.openSearch("x".repeat(5000))
        assertEquals(200, nav.consumeSearchQuery()?.length)
        nav.openSearch("   ")
        assertNull(nav.consumeSearchQuery())
    }

    @Test fun openingAPageClosesNowPlaying() {
        val nav = TvNavigator()
        nav.openNowPlaying()
        nav.open(album)
        assertFalse(nav.nowPlayingOpen)
        assertEquals(album, nav.backStack.last())
    }
}
