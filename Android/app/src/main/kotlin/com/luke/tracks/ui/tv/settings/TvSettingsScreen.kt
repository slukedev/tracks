package com.luke.tracks.ui.tv.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.components.rememberFocus
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.theme.TvLayout

/** Settings' pages on TV, in the order the list shows them. */
enum class TvSettingsPage(val key: String, val label: String, val icon: ImageVector) {
    Sources("sources", "Sources", Icons.Rounded.LibraryMusic),
    Appearance("appearance", "Appearance", Icons.Rounded.Palette),
    Radio("radio", "Tracks Radio", Icons.Rounded.Radio),
    Hidden("hidden", "Hear Less Of", Icons.Rounded.ThumbDown),
    About("about", "About", Icons.Rounded.Info);

    companion object {
        /** A page by the name the app links to it with, as the phone's Settings names them. */
        fun of(name: String?): TvSettingsPage = when (name) {
            "radio-genres" -> Radio
            "trackside", "servers", "sources/add" -> Sources
            "licenses" -> About
            else -> entries.firstOrNull { it.key == name } ?: Sources
        }
    }
}

/**
 * Settings in two panes: the pages down the left, the one with focus open on the right, as
 * Google TV's own settings are. Right goes into the page; Back or Left comes out to the list.
 * Only the page scrolls. [pageContent] lets screenshots fill a page from plain values.
 */
@Composable
fun TvSettingsScreen(initial: String? = null, pageContent: @Composable (TvSettingsPage) -> Unit = { Page(it) }) {
    val memory = rememberFocusMemory()
    var page by rememberSaveable { mutableStateOf(TvSettingsPage.of(initial)) }
    var inPage by remember { mutableStateOf(false) }
    val pageFocus = remember { FocusRequester() }
    // Opened at a page (Library's Link opens Sources): focus goes into it, not the list.
    memory.ClaimFocus(fallback = if (initial != null) pageFocus else memory.requester(page.key))
    BackHandler(enabled = inPage) { memory.requester(page.key).requestFocus() }

    Row(Modifier.fillMaxSize().padding(start = TvLayout.ContentStart, end = TvLayout.SafeEnd)) {
        Column(
            Modifier
                .width(ListWidth)
                .fillMaxHeight()
                .padding(top = TvLayout.SafeTop + 20.dp, bottom = TvLayout.SafeBottom)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TvSettingsPage.entries.forEach { entry ->
                TvListRow(
                    onClick = { page = entry },
                    headline = entry.label,
                    selected = page == entry,
                    dense = true,
                    leading = { Icon(entry.icon, contentDescription = null) },
                    modifier = Modifier
                        .rememberFocus(memory, entry.key)
                        .onFocusChanged { if (it.isFocused) page = entry },
                )
            }
        }
        Spacer(Modifier.width(36.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(pageFocus)
                .onFocusChanged { inPage = it.hasFocus }
                .focusGroup(),
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn(tween(150, delayMillis = 40)) togetherWith fadeOut(tween(90)) },
                label = "settings page",
            ) { shown -> pageContent(shown) }
        }
    }
}

@Composable
private fun Page(page: TvSettingsPage) {
    when (page) {
        TvSettingsPage.Sources -> TvSourcesPage()
        TvSettingsPage.Appearance -> TvAppearancePage()
        TvSettingsPage.Radio -> TvRadioPage()
        TvSettingsPage.Hidden -> TvHiddenSongsPage()
        TvSettingsPage.About -> TvAboutPage()
    }
}

private val ListWidth = 260.dp
