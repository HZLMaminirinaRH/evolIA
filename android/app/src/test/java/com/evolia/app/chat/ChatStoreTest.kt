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
}
