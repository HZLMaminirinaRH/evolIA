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

        val msgId = alice.send(bob.myBundleHex, "salama Bob")
        assertNotNull("seal to a valid bundle succeeds", msgId)
        relay(pathsA, pathsB)

        val received = bob.inbox()
        assertEquals(1, received.size)
        assertEquals("salama Bob", received[0].text)
        assertEquals("sender is Alice", alice.myFingerprint, received[0].senderFingerprint)
        assertEquals("sender bundle is discoverable", alice.myBundleHex, received[0].senderBundleHex)
        assertEquals("message id is preserved", msgId, received[0].messageId)
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
        assertTrue("invalid bundle returns null", alice.send("not-a-valid-bundle", "hi") == null)
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

        assertNotNull("send returns message id", alice.send(bob.myBundleHex, "salama"))
        assertEquals("a sent message is one sms_sent action", listOf("sms_sent" to 1), ActionQueue.drain(paths))

        // A rejected send (over cap) records no value.
        assertTrue("over-cap returns null", alice.send(bob.myBundleHex, "x".repeat(ChatManager.MAX_MESSAGE_CHARS + 1)) == null)
        assertTrue(ActionQueue.drain(paths).isEmpty())
    }

    @Test
    fun enforcesMiniMessageLengthCap() {
        val (alice, _) = manager()
        val (bob, _) = manager()
        assertTrue("empty is rejected", alice.send(bob.myBundleHex, "") == null)
        assertTrue("over 480 chars is rejected", alice.send(bob.myBundleHex, "x".repeat(ChatManager.MAX_MESSAGE_CHARS + 1)) == null)
        assertNotNull("exactly 480 chars is accepted", alice.send(bob.myBundleHex, "x".repeat(ChatManager.MAX_MESSAGE_CHARS)))
    }

    @Test
    fun inboxSkipsUndecryptable() {
        val (alice, pathsA) = manager()
        val (bob, pathsB) = manager()
        val (carol, _) = manager()

        // Alice seals to Carol, but the line is delivered to Bob's inbox: Bob
        // cannot open it (wrong key), so it is silently skipped.
        assertNotNull(alice.send(carol.myBundleHex, "for carol only"))
        relay(pathsA, pathsB)
        assertTrue("Bob cannot decrypt a message sealed for Carol", bob.inbox().isEmpty())
    }

    @Test
    fun inboxDedupsByIdAcrossTransports() {
        val (alice, pathsA) = manager()
        val (bob, pathsB) = manager()

        assertNotNull(alice.send(bob.myBundleHex, "delivered twice"))
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

    @Test
    fun ackProtocolRoundtrip() {
        val (alice, pathsA) = manager()
        val (bob, pathsB) = manager()

        // Alice sends a message to Bob.
        val msgId = alice.send(bob.myBundleHex, "hello")
        assertNotNull(msgId)
        relay(pathsA, pathsB)

        // Bob receives it and sends an ACK back.
        val received = bob.inbox()
        assertEquals(1, received.size)
        assertTrue("ACKs are filtered from inbox", received[0].messageId == msgId)
        assertTrue("sendAck queues an ACK envelope", bob.sendAck(alice.myFingerprint, msgId!!))

        // Relay the ACK back to Alice.
        val ackLine = pathsB.chatOutbox.readText().trim()
        pathsA.chatInbox.appendText(ackLine + "\n")

        // Alice reads the ACKs and finds the message marked delivered.
        val delivered = alice.getDeliveredMessageIds()
        assertTrue("Alice sees the message as delivered", msgId in delivered)
    }

    @Test
    fun offlineTransferRoundtrip() {
        // An offline BTC-e transfer rides the same sealed envelope as a chat
        // message but carries type="xfer" + a sealed JSON {"btce": N} body, so the
        // relay never sees the amount and the receiver gets a verifiable promise.
        val (alice, pathsA) = manager()
        val (bob, pathsB) = manager()

        val id = alice.sendTransfer(bob.myBundleHex, 4.75)
        assertNotNull("sendTransfer returns an envelope id", id)
        relay(pathsA, pathsB)

        val incoming = bob.incomingTransfers()
        assertEquals(1, incoming.size)
        assertEquals(4.75, incoming[0].amountBtce, 1e-9)
        assertEquals(alice.myFingerprint, incoming[0].senderFingerprint)
        // A transfer envelope must NOT appear as a plain chat message.
        assertTrue("xfer is filtered from chat inbox", bob.inbox().isEmpty())
    }

    @Test
    fun sendTransferRejectsBadInputs() {
        val (alice, _) = manager()
        val (bob, _) = manager()
        assertTrue("non-positive amount is rejected", alice.sendTransfer(bob.myBundleHex, 0.0) == null)
        assertTrue("negative amount is rejected", alice.sendTransfer(bob.myBundleHex, -1.0) == null)
        assertTrue("invalid bundle is rejected", alice.sendTransfer("not-a-bundle", 1.0) == null)
    }

    @Test
    fun acksAreFilteredFromInbox() {
        val (alice, pathsA) = manager()
        val (bob, pathsB) = manager()

        // Alice sends a message to Bob.
        val msgId = alice.send(bob.myBundleHex, "hello")
        relay(pathsA, pathsB)

        // Bob receives it and sends an ACK back.
        bob.sendAck(alice.myFingerprint, msgId!!)
        relay(pathsB, pathsA)

        // When Alice calls inbox(), ACK envelopes should be filtered out
        // (they are handled separately via readAcks).
        assertTrue("inbox() filters out ACK envelopes", alice.inbox().isEmpty())

        // But getDeliveredMessageIds() should find the ACK and return the msgId.
        val delivered = alice.getDeliveredMessageIds()
        assertTrue("getDeliveredMessageIds() finds the ACK", msgId in delivered)
    }
}
