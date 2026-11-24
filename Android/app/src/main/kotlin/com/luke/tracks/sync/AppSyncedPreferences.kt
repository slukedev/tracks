package com.luke.tracks.sync

import com.luke.tracks.data.AppSettings
import com.luke.tracks.data.ColorChoice
import com.luke.tracks.data.MiniPlayerColors
import com.luke.tracks.data.PaletteChoice
import com.luke.tracks.data.SettingsRepository
import com.luke.tracks.data.ThemeChoice
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import uniffi.tracks_engine.RadioDiscovery

/**
 * The settings that follow you to your other devices, under the keys the cloud keeps them by.
 * `scrobblesToLastFM` is the Apple apps' own; the rest are Android's, which the Apple apps
 * keep without reading. Everything else, servers and their keys included, stays on this phone.
 */
object SyncedSettings {
    class Key(val name: String, val read: (AppSettings) -> JsonElement, val write: (AppSettings, JsonElement) -> AppSettings?)

    private fun bool(name: String, get: (AppSettings) -> Boolean, set: AppSettings.(Boolean) -> AppSettings) =
        Key(name, { JsonPrimitive(get(it)) }, { s, v -> (v as? JsonPrimitive)?.booleanOrNull?.let { s.set(it) } })

    private inline fun <reified E : Enum<E>> choice(name: String, crossinline get: (AppSettings) -> E, crossinline set: AppSettings.(E) -> AppSettings) =
        Key(name, { JsonPrimitive(get(it).name) }, { s, v ->
            (v as? JsonPrimitive)?.contentOrNull?.let { raw -> enumValues<E>().firstOrNull { it.name == raw } }?.let { s.set(it) }
        })

    private fun texts(name: String, get: (AppSettings) -> Set<String>, set: AppSettings.(Set<String>) -> AppSettings) =
        Key(name, { JsonArray(get(it).sorted().map(::JsonPrimitive)) }, { s, v ->
            (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()?.let { s.set(it) }
        })

    val keys: List<Key> = listOf(
        bool("scrobblesToLastFM", { it.scrobbleEnabled }) { copy(scrobbleEnabled = it) },
        choice<ThemeChoice>("androidTheme", { it.theme }) { copy(theme = it) },
        choice<ColorChoice>("androidColors", { it.colors }) { copy(colors = it) },
        Key("androidSeedColor", { JsonPrimitive(it.seedColor) }, { s, v -> (v as? JsonPrimitive)?.intOrNull?.let { s.copy(seedColor = it) } }),
        choice<PaletteChoice>("androidPaletteStyle", { it.paletteStyle }) { copy(paletteStyle = it) },
        bool("androidPureBlack", { it.pureBlack }) { copy(pureBlack = it) },
        choice<MiniPlayerColors>("androidMiniPlayerColors", { it.miniPlayerColors }) { copy(miniPlayerColors = it) },
        choice<RadioDiscovery>("androidRadioDiscovery", { it.radioDiscovery }) { copy(radioDiscovery = it) },
        texts("androidRadioGenres", { it.radioGenres }) { copy(radioGenres = it) },
        bool("androidRadioOldFavorites", { it.radioOldFavorites }) { copy(radioOldFavorites = it) },
        bool("androidShowsTracksRadio", { it.showTracksRadio }) { copy(showTracksRadio = it) },
        texts("androidHearLessOf", { it.hiddenSongs }) { copy(hiddenSongs = it) },
    )

    fun read(settings: AppSettings): Map<String, JsonElement> = keys.associate { it.name to it.read(settings) }

    /** Values that came from elsewhere; one this build can't read leaves the setting as it is. */
    fun apply(settings: AppSettings, values: Map<String, JsonElement>): AppSettings =
        keys.fold(settings) { current, key -> values[key.name]?.let { key.write(current, it) } ?: current }
}

/** The app's own settings and likes, as sync reads and writes them. */
class AppSyncedPreferences(private val settings: SettingsRepository) : SyncedPreferences {
    override suspend fun settings() = SyncedSettings.read(settings.settings.first())

    override suspend fun applySettings(values: Map<String, JsonElement>) {
        if (values.keys.none { key -> SyncedSettings.keys.any { it.name == key } }) return
        settings.update { SyncedSettings.apply(it, values) }
    }

    override suspend fun likes() = settings.settings.first().likedSongs

    override suspend fun setLikes(likes: Set<String>) = settings.update { it.copy(likedSongs = likes) }
}
