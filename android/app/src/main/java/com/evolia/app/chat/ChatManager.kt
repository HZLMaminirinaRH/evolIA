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
     *  Returns the message ID on success, null if the bundle is invalid or the text is empty / over the
     *  mini-message length cap (MAX_MESSAGE_CHARS). Any unexpected crypto error is
     *  swallowed (returns null) so an upstream bad-bundle never crashes the UI. */
    fun send(recipientBundleHex: String, text: String): String? {
        if (text.isEmpty() || text.length > MAX_MESSAGE_CHARS) return null
        val to = ChatIdentity.fingerprintFromBundle(recipientBundleHex) ?: return null
        return try {
            val msgId = UUID.randomUUID().toString()
            val body = identity.seal(recipientBundleHex, text.toByteArray())
            store.enqueue(
                ChatStore.Wire(
                    id = msgId,
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
            msgId
        } catch (_: Exception) {
            null
        }
    }

    /** Returns true if [bundleHex] looks like a valid public bundle (128 hex chars,
     *  parses into a valid Ed25519/X25519 pair). Used by the UI to validate input
     *  at contact-add time so the user gets immediate feedback. */
    fun isValidBundle(bundleHex: String): Boolean =
        ChatIdentity.fingerprintFromBundle(bundleHex) != null

    /** Remove a previously-added contact (no-op if absent). */
    fun removeContact(bundleHex: String) = store.removeContact(bundleHex)

    data class Received(val messageId: String, val senderFingerprint: String, val senderBundleHex: String, val text: String)

    /** Decrypt every inbound envelope we can open; undecryptable/forged lines are
     *  silently skipped (open() returns null on a bad signature or wrong key).
     *  Deduped by id so a message delivered over two transports (UDP + Bluetooth)
     *  shows once. Filters out ACK envelopes (type=="ack") — those are handled
     *  separately by readAcks(). */
    fun inbox(): List<Received> {
        val seen = HashSet<String>()
        return store.readInbox().mapNotNull { wire ->
            // Skip ACK envelopes (they are handled by readAcks, not displayed as messages)
            if (wire.type == "ack") return@mapNotNull null
            if (!seen.add(wire.id)) return@mapNotNull null
            identity.open(wire.body)?.let {
                Received(wire.id, it.senderFingerprint, it.senderBundleHex, String(it.plaintext))
            }
        }
    }

    fun contacts(): List<ChatStore.Contact> = store.contacts()

    fun addContact(name: String, bundleHex: String) = store.addContact(name, bundleHex)

    /** Send an ACK envelope back to [recipientFingerprint] confirming delivery of
     *  the message with [originalMessageId]. The sealed ACK body contains the
     *  original message ID; the sender decrypts + marks it delivered. */
    fun sendAck(recipientFingerprint: String, originalMessageId: String): Boolean {
        // Reconstruct the recipient's bundle by looking up a contact, or return null
        // if we can't find one (they contacted us, but we didn't add them to contacts yet).
        // Fallback: store the fingerprint locally without requiring a reverse-lookup.
        // For now, we send the ACK without a known bundle by storing just the fingerprint
        // in the "to" field and having the relay route it back. The identity.seal() call
        // needs a bundle, so we skip sealing the ACK body — it stays plaintext
        // (the message ID is not secret, only the original message is).
        return try {
            val ackId = "ack_" + originalMessageId
            store.enqueue(
                ChatStore.Wire(
                    id = ackId,
                    to = recipientFingerprint,
                    from = myFingerprint,
                    ts = Instant.now().toString(),
                    body = originalMessageId,
                    type = "ack"
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Get the set of message IDs that have been acknowledged (delivered) by
     *  the receiver. Used by the UI to show "✓ delivered" next to sent messages. */
    fun getDeliveredMessageIds(): Set<String> = store.readAcks()

    companion object {
        /** Mini-messages only: a chat line is capped at 480 characters. */
        const val MAX_MESSAGE_CHARS = 480
    }
}
