package com.luke.tracks.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

/**
 * The bar over an album or artist page: clear over the tinted header, then solid with the
 * page's name once the header has scrolled away, so nothing slides under the buttons.
 */
@Composable
fun LibraryPageBar(title: String, list: LazyListState, moreLabel: String, onBack: () -> Unit, onMore: () -> Unit) {
    val scrolled by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val moved by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(
        if (moved) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        motion.defaultEffectsSpec(),
        label = "bar",
    )
    TopAppBar(
        title = {
            AnimatedVisibility(scrolled, enter = fadeIn(motion.fastEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
        actions = { IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, contentDescription = moreLabel) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = container, scrolledContainerColor = container),
    )
}
