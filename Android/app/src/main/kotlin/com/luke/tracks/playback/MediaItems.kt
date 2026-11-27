package com.luke.tracks.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.luke.tracks.applemusic.AppleMusicUris
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.net.ServerLinks

private const val IDENTITY = "tracks.identity"
private const val DURATION = "tracks.durationMs"

/**
 * The song as Media3 carries it. The address also rides in the request metadata, since a
 * MediaController's items reach the session without their local configuration. A server's
 * address is its [ServerLinks] link, so nothing a controller sees works as a credential.
 */
fun PlayableSong.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(uri)
    .setMimeType(if (isAppleMusic) MimeTypes.AUDIO_WAV else null)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistName)
            .setAlbumTitle(albumTitle)
            .setArtworkUri(artworkUri)
            .setTrackNumber(trackNumber)
            .setRecordingYear(year)
            .setGenre(genre)
            .setDurationMs(durationMs)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply {
                putString(IDENTITY, identity)
                durationMs?.let { putLong(DURATION, it) }
            })
            .build(),
    )
    .build()

/** Back from Media3, for items that came from anywhere: our queue, Android Auto, resumption. */
fun MediaItem.toPlayableSong(): PlayableSong {
    val meta = mediaMetadata
    val extras = meta.extras
    val address = localConfiguration?.uri ?: requestMetadata.mediaUri ?: Uri.EMPTY
    return PlayableSong(
        mediaId = mediaId,
        uri = address,
        title = meta.title?.toString().orEmpty(),
        artistName = meta.artist?.toString().orEmpty(),
        albumTitle = meta.albumTitle?.toString(),
        artworkUri = meta.artworkUri,
        durationMs = meta.durationMs ?: extras?.getLong(DURATION)?.takeIf { it > 0 },
        identity = extras?.getString(IDENTITY).orEmpty(),
        trackNumber = meta.trackNumber,
        year = meta.recordingYear,
        genre = meta.genre?.toString(),
    )
}

/** The item with its address put back from the request metadata, ready to play. */
fun MediaItem.restored(): MediaItem? {
    // A server's song always plays from its own link, whatever address came with it.
    ServerLibrary.parseMediaId(mediaId)?.let { (server, song) ->
        val link = ServerLinks.stream(server, song)
        return buildUpon().setUri(link).setRequestMetadata(requestMetadata.buildUpon().setMediaUri(link).build()).build()
    }
    if (localConfiguration != null) return this
    val address = requestMetadata.mediaUri ?: return null
    val pcm = address.scheme == AppleMusicUris.SCHEME
    return buildUpon().setUri(address).setMimeType(if (pcm) MimeTypes.AUDIO_WAV else null).build()
}

/** An Apple Music song, played by MusicKit beside its silent stand-in ([SourceDataSource]). */
val PlayableSong.isAppleMusic: Boolean get() = uri.scheme == AppleMusicUris.SCHEME
