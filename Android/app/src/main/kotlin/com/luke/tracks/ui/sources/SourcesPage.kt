package com.luke.tracks.ui.sources

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
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
import com.luke.tracks.sources.PlayFromChoice
import com.luke.tracks.streaming.ServiceStanding
import com.luke.tracks.sources.PlayFromPlace
import com.luke.tracks.sources.Removal
import com.luke.tracks.sources.Source
import com.luke.tracks.sources.SourceKind
import com.luke.tracks.ui.library.servers.rememberServerEditing
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.bottomClearance
import com.luke.tracks.ui.settings.GroupTitle
import com.luke.tracks.ui.settings.SettingsScaffold
import com.luke.tracks.ui.settings.TracksSwitch
import com.luke.tracks.ui.settings.rowColors
import com.luke.tracks.ui.settings.toggle
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
        }
    }.sortedBy { it.kind.ordinal }

    val open = { source: Source ->
        if (wide) shown = source.id.raw else navigator.open(SettingsPageRoute(sourcePage(source.id)))
    }
    val playFrom by registry.playFromChoices.collectAsState()
    val getOn by registry.getOn.collectAsState()
    val getsCopies by registry.getsCopies.collectAsState()
    val list: @Composable (Modifier) -> Unit = { modifier ->
        SourcesPageContent(
            sources = sources,
            playFrom = playFrom,
            get = getOn.takeIf { getsCopies },
            onGet = registry::setGet,
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
    playFrom: List<PlayFromChoice>,
    options: List<AddOption>,
    onOpen: (Source) -> Unit,
    onRemove: (Source) -> Unit,
    onReorder: (List<PlayFromPlace>) -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    selected: String? = null,
    initiallyAdding: Boolean = false,
    /** Whether Get is on; null when no server could get a song. */
    get: Boolean? = null,
    onGet: (Boolean) -> Unit = {},
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
                if (playFrom.size >= 2) {
                    item(key = "play-from") {
                        PlayFromSection(playFrom, onReorder, get, onGet, Modifier.padding(top = 20.dp).animateItem())
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
 * Where a song plays from: the first of these that can, your sources, the streaming services
 * your servers play and Get. Drag a row by its handle, or press and hold anywhere on it;
 * TalkBack offers Move up and Move down. Any row can go anywhere. Get's switch turns it off:
 * it keeps its place, unnumbered, and is passed over.
 */
@Composable
private fun PlayFromSection(
    choices: List<PlayFromChoice>,
    onReorder: (List<PlayFromPlace>) -> Unit,
    get: Boolean?,
    onGet: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val gapPx = with(LocalDensity.current) { ListItemDefaults.SegmentedGap.toPx() }
    val names = choices.map { it.place to it.name }
    val standings = choices.associate { it.place to it.standing }
    var explaining by remember { mutableStateOf<PlayFromChoice?>(null) }
    var dragging by remember { mutableStateOf<PlayFromPlace?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var rowPx by remember { mutableFloatStateOf(1f) }
    fun fromIndex() = names.indexOfFirst { it.first == dragging }
    fun targetIndex() = fromIndex().let { if (it < 0) -1 else (it + (offset / rowPx).roundToInt()).coerceIn(0, names.lastIndex) }

    fun move(from: Int, to: Int) {
        if (from == to || from !in names.indices || to !in names.indices) return
        val list = names.map { it.first }.toMutableList()
        list.add(to, list.removeAt(from))
        onReorder(list)
    }
    fun start(place: PlayFromPlace) {
        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        dragging = place
        offset = 0f
    }
    fun drag(amount: Float) {
        val before = (offset / rowPx).roundToInt()
        offset += amount
        if ((offset / rowPx).roundToInt() != before) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    fun end() {
        val from = fromIndex()
        if (from >= 0) {
            move(from, targetIndex())
            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        }
        dragging = null
        offset = 0f
    }
    fun cancel() {
        dragging = null
        offset = 0f
    }

    Column(modifier) {
        GroupTitle("Play from")
        val from = fromIndex()
        val target = targetIndex()
        names.forEachIndexed { i, (place, name) ->
            key(place.raw) {
                val isGet = place == PlayFromPlace.Get && get != null
                val standing = standings[place] ?: ServiceStanding.Ready
                val off = (isGet && get == false) || standing != ServiceStanding.Ready
                // Numbered by when each is tried, so one that's off or can't play now takes no number.
                val number = names.take(i + 1).count { (it.first != PlayFromPlace.Get || get != false) && (standings[it.first] ?: ServiceStanding.Ready) == ServiceStanding.Ready }
                val lifted = place == dragging
                val shift = when {
                    from < 0 || lifted -> 0f
                    i in (from + 1)..target -> -rowPx
                    i in target..<from -> rowPx
                    else -> 0f
                }
                val lift by animateDpAsState(if (lifted) 6.dp else 0.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "lift")
                val slide by animateFloatAsState(shift, MaterialTheme.motionScheme.fastSpatialSpec(), label = "slide")
                Surface(
                    shape = ListItemDefaults.segmentedShapes(i, names.size).shape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = lift,
                    modifier = Modifier
                        .padding(bottom = ListItemDefaults.SegmentedGap)
                        .onSizeChanged { rowPx = it.height + gapPx }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { translationY = if (lifted) offset else slide }
                        .then(
                            when {
                                isGet -> Modifier.toggleable(get == true, role = Role.Switch) { on -> haptics.toggle(on); onGet(on) }
                                standing != ServiceStanding.Ready -> Modifier.clickable(onClickLabel = "What to do") { explaining = choices.firstOrNull { it.place == place } }
                                else -> Modifier
                            },
                        )
                        .semantics(mergeDescendants = true) {
                            stateDescription = listOfNotNull("${i + 1} of ${names.size}", standing.word).joinToString(", ")
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
                            .pointerInput(place, names) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { start(place) },
                                    onDragEnd = ::end,
                                    onDragCancel = ::cancel,
                                    onDrag = { change, amount -> change.consume(); drag(amount.y) },
                                )
                            }
                            .padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (off) "" else "$number",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(28.dp),
                        )
                        Column(Modifier.weight(1f).alpha(if (off) 0.6f else 1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (isGet) Text(GetSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            standing.word?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        if (isGet) TracksSwitch(get == true, onCheckedChange = null)
                        Box(
                            Modifier
                                .size(56.dp)
                                .pointerInput(place, names) {
                                    detectVerticalDragGestures(
                                        onDragStart = { start(place) },
                                        onDragEnd = ::end,
                                        onDragCancel = ::cancel,
                                        onVerticalDrag = { change, amount -> change.consume(); drag(amount) },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    explaining?.let { choice ->
        AlertDialog(
            onDismissRequest = { explaining = null },
            title = { Text(choice.name) },
            text = { Text(choice.standing.help(choice.name).orEmpty()) },
            confirmButton = { TextButton(onClick = { explaining = null }) { Text("OK") } },
        )
    }
}

/** What Get does, under its name in Play From, on phone and TV. */
internal const val GetSummary = "Adds it to your library"

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
