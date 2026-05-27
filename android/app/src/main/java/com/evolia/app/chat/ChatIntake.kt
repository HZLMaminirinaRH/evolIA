package com.evolia.app.chat

import com.evolia.app.security.AdaptiveDefense
import com.evolia.app.security.AttackKind
import org.json.JSONObject

/**
 * Transport-agnostic receive pipeline for opaque chat envelopes — the Kotlin
 * mirror of go/chat's ParseIncoming + AddressedTo + AppendInbox. Any transport
 * the app drives itself (the Bluetooth relay now; a future ACK/HTTP layer) feeds
 * raw inbound bytes here: they are validated, hostile input is classified into
 * the SAME adaptive defense as block input (so injection/malformed arriving over
 * Bluetooth hardens evolIA exactly like over the UDP mesh), deduped by id, routed
 * by fingerprint, and appended to the inbox. The body stays sealed and opaque —
 * decryption is ChatManager's job. Pure (org.json only) so it unit-tests on the JVM.
 */
object ChatIntake {

    /** Receive-buffer/parse cap, mirroring go/chat.MaxDatagramBytes. */
    const val MAX_DATAGRAM_BYTES = 32 * 1024
    private const val MAX_BODY_BYTES = 16 * 1024

    enum class Result(val accepted: Boolean) {
        STORED(true),
        DUPLICATE(true),
        NOT_ADDRESSED(true),
        REJECTED_MALFORMED(false),
        REJECTED_INJECTION(false),
        REJECTED_TOO_LARGE(false),
    }

    /** Validate a raw datagram into a routing envelope, mirroring the Go relay:
     *  size-capped, every routing field present, injection-like fields rejected. */
    fun parse(data: ByteArray): Pair<ChatStore.Wire?, Result> {
        if (data.size > MAX_DATAGRAM_BYTES) return null to Result.REJECTED_TOO_LARGE
        val o = try {
            JSONObject(String(data, Charsets.UTF_8))
        } catch (_: Exception) {
            return null to Result.REJECTED_MALFORMED
        }
        val id = o.optString("id")
        val to = o.optString("to")
        val from = o.optString("from")
        val ts = o.optString("ts")
        val body = o.optString("body")
        if (id.isEmpty() || to.isEmpty() || from.isEmpty() || body.isEmpty()) {
            return null to Result.REJECTED_MALFORMED
        }
        if (body.length > MAX_BODY_BYTES) return null to Result.REJECTED_TOO_LARGE
        if (AdaptiveDefense.looksLikeInjection(id) ||
            AdaptiveDefense.looksLikeInjection(to) ||
            AdaptiveDefense.looksLikeInjection(from)
        ) {
            return null to Result.REJECTED_INJECTION
        }
        return ChatStore.Wire(id, to, from, ts, body) to Result.STORED
    }

    /** True if an inbound message is for us. Empty myFingerprint accepts all
     *  (single-device / pre-identity), mirroring go/chat.AddressedTo. */
    fun addressedTo(wire: ChatStore.Wire, myFingerprint: String): Boolean =
        myFingerprint.isEmpty() || wire.to == myFingerprint

    /**
     * Full intake of one raw inbound datagram. Hostile input is recorded into
     * [defense] (injection => SQL_INJECTION, malformed/oversize => MALFORMED),
     * exactly as the Go relay scores it, so the more attacks evolIA absorbs over
     * Bluetooth the harder it defends. Valid, addressed, non-duplicate messages
     * are appended to the inbox and their id added to [seen].
     */
    fun accept(
        data: ByteArray,
        myFingerprint: String,
        store: ChatStore,
        defense: AdaptiveDefense,
        seen: MutableSet<String>,
    ): Result {
        val (wire, result) = parse(data)
        if (wire == null) {
            when (result) {
                Result.REJECTED_INJECTION -> defense.record(AttackKind.SQL_INJECTION)
                else -> defense.record(AttackKind.MALFORMED)
            }
            return result
        }
        if (!addressedTo(wire, myFingerprint)) return Result.NOT_ADDRESSED
        if (!seen.add(wire.id)) return Result.DUPLICATE
        store.appendInbox(wire)
        return Result.STORED
    }
}
