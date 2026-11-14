package com.luke.tracks.lastfm

import com.luke.tracks.data.PlayEntity
import uniffi.tracks_engine.ScrobbleOutcome

/** The plays owed to Last.fm, as the drain reads and settles them. */
interface ScrobbleQueue {
    /** The oldest owed plays, at most [limit]. */
    suspend fun owed(limit: Int): List<PlayEntity>
    suspend fun markScrobbled(ids: List<String>, at: Long)
    /** Last.fm will never take this one: it stops being owed, with the reason. */
    suspend fun markFailed(id: String, reason: String)
    suspend fun noteAttempt(ids: List<String>)
}

/** How a drain ended. */
sealed interface DrainResult {
    val sent: Int

    /** Everything owed went, or nothing was owed. */
    data class Done(override val sent: Int) : DrainResult

    /** Offline, Last.fm busy, or a daily limit: try again later. */
    data class RetryLater(override val sent: Int, val reason: String) : DrainResult

    /** The session was revoked; nothing will go until the account is connected again. */
    data class NeedsReconnect(override val sent: Int, val reason: String) : DrainResult
}

/** What to record for one batch, from Last.fm's answer to each scrobble in it. */
data class Settlement(
    val accepted: List<String>,
    val rejected: List<Pair<String, String>>,
    /** Some were refused for now (a limit, the clock): wait before sending more. */
    val heldBack: String?,
)

/**
 * Sends everything owed to Last.fm, oldest first, in batches of [BATCH] (Last.fm's limit per
 * request), settling each play by Last.fm's answer for it. Only one batch is in flight at a
 * time, and a batch that fails as a whole stops the drain, since the next would fail too.
 */
class ScrobbleDrain(
    private val queue: ScrobbleQueue,
    /** Sends one batch; Last.fm's answer for each play, in order (fewer if fewer went). */
    private val submit: suspend (List<PlayEntity>) -> List<ScrobbleOutcome>,
    private val isRetryable: (Throwable) -> Boolean,
    private val isAccountProblem: (Throwable) -> Boolean,
    private val describe: (Throwable) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): DrainResult {
        var sent = 0
        while (true) {
            val batch = queue.owed(BATCH)
            if (batch.isEmpty()) return DrainResult.Done(sent)
            val outcomes = try {
                submit(batch)
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val reason = describe(error)
                return when {
                    isAccountProblem(error) -> DrainResult.NeedsReconnect(sent, reason)
                    isRetryable(error) -> DrainResult.RetryLater(sent, reason)
                    else -> {
                        // The request itself was refused: charge the plays, and give up on any
                        // that have been tried too often.
                        queue.noteAttempt(batch.map { it.id })
                        batch.filter { it.scrobbleAttempts + 1 >= MAX_ATTEMPTS }.forEach { queue.markFailed(it.id, reason) }
                        DrainResult.RetryLater(sent, reason)
                    }
                }
            }
            if (outcomes.isEmpty()) return DrainResult.RetryLater(sent, "Last.fm didn't answer")
            val settlement = settle(batch, outcomes)
            if (settlement.accepted.isNotEmpty()) queue.markScrobbled(settlement.accepted, clock())
            settlement.rejected.forEach { (id, reason) -> queue.markFailed(id, reason) }
            sent += settlement.accepted.size
            if (settlement.heldBack != null) return DrainResult.RetryLater(sent, settlement.heldBack)
            // Fewer answers than plays means the rest didn't go: they're still owed, so look
            // again, as long as this batch settled something.
            if (settlement.accepted.isEmpty() && settlement.rejected.isEmpty()) {
                return DrainResult.RetryLater(sent, "Last.fm didn't take any")
            }
            if (batch.size < BATCH && outcomes.size >= batch.size) return DrainResult.Done(sent)
        }
    }

    companion object {
        const val BATCH = 50
        const val MAX_ATTEMPTS = 5

        fun settle(batch: List<PlayEntity>, outcomes: List<ScrobbleOutcome>): Settlement {
            val accepted = mutableListOf<String>()
            val rejected = mutableListOf<Pair<String, String>>()
            var heldBack: String? = null
            batch.zip(outcomes).forEach { (play, outcome) ->
                when (outcome) {
                    is ScrobbleOutcome.Accepted -> accepted += play.id
                    is ScrobbleOutcome.Ignored ->
                        if (outcome.retryable) heldBack = ignoredReason(outcome)
                        else rejected += play.id to ignoredReason(outcome)
                }
            }
            return Settlement(accepted, rejected, heldBack)
        }

        /** Last.fm's ignored-message codes, said plainly. */
        fun ignoredReason(outcome: ScrobbleOutcome.Ignored): String = when (outcome.code) {
            1L -> "Last.fm ignored the artist"
            2L -> "Last.fm ignored the title"
            3L -> "Too old for Last.fm"
            4L -> "Too far in the future for Last.fm"
            5L -> "Last.fm's daily limit was reached"
            else -> outcome.message.ifBlank { "Last.fm didn't take it" }
        }
    }
}
