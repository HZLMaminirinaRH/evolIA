package com.evolia.app.chat

import java.time.Instant
import java.util.UUID

/**
 * Ties the end-to-end identity to the file-backed transport: seals outgoing
 * messages into the outbox and decrypts inbound envelopes from the inbox. The
 * Go relay only ever moves the opaque sealed bodies between peers — all
 * encryption/authentication happens here.
 *
 * Constructed with an explicit ChatIdentity so it unit-tests on the JVM; the app
 * supplies ChatIdentityStore.loadOrCreate() (Keystore-backed).
 */
class ChatManager(private val identity: ChatIdentity, private val store: ChatStore) {

    val myBundleHex: String get() = identity.publicBundleHex()
    val myFingerprint: String get() = identity.fingerprint()

    init {
        // Publish our fingerprint so the relay routes inbound messages to us.
        store.publishFingerprint(myFingerprint)
    }

    /** Seal [text] for the holder of [recipientBundleHex] and queue it for relay.
     *  Returns false if the bundle is not a valid chat identity. */
    fun send(recipientBundleHex: String, text: String): Boolean {
        val to = ChatIdentity.fingerprintFromBundle(recipientBundleHex) ?: return false
        val body = identity.seal(recipientBundleHex, text.toByteArray())
        store.enqueue(
            ChatStore.Wire(
                id = UUID.randomUUID().toString(),
                to = to,
                from = myFingerprint,
                ts = Instant.now().toString(),
                body = body,
            ),
        )
        return true
    }

    data class Received(val senderFingerprint: String, val senderBundleHex: String, val text: String)

    /** Decrypt every inbound envelope we can open; undecryptable/forged lines are
     *  silently skipped (open() returns null on a bad signature or wrong key). */
    fun inbox(): List<Received> = store.readInbox().mapNotNull { wire ->
        identity.open(wire.body)?.let {
            Received(it.senderFingerprint, it.senderBundleHex, String(it.plaintext))
        }
    }

    fun contacts(): List<ChatStore.Contact> = store.contacts()

    fun addContact(name: String, bundleHex: String) = store.addContact(name, bundleHex)
}
