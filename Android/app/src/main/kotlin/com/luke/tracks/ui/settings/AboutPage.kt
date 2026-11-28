package com.luke.tracks.ui.settings

import com.luke.tracks.system.isTv
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.luke.tracks.BuildConfig
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.currentNavigator
import com.luke.tracks.ui.onboarding.Cassette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AboutPage() {
    val navigator = currentNavigator()
    val engine by produceState("") { value = withContext(Dispatchers.IO) { runCatching { uniffi.tracks_engine.engineVersion() }.getOrDefault("") } }

    SettingsScaffold(title = "About Tracks") {
        item(key = "brand") {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cassette(Modifier.width(150.dp))
                    Spacer(Modifier.height(18.dp))
                    Text("Tracks", style = MaterialTheme.typography.headlineMediumEmphasized)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Keep track. Find more.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        group(key = "versions") {
            link("Version", summary = BuildConfig.VERSION_NAME, icon = Icons.Rounded.Info, tone = Tone.Neutral) {}
            if (engine.isNotBlank()) {
                link("Shared engine", summary = "The same Rust engine as Tracks on iPhone and Mac, version $engine", icon = Icons.Rounded.Memory, tone = Tone.Neutral) {}
            }
        }
        group(key = "more") {
            link("Privacy", summary = "What Tracks keeps, and where", icon = Icons.Rounded.Shield, tone = Tone.Secondary) {
                navigator.open(SettingsPageRoute("privacy"))
            }
            link("Licenses", summary = "Tracks is free software under the GPL", icon = Icons.Rounded.Description, tone = Tone.Tertiary) {
                navigator.open(SettingsPageRoute("licenses"))
            }
        }
    }
}

@Composable
fun PrivacyPage() {
    SettingsScaffold(title = "Privacy") {
        item(key = "hero") {
            StatusHero(
                icon = Icons.Rounded.Shield,
                shape = androidx.compose.material3.MaterialShapes.Clover4Leaf,
                tone = Tone.Primary,
                headline = "Privacy",
                body = "Tracks has no account, no ads and no analytics.",
            )
        }
        group("What Tracks keeps", key = "keeps") {
            custom {
                Point(Icons.Rounded.PhoneAndroid, "On this phone", "Each song you play in Tracks and when you played it, stored on this phone.")
                Point(Icons.Rounded.GraphicEq, "Last.fm, if you connect it", "Each song you play in Tracks is sent to your Last.fm profile. Turn scrobbling off or disconnect at any time.")
                Point(Icons.Rounded.CloudOff, "Covers", "Covers load from the services that provide them. Your history isn't sent with them.", last = true)
            }
        }
        footnote("Your history and settings go in your phone's own Android backup, so a new phone can pick them up. Uninstalling Tracks removes everything it kept on this one.", key = "backup")
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String, last: Boolean = false) {
    Row(Modifier.padding(bottom = if (last) 0.dp else 18.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class Library(val name: String, val license: String)

private val libraries = listOf(
    Library("Jetpack Compose and Material 3", "Apache License 2.0"),
    Library("AndroidX: Media3, Room, WorkManager, DataStore, Glance, Navigation 3", "Apache License 2.0"),
    Library("Kotlin and kotlinx", "Apache License 2.0"),
    Library("OkHttp", "Apache License 2.0"),
    Library("Coil", "Apache License 2.0"),
    Library("MaterialKolor", "MIT License"),
    Library("JNA", "Apache License 2.0"),
    Library("UniFFI", "Mozilla Public License 2.0"),
    Library("The Rust engine's crates (rusqlite, serde and others)", "MIT and Apache License 2.0"),
)

@Composable
fun LicensesPage() {
    val context = LocalContext.current
    val font by produceState("") {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/google_sans_flex_OFL.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    SettingsScaffold(title = "Licenses") {
        group("Tracks", key = "tracks") {
            link(
                "GNU General Public License v3.0",
                summary = "You can use, study, share and change Tracks. Copies you share must stay under the same license.",
                icon = Icons.Rounded.Code,
                tone = Tone.Primary,
            ) {
                if (!context.isTv) runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gnu.org/licenses/gpl-3.0.html")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        group("Font", key = "font") {
            row { index, count -> Expandable("Google Sans Flex", "SIL Open Font License 1.1", font, index, count) }
        }
        group("Open-source libraries", key = "libraries") {
            libraries.forEach { info(it.name, it.license) }
        }
    }
}

/** A row that opens in place to show a license's full text. */
@Composable
private fun Expandable(title: String, summary: String, text: String, index: Int, count: Int) {
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val turn by animateFloatAsState(if (open) 180f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "chevron")
    val shape = ListItemDefaults.segmentedShapes(index = index, count = count).shape
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = shape, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .clickable { open = !open; haptics.toggle(open) }
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToneIcon(Icons.Rounded.TextFields, Tone.Secondary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ExpandMore, contentDescription = if (open) "Hide license" else "Show license", modifier = Modifier.rotate(turn))
            }
            if (open && text.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(text.trim(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
