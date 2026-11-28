package com.luke.tracks.ui.tv.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.sources.PlayFromPlace
import com.luke.tracks.sources.Source
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceSelection
import com.luke.tracks.sources.SourceStatus
import com.luke.tracks.sync.CloudSync
import com.luke.tracks.sync.SyncStatus
import com.luke.tracks.sync.TvSync
import com.luke.tracks.ui.sources.GetSummary
import com.luke.tracks.ui.sources.rememberSources
import com.luke.tracks.ui.sources.songs
import com.luke.tracks.ui.sources.sourceList
import com.luke.tracks.ui.sources.sourceSelection
import com.luke.tracks.ui.tv.components.TvButton
import com.luke.tracks.ui.tv.components.TvButtonStyle
import com.luke.tracks.ui.tv.components.TvChip
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.components.TvSidePanel
import com.luke.tracks.ui.tv.components.tvTouch
import com.luke.tracks.ui.tv.link.TvLinkFlow
import com.luke.tracks.ui.tv.theme.LocalReducedMotion
import com.luke.tracks.ui.tv.theme.TvMotion
import com.luke.tracks.ui.tv.theme.tvFocusScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme as M3Theme

/** The Sync switch: on while history goes to and from a linked server, with how it's going in a few words. */
data class SyncSwitch(val on: Boolean, val line: String?, val problem: Boolean)

/**
 * The switch for [status] on a server this TV is linked to; null hides it, where that server
 * doesn't keep Trackside Cloud (or hasn't said) and sync isn't on with it. A server paired
 * again reads as off until it's switched on again, since nothing syncs until then.
 */
fun syncSwitch(status: SyncStatus, linkedServerId: String?, offersCloud: Boolean?): SyncSwitch? {
    linkedServerId ?: return null
    val chosen = status.serverId == linkedServerId
    if (!chosen && offersCloud != true) return null
    if (!chosen) return SyncSwitch(on = false, line = null, problem = false)
    // The phone's sentence says to tap; a TV is driven with a remote.
    val problem = (if (status.needsConfirmation) PairedAgain else status.problem).takeUnless { status.syncing }
    val line = when {
        status.syncing -> "Syncing"
        problem != null -> problem
        status.lastSyncedAt != null -> "Up to date"
        else -> "Syncing"
    }
    return SyncSwitch(on = !status.needsConfirmation, line = line, problem = problem != null)
}

internal const val PairedAgain = "Paired again. Select to keep syncing."

/**
 * Settings > Sources: the Trackside servers this TV plays from, each with its own panel to
 * refresh, sync or unlink it, linking another, and once there are two, which of them Play and
 * Library show and the order copies play from. Linking sits in the page while there's none.
 */
@Composable
fun TvSourcesPage() {
    val graph = LocalContext.current.graph
    val servers = (graph.library as? CombinedLibrary)?.servers
    val sync = remember(graph) { CloudSync.of(graph) }
    val registry = rememberSources()
    val sources by sourceList()
    val selection by sourceSelection()
    val choices by registry.playFromChoices.collectAsState()
    val getOn by registry.getOn.collectAsState()
    val getsCopies by registry.getsCopies.collectAsState()
    val status by remember(sync) { sync?.status ?: MutableStateFlow(SyncStatus()) }.collectAsStateWithLifecycle()
    fun serverId(source: Source) = (source.id as? SourceId.Server)?.id
    TvSourcesContent(
        sources = sources,
        selection = selection,
        playFrom = choices.map { it.place },
        placeNames = choices.associate { it.place to it.name },
        get = getOn.takeIf { getsCopies },
        onGet = registry::setGet,
        onSelect = registry::select,
        onReorder = registry::setPlayFrom,
        onRefresh = { source ->
            val entry = servers?.entries?.value?.firstOrNull { it.server.id == serverId(source) }
            if (entry != null) graph.scope.launch { servers.sync(entry.server) }
        },
        onUnlink = { source ->
            serverId(source)?.let { id ->
                graph.scope.launch {
                    // Sync goes with the link.
                    if (sync?.chosen() == id) sync.choose(null)
                    servers?.remove(id)
                }
            }
        },
        syncFor = { source ->
            val id = serverId(source)
            val offersCloud by produceState<Boolean?>(null, servers, id) {
                value = id?.let {
                    try {
                        servers?.tracksideClient(it)?.capabilities()?.offersCloud
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            if (sync == null) null else syncSwitch(status, id, offersCloud)
        },
        onSync = { source, on ->
            val id = serverId(source)
            if (sync != null && id != null) graph.scope.launch { if (on) TvSync.turnOn(sync, id) else sync.choose(null) }
        },
    )
}

private enum class Linking(val heading: String) { Another("Link Another Server"), Again("Link Again") }

/** The page from plain values, for screenshots. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvSourcesContent(
    sources: List<Source>,
    selection: SourceSelection,
    playFrom: List<PlayFromPlace>,
    onSelect: (SourceSelection) -> Unit,
    onReorder: (List<PlayFromPlace>) -> Unit,
    onRefresh: (Source) -> Unit,
    onUnlink: (Source) -> Unit,
    syncFor: @Composable (Source) -> SyncSwitch? = { null },
    onSync: (Source, Boolean) -> Unit = { _, _ -> },
    /** Names for places that aren't servers: the streaming services, and Get. */
    placeNames: Map<PlayFromPlace, String> = emptyMap(),
    /** Whether Get is on; null when no server could get a song. */
    get: Boolean? = null,
    onGet: (Boolean) -> Unit = {},
) {
    var linking by remember { mutableStateOf<Linking?>(null) }
    var shown by remember { mutableStateOf<SourceId?>(null) }
    // Linking again waits for the panel to hand focus back before the page changes under it.
    var linkAgain by remember { mutableStateOf(false) }
    LaunchedEffect(linkAgain) {
        if (!linkAgain) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        linkAgain = false
        linking = Linking.Again
    }
    // The row a panel opened from, for focus to go back to; null once that server is unlinked.
    var openedFrom by remember { mutableStateOf<SourceId?>(null) }
    val linkRow = remember { FocusRequester() }
    val rows = remember { mutableMapOf<SourceId, FocusRequester>() }
    fun row(id: SourceId) = rows.getOrPut(id) { FocusRequester() }
    // Back from linking, or linked: focus lands on the rows again rather than nowhere.
    var refocus by remember { mutableStateOf(false) }
    LaunchedEffect(refocus, linking, sources.isEmpty()) {
        if (!refocus || linking != null || sources.isEmpty()) return@LaunchedEffect
        refocus = false
        linkRow.focusSoon()
    }
    Box(Modifier.fillMaxSize()) {
        PageColumn {
            val flow = linking
            when {
                sources.isEmpty() -> {
                    PageHeading("Link This TV")
                    TvLinkFlow(onLinked = { refocus = true }, claimFocus = false)
                }
                flow != null -> {
                    BackHandler {
                        linking = null
                        refocus = true
                    }
                    PageHeading(flow.heading)
                    TvLinkFlow(onLinked = {
                        linking = null
                        refocus = true
                    })
                }
                else -> {
                    PageHeading("Servers")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        sources.forEach { source ->
                            key(source.id.raw) {
                                TvListRow(
                                    onClick = {
                                        shown = source.id
                                        openedFrom = source.id
                                    },
                                    headline = source.name,
                                    supporting = source.status.rowLine(source.songCount),
                                    supportingColor = if (source.status.needsAction) M3Theme.colorScheme.error else Color.Unspecified,
                                    leading = { RowIcon(Icons.Rounded.Dns) },
                                    modifier = Modifier.focusRequester(row(source.id)),
                                )
                            }
                        }
                        TvListRow(
                            onClick = { linking = Linking.Another },
                            headline = "Link Another Server",
                            leading = { RowIcon(Icons.Rounded.Add) },
                            modifier = Modifier.focusRequester(linkRow),
                        )
                    }
                    if (sources.size >= 2) {
                        PageHeading("Show")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val all = selection is SourceSelection.All
                            TvChip("All Sources", selected = all, onClick = { onSelect(SourceSelection.All) }, icon = if (all) Icons.Rounded.Check else null)
                            sources.forEach { source ->
                                key(source.id.raw) {
                                    val only = SourceSelection.Only(source.id)
                                    val picked = selection == only
                                    TvChip(
                                        source.name,
                                        selected = picked,
                                        onClick = { onSelect(only) },
                                        icon = if (picked) Icons.Rounded.Check else null,
                                        modifier = Modifier.widthIn(max = ChipMax),
                                    )
                                }
                            }
                        }
                    }
                    if (playFrom.size >= 2) {
                        PageHeading("Play From")
                        PlayFromList(sources, playFrom, placeNames, onReorder, get, onGet)
                    }
                }
            }
        }
        ServerPanel(
            source = sources.firstOrNull { it.id == shown },
            onClose = { shown = null },
            returnFocus = openedFrom?.let(::row) ?: linkRow,
            syncFor = syncFor,
            onSync = onSync,
            onRefresh = onRefresh,
            onLinkAgain = {
                shown = null
                openedFrom = null
                linkAgain = true
            },
            onUnlink = { source ->
                shown = null
                openedFrom = null
                onUnlink(source)
            },
        )
    }
}

/** A server's panel: where it stands, Refresh (Link Again when signed out), Sync and Unlink, which asks first. */
@Composable
private fun ServerPanel(
    source: Source?,
    onClose: () -> Unit,
    returnFocus: FocusRequester,
    syncFor: @Composable (Source) -> SyncSwitch?,
    onSync: (Source, Boolean) -> Unit,
    onRefresh: (Source) -> Unit,
    onLinkAgain: () -> Unit,
    onUnlink: (Source) -> Unit,
) {
    // The panel keeps showing the server it opened with while it slides away.
    var last by remember { mutableStateOf(source) }
    SideEffect { if (source != null) last = source }
    val held = source ?: last
    var confirming by remember(source?.id) { mutableStateOf(false) }
    val unlinkRow = remember { FocusRequester() }
    val unlinkButton = remember { FocusRequester() }
    var asked by remember(source?.id) { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            asked = true
            unlinkButton.focusSoon()
        } else if (asked) {
            unlinkRow.focusSoon()
        }
    }
    TvSidePanel(
        visible = source != null,
        onDismiss = { if (confirming) confirming = false else onClose() },
        title = held?.let { if (confirming) "Unlink ${it.name}?" else it.name },
        returnFocus = returnFocus,
    ) {
        held ?: return@TvSidePanel
        val colors = MaterialTheme.colorScheme
        if (confirming) {
            Text("Your plays stay in History.", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
            TvButton("Unlink", onClick = { onUnlink(held) }, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(unlinkButton))
            TvButton("Cancel", onClick = { confirming = false })
            return@TvSidePanel
        }
        val status = held.status
        Text(
            status.panelLine(held.songCount),
            style = MaterialTheme.typography.titleMedium,
            color = if (status.needsAction) colors.error else colors.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (status == SourceStatus.SignIn) {
            TvListRow(onClick = onLinkAgain, headline = "Link Again", leading = { RowIcon(Icons.Rounded.Refresh) })
        } else {
            TvListRow(onClick = { onRefresh(held) }, headline = "Refresh", leading = { RowIcon(Icons.Rounded.Refresh) })
        }
        syncFor(held)?.let { sync ->
            ToggleRow(
                "Sync",
                checked = sync.on,
                onChange = { onSync(held, it) },
                icon = Icons.Rounded.Sync,
                supporting = sync.line,
                supportingColor = if (sync.problem) M3Theme.colorScheme.error else Color.Unspecified,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        TvListRow(
            onClick = { confirming = true },
            headline = "Unlink",
            leading = { RowIcon(Icons.Rounded.LinkOff) },
            modifier = Modifier.focusRequester(unlinkRow),
        )
    }
}

/**
 * Where a song plays from: the first of these that can, the servers, the streaming services
 * they play and Get. Select a row to pick it up, Up and Down to carry it, select to put it
 * down; Back puts it back. By touch, a tap picks it up, its arrows carry it and another tap
 * puts it down. TalkBack offers Move Up and Move Down. Saved when it's put down.
 *
 * Get's row is a switch: select turns it on or off, and holding select (or a long press) picks
 * it up. Off, it keeps its place, unnumbered, and is passed over.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PlayFromList(
    sources: List<Source>,
    places: List<PlayFromPlace>,
    placeNames: Map<PlayFromPlace, String>,
    onReorder: (List<PlayFromPlace>) -> Unit,
    get: Boolean?,
    onGet: (Boolean) -> Unit,
) {
    var reorder by remember { mutableStateOf(RemoteReorder(places)) }
    LaunchedEffect(places) { reorder = reorder.following(places) }
    BackHandler(enabled = reorder.isLifted) { reorder = reorder.cancel() }
    val reduced = LocalReducedMotion.current
    fun drop() {
        val (next, save) = reorder.drop()
        reorder = next
        save?.let(onReorder)
    }
    fun moveNow(place: PlayFromPlace, by: Int) {
        val (next, save) = RemoteReorder(reorder.items).pickUp(place).move(by).drop()
        reorder = next
        save?.let(onReorder)
    }
    val names = placeNames + sources.associate { PlayFromPlace.From(it.id) as PlayFromPlace to it.name }
    val shown = reorder.items.filter { it in names }
    LookaheadScope {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.forEachIndexed { i, place ->
                key(place.raw) {
                    val lifted = reorder.lifted == place
                    val getSwitch = get.takeIf { place == PlayFromPlace.Get }
                    PlayFromRow(
                        number = i + 1,
                        count = shown.size,
                        // Numbered by when each is tried, so Get takes no number while it's off.
                        shownNumber = if (getSwitch == false) null else shown.take(i + 1).count { it != PlayFromPlace.Get || get != false },
                        name = names.getValue(place),
                        switch = getSwitch,
                        lifted = lifted,
                        canUp = lifted && reorder.canMove(-1),
                        canDown = lifted && reorder.canMove(1),
                        onClick = {
                            when {
                                lifted -> drop()
                                getSwitch != null -> onGet(!getSwitch)
                                else -> reorder = reorder.pickUp(place)
                            }
                        },
                        onLongClick = if (getSwitch != null) ({ if (!lifted) reorder = reorder.pickUp(place) }) else null,
                        onCarry = { by -> reorder = reorder.move(by) },
                        onLeave = { reorder = reorder.cancel() },
                        onMoveNow = { by -> moveNow(place, by) },
                        modifier = if (reduced) Modifier else Modifier.animateBounds(this@LookaheadScope, boundsTransform = Carry),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private val Carry = BoundsTransform { _, _ -> tween(180, easing = TvMotion.EaseInOut) }

private val RowShape = RoundedCornerShape(14.dp)

/** A long server name ends in an ellipsis rather than pushing its chip off the page. */
private val ChipMax = 360.dp

/**
 * One place in Play From. Lifted, it fills with the primary colour and glows, arrows show
 * where it can go, and Up and Down carry it instead of moving focus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayFromRow(
    number: Int,
    count: Int,
    shownNumber: Int?,
    name: String,
    /** Get's on or off, drawn as a switch at the row's end; null for every other place. */
    switch: Boolean?,
    lifted: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onCarry: (Int) -> Unit,
    onLeave: () -> Unit,
    onMoveNow: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val reduced = LocalReducedMotion.current
    val colors = MaterialTheme.colorScheme
    val bring = remember { BringIntoViewRequester() }
    val raise by animateFloatAsState(if (lifted && !reduced) 1.02f else 1f, tween(TvMotion.FocusInMs, easing = TvMotion.EaseOut), label = "lift")
    var height by remember { mutableFloatStateOf(0f) }
    // Carried off the end of what's visible, the page scrolls with it, keeping a row of room
    // either side; asked after the move is laid out.
    LaunchedEffect(number, lifted) {
        if (!lifted) return@LaunchedEffect
        withFrameNanos { }
        bring.bringIntoView(Rect(0f, -height, 1f, height * 2))
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (lifted) 1f else 0f)
            .bringIntoViewRequester(bring)
            .onSizeChanged { height = it.height.toFloat() }
            .onPreviewKeyEvent { event ->
                if (!lifted) return@onPreviewKeyEvent false
                val by = when (event.key) {
                    Key.DirectionUp -> -1
                    Key.DirectionDown -> 1
                    Key.DirectionLeft, Key.DirectionRight -> 0
                    else -> return@onPreviewKeyEvent false
                }
                if (by != 0 && event.type == KeyEventType.KeyDown) onCarry(by)
                true
            }
            .onFocusChanged { if (lifted && !it.isFocused) onLeave() }
            .graphicsLayer {
                scaleX = raise
                scaleY = raise
            }
            .tvFocusScale(interaction, TvMotion.RowFocusScale)
            .tvTouch(interaction, onLongClick = onLongClick, onClick = onClick)
            .semantics(mergeDescendants = true) {
                stateDescription = if (lifted) "Picked up, $number of $count" else "$number of $count"
                if (switch != null) {
                    role = Role.Switch
                    toggleableState = ToggleableState(switch)
                }
                customActions = listOfNotNull(
                    CustomAccessibilityAction("Move Up") { onMoveNow(-1); true }.takeIf { number > 1 },
                    CustomAccessibilityAction("Move Down") { onMoveNow(1); true }.takeIf { number < count },
                )
            },
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = colors.onSurface,
            focusedContainerColor = if (lifted) colors.primary else M3Theme.colorScheme.surfaceContainerHighest,
            focusedContentColor = if (lifted) colors.onPrimary else colors.onSurface,
            pressedContainerColor = if (lifted) colors.primary else M3Theme.colorScheme.surfaceContainerHighest,
            pressedContentColor = if (lifted) colors.onPrimary else colors.onSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(TvMotion.FocusBorderWidth, colors.primary), inset = 0.dp, shape = RowShape),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = if (lifted) Glow(colors.primary.copy(alpha = 0.4f), 10.dp) else Glow.None,
        ),
        interactionSource = interaction,
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                shownNumber?.toString().orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = if (lifted) LocalContentColor.current else colors.primary,
                modifier = Modifier.width(24.dp),
            )
            Column(Modifier.weight(1f).alpha(if (switch == false && !lifted) 0.6f else 1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (switch != null) {
                    Text(GetSummary, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                lifted -> Row {
                    Arrow(Icons.Rounded.KeyboardArrowUp, canUp) { onCarry(-1) }
                    Arrow(Icons.Rounded.KeyboardArrowDown, canDown) { onCarry(1) }
                }
                switch != null -> Switch(switch)
                focused -> Icon(Icons.Rounded.DragHandle, contentDescription = null, tint = LocalContentColor.current.copy(alpha = 0.72f), modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** Where a lifted row can go. A tap carries it; at an end, a tap does nothing rather than put it down. */
@Composable
private fun Arrow(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onTap: () -> Unit) {
    val canTap by rememberUpdatedState(enabled)
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier.size(ArrowTarget).pointerInput(Unit) { detectTapGestures { if (canTap) tap() } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.38f), modifier = Modifier.size(24.dp))
    }
}

/** As big as a finger needs that still fits the row's 56dp, so lifting it doesn't change its height. */
private val ArrowTarget = 40.dp

/** A server's word on its row. */
internal fun SourceStatus.rowLine(songCount: Int?): String = when (this) {
    SourceStatus.Connected -> songCount?.let(::songs) ?: "Connected"
    SourceStatus.Away -> "Away"
    // "Refreshing", like its row, so it never reads as a second "Syncing" beside the Sync switch's.
    is SourceStatus.Syncing -> "Refreshing"
    is SourceStatus.Offline -> "Offline"
    SourceStatus.SignIn -> "Signed Out"
    SourceStatus.CheckCertificate -> "Check Certificate"
    is SourceStatus.Songs -> songs(count)
    SourceStatus.Off -> "Off"
}

/** Where a server stands, in its panel. */
internal fun SourceStatus.panelLine(songCount: Int?): String = when (this) {
    SourceStatus.Connected -> songCount?.let { "Connected, ${songs(it)}" } ?: "Connected"
    SourceStatus.Away -> songCount?.let { "Away, ${songs(it)}" } ?: "Away"
    is SourceStatus.Offline -> reason ?: "Offline"
    SourceStatus.SignIn -> "This TV was signed out."
    SourceStatus.CheckCertificate -> "The server's certificate changed."
    else -> rowLine(songCount)
}

/** Focus, once whatever holds it has been laid out. */
private suspend fun FocusRequester.focusSoon() {
    repeat(6) {
        withFrameNanos { }
        if (requestFocus()) return
    }
}
