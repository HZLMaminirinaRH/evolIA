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
     *  Go chat.Message fields exactly so both sides read the same lines. */
    data class Wire(val id: String, val to: String, val from: String, val ts: String, val body: String) {
        fun toJson(): String = JSONObject()
            .put("id", id).put("to", to).put("from", from).put("ts", ts).put("body", body)
            .toString()

        companion object {
            fun fromJson(line: String): Wire? = try {
                val o = JSONObject(line)
                val w = Wire(o.optString("id"), o.optString("to"), o.optString("from"), o.optString("ts"), o.optString("body"))
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

    /** Queue a sealed envelope for the relay to carry to peers. */
    fun enqueue(wire: Wire) {
        paths.home.mkdirs()
        paths.chatOutbox.appendText(wire.toJson() + "\n")
    }

    /** Inbound envelopes the relay delivered to us (newest last). */
    fun readInbox(): List<Wire> {
        val f = paths.chatInbox
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { if (it.isBlank()) null else Wire.fromJson(it) }
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
    }

    /** Add or update a contact (keyed by bundle), persisting the list. */
    fun addContact(name: String, bundleHex: String) {
        paths.home.mkdirs()
        val merged = (contacts().filter { it.bundleHex != bundleHex } + Contact(name, bundleHex))
        val arr = JSONArray()
        merged.forEach { arr.put(JSONObject().put("name", it.name).put("bundle", it.bundleHex)) }
        paths.chatContacts.writeText(arr.toString())
    }
}
