package com.luke.tracks.tv

import com.luke.tracks.ui.tv.settings.RemoteReorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Play From on TV: picking a row up, carrying it, putting it down or back. */
class RemoteReorderTest {
    private val start = RemoteReorder(listOf("a", "b", "c"))

    @Test fun pickingUpLiftsOnlyARowThatsThere() {
        val lifted = start.pickUp("b")
        assertEquals("b", lifted.lifted)
        assertTrue(lifted.isLifted)
        assertEquals(start, start.pickUp("z"))
    }

    @Test fun upAndDownCarryTheLiftedRow() {
        val carried = start.pickUp("a").move(1).move(1)
        assertEquals(listOf("b", "c", "a"), carried.items)
        assertEquals("a", carried.lifted)
        assertEquals(listOf("b", "a", "c"), carried.move(-1).items)
    }

    @Test fun nothingMovesUntilARowIsLifted() {
        assertEquals(start, start.move(1))
        assertFalse(start.canMove(1))
    }

    @Test fun theRowStopsAtEitherEnd() {
        val top = start.pickUp("a")
        assertFalse(top.canMove(-1))
        assertEquals(top, top.move(-1))
        val bottom = start.pickUp("c")
        assertFalse(bottom.canMove(1))
        assertEquals(bottom, bottom.move(1))
        assertTrue(bottom.canMove(-2))
        assertFalse(bottom.canMove(-3))
    }

    @Test fun puttingDownSavesOnlyAnOrderThatChanged() {
        val (dropped, save) = start.pickUp("a").move(2).drop()
        assertEquals(listOf("b", "c", "a"), save)
        assertNull(dropped.lifted)
        assertEquals(listOf("b", "c", "a"), dropped.items)

        // Carried away and back again: nothing to save.
        assertNull(start.pickUp("a").move(1).move(-1).drop().second)
        assertNull(start.drop().second)
    }

    @Test fun backPutsTheRowWhereItWas() {
        val cancelled = start.pickUp("a").move(2).cancel()
        assertEquals(listOf("a", "b", "c"), cancelled.items)
        assertFalse(cancelled.isLifted)
        // Its next move starts from the order it came back to.
        assertEquals(listOf("a", "c", "b"), cancelled.pickUp("b").move(1).drop().second)
    }

    @Test fun followsTheSavedOrderAndCallsOffAMoveWhenARowComesOrGoes() {
        assertEquals(listOf("c", "a", "b"), start.following(listOf("c", "a", "b")).items)

        val carrying = start.pickUp("a").move(1)
        // The same rows in another order (a sync): the row keeps being carried.
        assertEquals(carrying, carrying.following(listOf("c", "b", "a")))
        // A server unlinked while carrying: the move is called off, on the new list.
        val gone = carrying.following(listOf("a", "c"))
        assertFalse(gone.isLifted)
        assertEquals(listOf("a", "c"), gone.items)
    }
}
