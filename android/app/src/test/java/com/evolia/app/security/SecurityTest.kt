package com.evolia.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests mirroring evolia-security's Rust test suite. */
class SecurityTest {

    @Test
    fun encryptDecryptRoundtrip() {
        val s = Security("dev-1", "password123")
        val ct = s.encrypt("hello world")
        assertEquals("hello world", s.decrypt(ct))
    }

    @Test
    fun wrongKeyCannotDecrypt() {
        val a = Security("dev-1", "pw-a")
        val b = Security("dev-1", "pw-b")
        val ct = a.encrypt("secret")
        var failed = false
        try {
            b.decrypt(ct)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("a different key must not decrypt", failed)
    }

    @Test
    fun signatures() {
        val s = Security("dev-1", "password123")
        val sig = s.sign("payload")
        assertTrue(s.verify("payload", sig))
        assertFalse(s.verify("tampered", sig))
        assertFalse(s.verify("payload", "not-hex"))
    }

    @Test
    fun sessionTokenValidThenDeviceBound() {
        val s = Security("dev-1", "password123")
        val tok = s.generateSessionToken("owner", 3600)
        assertEquals("owner", s.validateSessionToken(tok.token))

        val other = Security("dev-2", "password123")
        assertNull(other.validateSessionToken(tok.token))
    }

    @Test
    fun expiredTokenRejected() {
        val s = Security("dev-1", "password123")
        val tok = s.generateSessionToken("owner", -1)
        assertNull(s.validateSessionToken(tok.token))
    }

    @Test
    fun fleetKeyIsDeterministicPerPassword() {
        val a = Security.deriveFleetKey("password123")
        assertEquals("same password => same fleet key", a, Security.deriveFleetKey("password123"))
        assertNotEquals("different password => different fleet key", a, Security.deriveFleetKey("other-pass"))
        assertEquals("32-byte key in hex", 64, a.length)
    }
}
