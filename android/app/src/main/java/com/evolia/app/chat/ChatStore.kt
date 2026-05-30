package com.evolia.app.chat

import com.evolia.app.core.EvoliaPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File-backed state for the peer chat, sharing the exact EVOLIA_HOME layout the
 * Go relay reads/writes (evolia_chat_outbox.jsonl / _inbox.jsonl /
 * _fingerprint.txt). The app appends sealed envelopes to the outbox (the relay
 * drains and carries them) and reads the inbox (the relay fills it with inbound
 * envelopes addressed to this node). Bodies are opaque to the relay — only this
 * side holds the key. Self-contained (java.io only) so it unit-tests on the JVM.
 */
class ChatStore(private val paths: EvoliaPaths) {

    /** The routing envelope around an opaque, end-to-end-sealed body. Mirrors the
     *  Go chat.Message fields exactly so both sides read the same lines. Type field
     *  defaults to "msg" (message) for backwards compat; "ack" envelopes carry
     *  delivery receipts from receiver back to sender. */
    data class Wire(val id: String, val to: String, val from: String, val ts: String, val body: String, val type: String = "msg") {
        fun toJson(): String = JSONObject()
            .put("id", id).put("to", to).put("from", from).put("ts", ts).put("body", body)
            .put("type", type)
            .toString()

        companion object {
            fun fromJson(line: String): Wire? = try {
                val o = JSONObject(line)
                val w = Wire(
                    o.optString("id"), o.optString("to"), o.optString("from"), o.optString("ts"), o.optString("body"),
                    o.optString("type", "msg")
                )
                if (w.id.isEmpty() || w.body.isEmpty()) null else w
            } catch (_: Exception) {
                null
            }
        }
    }

    data class Contact(val name: String, val bundleHex: String)

    /** Advertise our chat fingerprint so the relay can route inbound messages. */
    fun publishFingerprint(fingerprint: String) {
        paths.home.mkdirs()
        paths.chatFingerprint.writeText(fingerprint)
    }

    /**
     * Queue a sealed envelope, fanned out to BOTH transport outboxes: chatOutbox
     * (drained by the Go mesh-sync binary for UDP/Wi-Fi) and chatOutboxBt (drained
     * by the in-app Bluetooth relay). Two independent queues are deliberate — if
     * both transports drained one shared file, whichever ran first would consume
     * the message and starve the other (the live "Bluetooth on, queue already
     * empty, never delivered" race the user hit when Wi-Fi was off: Go drained the
     * outbox, the UDP datagram went nowhere, and BT found nothing left). Because
     * the receiver dedups by wire.id, offering the same message to both transports
     * delivers it once with no double-show.
     */
    fun enqueue(wire: Wire) {
        paths.home.mkdirs()
        val line = wire.toJson() + "\n"
        paths.chatOutbox.appendText(line)
        paths.chatOutboxBt.appendText(line)
    }

    /** Inbound envelopes the relay delivered to us (newest last). */
    fun readInbox(): List<Wire> {
        val f = paths.chatInbox
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { if (it.isBlank()) null else Wire.fromJson(it) }
    }

    /**
     * Append an inbound envelope to the inbox — the receive sink for transports
     * the app drives itself (the Bluetooth relay). The Go UDP relay appends here
     * too; dedup is the caller's (it holds the seen-id set), and ChatManager.inbox
     * also dedups by id on read, so a message delivered over two transports shows
     * once.
     */
    fun appendInbox(wire: Wire) {
        paths.home.mkdirs()
        paths.chatInbox.appendText(wire.toJson() + "\n")
    }

    /** Ids already in the inbox — preload a dedup set so a transport does not
     *  re-deliver a message already stored (mirror of Go chat.LoadSeenIDs). */
    fun inboxIds(): Set<String> = readInbox().mapTo(mutableSetOf()) { it.id }

    /**
     * Atomically take an outbox aside and parse its queued envelopes, so a message
     * is read once even if another reader drains concurrently — the rename-aside
     * pattern of the Go relay and evolia_actions.drain. A missing outbox yields no
     * messages; unparseable lines are skipped.
     */
    private fun drain(outbox: File): List<Wire> {
        val tmp = File(outbox.path + ".draining")
        if (!outbox.renameTo(tmp)) return emptyList()
        val msgs = tmp.readLines().mapNotNull { if (it.isBlank()) null else Wire.fromJson(it) }
        tmp.delete()
        return msgs
    }

    /** Drain the UDP/Wi-Fi outbox. On Android the Go mesh-sync binary owns this
     *  queue in production; this Kotlin path is the same rename-aside contract,
     *  exercised by the store tests and available to a future in-app UDP relay. */
    fun drainOutbox(): List<Wire> = drain(paths.chatOutbox)

    /** Drain the Bluetooth outbox — the queue the in-app RFCOMM relay owns. */
    fun drainBtOutbox(): List<Wire> = drain(paths.chatOutboxBt)

    /** Re-queue UDP envelopes that found no peer this tick. Append is commutative
     *  with a concurrent drain and the receiver dedups by id, so order/duplication
     *  is harmless. Bounded to the newest MAX_OUTBOX so an offline spell can't grow
     *  the queue without limit (mirrors the Go relay's MAX_QUEUE). */
    fun requeueOutbox(wires: List<Wire>) = requeue(paths.chatOutbox, wires)

    /** Re-queue Bluetooth envelopes undelivered this tick (no bonded peer reached). */
    fun requeueBtOutbox(wires: List<Wire>) = requeue(paths.chatOutboxBt, wires)

    private fun requeue(outbox: File, wires: List<Wire>) {
        if (wires.isEmpty()) return
        paths.home.mkdirs()
        // Keep only the newest MAX_OUTBOX so a long offline spell (e.g. Bluetooth
        // with no peer ever in range) cannot grow the queue unbounded.
        val bounded = if (wires.size > MAX_OUTBOX) wires.takeLast(MAX_OUTBOX) else wires
        outbox.appendText(bounded.joinToString("") { it.toJson() + "\n" })
    }

    /** Pending count in the Bluetooth outbox — surfaced by the in-app diagnostic so
     *  a stuck/undelivered message is visible (the UDP outbox is drained by Go, so
     *  it reads ~0 even mid-flight; the BT queue reflects real undelivered depth). */
    fun btOutboxPending(): Int {
        val f = paths.chatOutboxBt
        return if (f.exists()) f.readLines().count { it.isNotBlank() } else 0
    }

    fun contacts(): List<Contact> {
        val f = paths.chatContacts
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                val bundle = o.optString("bundle")
                if (name.isEmpty() || bundle.isEmpty()) null else Contact(name, bundle)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Ephemeral chat: drop ALL message content (inbox + outbox, and any
     * half-drained outbox the relay left mid-cycle). Called when evolIA stops, so
     * nothing confidential lingers on disk past a running session — the app keeps
     * no message history. The identity key and contacts are kept (they are not
     * messages, and the device must stay reachable on the next start).
     */
    fun purgeMessages() {
        paths.chatInbox.delete()
        paths.chatOutbox.delete()
        File(paths.chatOutbox.path + ".draining").delete()
        // The Bluetooth fan-out queue is message content too — wipe it (and any
        // half-drained tmp) so a stop leaves nothing confidential on disk.
        paths.chatOutboxBt.delete()
        File(paths.chatOutboxBt.path + ".draining").delete()
    }

    /** Add or update a contact (keyed by bundle), persisting the list. */
    fun addContact(name: String, bundleHex: String) {
        paths.home.mkdirs()
        val merged = (contacts().filter { it.bundleHex != bundleHex } + Contact(name, bundleHex))
        val arr = JSONArray()
        merged.forEach { arr.put(JSONObject().put("name", it.name).put("bundle", it.bundleHex)) }
        paths.chatContacts.writeText(arr.toString())
    }

    /** Remove a contact by bundle, persisting the trimmed list. No-op if absent. */
    fun removeContact(bundleHex: String) {
        paths.home.mkdirs()
        val filtered = contacts().filter { it.bundleHex != bundleHex }
        val arr = JSONArray()
        filtered.forEach { arr.put(JSONObject().put("name", it.name).put("bundle", it.bundleHex)) }
        paths.chatContacts.writeText(arr.toString())
    }

    /** Queue an ACK envelope to acknowledge a received message to the sender. The
     *  sealed body holds the original message id; the relay carries it back over
     *  the same sealed channel, and the sender decrypts + marks the message delivered. */
    fun enqueueAck(toFingerprint: String, messageId: String) {
        val ack = Wire(
            id = "ack_" + messageId,
            to = toFingerprint,
            from = "", // filled by ChatManager when sealing
            ts = System.currentTimeMillis().toString(),
            body = messageId, // the ACK body is the original message ID
            type = "ack"
        )
        enqueue(ack)
    }

    /** Extract all ACK message IDs from the inbox (messages where type=="ack"),
     *  marking them as delivery confirmations for the original messages. */
    fun readAcks(): Set<String> {
        return readInbox()
            .filter { it.type == "ack" }
            .mapTo(mutableSetOf()) { it.body } // body holds the original message id
    }

    companion object {
        /** Upper bound on a re-queued outbox, so an offline spell can't grow it
         *  unbounded (mini-messages, ephemeral; mirrors the Go relay's MAX_QUEUE). */
        const val MAX_OUTBOX = 256
    }
}
