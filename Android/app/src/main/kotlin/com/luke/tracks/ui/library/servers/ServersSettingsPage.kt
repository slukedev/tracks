package com.luke.tracks.ui.library.servers

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.ServerEntry
import com.luke.tracks.ui.library.MiniPlayerClearance
import com.luke.tracks.ui.navigation.LocalNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Settings > Servers: add, edit and remove your Subsonic servers. */
@Composable
fun ServersSettingsPage() {
    val context = LocalContext.current
    val servers = (context.graph.library as? CombinedLibrary)?.servers
    val entries by remember(servers) { servers?.entries ?: MutableStateFlow(emptyList()) }.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ServersPageContent(entries, onSync = { scope.launch { servers?.syncAll() } })
}

@Composable
fun ServersPageContent(entries: List<ServerEntry>, onSync: () -> Unit) {
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    val editing = rememberServerEditing()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Servers") },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onSync() }) {
                            Icon(Icons.Rounded.Sync, contentDescription = "Check for new songs")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        ServerList(
            entries = entries,
            onAdd = { editing.add() },
            onEdit = { editing.edit(it) },
            onRemove = { editing.remove(it) },
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = MiniPlayerClearance),
        )
    }
    editing.Sheets()
}
