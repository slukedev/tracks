package com.luke.tracks.ui.sources

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.sources.Source
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceSelection
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.SettingsPageRoute

/** Past this many sources, servers fold into their own part of the menu. */
private const val FoldServersPast = 6

/**
 * The source filter: a chip at the start of Play's and Library's chip row, there only once
 * there are two or more sources. It narrows what those pages draw from and never touches
 * the queue.
 */
@Composable
fun SourceFilterChip(modifier: Modifier = Modifier) {
    val registry = rememberSources()
    val sources by sourceList()
    val selection by sourceSelection()
    if (sources.size < 2) return
    SourceFilterChip(sources, selection, onSelect = registry::select, modifier)
}

@Composable
fun SourceFilterChip(sources: List<Source>, selection: SourceSelection, onSelect: (SourceSelection) -> Unit, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    var open by remember { mutableStateOf(false) }
    var serversOpen by remember { mutableStateOf(false) }
    val chosen = (selection as? SourceSelection.Only)?.let { only -> sources.firstOrNull { it.id == only.source } }
    val label = chosen?.name ?: "All sources"
    val pick = { next: SourceSelection ->
        open = false
        serversOpen = false
        if (next != selection) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onSelect(next)
    }
    Box(modifier) {
        FilterChip(
            selected = chosen != null,
            onClick = { open = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = chosen?.let { { SourceMark(it, size = FilterChipDefaults.IconSize, tint = MaterialTheme.colorScheme.onSecondaryContainer) } },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .semantics {
                    contentDescription = "Source, $label"
                    role = Role.DropdownList
                },
        )
        DropdownMenu(open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.large) {
            Choice("All sources", selection is SourceSelection.All) { pick(SourceSelection.All) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val folded = sources.size > FoldServersPast
            sources.filter { !folded || it.id !is SourceId.Server }.forEach { source ->
                Choice(source.name, chosen?.id == source.id, leading = { SourceMark(source, size = 20.dp) }) { pick(SourceSelection.Only(source.id)) }
            }
            if (folded) {
                DropdownMenuItem(
                    text = { Text("Servers") },
                    trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) },
                    onClick = { serversOpen = !serversOpen },
                )
                if (serversOpen) {
                    sources.filter { it.id is SourceId.Server }.forEach { source ->
                        Choice(source.name, chosen?.id == source.id, leading = { SourceMark(source, size = 20.dp) }) { pick(SourceSelection.Only(source.id)) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text("Manage sources") },
                leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                onClick = {
                    open = false
                    navigator.open(SettingsPageRoute("sources"))
                },
            )
        }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = leading,
        trailingIcon = if (selected) ({ Icon(Icons.Rounded.Check, "Selected") }) else null,
        onClick = onClick,
    )
}
