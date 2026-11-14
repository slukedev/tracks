package com.luke.tracks.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.toMutableStateList

/**
 * One back stack per tab, as Google's apps keep them: switching tabs keeps your place, and
 * tapping the current tab again goes back to its top.
 */
@Stable
class Navigator(start: Tab = Tab.Play) {
    var tab by mutableStateOf(start)
        private set

    private val stacks = mutableStateMapOf<Tab, SnapshotStateList<Route>>().apply {
        Tab.entries.forEach { put(it, listOf(it.root()).toMutableStateList()) }
    }

    /** Whether the full-screen player is showing. */
    var nowPlayingOpen by mutableStateOf(false)

    /** Whether the mini player is on screen, set by the mini player itself. */
    var miniPlayerVisible by mutableStateOf(false)

    val backStack: SnapshotStateList<Route> get() = stacks.getValue(tab)

    fun select(target: Tab) {
        if (target == tab) {
            val stack = stacks.getValue(target)
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
        } else {
            tab = target
        }
    }

    fun open(route: Route) {
        nowPlayingOpen = false
        val tabRoot = Tab.entries.firstOrNull { it.root() == route }
        if (tabRoot != null) select(tabRoot) else backStack.add(route)
    }

    /** False when there was nothing to go back to. */
    fun back(): Boolean {
        if (nowPlayingOpen) {
            nowPlayingOpen = false
            return true
        }
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            return true
        }
        if (tab != Tab.Play) {
            tab = Tab.Play
            return true
        }
        return false
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator") }

@Composable
fun currentNavigator(): Navigator = LocalNavigator.current
