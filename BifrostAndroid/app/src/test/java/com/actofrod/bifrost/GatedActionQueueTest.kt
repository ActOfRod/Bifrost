package com.actofrod.bifrost

import com.winlator.winhandler.GatedActionQueue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatedActionQueueTest {
    @Test
    fun beforeInitUngatedActionsRunInOrderAndGatedActionsWait() {
        val queue = GatedActionQueue()
        val gated = Runnable {}
        val firstUngated = Runnable {}
        val secondUngated = Runnable {}
        queue.add(gated)
        queue.addUngated(firstUngated)
        queue.addUngated(secondUngated)

        assertSame(firstUngated, queue.poll(false))
        assertSame(secondUngated, queue.poll(false))
        assertNull(queue.poll(false))
        assertFalse(queue.isEmpty())
    }

    @Test
    fun afterInitRemainingUngatedActionsPrecedeGatedActionsInOrder() {
        val queue = GatedActionQueue()
        val firstGated = Runnable {}
        val secondGated = Runnable {}
        val ungated = Runnable {}
        queue.add(firstGated)
        queue.add(secondGated)
        queue.addUngated(ungated)

        assertSame(ungated, queue.poll(true))
        assertSame(firstGated, queue.poll(true))
        assertSame(secondGated, queue.poll(true))
        assertNull(queue.poll(true))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun ungatedActionsAddedAfterInitStillRunBeforeGatedActions() {
        val queue = GatedActionQueue()
        val gated = Runnable {}
        val ungated = Runnable {}
        queue.add(gated)
        queue.addUngated(ungated)

        assertSame(ungated, queue.poll(true))
        assertSame(gated, queue.poll(true))
        assertNull(queue.poll(true))
    }
}
