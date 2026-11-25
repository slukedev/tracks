package com.luke.tracks.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import uniffi.tracks_engine.ShuffleItem
import uniffi.tracks_engine.foldedName
import uniffi.tracks_engine.freshShuffle

/**
 * ExoPlayer with Tracks' shuffle: the engine's artist-spread order (the same as on iPhone),
 * laid out in the queue itself so Up Next shows what really plays next, from any controller:
 * the app, the notification, Android Auto. Turning shuffle off puts the queue back in order.
 */
class FreshShufflePlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
    /** The queue in order, while shuffled. */
    private var original: List<MediaItem>? = null

    init {
        // The order is ours; ExoPlayer's own shuffle flag only says it's on.
        exo.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(0))
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled) shuffleAroundCurrent() else unshuffle()
        super.setShuffleModeEnabled(shuffleModeEnabled)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) = setMediaItems(mediaItems, true)

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        if (!shuffleModeEnabled || mediaItems.size < 2) {
            original = null
            super.setMediaItems(mediaItems, resetPosition)
            return
        }
        original = mediaItems.toList()
        super.setMediaItems(shuffled(mediaItems), resetPosition)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (!shuffleModeEnabled || mediaItems.size < 2) {
            original = null
            super.setMediaItems(mediaItems, startIndex, startPositionMs)
            return
        }
        // The chosen song plays first, then the rest shuffled.
        original = mediaItems.toList()
        val start = startIndex.coerceIn(0, mediaItems.lastIndex)
        val first = mediaItems[start]
        val rest = mediaItems.filterIndexed { i, _ -> i != start }
        super.setMediaItems(listOf(first) + shuffled(rest), 0, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem) = setMediaItems(listOf(mediaItem), true)
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = setMediaItems(listOf(mediaItem), resetPosition)
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = setMediaItems(listOf(mediaItem), 0, startPositionMs)

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        original = original?.plus(mediaItems)
        super.addMediaItems(mediaItems)
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        original = original?.plus(mediaItems)
        super.addMediaItems(index, mediaItems)
    }

    override fun addMediaItem(mediaItem: MediaItem) = addMediaItems(listOf(mediaItem))
    override fun addMediaItem(index: Int, mediaItem: MediaItem) = addMediaItems(index, mutableListOf(mediaItem))

    /** A song put in another's place (another copy, or a song got to play) takes its place in the order too. */
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        val old = if (index in 0 until exo.mediaItemCount) exo.getMediaItemAt(index).mediaId else null
        if (old != null) original = original?.map { if (it.mediaId == old) mediaItem else it }
        super.replaceMediaItem(index, mediaItem)
    }

    override fun clearMediaItems() {
        original = null
        super.clearMediaItems()
    }

    private fun items(): List<MediaItem> = List(exo.mediaItemCount) { exo.getMediaItemAt(it) }

    private fun shuffleAroundCurrent() {
        val count = exo.mediaItemCount
        if (count < 2) return
        val all = items()
        if (original == null) original = all
        val current = exo.currentMediaItemIndex
        val rest = all.filterIndexed { i, _ -> i != current }
        // Three edits, so the song playing now never stops and controllers see one change each.
        exo.removeMediaItems(current + 1, count)
        exo.removeMediaItems(0, current)
        exo.addMediaItems(1, shuffled(rest))
    }

    private fun unshuffle() {
        val ordered = original ?: return
        original = null
        val now = items()
        if (now.size < 2) return
        // What's still queued, in the order it was, then anything added since.
        val left = now.groupingBy { it.mediaId }.eachCount().toMutableMap()
        val desired = mutableListOf<MediaItem>()
        ordered.forEach { item ->
            val n = left[item.mediaId] ?: 0
            if (n > 0) { desired += item; left[item.mediaId] = n - 1 }
        }
        now.forEach { item ->
            val n = left[item.mediaId] ?: 0
            if (n > 0) { desired += item; left[item.mediaId] = n - 1 }
        }
        val currentId = exo.currentMediaItem?.mediaId ?: return
        val at = desired.indexOfFirst { it.mediaId == currentId }.takeIf { it >= 0 } ?: return
        val current = exo.currentMediaItemIndex
        exo.removeMediaItems(current + 1, now.size)
        exo.removeMediaItems(0, current)
        exo.addMediaItems(0, desired.subList(0, at).toList())
        exo.addMediaItems(desired.subList(at + 1, desired.size).toList())
    }

    companion object {
        /** [items] in the engine's fresh order: no artist twice in a row where it can help it. */
        fun shuffled(items: List<MediaItem>, seed: ULong = System.nanoTime().toULong()): List<MediaItem> {
            if (items.size < 2) return items
            val keyed = items.mapIndexed { i, item ->
                ShuffleItem(id = "$i", artistKey = foldedName(item.mediaMetadata.artist?.toString().orEmpty()), recentlyHeard = false)
            }
            return freshShuffle(keyed, seed).mapNotNull { it.toIntOrNull()?.let(items::getOrNull) }
        }
    }
}
