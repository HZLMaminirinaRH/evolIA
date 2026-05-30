package com.evolia.app.chat

import com.evolia.app.core.EvoliaPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Pure-JVM tests for the chat-store transport primitives the Bluetooth relay
 *  drives directly (outbox drain/requeue, inbox append + seen-id preload). */
class ChatStoreTest {

    private fun store(): Pair<ChatStore, EvoliaPaths> {
        val paths = EvoliaPaths(Files.createTempDirectory("evolia-store").toFile())
        return ChatStore(paths) to paths
    }

    private fun wire(id: String) = ChatStore.Wire(id, "to", "from", "ts", "body-$id")

    @Test
    fun appendInboxRoundtrip() {
        val (s, _) = store()
        s.appendInbox(wire("a"))
        s.appendInbox(wire("b"))
        assertEquals(listOf("a", "b"), s.readInbox().map { it.id })
        assertEquals(setOf("a", "b"), s.inboxIds())
    }

    @Test
    fun drainOutboxTakesOnceThenEmpty() {
        val (s, paths) = store()
        s.enqueue(wire("a"))
        s.enqueue(wire("b"))
        assertEquals(listOf("a", "b"), s.drainOutbox().map { it.id })
        assertFalse("outbox is consumed (renamed aside)", paths.chatOutbox.exists())
        assertTrue("a second drain is empty", s.drainOutbox().isEmpty())
    }

    @Test
    fun requeueRestoresUndelivered() {
        val (s, _) = store()
        s.enqueue(wire("a"))
        s.enqueue(wire("b"))
        val drained = s.drainOutbox()
        s.requeueOutbox(drained)
        assertEquals("re-queued envelopes drain again", listOf("a", "b"), s.drainOutbox().map { it.id })
    }

    @Test
    fun drainOnMissingOutboxIsEmpty() {
        val (s, _) = store()
        assertTrue(s.drainOutbox().isEmpty())
    }

    @Test
    fun enqueueFansOutToBothTransportQueues() {
        // The UDP outbox (drained by the Go binary) and the Bluetooth outbox are
        // independent: draining one must not consume the other, so each transport
        // gets its own copy and neither starves the other.
        val (s, _) = store()
        s.enqueue(wire("a"))
        s.enqueue(wire("b"))
        // Drain the UDP queue first; the BT queue must still hold both.
        assertEquals(listOf("a", "b"), s.drainOutbox().map { it.id })
        assertEquals("BT queue is not consumed by a UDP drain", listOf("a", "b"), s.drainBtOutbox().map { it.id })
        // Both now empty.
        assertTrue(s.drainOutbox().isEmpty())
        assertTrue(s.drainBtOutbox().isEmpty())
    }

    @Test
    fun btOutboxPendingCountsUndelivered() {
        val (s, _) = store()
        assertEquals(0, s.btOutboxPending())
        s.enqueue(wire("a"))
        s.enqueue(wire("b"))
        assertEquals(2, s.btOutboxPending())
        // Draining the UDP queue must NOT change the BT pending count.
        s.drainOutbox()
        assertEquals("UDP drain leaves the BT queue intact", 2, s.btOutboxPending())
        s.drainBtOutbox()
        assertEquals(0, s.btOutboxPending())
    }

    @Test
    fun requeueBtRestoresUndelivered() {
        val (s, _) = store()
        s.enqueue(wire("a"))
        val drained = s.drainBtOutbox()
        s.requeueBtOutbox(drained)
        assertEquals("re-queued BT envelopes drain again", listOf("a"), s.drainBtOutbox().map { it.id })
    }

    @Test
    fun purgeWipesBothQueues() {
        val (s, paths) = store()
        s.enqueue(wire("a"))
        s.appendInbox(wire("x"))
        assertTrue(paths.chatOutbox.exists())
        assertTrue(paths.chatOutboxBt.exists())
        s.purgeMessages()
        assertFalse("UDP outbox wiped", paths.chatOutbox.exists())
        assertFalse("BT outbox wiped", paths.chatOutboxBt.exists())
        assertEquals(0, s.btOutboxPending())
        assertTrue(s.readInbox().isEmpty())
    }

    @Test
    fun addThenRemoveContactRoundtrip() {
        val (s, _) = store()
        s.addContact("Alice", "aa")
        s.addContact("Bob", "bb")
        assertEquals(listOf("Alice", "Bob"), s.contacts().map { it.name })
        s.removeContact("aa")
        assertEquals(listOf("Bob"), s.contacts().map { it.name })
        // Removing an unknown bundle is a no-op (no crash, no spurious entries).
        s.removeContact("ff")
        assertEquals(listOf("Bob"), s.contacts().map { it.name })
    }
}
