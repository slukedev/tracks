package com.luke.tracks.ui.tv.link

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.time.Instant

/** Where linking stands, for the screen. */
sealed interface LinkStep {
    data object Starting : LinkStep
    data class Showing(val offer: LinkOffer) : LinkStep
    data class Linked(val serverName: String) : LinkStep
    data class Failed(val problem: LinkProblem) : LinkStep
}

/**
 * Links to [server]: asks for a code, then asks how it's going every interval the server
 * names, longer after it says to slow down and after a failed ask, with a fresh code when one
 * runs out. Runs while collected, so leaving the screen stops it.
 */
fun linkSteps(linker: TvLinker, server: LinkServer, now: () -> Instant = Instant::now): Flow<LinkStep> = flow {
    emit(LinkStep.Starting)
    while (true) {
        val offer = attempt { linker.start(server) } ?: return@flow
        if (!offer.isValid(now())) {
            emit(LinkStep.Failed(LinkProblem.Failed))
            return@flow
        }
        emit(LinkStep.Showing(offer))
        var waitMs = offer.intervalSeconds * 1000L
        var failures = 0
        while (true) {
            delay(waitMs)
            if (!now().isBefore(offer.expiresAt)) break
            val answer = try {
                linker.poll(offer).also { failures = 0 }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LinkException) {
                emit(LinkStep.Failed(error.problem))
                return@flow
            } catch (_: Exception) {
                // A dropped connection: ask again later rather than give up on the code.
                failures++
                waitMs = (waitMs * 2).coerceAtMost(MaxWaitMs)
                if (failures >= MaxFailures) {
                    emit(LinkStep.Failed(LinkProblem.Offline))
                    return@flow
                }
                continue
            }
            when (answer) {
                LinkPoll.Pending -> Unit
                is LinkPoll.SlowDown -> waitMs = maxOf(waitMs + SlowDownMs, answer.intervalSeconds * 1000L).coerceAtMost(MaxWaitMs)
                LinkPoll.Expired -> break
                LinkPoll.Denied -> {
                    emit(LinkStep.Failed(LinkProblem.Denied))
                    return@flow
                }
                is LinkPoll.Linked -> {
                    emit(LinkStep.Linked(answer.serverName.trim().take(MaxName).ifEmpty { server.name }))
                    return@flow
                }
            }
        }
    }
}

private suspend fun FlowCollector<LinkStep>.attempt(start: suspend () -> LinkOffer): LinkOffer? = try {
    start()
} catch (error: CancellationException) {
    throw error
} catch (error: LinkException) {
    emit(LinkStep.Failed(error.problem))
    null
} catch (_: IOException) {
    emit(LinkStep.Failed(LinkProblem.Offline))
    null
} catch (_: Exception) {
    emit(LinkStep.Failed(LinkProblem.Failed))
    null
}

/** Only what Trackside sends: a code shaped as it shows one, its own link, sane timing. */
internal fun LinkOffer.isValid(now: Instant): Boolean =
    deviceCode.isNotBlank() && deviceCode.length <= 512 &&
        normalizedUserCode(userCode) == userCode &&
        intervalSeconds in 1..MaxIntervalSeconds &&
        expiresAt.isAfter(now)

/** The link the QR carries, when it's Tracks' own and names this code; else no QR. */
internal fun LinkOffer.qrLink(): String? = linkUrl.takeIf {
    it.length <= 512 && it.startsWith("tracks://link?") && it.contains(userCode) && it.none { c -> c.isWhitespace() || c.isISOControl() }
}

private const val MaxIntervalSeconds = 60
private const val SlowDownMs = 5_000L
private const val MaxWaitMs = 60_000L
private const val MaxFailures = 6
private const val MaxName = 80
