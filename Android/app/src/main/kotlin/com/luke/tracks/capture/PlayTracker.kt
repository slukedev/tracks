package com.luke.tracks.capture

import java.util.Locale

/**
 * What an app says is playing, read from its MediaSession's metadata. Plain values only, so
 * the tracker runs (and is tested) on the JVM.
 */
data class TrackMetadata(
    val title: String?,
    val artist: String?,
    val album: String? = null,
    val albumArtist: String? = null,
    /** Zero, negative or missing for a live stream. */
    val durationMs: Long? = null,
    val mediaId: String? = null,
    /** The line apps show under the title, which radio apps often use for the station. */
    val displaySubtitle: String? = null,
    /** An http(s) cover address, when the app gives one. */
    val artUri: String? = null,
    /** Android's media type hints (METADATA_KEY_BT_FOLDER_TYPE and friends), when present. */
    val mediaTypeHint: String? = null,
)

/** The song a tracker settled on, ready to be kept. */
data class TrackedSong(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val mediaId: String?,
    val artUri: String?,
    val isRadio: Boolean,
    val stationName: String?,
) {
    /** Same song, whatever the case or spacing: what dedupe compares. */
    val key: String get() = PlayTracker.songKey(title, artist)
}

/** A song that played long enough to keep. */
data class KeptSong(
    val song: TrackedSong,
    /** When it started playing, wall clock milliseconds. */
    val startedAtMs: Long,
    /** How long it actually played. */
    val listenedMs: Long,
)

/** Why a session's song is not being followed, for the probe and the tests. */
enum class Ignored { OwnApp, ExcludedApp, NotAudioApp, Podcast, Advert, TooLong, Incomplete }

/**
 * Follows what each app plays and decides when a song is kept.
 *
 * Played time counts only while the app says it is playing. A song is kept once it has
 * played long enough by Last.fm's rule ([shouldKeep]), once per play: the same song carrying
 * on (new artwork, a position update) is the same play, and it plays again only when it
 * starts over from the top. Two apps reporting the same song at once keep it once.
 *
 * Not thread safe: feed it from one thread (the listener service's main thread).
 */
class PlayTracker(
    private val ownPackage: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether a song of `durationSeconds` (null when unknown) has played enough. */
    private val shouldKeep: (durationSeconds: Double?, playedSeconds: Double) -> Boolean = ::lastFmRule,
    private val isExcluded: (packageName: String) -> Boolean = { false },
    private val onKeep: (KeptSong) -> Unit,
    /** A new song started playing, for Last.fm's now playing. */
    private val onStart: (TrackedSong) -> Unit = {},
) {
    private class Watch(
        val song: TrackedSong,
        val startedAtMs: Long,
    ) {
        var playedMs = 0L
        var playingSinceMs: Long? = null
        var kept = false
        /** Whether [onStart] has been told about this play. */
        var announced = false
        /** The last position the app reported, and when, to notice a restart. */
        var lastPositionMs: Long? = null
        var lastPositionAtMs: Long = 0

        fun played(now: Long): Long = playedMs + (playingSinceMs?.let { now - it } ?: 0)
    }

    private class Session {
        var watch: Watch? = null
        var playing = false
        var ignored: Ignored? = null
    }

    private val sessions = mutableMapOf<String, Session>()

    /** Songs kept lately, by key, with when: what keeps two apps' reports of one play apart. */
    private val recentlyKept = ArrayDeque<Pair<String, Long>>()

    /** Why each app's current item is being passed over, if it is. */
    fun ignoredReason(packageName: String): Ignored? = sessions[packageName]?.ignored

    /** The song being followed in [packageName], kept or not. */
    fun current(packageName: String): TrackedSong? = sessions[packageName]?.watch?.song

    fun onMetadata(packageName: String, metadata: TrackMetadata?, appIsRadio: Boolean = RadioApps.isRadio(packageName)) {
        val session = sessions.getOrPut(packageName) { Session() }
        val now = clock()
        val (song, reason) = classify(packageName, metadata, appIsRadio)
        session.ignored = reason
        val watch = session.watch
        if (song != null && watch != null && watch.song.key == song.key) {
            // The same play carrying on: take the better details, keep the clock.
            session.watch = Watch(song.mergedWith(watch.song), watch.startedAtMs).also {
                it.playedMs = watch.playedMs
                it.playingSinceMs = watch.playingSinceMs
                it.kept = watch.kept
                it.announced = watch.announced
                it.lastPositionMs = watch.lastPositionMs
                it.lastPositionAtMs = watch.lastPositionAtMs
            }
            check(packageName, now)
            return
        }
        end(packageName, now)
        if (song == null) return
        session.watch = Watch(song, now).also {
            if (session.playing) {
                it.playingSinceMs = now
                it.announced = true
            }
        }
        if (session.playing) onStart(song)
        check(packageName, now)
    }

    /**
     * The app's playback state: whether it is playing, and where in the song, when it says.
     */
    fun onPlayback(packageName: String, playing: Boolean, positionMs: Long? = null) {
        val session = sessions.getOrPut(packageName) { Session() }
        val now = clock()
        val watch = session.watch
        if (watch != null && positionMs != null) {
            if (startedOver(watch, positionMs, now)) {
                // Repeat one, or "play again": a new play of the same song.
                val song = watch.song
                end(packageName, now)
                session.watch = Watch(song, now).also {
                    if (playing) it.playingSinceMs = now
                    it.announced = playing
                    it.lastPositionMs = positionMs
                    it.lastPositionAtMs = now
                }
                session.playing = playing
                if (playing) onStart(song)
                check(packageName, now)
                return
            }
            watch.lastPositionMs = positionMs
            watch.lastPositionAtMs = now
        }
        if (playing == session.playing) {
            check(packageName, now)
            return
        }
        session.playing = playing
        if (watch != null) {
            if (playing) {
                watch.playingSinceMs = now
                if (!watch.announced) {
                    watch.announced = true
                    onStart(watch.song)
                }
            } else {
                watch.playedMs = watch.played(now)
                watch.playingSinceMs = null
            }
        }
        check(packageName, now)
    }

    /** The app's session ended: whatever its song reached is all it gets. */
    fun onSessionGone(packageName: String) {
        end(packageName, clock())
        sessions.remove(packageName)
    }

    /** Checks every playing song against the rule; call it when [msUntilDue] comes round. */
    fun tick() {
        val now = clock()
        sessions.keys.toList().forEach { check(it, now) }
    }

    /**
     * How long until the soonest playing song could be kept, for the service to wake then.
     * Null when nothing is playing toward a keep.
     */
    fun msUntilDue(): Long? {
        val now = clock()
        return sessions.values.mapNotNull { session ->
            val watch = session.watch ?: return@mapNotNull null
            if (watch.kept || watch.playingSinceMs == null) return@mapNotNull null
            val needed = requiredMs(watch.song.durationMs) ?: return@mapNotNull null
            (needed - watch.played(now)).coerceAtLeast(0)
        }.minOrNull()
    }

    private fun requiredMs(durationMs: Long?): Long? {
        // The smallest whole second the rule accepts, found once per song length.
        val seconds = durationMs?.div(1000.0)
        var low = 0
        var high = 4 * 60 + 1
        if (!shouldKeep(seconds, high.toDouble())) return null
        while (low < high) {
            val mid = (low + high) / 2
            if (shouldKeep(seconds, mid.toDouble())) high = mid else low = mid + 1
        }
        return low * 1000L
    }

    private fun startedOver(watch: Watch, positionMs: Long, now: Long): Boolean {
        val last = watch.lastPositionMs ?: return false
        if (watch.played(now) < RESTART_MIN_PLAYED_MS) return false
        val expected = last + (if (watch.playingSinceMs != null) now - watch.lastPositionAtMs else 0)
        return positionMs < RESTART_NEAR_TOP_MS && expected - positionMs > RESTART_JUMP_MS
    }

    private fun check(packageName: String, now: Long) {
        val watch = sessions[packageName]?.watch ?: return
        if (watch.kept) return
        val played = watch.played(now)
        val duration = watch.song.durationMs?.takeIf { it > 0 }
        // Never more than the song itself: a paused app can leave the clock running.
        val counted = if (duration != null) played.coerceAtMost(duration) else played
        if (!shouldKeep(duration?.div(1000.0), counted / 1000.0)) return
        watch.kept = true
        pruneRecent(now)
        if (recentlyKept.any { it.first == watch.song.key }) return
        recentlyKept.addLast(watch.song.key to now)
        onKeep(KeptSong(watch.song, watch.startedAtMs, counted))
    }

    private fun pruneRecent(now: Long) {
        while (recentlyKept.isNotEmpty() && now - recentlyKept.first().second > DUPLICATE_WINDOW_MS) {
            recentlyKept.removeFirst()
        }
    }

    /** Ends the current watch: banks the time played. */
    private fun end(packageName: String, now: Long) {
        val session = sessions[packageName] ?: return
        val watch = session.watch ?: return
        check(packageName, now)
        watch.playedMs = watch.played(now)
        watch.playingSinceMs = null
        session.watch = null
    }

    /** The song in [metadata], or why it isn't one worth keeping. */
    internal fun classify(packageName: String, metadata: TrackMetadata?, appIsRadio: Boolean): Pair<TrackedSong?, Ignored?> {
        if (packageName == ownPackage) return null to Ignored.OwnApp
        if (isExcluded(packageName)) return null to Ignored.ExcludedApp
        if (metadata == null) return null to Ignored.Incomplete
        if (packageName in NotMusicApps.packages) return null to Ignored.NotAudioApp
        if (PodcastApps.isPodcast(packageName, metadata)) return null to Ignored.Podcast
        var title = metadata.title.clean()
        var artist = metadata.artist.clean() ?: metadata.albumArtist.clean()
        val album = metadata.album.clean()
        val subtitle = metadata.displaySubtitle.clean()
        val duration = metadata.durationMs?.takeIf { it > 0 }
        val live = duration == null
        val radio = appIsRadio || (live && (album != null || subtitle != null))
        var station: String? = null
        if (radio) {
            // Radio apps often put "Artist - Title" in the title and the station in the
            // artist line. Split it, and take the station from what's left over.
            val split = title?.let(::splitArtistTitle)
            if (split != null && (artist == null || artist.equals(album, true) || artist.equals(subtitle, true) || appIsRadio && live)) {
                station = artist ?: album ?: subtitle
                artist = split.first
                title = split.second
            } else {
                // Without a length it's a live stream, where the album line is the station.
                val candidates = if (live) listOf(album, subtitle) else listOf(subtitle)
                station = candidates.firstOrNull {
                    it != null && !it.equals(artist, true) && !it.equals(title, true) && (live || !it.equals(album, true))
                }
            }
        }
        if (title == null || artist == null) return null to Ignored.Incomplete
        if (Adverts.isAdvert(title, artist, metadata.mediaId, duration)) return null to Ignored.Advert
        if (duration != null && duration > LONGEST_SONG_MS) return null to Ignored.TooLong
        if (title.equals(station, true) || radio && title.equals(artist, true)) return null to Ignored.Incomplete
        return TrackedSong(
            packageName = packageName,
            title = title,
            artist = artist,
            album = if (radio && album == station) null else album,
            durationMs = duration,
            mediaId = metadata.mediaId.clean(),
            artUri = metadata.artUri?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            isRadio = radio,
            stationName = station,
        ) to null
    }

    private fun TrackedSong.mergedWith(old: TrackedSong) = copy(
        album = album ?: old.album,
        durationMs = durationMs ?: old.durationMs,
        artUri = artUri ?: old.artUri,
        mediaId = mediaId ?: old.mediaId,
        stationName = stationName ?: old.stationName,
    )

    companion object {
        /** Longer than this is a podcast, an audiobook chapter or a mix, not a song. */
        const val LONGEST_SONG_MS = 30 * 60 * 1000L
        /** One play reported by two apps arrives within this. */
        const val DUPLICATE_WINDOW_MS = 45_000L
        private const val RESTART_MIN_PLAYED_MS = 15_000L
        private const val RESTART_NEAR_TOP_MS = 5_000L
        private const val RESTART_JUMP_MS = 10_000L

        /**
         * Last.fm's rule, for songs: longer than 30 seconds, and played for half its length
         * or four minutes, whichever comes first. A live stream with no length counts after
         * 30 seconds. The app passes the engine's own `lastfmShouldScrobble` instead.
         */
        fun lastFmRule(durationSeconds: Double?, playedSeconds: Double): Boolean {
            if (durationSeconds == null) return playedSeconds >= 30.0
            if (durationSeconds <= 30.0) return false
            return playedSeconds >= minOf(durationSeconds / 2, 240.0)
        }

        fun songKey(title: String, artist: String): String =
            (title.trim() + "\u0000" + artist.trim()).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

        private val separators = listOf(" - ", " \u2013 ", " \u2014 ", " | ", " / ")

        /** "Artist - Title", as radio streams send it. */
        fun splitArtistTitle(text: String): Pair<String, String>? {
            for (sep in separators) {
                val at = text.indexOf(sep)
                if (at > 0) {
                    val a = text.substring(0, at).trim()
                    val t = text.substring(at + sep.length).trim()
                    if (a.isNotEmpty() && t.isNotEmpty()) return a to t
                }
            }
            return null
        }

        private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }
}

/** Apps that play live radio: their songs are kept as radio. */
object RadioApps {
    val packages = setOf(
        "tunein.player", "radiotime.player", "com.clearchannel.iheartradio.controller",
        "com.audials", "de.radio.android", "de.radio.android.prime", "com.appgeneration.itunerfree",
        "com.streema.simpleradio", "com.bbc.sounds", "uk.co.bbc.android.iplayerradiov2",
        "com.globalplayer", "com.radio.fmradio", "com.nst.iptvsmarterstvbox", "garden.radio",
        "com.thisisaim.abcradio", "com.sirius", "com.siriusxm.android", "com.pandora.android",
        "com.radioplayer.android", "nl.nlziet.radio", "com.audioaddict.di", "com.audioaddict.rr",
        "com.audioaddict.jr", "com.audioaddict.sky", "com.somafm", "io.github.muntashirakon.radio",
        "net.programmierecke.radiodroid2", "com.ilv.vradio", "com.maxxt.pcradio",
    )

    fun isRadio(packageName: String): Boolean = packageName in packages
}

/** Apps whose sessions are video or calls, never songs. */
object NotMusicApps {
    val packages = setOf(
        "com.google.android.youtube", "com.google.android.apps.youtube.kids", "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus", "tv.twitch.android.app",
        "com.google.android.apps.photos", "com.google.android.videos", "com.hbo.hbonow", "com.wbd.stream",
        "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.whatsapp",
        "org.telegram.messenger", "com.google.android.dialer", "com.google.android.apps.tachyon",
        "com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx", "com.brave.browser",
        "com.sec.android.app.sbrowser", "com.google.android.apps.maps", "com.waze",
    )
}

/** Podcasts and audiobooks, by app and by the hints a music app leaves in its metadata. */
object PodcastApps {
    val packages = setOf(
        "com.google.android.apps.podcasts", "au.com.shiftyjelly.pocketcasts", "com.bambuna.podcastaddict",
        "fm.castbox.audiobook.radio.podcast", "com.audible.application", "app.antennapod",
        "de.danoeh.antennapod", "com.podcast.podcasts", "com.stitcher.app", "com.spreaker.android",
        "com.overcast", "com.google.android.apps.books", "com.storytel", "com.bookbeat",
        "com.everand.android", "com.scribd.app.reader0", "com.ivoox.app", "fm.player",
    )

    private val idHints = listOf("episode", "podcast", "audiobook", "chapter", "show:")

    fun isPodcast(packageName: String, metadata: TrackMetadata): Boolean {
        if (packageName in packages) return true
        val id = metadata.mediaId?.lowercase(Locale.ROOT) ?: ""
        if (idHints.any { it in id }) return true
        val hint = metadata.mediaTypeHint?.lowercase(Locale.ROOT) ?: ""
        return "podcast" in hint || "audiobook" in hint || "audio_book" in hint
    }
}

/** Adverts between songs (Spotify's free tier, radio breaks). */
object Adverts {
    private val words = setOf("advertisement", "advert", "ad break", "sponsored", "spotify", "commercial break")

    fun isAdvert(title: String, artist: String, mediaId: String?, durationMs: Long?): Boolean {
        val t = title.lowercase(Locale.ROOT)
        val a = artist.lowercase(Locale.ROOT)
        if (t in words || a in words) return true
        if (mediaId != null && (":ad:" in mediaId || mediaId.startsWith("ad:") || "/ad/" in mediaId)) return true
        return t.startsWith("advertisement") || a.startsWith("advertisement")
    }
}
