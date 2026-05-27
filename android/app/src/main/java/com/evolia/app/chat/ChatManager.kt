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
 * supplies ChatIdentityStore.loadOrCreate() (Keystore-backed). [onMessageSent]
 * lets the app record the value of composing a message (a digital action that
 * feeds V -> BTC-e) without coupling this class to the action queue or paths.
 */
class ChatManager(
    private val identity: ChatIdentity,
    private val store: ChatStore,
    private val onMessageSent: () -> Unit = {},
) {

    val myBundleHex: String get() = identity.publicBundleHex()
    val myFingerprint: String get() = identity.fingerprint()

    init {
        // Publish our fingerprint so the relay routes inbound messages to us.
        store.publishFingerprint(myFingerprint)
    }

    /** Seal [text] for the holder of [recipientBundleHex] and queue it for relay.
     *  Returns false if the bundle is invalid or the text is empty / over the
     *  mini-message length cap (MAX_MESSAGE_CHARS). */
    fun send(recipientBundleHex: String, text: String): Boolean {
        if (text.isEmpty() || text.length > MAX_MESSAGE_CHARS) return false
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
        // Composing a message is a valued digital action (like sending a text),
        // counted on the sender's own device — so chat engagement contributes to
        // V -> BTC-e just like screen/photo/video activity.
        onMessageSent()
        return true
    }

    data class Received(val senderFingerprint: String, val senderBundleHex: String, val text: String)

    /** Decrypt every inbound envelope we can open; undecryptable/forged lines are
     *  silently skipped (open() returns null on a bad signature or wrong key).
     *  Deduped by id so a message delivered over two transports (UDP + Bluetooth)
     *  shows once. */
    fun inbox(): List<Received> {
        val seen = HashSet<String>()
        return store.readInbox().mapNotNull { wire ->
            if (!seen.add(wire.id)) return@mapNotNull null
            identity.open(wire.body)?.let {
                Received(it.senderFingerprint, it.senderBundleHex, String(it.plaintext))
            }
        }
    }

    fun contacts(): List<ChatStore.Contact> = store.contacts()

    fun addContact(name: String, bundleHex: String) = store.addContact(name, bundleHex)

    companion object {
        /** Mini-messages only: a chat line is capped at 480 characters. */
        const val MAX_MESSAGE_CHARS = 480
    }
}
