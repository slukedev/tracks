package com.luke.tracks.ui.settings

import androidx.compose.runtime.Composable
import com.luke.tracks.ui.library.servers.ServersSettingsPage

/**
 * A page inside Settings, by name: capture, apps, lastfm, servers, appearance, radio, hidden,
 * privacy, about, licenses.
 */
@Composable
fun SettingsPageScreen(page: String) {
    when (page) {
        "capture" -> CapturePage()
        "apps" -> AppsPage()
        "lastfm" -> LastFmPage()
        "servers" -> ServersSettingsPage()
        "appearance" -> AppearancePage()
        "radio" -> RadioPage()
        "hidden" -> HiddenSongsPage()
        "privacy" -> PrivacyPage()
        "about" -> AboutPage()
        "licenses" -> LicensesPage()
        else -> SettingsScreen()
    }
}
