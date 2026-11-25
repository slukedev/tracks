package com.luke.tracks.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Who may do what with the player's session. The media service has to be exported for Android
 * Auto, Wear OS, the Assistant and the system's media controls, so any installed app can bind
 * it too. Only Tracks itself and controllers the system vouches for may browse your library
 * and history, queue songs or use Tracks' own commands; anything else gets the transport
 * controls a lock screen has, and nothing it hands over is ever played.
 */
object ControllerAccess {
    /** The system's controllers by package, trusted only when the package is part of the system image. */
    val SYSTEM_CONTROLLERS = setOf(
        "com.android.systemui",
        "com.android.bluetooth",
        "com.google.android.projection.gearhead",
        "com.google.android.carassistant",
        "com.android.car.media",
        "com.google.android.googlequicksearchbox",
        "com.google.android.wearable.app",
        "com.google.android.apps.wear.companion",
    )

    data class Controller(
        val packageName: String,
        val uid: Int,
        /** Media3's own check: the system, or an app holding MEDIA_CONTENT_CONTROL or notification access. */
        val systemTrusted: Boolean,
        /** Media3 says it's the media notification, Android Auto or Android Automotive. */
        val systemRole: Boolean,
        /** The package is installed as part of the system image and owns [uid]. */
        val systemPackage: Boolean,
    )

    fun trusts(controller: Controller, ownPackage: String, ownUid: Int): Boolean = when {
        controller.packageName == ownPackage && controller.uid == ownUid -> true
        controller.systemTrusted || controller.systemRole -> true
        controller.packageName in SYSTEM_CONTROLLERS && controller.systemPackage -> true
        else -> false
    }

    /** Whether the controller is Tracks itself, whose own queue items are taken as they are. */
    fun isOwn(controller: Controller, ownPackage: String, ownUid: Int) = controller.packageName == ownPackage && controller.uid == ownUid

    /** What an untrusted controller may do: play, pause, skip and seek, and see what's playing. */
    val PLAYBACK_ONLY: Player.Commands = Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_PREPARE,
        Player.COMMAND_STOP,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_METADATA,
        Player.COMMAND_RELEASE,
    ).build()

    /**
     * The item to play for one a controller sent. Tracks' own queue comes back as it was. From
     * anyone else, only what the library knows by the item's id (or a search) is played, with
     * the library's own address and details: a controller's address and made-up details never
     * reach the player, so they can't end up in History or on Last.fm.
     */
    suspend fun resolve(
        item: MediaItem,
        own: Boolean,
        restore: (MediaItem) -> MediaItem?,
        search: suspend (String) -> MediaItem?,
        lookup: suspend (String) -> MediaItem?,
    ): MediaItem? {
        if (own) restore(item)?.let { return it }
        item.requestMetadata.searchQuery?.let { query -> return search(query) }
        if (item.mediaId.isEmpty()) return null
        return lookup(item.mediaId)
    }
}
