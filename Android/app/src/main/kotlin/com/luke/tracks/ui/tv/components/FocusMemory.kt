package com.luke.tracks.ui.tv.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/**
 * Which item had focus on each back-stack entry, kept by the navigator so it outlives the
 * entry's composition, and which entry should take focus next. Entries are keyed by
 * destination and depth ("Play/0", "Play/1"), so a popped entry's memory is forgotten.
 */
@Stable
class TvFocusStore {
    private val lastKeys = HashMap<String, Any>()
    private val live = HashMap<String, FocusMemory>()

    /** The entry that should move focus into itself once it can; null while focus is the person's. */
    var claim: String? by mutableStateOf(null)
        internal set

    fun lastKey(routeKey: String): Any? = lastKeys[routeKey]

    fun requestClaim(routeKey: String) {
        claim = routeKey
    }

    /** Someone moved focus themselves (into the rail, say): no entry should take it back. */
    fun dropClaim() {
        claim = null
    }

    fun forget(routeKey: String) {
        lastKeys.remove(routeKey)
        if (claim == routeKey) claim = null
    }

    /** Focuses the item the entry last had focused, if it is on screen. */
    fun restore(routeKey: String): Boolean = live[routeKey]?.restore() == true

    internal fun record(routeKey: String, key: Any) {
        lastKeys[routeKey] = key
    }

    internal fun attach(memory: FocusMemory) {
        live[memory.routeKey] = memory
    }

    internal fun detach(memory: FocusMemory) {
        if (live[memory.routeKey] === memory) live.remove(memory.routeKey)
    }

    internal fun claimed(routeKey: String) {
        if (claim == routeKey) claim = null
    }
}

/** One back-stack entry's view of the [TvFocusStore]. */
@Stable
class FocusMemory internal constructor(private val store: TvFocusStore, val routeKey: String) {
    private val requesters = HashMap<Any, FocusRequester>()

    val lastKey: Any? get() = store.lastKey(routeKey)

    fun requester(key: Any): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun restore(): Boolean {
        val key = lastKey ?: return false
        return requesters[key]?.requestFocus() == true
    }

    internal fun record(key: Any) = store.record(routeKey, key)

    internal fun release(key: Any) {
        requesters.remove(key)
    }

    internal fun claimIsMine(): Boolean = store.claim == routeKey

    internal fun claimed() = store.claimed(routeKey)

    internal fun attach() = store.attach(this)

    internal fun detach() = store.detach(this)
}

/** The current back-stack entry's key, provided by the shell. */
val LocalTvRouteKey: ProvidableCompositionLocal<String> = staticCompositionLocalOf { "" }

/** The navigator's focus store, provided by the shell; a loose one for previews and tests. */
val LocalTvFocusStore: ProvidableCompositionLocal<TvFocusStore> = staticCompositionLocalOf { TvFocusStore() }

@Composable
fun rememberFocusMemory(routeKey: String = LocalTvRouteKey.current): FocusMemory {
    val store = LocalTvFocusStore.current
    val memory = remember(store, routeKey) { FocusMemory(store, routeKey) }
    DisposableEffect(memory) {
        memory.attach()
        onDispose { memory.detach() }
    }
    return memory
}

/** Tags a focusable item so the entry remembers it had focus and can give it focus again. */
@Composable
fun Modifier.rememberFocus(memory: FocusMemory, key: Any): Modifier {
    DisposableEffect(memory, key) { onDispose { memory.release(key) } }
    return focusRequester(memory.requester(key))
        .onFocusChanged { if (it.isFocused) memory.record(key) }
}

/**
 * Moves focus into this entry when the shell hands it over: at launch, after opening or going
 * back to it, and after picking it in the rail. It focuses the remembered item, else
 * [fallback], trying again across frames while the content lays out and then for a few
 * seconds while it loads.
 */
@Composable
fun FocusMemory.ClaimFocus(fallback: FocusRequester?) {
    val memory = this
    val mine = claimIsMine()
    LaunchedEffect(memory, mine, fallback) {
        if (!mine) return@LaunchedEffect
        fun take(): Boolean = memory.restore() || fallback?.requestFocus() == true
        repeat(FrameTries) {
            withFrameNanos { }
            if (take()) return@LaunchedEffect memory.claimed()
        }
        repeat(LoadTries) {
            delay(LoadRetryMs)
            if (!memory.claimIsMine()) return@LaunchedEffect
            if (take()) return@LaunchedEffect memory.claimed()
        }
        memory.claimed()
    }
}

private const val FrameTries = 6
private const val LoadTries = 50
private const val LoadRetryMs = 100L
