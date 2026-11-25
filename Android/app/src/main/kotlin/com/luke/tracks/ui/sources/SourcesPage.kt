package com.luke.tracks.ui.sources

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.window.core.layout.WindowSizeClass
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.musicPermission
import com.luke.tracks.sources.PlayFromPlace
import com.luke.tracks.sources.Removal
import com.luke.tracks.sources.Source
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceKind
import com.luke.tracks.ui.library.servers.rememberServerEditing
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.bottomClearance
import com.luke.tracks.ui.settings.GroupTitle
import com.luke.tracks.ui.settings.SettingsScaffold
import com.luke.tracks.ui.settings.rowColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A way in from Add Source. */
data class AddOption(
    val key: String,
    val title: String,
    val kind: SourceKind,
    val subtitle: String? = null,
    val iconRes: Int? = null,
    val onClick: () -> Unit,
)

/**
 * Settings > Sources: every source with where it stands, Add Source, and the order copies
 * play from once there are two or more. On a wide screen the chosen source's page sits beside
 * the list.
 */
@Composable
fun SourcesSettingsPage(adding: Boolean = false) {
    val context = LocalContext.current
    val registry = rememberSources()
    val sources by sourceList()
    val providers by registry.registered.collectAsState()
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val editing = rememberServerEditing()
    val library = context.graph.library as? CombinedLibrary
    var removing by remember { mutableStateOf<Source?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        haptics.performHapticFeedback(if (granted) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        scope.launch { library?.device?.refresh() }
    }
    val wide = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    var shown by rememberSaveable { mutableStateOf<String?>(null) }

    val options = providers.mapNotNull { provider ->
        val offered by provider.offersAdd.collectAsState()
        if (!offered) return@mapNotNull null
        when (provider.kind) {
            SourceKind.Device -> AddOption("device", "On This Device", SourceKind.Device) { permission.launch(musicPermission) }
            SourceKind.Server -> AddOption("server", "Server", SourceKind.Server) { editing.add() }
            SourceKind.AppleMusic -> AddOption("appleMusic", "Apple Music", SourceKind.AppleMusic, provider.addSubtitle, provider.iconRes) {
                navigator.open(SettingsPageRoute(sourcePage(SourceId.AppleMusic)))
            }
            SourceKind.Spotify -> AddOption("spotify", "Spotify", SourceKind.Spotify, provider.addSubtitle, provider.iconRes) {
                navigator.open(SettingsPageRoute(sourcePage(SourceId.Spotify)))
            }
        }
    }.sortedBy { it.kind.ordinal }

    val open = { source: Source ->
        if (wide) shown = source.id.raw else navigator.open(SettingsPageRoute(sourcePage(source.id)))
    }
    val list: @Composable (Modifier) -> Unit = { modifier ->
        SourcesPageContent(
            sources = sources,
            playFrom = registry.arrangedPlayFrom(sources),
            options = options,
            onOpen = open,
            onRemove = { removing = it },
            onReorder = registry::setPlayFrom,
            showBack = navigator.backStack.size > 1,
            selected = if (wide) shown ?: sources.firstOrNull()?.id?.raw else null,
            initiallyAdding = adding,
            modifier = modifier,
        )
    }
    if (wide && sources.isNotEmpty()) {
        Row(Modifier.fillMaxSize()) {
            list(Modifier.weight(0.42f))
            Box(Modifier.weight(0.58f)) {
                val raw = shown ?: sources.first().id.raw
                SourceDetailPage(raw, showBack = false)
            }
        }
    } else {
        list(Modifier)
    }

    editing.Sheets()
    removing?.let { source ->
        val provider = registry.provider(source.kind)
        RemoveSourceDialog(
            provider?.removal(source) ?: Removal.standard(source),
            onConfirm = {
                removing = null
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch { provider?.remove(source.id) }
            },
            onDismiss = { removing = null },
        )
    }
}

/** The list, from plain values so it renders the same in screenshots. */
@Composable
fun SourcesPageContent(
    sources: List<Source>,
    playFrom: List<PlayFromPlace>,
    options: List<AddOption>,
    onOpen: (Source) -> Unit,
    onRemove: (Source) -> Unit,
    onReorder: (List<PlayFromPlace>) -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    selected: String? = null,
    initiallyAdding: Boolean = false,
) {
    var adding by rememberSaveable { mutableStateOf(initiallyAdding) }
    val list = rememberLazyListState()
    val expanded by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 48 } }
    val reduce = rememberReduceMotion()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.geometry.Rect>()
    BackHandler(enabled = adding) { adding = false }

    SharedTransitionLayout(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            SettingsScaffold(title = "Sources", showBack = showBack, listState = list) {
                itemsIndexed(sources, key = { _, source -> source.id.raw }) { index, source ->
                    SourceRow(
                        source = source,
                        index = index,
                        count = sources.size,
                        selected = source.id.raw == selected,
                        onOpen = { onOpen(source) },
                        onRemove = if (source.kind == SourceKind.Device) null else ({ onRemove(source) }),
                        modifier = Modifier.padding(bottom = ListItemDefaults.SegmentedGap).animateItem(),
                    )
                }
                if (sources.size >= 2) {
                    item(key = "play-from") {
                        PlayFromSection(sources, playFrom, onReorder, Modifier.padding(top = 20.dp).animateItem())
                    }
                }
                // Room for the button over the end of the list.
                item(key = "fab-room") { Spacer(Modifier.heightIn(min = 88.dp)) }
            }

            val fabShape = MaterialTheme.shapes.large
            AnimatedVisibility(
                visible = !adding,
                enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomClearance),
            ) {
                ExtendedFloatingActionButton(
                    text = { Text("Add source") },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    expanded = expanded,
                    onClick = { adding = true },
                    modifier = if (reduce) Modifier else Modifier.sharedBounds(
                        rememberSharedContentState("add-source"),
                        this,
                        boundsTransform = { _, _ -> spatial },
                        clipInOverlayDuringTransition = OverlayClip(fabShape),
                    ),
                )
            }
            AddSourceOverlay(
                visible = adding,
                options = options,
                onClose = { adding = false },
                reduce = reduce,
                spatial = spatial,
            )
        }
    }
}

@Composable
private fun SharedTransitionScope.AddSourceOverlay(
    visible: Boolean,
    options: List<AddOption>,
    onClose: () -> Unit,
    reduce: Boolean,
    spatial: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.geometry.Rect>,
) {
    val wide = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    if (wide) {
        AnimatedVisibility(visible, enter = fadeIn(effects), exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)).pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }, contentAlignment = Alignment.Center) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .padding(24.dp)
                        .animateEnterExit(enter = scaleIn(effects, initialScale = 0.95f), exit = scaleOut(MaterialTheme.motionScheme.fastEffectsSpec(), targetScale = 0.95f)),
                ) {
                    AddSourcePanel(options, onClose = onClose, fullScreen = false)
                }
            }
        }
        return
    }
    AnimatedVisibility(visible, enter = fadeIn(effects), exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (reduce) Modifier else Modifier.sharedBounds(
                        rememberSharedContentState("add-source"),
                        this,
                        boundsTransform = { _, _ -> spatial },
                    ),
                ),
        ) {
            AddSourcePanel(options, onClose = onClose, fullScreen = true)
        }
    }
}


@Composable
private fun SourceRow(
    source: Source,
    index: Int,
    count: Int,
    selected: Boolean,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val registry = rememberSources()
    var menu by remember { mutableStateOf(false) }
    val word = source.status.word()
    Box(modifier) {
        SegmentedListItem(
            onClick = onOpen,
            onLongClick = onRemove?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true } },
            onLongClickLabel = onRemove?.let { "More options" },
            selected = selected,
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = rowColors(),
            leadingContent = { SourceGlyph(source.kind, iconRes = registry.iconRes(source.kind)) },
            supportingContent = {
                Text(
                    word,
                    color = if (source.status.needsAction) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = word },
        ) {
            Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) {
            DropdownMenu(menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large) {
                DropdownMenuItem(
                    text = { Text("Remove") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

/**
 * Which copy plays when a song is in more than one source: press and hold a row to drag it,
 * or use TalkBack's Move up and Move down.
 */
@Composable
private fun PlayFromSection(sources: List<Source>, places: List<PlayFromPlace>, onReorder: (List<PlayFromPlace>) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val rowPx = with(LocalDensity.current) { 64.dp.toPx() }
    val names = places.mapNotNull { place ->
        val source = (place as? PlayFromPlace.From)?.source?.let { id -> sources.firstOrNull { it.id == id } } ?: return@mapNotNull null
        place to source
    }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    fun move(from: Int, to: Int) {
        if (from == to || to !in names.indices) return
        val list = names.map { it.first }.toMutableList()
        list.add(to, list.removeAt(from))
        onReorder(list)
    }
    Column(modifier) {
        GroupTitle("Play from")
        names.forEachIndexed { i, (place, source) ->
            val lifted = dragging == i
            val lift by animateDpAsState(if (lifted) 6.dp else 0.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "lift")
            Surface(
                shape = ListItemDefaults.segmentedShapes(i, names.size).shape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = lift,
                modifier = Modifier
                    .padding(bottom = ListItemDefaults.SegmentedGap)
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer { translationY = if (lifted) offset else 0f }
                    .semantics(mergeDescendants = true) {
                        customActions = listOfNotNull(
                            CustomAccessibilityAction("Move up") { move(i, i - 1); true }.takeIf { i > 0 },
                            CustomAccessibilityAction("Move down") { move(i, i + 1); true }.takeIf { i < names.lastIndex },
                        )
                    },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .pointerInput(names.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    dragging = i
                                    offset = 0f
                                },
                                onDragEnd = {
                                    val from = dragging ?: return@detectDragGesturesAfterLongPress
                                    move(from, (from + (offset / rowPx).roundToInt()).coerceIn(0, names.lastIndex))
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    dragging = null
                                    offset = 0f
                                },
                                onDragCancel = { dragging = null; offset = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val before = (offset / rowPx).roundToInt()
                                    offset += amount.y
                                    if ((offset / rowPx).roundToInt() != before) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                },
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(28.dp),
                    )
                    Text(source.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Always confirms, with what goes and what stays. */
@Composable
fun RemoveSourceDialog(removal: Removal, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
        title = { Text(removal.title) },
        text = { Text(removal.message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(removal.confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
