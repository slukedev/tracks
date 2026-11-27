package com.luke.tracks.ui.tv.settings

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.BuildConfig
import com.luke.tracks.ui.onboarding.Cassette
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.components.TvSidePanel
import com.luke.tracks.ui.tv.theme.TvMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The libraries Tracks for TV is built with, and their licences, as About on the phone lists them. */
private val TvLibraries = listOf(
    "Jetpack Compose, Material 3 and Compose for TV" to "Apache License 2.0",
    "AndroidX: Media3, Room, WorkManager, DataStore, Navigation 3" to "Apache License 2.0",
    "Kotlin and kotlinx" to "Apache License 2.0",
    "OkHttp" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
    "ZXing" to "Apache License 2.0",
    "MaterialKolor" to "MIT License",
    "JNA" to "Apache License 2.0",
    "UniFFI" to "Mozilla Public License 2.0",
    "The Rust engine's crates (rusqlite, serde and others)" to "MIT and Apache License 2.0",
)

private const val Gpl = "Tracks is free software under the GNU General Public License v3.0. You can use, study, share and change it. Copies you share must stay under the same license."

/** Settings > About: the version and the licences, all as text; nothing opens a page elsewhere. */
@Composable
fun TvAboutPage() {
    val context = LocalContext.current
    val engine by produceState("") { value = withContext(Dispatchers.IO) { runCatching { uniffi.tracks_engine.engineVersion() }.getOrDefault("") } }
    val font by produceState("") {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/google_sans_flex_OFL.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    TvAboutContent(version = BuildConfig.VERSION_NAME, engine = engine, fontLicense = font)
}

@Composable
fun TvAboutContent(version: String, engine: String, fontLicense: String) {
    var reading by remember { mutableStateOf<Pair<String, String>?>(null) }
    Box(Modifier.fillMaxSize()) {
    PageColumn {
        Row(Modifier.padding(start = 4.dp, top = 20.dp, bottom = 16.dp)) {
            Cassette(Modifier.width(96.dp))
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tracks", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("Version $version", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (engine.isNotBlank()) Text("Engine $engine", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TvListRow(onClick = { reading = "Tracks" to Gpl }, headline = "Tracks", supporting = "GNU General Public License v3.0", leading = { RowIcon(Icons.Rounded.Code) })
        if (fontLicense.isNotBlank()) {
            TvListRow(onClick = { reading = "Google Sans Flex" to fontLicense }, headline = "Google Sans Flex", supporting = "SIL Open Font License 1.1", leading = { RowIcon(Icons.Rounded.TextFields) })
        }
        PageHeading("Open-Source Libraries")
        TvLibraries.forEach { (name, license) ->
            TvListRow(onClick = {}, headline = name, supporting = license, dense = true)
        }
    }
    val shown = reading
    TvSidePanel(visible = shown != null, onDismiss = { reading = null }, title = shown?.first, width = 520.dp) {
        if (shown != null) LicenseText(shown.second)
    }
    }
}

/** A licence's text, scrolled with up and down while it has focus. */
@Composable
private fun LicenseText(text: String) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 160.dp.toPx() }
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
    Column(
        Modifier
            .fillMaxSize()
            .border(BorderStroke(2.dp, border), RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val by = when (event.key) {
                    Key.DirectionDown -> step
                    Key.DirectionUp -> -step
                    else -> return@onKeyEvent false
                }
                scope.launch { scroll.animateScrollBy(by, tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut)) }
                true
            }
            .focusable()
            .verticalScroll(scroll)
            .padding(12.dp),
    ) {
        Text(text.trim(), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
