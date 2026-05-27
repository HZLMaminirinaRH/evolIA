package com.evolia.app.chat

import com.evolia.app.core.ActionQueue
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
    fun sendingRecordsSmsSentValueAction() {
        val home = Files.createTempDirectory("evolia-chat").toFile()
        val paths = EvoliaPaths(home)
        val alice = ChatManager(ChatIdentity.generate(), ChatStore(paths)) {
            ActionQueue.enqueue(paths, "sms_sent")
        }
        val bobPaths = EvoliaPaths(Files.createTempDirectory("evolia-chat-b").toFile())
        val bob = ChatManager(ChatIdentity.generate(), ChatStore(bobPaths))

        assertTrue(alice.send(bob.myBundleHex, "salama"))
        assertEquals("a sent message is one sms_sent action", listOf("sms_sent" to 1), ActionQueue.drain(paths))

        // A rejected send (over cap) records no value.
        alice.send(bob.myBundleHex, "x".repeat(ChatManager.MAX_MESSAGE_CHARS + 1))
        assertTrue(ActionQueue.drain(paths).isEmpty())
    }

    @Test
    fun enforcesMiniMessageLengthCap() {
        val (alice, _) = manager()
        val (bob, _) = manager()
        assertFalse("empty is rejected", alice.send(bob.myBundleHex, ""))
        assertFalse("over 480 chars is rejected", alice.send(bob.myBundleHex, "x".repeat(ChatManager.MAX_MESSAGE_CHARS + 1)))
        assertTrue("exactly 480 chars is accepted", alice.send(bob.myBundleHex, "x".repeat(ChatManager.MAX_MESSAGE_CHARS)))
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
    fun inboxDedupsByIdAcrossTransports() {
        val (alice, pathsA) = manager()
        val (bob, pathsB) = manager()

        assertTrue(alice.send(bob.myBundleHex, "delivered twice"))
        val line = pathsA.chatOutbox.readText().trim()
        // Same envelope arrives over two transports (UDP + Bluetooth).
        pathsB.chatInbox.appendText(line + "\n")
        pathsB.chatInbox.appendText(line + "\n")

        val received = bob.inbox()
        assertEquals("a message delivered over two transports shows once", 1, received.size)
        assertEquals("delivered twice", received[0].text)
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
