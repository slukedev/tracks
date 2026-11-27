package com.luke.tracks.ui.settings

import androidx.compose.runtime.Composable

/**
 * A page inside Settings, by name: sources, source/<id>, lastfm, appearance, radio, devices,
 * device/<server>/<speaker>, hidden, privacy, about, licenses. "servers" is the old name for Sources, kept for links.
 */
@Composable
fun SettingsPageScreen(page: String) {
    when {
        page.startsWith("source/") -> com.luke.tracks.ui.sources.SourceDetailPage(page.removePrefix("source/"))
        page.startsWith("device/") -> com.luke.tracks.ui.devices.parseSpeakerPage(page)
            ?.let { com.luke.tracks.ui.devices.SpeakerSettingsPage(it) }
            ?: com.luke.tracks.ui.devices.DevicesSettingsPage()
        else -> NamedPage(page)
    }
}

@Composable
private fun NamedPage(page: String) {
    when (page) {
        "sources", "servers" -> com.luke.tracks.ui.sources.SourcesSettingsPage()
        "sources/add" -> com.luke.tracks.ui.sources.SourcesSettingsPage(adding = true)
        "sync" -> SyncPage()
        "lastfm" -> LastFmPage()
        "getmusic" -> com.luke.tracks.ui.library.servers.GetMusicSettingsPage()
        "lidarr" -> com.luke.tracks.ui.library.servers.LidarrSettingsPage()
        "appearance" -> AppearancePage()
        "radio" -> RadioPage()
        "devices" -> com.luke.tracks.ui.devices.DevicesSettingsPage()
        "radio-genres" -> RadioGenresPage()
        "hidden" -> HiddenSongsPage()
        "privacy" -> PrivacyPage()
        "about" -> AboutPage()
        "licenses" -> LicensesPage()
        else -> SettingsScreen()
    }
}
