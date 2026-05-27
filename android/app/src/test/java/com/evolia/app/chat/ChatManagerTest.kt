package com.evolia.app.chat

import com.evolia.app.core.EvoliaPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Pure-JVM tests for the chat store + manager (seal -> outbox -> inbox -> open). */
class ChatManagerTest {

    private fun manager(): Pair<ChatManager, EvoliaPaths> {
        val home = Files.createTempDirectory("evolia-chat").toFile()
        val paths = EvoliaPaths(home)
        return ChatManager(ChatIdentity.generate(), ChatStore(paths)) to paths
    }

    // The Go relay carries an outbox line to the recipient's inbox; simulate it.
    private fun relay(from: EvoliaPaths, to: EvoliaPaths) {
        val line = from.chatOutbox.readText().trim()
        to.chatInbox.appendText(line + "\n")
    }

    @Test
    fun endToEndRoundtrip() {
        val (alice, pathsA) = manager()
        val (bob, pathsB) = manager()

        assertTrue("seal to a valid bundle succeeds", alice.send(bob.myBundleHex, "salama Bob"))
        relay(pathsA, pathsB)

        val received = bob.inbox()
        assertEquals(1, received.size)
        assertEquals("salama Bob", received[0].text)
        assertEquals("sender is Alice", alice.myFingerprint, received[0].senderFingerprint)
        assertEquals("sender bundle is discoverable", alice.myBundleHex, received[0].senderBundleHex)
    }

    @Test
    fun publishesFingerprintForRouting() {
        val (alice, pathsA) = manager()
        assertTrue(pathsA.chatFingerprint.exists())
        assertEquals(alice.myFingerprint, pathsA.chatFingerprint.readText().trim())
    }

    @Test
    fun sendToInvalidBundleFails() {
        val (alice, _) = manager()
        assertFalse(alice.send("not-a-valid-bundle", "hi"))
    }

    @Test
    fun inboxSkipsUndecryptable() {
        val (alice, pathsA) = manager()
        val (bob, pathsB) = manager()
        val (carol, _) = manager()

        // Alice seals to Carol, but the line is delivered to Bob's inbox: Bob
        // cannot open it (wrong key), so it is silently skipped.
        assertTrue(alice.send(carol.myBundleHex, "for carol only"))
        relay(pathsA, pathsB)
        assertTrue("Bob cannot decrypt a message sealed for Carol", bob.inbox().isEmpty())
    }

    @Test
    fun contactsPersist() {
        val (alice, _) = manager()
        val (bob, _) = manager()
        alice.addContact("Bob", bob.myBundleHex)
        alice.addContact("Bob", bob.myBundleHex) // de-dup by bundle
        val contacts = alice.contacts()
        assertEquals(1, contacts.size)
        assertEquals("Bob", contacts[0].name)
        assertEquals(bob.myBundleHex, contacts[0].bundleHex)
    }

    @Test
    fun purgeMessagesIsEphemeralButKeepsContacts() {
        val home = Files.createTempDirectory("evolia-chat").toFile()
        val paths = EvoliaPaths(home)
        val store = ChatStore(paths)

        store.addContact("Bob", "deadbeefbundle")
        store.enqueue(ChatStore.Wire("id1", "to", "from", "ts", "sealed-body"))
        paths.chatInbox.writeText("{\"id\":\"x\",\"to\":\"t\",\"from\":\"f\",\"ts\":\"s\",\"body\":\"b\"}\n")
        assertTrue(paths.chatOutbox.exists())
        assertTrue(paths.chatInbox.exists())

        store.purgeMessages()

        assertFalse("inbox is wiped on stop", paths.chatInbox.exists())
        assertFalse("outbox is wiped on stop", paths.chatOutbox.exists())
        assertTrue("messages are gone", store.readInbox().isEmpty())
        assertEquals("contacts survive (not messages)", 1, store.contacts().size)
    }

    @Test
    fun wireSerializationRoundtrip() {
        val w = ChatStore.Wire("id1", "tofp", "fromfp", "2026-05-27T00:00:00Z", "opaque-body")
        val back = ChatStore.Wire.fromJson(w.toJson())
        assertNotNull(back)
        assertEquals(w, back)
    }
}
