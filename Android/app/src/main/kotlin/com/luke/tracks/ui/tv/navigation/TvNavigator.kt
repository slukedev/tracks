package com.luke.tracks.ui.tv.navigation

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.toMutableStateList
import com.luke.tracks.ui.navigation.AppNavigation
import com.luke.tracks.ui.navigation.Route
import com.luke.tracks.ui.tv.components.TvFocusStore

/** One page on a destination's stack, at its [depth] from the root. */
data class TvStackEntry(val route: Route, val depth: Int)

/** What a press of Back did, for the shell to follow up on focus. */
enum class TvBack {
    ClosedNowPlaying,
    Popped,
    /** At a page's top: focus moves to the rail. */
    FocusRail,
    /** In the rail away from Play: Play is shown, focus stays in the rail. */
    WentToPlay,
    /** In the rail on Play: nothing left, the app goes to the background. */
    Exit,
}

/**
 * The TV app's place: one back stack per rail destination, so moving around the rail keeps
 * where you were in each, the full-screen player over everything, and which page should
 * take focus next. Snapshot state only, so it lives as a plain activity field.
 */
@Stable
class TvNavigator(start: TvDestination = TvDestination.Play) : AppNavigation {
    var destination by mutableStateOf(start)
        private set

    private val stacks = mutableStateMapOf<TvDestination, SnapshotStateList<TvStackEntry>>().apply {
        TvDestination.entries.forEach { put(it, listOf(TvStackEntry(it.root, 0)).toMutableStateList()) }
    }

    var nowPlayingOpen by mutableStateOf(false)
        private set

    val focus = TvFocusStore()

    private var searchQuery: String? by mutableStateOf(null)

    init {
        focus.requestClaim(topKey)
    }

    val backStack: List<Route> get() = stacks.getValue(destination).map { it.route }

    /** [of]'s entries, for the shell's display; the same route twice is two entries. */
    fun entries(of: TvDestination): List<TvStackEntry> = stacks.getValue(of)

    /** The key of [destination]'s entry at [depth], for focus memory. */
    fun entryKey(of: TvDestination, depth: Int): String = "${of.name}/$depth"

    val topKey: String get() = entryKey(destination, stacks.getValue(destination).lastIndex)

    /**
     * Picks a rail destination and hands focus to its page. Picking the one already showing
     * goes back to its top.
     */
    fun select(target: TvDestination) {
        nowPlayingOpen = false
        if (target == destination) popToRoot(target) else destination = target
        focus.requestClaim(topKey)
    }

    /** Opens [route] on top of the current destination, or switches to it if it's a rail root. */
    fun open(route: Route) {
        nowPlayingOpen = false
        val root = TvDestination.entries.firstOrNull { it.root == route }
        if (root != null) {
            select(root)
            return
        }
        val stack = stacks.getValue(destination)
        if (stack.last().route != route) {
            stack.add(TvStackEntry(route, stack.size))
            focus.forget(topKey)
        }
        focus.requestClaim(topKey)
    }

    override fun openNowPlaying() {
        nowPlayingOpen = true
        focus.requestClaim(NowPlayingKey)
    }

    fun closeNowPlaying() {
        if (!nowPlayingOpen) return
        nowPlayingOpen = false
        focus.forget(NowPlayingKey)
        focus.requestClaim(topKey)
    }

    /** Search from outside (the system's search, a voice query): a fresh Search with [query] in it. */
    override fun openSearch(query: String) {
        val clean = query.filterNot { it.isISOControl() }.trim().take(MaxQuery)
        searchQuery = clean.ifEmpty { null }
        popToRoot(TvDestination.Search)
        select(TvDestination.Search)
    }

    /** The query Search was opened with, once. */
    fun consumeSearchQuery(): String? = searchQuery.also { searchQuery = null }

    /** Whether Back has anything to do, so at the very end it can fall through to the system. */
    fun canGoBack(railFocused: Boolean): Boolean =
        nowPlayingOpen || stacks.getValue(destination).size > 1 || !railFocused || destination != TvDestination.Play

    fun back(railFocused: Boolean): TvBack {
        if (nowPlayingOpen) {
            closeNowPlaying()
            return TvBack.ClosedNowPlaying
        }
        val stack = stacks.getValue(destination)
        if (stack.size > 1) {
            focus.forget(topKey)
            stack.removeAt(stack.lastIndex)
            if (railFocused) focus.dropClaim() else focus.requestClaim(topKey)
            return TvBack.Popped
        }
        if (!railFocused) {
            focus.dropClaim()
            return TvBack.FocusRail
        }
        if (destination != TvDestination.Play) {
            destination = TvDestination.Play
            focus.dropClaim()
            return TvBack.WentToPlay
        }
        return TvBack.Exit
    }

    private fun popToRoot(of: TvDestination) {
        val stack = stacks.getValue(of)
        while (stack.size > 1) {
            focus.forget(entryKey(of, stack.lastIndex))
            stack.removeAt(stack.lastIndex)
        }
    }

    companion object {
        const val NowPlayingKey = "NowPlaying"
        private const val MaxQuery = 200
    }
}

val LocalTvNavigator: ProvidableCompositionLocal<TvNavigator> = staticCompositionLocalOf { error("No TvNavigator") }
