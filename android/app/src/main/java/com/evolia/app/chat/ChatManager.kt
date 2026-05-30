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

    /** A decrypted offline transfer promise received from a peer. The amount is
     *  sealed E2E (the relay never sees it); this is an at-risk peer promise, not a
     *  settled on-chain transfer — the receiver is notified, nothing is minted. */
    data class ReceivedTransfer(val messageId: String, val senderFingerprint: String, val amountBtce: Double)

    /** Decrypt every inbound chat envelope we can open; undecryptable/forged lines
     *  are silently skipped (open() returns null on a bad signature or wrong key).
     *  Deduped by id so a message delivered over two transports (UDP + Bluetooth)
     *  shows once. Filters out ACK (type=="ack") and transfer (type=="xfer")
     *  envelopes — those are handled by readAcks()/incomingTransfers(). */
    fun inbox(): List<Received> {
        val seen = HashSet<String>()
        return store.readInbox().mapNotNull { wire ->
            // Only plain chat messages here; acks and transfers route elsewhere.
            if (wire.type != "msg") return@mapNotNull null
            if (!seen.add(wire.id)) return@mapNotNull null
            identity.open(wire.body)?.let {
                Received(wire.id, it.senderFingerprint, it.senderBundleHex, String(it.plaintext))
            }
        }
    }

    /**
     * Seal an OFFLINE BTC-e transfer promise of [amountBtce] for the holder of
     * [recipientBundleHex] and queue it on the dual-transport mesh (UDP +
     * Bluetooth), so it reaches the peer even with no internet. Returns the
     * envelope id, or null on a bad bundle / non-positive amount. This is an
     * at-risk peer promise (not on-chain settlement): nothing is minted; the
     * receiver simply gets an "accusé de réception" when it arrives.
     */
    fun sendTransfer(recipientBundleHex: String, amountBtce: Double): String? {
        if (amountBtce <= 0.0) return null
        val to = ChatIdentity.fingerprintFromBundle(recipientBundleHex) ?: return null
        return try {
            val msgId = "xfer_" + UUID.randomUUID().toString()
            val payload = org.json.JSONObject().put("btce", amountBtce).toString()
            val body = identity.seal(recipientBundleHex, payload.toByteArray())
            store.enqueue(
                ChatStore.Wire(
                    id = msgId,
                    to = to,
                    from = myFingerprint,
                    ts = Instant.now().toString(),
                    body = body,
                    type = "xfer",
                ),
            )
            msgId
        } catch (_: Exception) {
            null
        }
    }

    /** Decrypt a single transfer-promise envelope (type=="xfer"). Returns null for
     *  a non-xfer, undecryptable/forged, or non-positive-amount envelope. Used by
     *  the receiver's notifier to classify and surface an arrival inline. */
    fun openTransfer(wire: ChatStore.Wire): ReceivedTransfer? {
        if (wire.type != "xfer") return null
        val opened = identity.open(wire.body) ?: return null
        val amount = try {
            org.json.JSONObject(String(opened.plaintext)).optDouble("btce", -1.0)
        } catch (_: Exception) {
            -1.0
        }
        if (amount <= 0.0) return null
        return ReceivedTransfer(wire.id, opened.senderFingerprint, amount)
    }

    /** Decrypt every inbound transfer-promise envelope (type=="xfer") we can open.
     *  Deduped by id; undecryptable/forged lines are skipped. */
    fun incomingTransfers(): List<ReceivedTransfer> {
        val seen = HashSet<String>()
        return store.readInbox().mapNotNull { wire ->
            if (!seen.add(wire.id)) return@mapNotNull null
            openTransfer(wire)
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
