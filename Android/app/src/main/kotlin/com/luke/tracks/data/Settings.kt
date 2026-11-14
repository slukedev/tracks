package com.luke.tracks.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

enum class ThemeChoice { System, Light, Dark }

/** Where the app's colors come from. */
enum class ColorChoice { Wallpaper, Tracks, NowPlaying }

data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.System,
    val colors: ColorChoice = ColorChoice.Wallpaper,
    /** Keep songs played in other apps (needs notification access). */
    val captureOtherApps: Boolean = true,
    /** Apps whose plays are not kept, by package. */
    val excludedApps: Set<String> = emptySet(),
    /** Songs asked to hear less of; they leave every mix and the radio. */
    val hiddenSongs: Set<String> = emptySet(),
    val scrobbleEnabled: Boolean = true,
    val lastFmUser: String? = null,
    /** How often Tracks Radio plays something new, 0 to 1. */
    val radioAdventure: Float = 0.25f,
    val showSampleData: Boolean = false,
    val onboarded: Boolean = false,
    val subsonicServers: String = "[]",
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val colors = stringPreferencesKey("colors")
        val capture = booleanPreferencesKey("captureOtherApps")
        val excluded = stringSetPreferencesKey("excludedApps")
        val hidden = stringSetPreferencesKey("hiddenSongs")
        val scrobble = booleanPreferencesKey("scrobbleEnabled")
        val lastFmUser = stringPreferencesKey("lastFmUser")
        val radio = floatPreferencesKey("radioAdventure")
        val sample = booleanPreferencesKey("showSampleData")
        val onboarded = booleanPreferencesKey("onboarded")
        val servers = stringPreferencesKey("subsonicServers")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        theme = this[Keys.theme]?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() } ?: ThemeChoice.System,
        colors = this[Keys.colors]?.let { runCatching { ColorChoice.valueOf(it) }.getOrNull() } ?: ColorChoice.Wallpaper,
        captureOtherApps = this[Keys.capture] ?: true,
        excludedApps = this[Keys.excluded] ?: emptySet(),
        hiddenSongs = this[Keys.hidden] ?: emptySet(),
        scrobbleEnabled = this[Keys.scrobble] ?: true,
        lastFmUser = this[Keys.lastFmUser],
        radioAdventure = this[Keys.radio] ?: 0.25f,
        showSampleData = this[Keys.sample] ?: false,
        onboarded = this[Keys.onboarded] ?: false,
        subsonicServers = this[Keys.servers] ?: "[]",
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p ->
            val new = transform(p.toSettings())
            p[Keys.theme] = new.theme.name
            p[Keys.colors] = new.colors.name
            p[Keys.capture] = new.captureOtherApps
            p[Keys.excluded] = new.excludedApps
            p[Keys.hidden] = new.hiddenSongs
            p[Keys.scrobble] = new.scrobbleEnabled
            if (new.lastFmUser == null) p.remove(Keys.lastFmUser) else p[Keys.lastFmUser] = new.lastFmUser
            p[Keys.radio] = new.radioAdventure
            p[Keys.sample] = new.showSampleData
            p[Keys.onboarded] = new.onboarded
            p[Keys.servers] = new.subsonicServers
        }
    }
}
