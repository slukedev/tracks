package com.luke.tracks.library.trackside

import com.luke.tracks.data.ListenDao
import com.luke.tracks.data.ListenEntity
import com.luke.tracks.data.PlayEntity
import com.luke.tracks.library.ServerLibrary
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Instant

/**
 * Plays of a Trackside server's songs, kept in the history database until the server has
 * them, so a play made on a plane or in a tunnel reaches it later. Each goes with the play's
 * own id, so sending one twice, or from a retry, is still one listen.
 */
class ListenOutbox(
    private val dao: ListenDao,
    /** A Trackside server's client by Tracks' id for it; null while it can't be reached that way. */
    private val client: suspend (String) -> TracksideClient?,
    /** Whether Tracks still has a server by that id, and it's Trackside. */
    private val isTrackside: (String) -> Boolean,
    private val now: () -> Instant = Instant::now,
) {
    enum class Outcome { Done, RetryLater }

    /** Keeps the plays that are of a Trackside server's songs; a play kept again replaces its listen with the final length. */
    suspend fun record(plays: List<PlayEntity>) {
        val listens = plays.mapNotNull { play ->
            // A new song the server played without having it goes as a listen without a song id.
            val (serverId, songId) = ServerLibrary.parseMediaId(play.mediaId)
                ?: ServerLibrary.parseNewSongMediaId(play.mediaId)?.let { it to null }
                ?: return@mapNotNull null
            if (!isTrackside(serverId)) return@mapNotNull null
            ListenEntity(serverId, play.id, songId, play.playedAt, play.listenedMs, play.title, play.artistName, play.albumTitle, play.durationMs)
        }
        if (listens.isNotEmpty()) dao.put(listens)
    }

    suspend fun drain(): Outcome {
        var outcome = Outcome.Done
        for (serverId in dao.servers()) {
            if (!isTrackside(serverId)) {
                dao.removeServer(serverId)
                continue
            }
            val server = client(serverId) ?: continue
            if (drain(serverId, server) == Outcome.RetryLater) outcome = Outcome.RetryLater
        }
        return outcome
    }

    private suspend fun drain(serverId: String, server: TracksideClient): Outcome {
        while (true) {
            val batch = dao.waiting(serverId, BATCH)
            if (batch.isEmpty()) return Outcome.Done
            val ids = batch.map { it.id }
            val results = try {
                server.sendListens(batch.map { it.wire() }, now())
            } catch (error: CancellationException) {
                throw error
            } catch (_: TracksideError.Unauthenticated) {
                // Kept for when it's paired again.
                return Outcome.Done
            } catch (error: Exception) {
                dao.noteAttempt(serverId, ids)
                val transient = error is IOException || error is TracksideError.RateLimited || error is TracksideError.Unavailable ||
                    error is TracksideError.Unproven || error is com.luke.tracks.net.Unproven ||
                    (error is TracksideError.Server && error.status >= 500)
                return if (transient) Outcome.RetryLater else Outcome.Done
            }
            // Accepted, already there, or refused for good: each is done. One without an answer waits.
            val answered = results.filter { it.status in FINISHED }.map { it.clientListenId }.filter { it in ids }
            if (answered.isEmpty()) return Outcome.RetryLater
            dao.remove(serverId, answered)
            if (batch.size < BATCH) return if (answered.size < batch.size) Outcome.RetryLater else Outcome.Done
        }
    }

    private fun ListenEntity.wire() = TracksideListen(
        clientListenId = id,
        songId = songId,
        startedAt = Instant.ofEpochMilli(startedAt).toString(),
        playedMs = playedMs,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
    )

    companion object {
        const val BATCH = 1_000
        private val FINISHED = setOf("accepted", "duplicate", "rejected")
    }
}
