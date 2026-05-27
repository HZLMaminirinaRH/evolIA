package com.evolia.app.chat

import com.evolia.app.core.EvoliaPaths
import com.evolia.app.security.AdaptiveDefense
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Pure-JVM tests for the transport-agnostic receive pipeline: validation,
 * routing, dedup, and the adaptive-defense scoring of hostile input — the same
 * guarantees the Go relay gives, now reachable over Bluetooth.
 */
class ChatIntakeTest {

    private val myFp = "0011223344556677"

    private class Env {
        val store = ChatStore(EvoliaPaths(Files.createTempDirectory("evolia-intake").toFile()))
        val defense = AdaptiveDefense()
        val seen = mutableSetOf<String>()
    }

    private fun wire(
        id: String = "id1",
        to: String = "0011223344556677",
        from: String = "ffffffffffffffff",
        body: String = "sealed-body",
    ) = ChatStore.Wire(id, to, from, "ts", body).toJson().toByteArray(Charsets.UTF_8)

    @Test
    fun validAddressedIsStored() {
        val e = Env()
        assertEquals(ChatIntake.Result.STORED, ChatIntake.accept(wire(), myFp, e.store, e.defense, e.seen))
        assertEquals(1, e.store.readInbox().size)
        assertTrue(e.seen.contains("id1"))
        assertEquals("clean input does not feed the defense", 0.0, e.defense.level(), 1e-9)
    }

    @Test
    fun duplicateIdStoredOnce() {
        val e = Env()
        ChatIntake.accept(wire(), myFp, e.store, e.defense, e.seen)
        assertEquals(ChatIntake.Result.DUPLICATE, ChatIntake.accept(wire(), myFp, e.store, e.defense, e.seen))
        assertEquals(1, e.store.readInbox().size)
    }

    @Test
    fun notAddressedIsDropped() {
        val e = Env()
        val r = ChatIntake.accept(wire(to = "someoneelsefingerprint"), myFp, e.store, e.defense, e.seen)
        assertEquals(ChatIntake.Result.NOT_ADDRESSED, r)
        assertTrue(e.store.readInbox().isEmpty())
    }

    @Test
    fun emptyFingerprintAcceptsAll() {
        val e = Env()
        val r = ChatIntake.accept(wire(to = "anything"), "", e.store, e.defense, e.seen)
        assertEquals(ChatIntake.Result.STORED, r)
    }

    @Test
    fun injectionFieldRejectedAndScored() {
        val e = Env()
        val r = ChatIntake.accept(wire(to = "abc' or 1=1"), myFp, e.store, e.defense, e.seen)
        assertEquals(ChatIntake.Result.REJECTED_INJECTION, r)
        assertTrue(e.store.readInbox().isEmpty())
        assertEquals("injection hardens the defense (SQL severity)", 1.0, e.defense.level(), 1e-9)
    }

    @Test
    fun malformedJsonRejectedAndScored() {
        val e = Env()
        val r = ChatIntake.accept("not json".toByteArray(), myFp, e.store, e.defense, e.seen)
        assertEquals(ChatIntake.Result.REJECTED_MALFORMED, r)
        assertEquals("malformed hardens the defense (lighter severity)", 0.3, e.defense.level(), 1e-9)
    }

    @Test
    fun missingRoutingFieldRejected() {
        val e = Env()
        val data = JSONObject().put("id", "x").put("to", myFp).put("body", "b").toString().toByteArray()
        assertEquals(ChatIntake.Result.REJECTED_MALFORMED, ChatIntake.accept(data, myFp, e.store, e.defense, e.seen))
    }

    @Test
    fun oversizedDatagramRejected() {
        val e = Env()
        val data = ByteArray(ChatIntake.MAX_DATAGRAM_BYTES + 1)
        assertEquals(ChatIntake.Result.REJECTED_TOO_LARGE, ChatIntake.accept(data, myFp, e.store, e.defense, e.seen))
    }
}
