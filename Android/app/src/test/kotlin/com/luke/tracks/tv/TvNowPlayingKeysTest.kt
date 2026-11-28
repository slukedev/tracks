package com.luke.tracks.tv

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.luke.tracks.ui.theme.TracksBrand
import com.luke.tracks.ui.tv.components.LocalTvFocusStore
import com.luke.tracks.ui.tv.components.TvFocusStore
import com.luke.tracks.ui.tv.nowplaying.IdleStage
import com.luke.tracks.ui.tv.nowplaying.NowPlayingIdle
import com.luke.tracks.ui.tv.nowplaying.SongMarks
import com.luke.tracks.ui.tv.nowplaying.TvNowPlaying
import com.luke.tracks.ui.tv.nowplaying.seekStepMs
import com.luke.tracks.ui.tv.theme.TvTracksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Now Playing with the remote: play and pause, seeking, waking from rest. The clock is the test's. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvNowPlayingKeysTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var player: FakePlayer

    /** Paused, the position holds still, so seeks land on exact times. */
    private fun show(playing: Boolean = true) {
        player = FakePlayer(playerState(playing = playing), positionMs = 60_000)
        compose.mainClock.autoAdvance = false
        val store = TvFocusStore().apply { requestClaim("") }
        compose.setContent {
            TvTracksTheme(dark = true, dynamic = false, seed = TracksBrand) {
                CompositionLocalProvider(LocalTvFocusStore provides store) {
                    TvNowPlaying(player.state.value, player.position, player, radio = null, marks = SongMarks(false, false), onLike = {}, onHearLess = { _, _ -> }, onClose = {})
                }
            }
        }
        advance(600)
    }

    @Test fun opensOnPauseAndTheCentreKeyPlaysOrPauses() {
        show()
        assertEquals("Pause", focused())
        press(Key.DirectionCenter)
        assertEquals(listOf("playPause"), player.calls)
    }

    @Test fun leftAndRightOnTheBarSeekTenSecondsAPress() {
        show(playing = false)
        assertEquals("Play", focused())
        press(Key.DirectionUp)
        assertEquals("Seek", focused())
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        assertEquals(listOf(70_000L, 80_000L, 70_000L), player.seeks)
        press(Key.DirectionDown)
        assertEquals("Play", focused())
    }

    @Test fun holdingSeeksFurtherAndOnlyOnceWhenLetGo() {
        show(playing = false)
        press(Key.DirectionUp)
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionRight)
            advanceEventTime(3_000)
            keyUp(Key.DirectionRight)
        }
        advance(300)
        assertEquals(1, player.seeks.size)
        // Well past ten seconds a step: the steps grew while it was held.
        assertTrue("seeked to ${player.seeks}", player.seeks.single() > 60_000 + 100_000)
        assertEquals(listOf(10_000L, 20_000L, 30_000L), listOf(0L, 1_000L, 5_000L).map(::seekStepMs))
    }

    @Test fun afterFifteenQuietSecondsTheFirstPressOnlyWakesIt() {
        show()
        advance(NowPlayingIdle.IdleAfterMs - 1_000)
        press(Key.DirectionRight)
        assertEquals("Next", focused())
        press(Key.DirectionLeft)

        advance(NowPlayingIdle.IdleAfterMs + 500)
        press(Key.DirectionRight)
        assertEquals("Pause", focused())
        press(Key.DirectionRight)
        assertEquals("Next", focused())
        assertTrue(player.calls.isEmpty())
    }

    @Test fun theCentreKeyAtRestWakesAndPlaysOrPauses() {
        show()
        advance(NowPlayingIdle.IdleAfterMs + 500)
        press(Key.DirectionCenter)
        assertEquals(listOf("playPause"), player.calls)
        assertEquals("Pause", focused())
    }

    @Test fun mediaKeysAreLeftToTheMediaSession() {
        show()
        advance(NowPlayingIdle.IdleAfterMs + 500)
        press(Key.MediaPlayPause)
        assertTrue(player.calls.isEmpty())
    }

    @Test fun restTimesFollowTheSchedule() {
        assertEquals(IdleStage.Awake, NowPlayingIdle.at(14_999).stage)
        assertEquals(IdleStage.Idle, NowPlayingIdle.at(15_000).stage)
        assertEquals(0, NowPlayingIdle.at(104_999).drift)
        assertEquals(1, NowPlayingIdle.at(105_000).drift)
        assertEquals(IdleStage.Dim, NowPlayingIdle.at(300_000).stage)
        assertEquals(15_000L, NowPlayingIdle.nextChangeAfter(0))
        assertEquals(105_000L, NowPlayingIdle.nextChangeAfter(15_000))
        assertEquals(300_000L, NowPlayingIdle.nextChangeAfter(285_000))
        assertEquals(375_000L, NowPlayingIdle.nextChangeAfter(300_000))
        // Every step moves the art somewhere new, and the first stays put.
        val offsets = (0..5).map { NowPlayingIdle.at(15_000L + it * 90_000).offset }
        assertEquals(androidx.compose.ui.unit.DpOffset.Zero, offsets[0])
        assertTrue(offsets.zipWithNext().all { (a, b) -> a != b })
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        advance(300)
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun focused(): String? {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
    }
}
