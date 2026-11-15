package com.luke.tracks.play

import com.luke.tracks.playback.radio.RadioWords
import com.luke.tracks.ui.settings.showFooter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tracks_engine.RadioDiscovery
import uniffi.tracks_engine.RadioReason
import uniffi.tracks_engine.RadioSteering
import java.time.LocalDate
import java.time.ZoneOffset

/** What Tracks Radio says, in every combination it can come up in. */
class RadioWordsTest {
    private val utc = ZoneOffset.UTC
    private fun ms(year: Int, month: Int) = LocalDate.of(year, month, 15).atStartOfDay(utc).toInstant().toEpochMilli()

    @Test fun everyReasonReadsAsALine() {
        val now = ms(2026, 11)
        assertEquals("Not played since March", RadioWords.line(RadioReason.OldFavorite(ms(2026, 3)), now, utc))
        assertEquals("Not played since March 2024", RadioWords.line(RadioReason.OldFavorite(ms(2024, 3)), now, utc))
        assertEquals("Because you played “Paper Lanterns”", RadioWords.line(RadioReason.GoesWith("Paper Lanterns")))
        assertEquals("More Jazz, since you let one play", RadioWords.line(RadioReason.MoreOf("Jazz")))
        assertEquals("New to you, by an artist you play", RadioWords.line(RadioReason.NewFromYourArtist))
    }

    @Test fun decadesReadAsPeopleSayThem() {
        assertEquals("’80s", RadioWords.decade(1985))
        assertEquals("’00s", RadioWords.decade(1903))
        assertEquals("2010s", RadioWords.decade(2015))
        assertEquals("Less from the ’90s for now", RadioWords.headline(RadioSteering.AwayFromDecade(1990)))
    }

    @Test fun onlyTurnsAwaySayThemselvesAtOnce() {
        with(RadioWords) {
            assertTrue(RadioSteering.FewerNewFinds.isTurningAway)
            assertTrue(RadioSteering.AwayFromGenre("Rock").isTurningAway)
            assertFalse(RadioSteering.SettledOn("Rock").isTurningAway)
            assertFalse(RadioSteering.TowardArtist("Mara Solis").isTurningAway)
        }
    }

    @Test fun tuningSummariesReadInEveryCombination() {
        assertEquals("Balanced", RadioWords.shortSummary(RadioDiscovery.BALANCED, emptySet()))
        assertEquals("Familiar · Jazz", RadioWords.shortSummary(RadioDiscovery.FAMILIAR, setOf("Jazz")))
        assertEquals("Lots of new finds, leaning into Hip-Hop and Jazz", RadioWords.summary(RadioDiscovery.ADVENTUROUS, setOf("Jazz", "Hip-Hop")))
        assertEquals("Hip-Hop, Jazz and more", RadioWords.genreList(setOf("Rock", "Jazz", "Hip-Hop")))
        assertEquals("Everything", RadioWords.genreList(emptySet()))
    }

    @Test fun theShowFooterSaysWhereItIsOrWhatPlaysInstead() {
        assertTrue(showFooter(true).contains("It's at the top of Play."))
        assertTrue(showFooter(false).startsWith("Tracks Radio is hidden."))
        assertFalse(showFooter(true).contains("—"))
    }
}
