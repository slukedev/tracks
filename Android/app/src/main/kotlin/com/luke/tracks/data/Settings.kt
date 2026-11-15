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
import uniffi.tracks_engine.RadioDiscovery
import uniffi.tracks_engine.RadioTuning

private val Context.settingsStore by preferencesDataStore(name = "settings")

enum class ThemeChoice { System, Light, Dark }

/** Where the app's colors come from: the system's (from the wallpaper), or a color you pick. */
enum class ColorChoice {
    Wallpaper, Custom;

    companion object {
        /** Reads stored values, including the older "Tracks" for Tracks red. */
        fun parse(value: String?): ColorChoice = when (value) {
            null, "Wallpaper" -> Wallpaper
            else -> Custom
        }
    }
}

/** How a picked color becomes a palette, as Android's own wallpaper styles do. */
enum class PaletteChoice { TonalSpot, Vibrant, Fidelity, Neutral }

/** Whose colors the mini player wears: the album playing, or the app's own. */
enum class MiniPlayerColors { Album, App }

/** Tracks red, from the icon. */
const val TRACKS_RED: Int = 0xFFE83A5F.toInt()

data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.System,
    val colors: ColorChoice = ColorChoice.Wallpaper,
    /** The color the palette is made from when [colors] is Custom, as ARGB. */
    val seedColor: Int = TRACKS_RED,
    val paletteStyle: PaletteChoice = PaletteChoice.Vibrant,
    /** True black backgrounds in the dark theme. */
    val pureBlack: Boolean = false,
    val miniPlayerColors: MiniPlayerColors = MiniPlayerColors.Album,
    /** Songs asked to hear less of; they leave every mix and the radio. */
    val hiddenSongs: Set<String> = emptySet(),
    val scrobbleEnabled: Boolean = true,
    val lastFmUser: String? = null,
    /** How far Tracks Radio strays from what you know. */
    val radioDiscovery: RadioDiscovery = RadioDiscovery.BALANCED,
    /** Genres Tracks Radio leans into, as your music spells them. Empty plays across everything. */
    val radioGenres: Set<String> = emptySet(),
    /** Songs not heard in a few months come round more often on Tracks Radio. */
    val radioOldFavorites: Boolean = false,
    /** Tracks Radio shows on Play and in the car. */
    val showTracksRadio: Boolean = true,
    val showSampleData: Boolean = false,
    val onboarded: Boolean = false,
    val subsonicServers: String = "[]",
    /** Songs liked from the player or the media controls, by identity. */
    val likedSongs: Set<String> = emptySet(),
)

/** The old slider's five steps, as the nearest of the three discoveries. */
private fun discoveryFromAdventure(adventure: Float): RadioDiscovery = when {
    adventure < 0.125f -> RadioDiscovery.FAMILIAR
    adventure < 0.5f -> RadioDiscovery.BALANCED
    else -> RadioDiscovery.ADVENTUROUS
}

/** How Tracks Radio is tuned, as the engine takes it. */
val AppSettings.radioTuning: RadioTuning
    get() = RadioTuning(radioDiscovery, radioGenres.sorted(), radioOldFavorites)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val colors = stringPreferencesKey("colors")
        val seed = androidx.datastore.preferences.core.intPreferencesKey("seedColor")
        val style = stringPreferencesKey("paletteStyle")
        val pureBlack = booleanPreferencesKey("pureBlack")
        val miniPlayerColors = stringPreferencesKey("miniPlayerColors")
        val hidden = stringSetPreferencesKey("hiddenSongs")
        val scrobble = booleanPreferencesKey("scrobbleEnabled")
        val lastFmUser = stringPreferencesKey("lastFmUser")
        /** The old five-step slider, read once to choose a discovery. */
        val oldAdventure = floatPreferencesKey("radioAdventure")
        val discovery = stringPreferencesKey("radioDiscovery")
        val radioGenres = stringSetPreferencesKey("radioGenres")
        val oldFavorites = booleanPreferencesKey("radioOldFavorites")
        val showRadio = booleanPreferencesKey("showTracksRadio")
        val sample = booleanPreferencesKey("showSampleData")
        val onboarded = booleanPreferencesKey("onboarded")
        val servers = stringPreferencesKey("subsonicServers")
        val liked = stringSetPreferencesKey("likedSongs")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        theme = this[Keys.theme]?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() } ?: ThemeChoice.System,
        colors = ColorChoice.parse(this[Keys.colors]),
        seedColor = this[Keys.seed] ?: TRACKS_RED,
        paletteStyle = this[Keys.style]?.let { runCatching { PaletteChoice.valueOf(it) }.getOrNull() } ?: PaletteChoice.Vibrant,
        pureBlack = this[Keys.pureBlack] ?: false,
        miniPlayerColors = this[Keys.miniPlayerColors]?.let { runCatching { MiniPlayerColors.valueOf(it) }.getOrNull() } ?: MiniPlayerColors.Album,
        hiddenSongs = this[Keys.hidden] ?: emptySet(),
        scrobbleEnabled = this[Keys.scrobble] ?: true,
        lastFmUser = this[Keys.lastFmUser],
        radioDiscovery = this[Keys.discovery]?.let { runCatching { RadioDiscovery.valueOf(it) }.getOrNull() }
            ?: this[Keys.oldAdventure]?.let(::discoveryFromAdventure) ?: RadioDiscovery.BALANCED,
        radioGenres = this[Keys.radioGenres] ?: emptySet(),
        radioOldFavorites = this[Keys.oldFavorites] ?: false,
        showTracksRadio = this[Keys.showRadio] ?: true,
        showSampleData = this[Keys.sample] ?: false,
        onboarded = this[Keys.onboarded] ?: false,
        subsonicServers = this[Keys.servers] ?: "[]",
        likedSongs = this[Keys.liked] ?: emptySet(),
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p ->
            val new = transform(p.toSettings())
            p[Keys.theme] = new.theme.name
            p[Keys.colors] = new.colors.name
            p[Keys.seed] = new.seedColor
            p[Keys.style] = new.paletteStyle.name
            p[Keys.pureBlack] = new.pureBlack
            p[Keys.miniPlayerColors] = new.miniPlayerColors.name
            p[Keys.hidden] = new.hiddenSongs
            p[Keys.scrobble] = new.scrobbleEnabled
            if (new.lastFmUser == null) p.remove(Keys.lastFmUser) else p[Keys.lastFmUser] = new.lastFmUser
            p[Keys.discovery] = new.radioDiscovery.name
            p[Keys.radioGenres] = new.radioGenres
            p[Keys.oldFavorites] = new.radioOldFavorites
            p[Keys.showRadio] = new.showTracksRadio
            p[Keys.sample] = new.showSampleData
            p[Keys.onboarded] = new.onboarded
            p[Keys.servers] = new.subsonicServers
            p[Keys.liked] = new.likedSongs
        }
    }
}
