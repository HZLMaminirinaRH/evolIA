package com.evolia.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

/** Pure-JVM tests for the end-to-end chat crypto core. */
class ChatIdentityTest {

    @Test
    fun sealOpenRoundtrip() {
        val alice = ChatIdentity.generate()
        val bob = ChatIdentity.generate()
        val envelope = alice.seal(bob.publicBundleHex(), "salama, Bob".toByteArray())
        val opened = bob.open(envelope)
        assertNotNull("Bob must decrypt Alice's message", opened)
        assertEquals("salama, Bob", String(opened!!.plaintext))
        assertEquals("sender identifies as Alice", alice.fingerprint(), opened.senderFingerprint)
    }

    @Test
    fun thirdPartyCannotOpen() {
        val alice = ChatIdentity.generate()
        val bob = ChatIdentity.generate()
        val carol = ChatIdentity.generate()
        val envelope = alice.seal(bob.publicBundleHex(), "secret".toByteArray())
        assertNull("only the recipient can decrypt", carol.open(envelope))
    }

    @Test
    fun tamperedCiphertextRejected() {
        val alice = ChatIdentity.generate()
        val bob = ChatIdentity.generate()
        val envelope = alice.seal(bob.publicBundleHex(), "integrity".toByteArray())
        // Deterministically flip the last nibble of the ciphertext field.
        val o = JSONObject(envelope)
        val ct = o.getString("ct")
        o.put("ct", ct.dropLast(1) + if (ct.last() == '0') '1' else '0')
        assertNull("a tampered envelope must not open", bob.open(o.toString()))
    }

    @Test
    fun forgedSenderRejected() {
        val alice = ChatIdentity.generate()
        val bob = ChatIdentity.generate()
        val mallory = ChatIdentity.generate()
        val envelope = alice.seal(bob.publicBundleHex(), "who am i".toByteArray())
        // Mallory swaps the "from" bundle to her own; the signature no longer matches.
        val spoofed = envelope.replace(alice.publicBundleHex(), mallory.publicBundleHex())
        assertNull("a swapped sender bundle fails signature verification", bob.open(spoofed))
    }

    @Test
    fun challengeResponseProvesOwnership() {
        val bob = ChatIdentity.generate()
        val alice = ChatIdentity.generate()
        val challenge = ChatIdentity.newChallenge()
        val response = bob.respondChallenge(challenge)
        assertTrue("Bob proves he owns his key", ChatIdentity.verifyChallenge(bob.publicBundleHex(), challenge, response))
        assertFalse("Alice's bundle cannot validate Bob's response", ChatIdentity.verifyChallenge(alice.publicBundleHex(), challenge, response))
        assertFalse("a tampered response fails", ChatIdentity.verifyChallenge(bob.publicBundleHex(), ChatIdentity.newChallenge(), response))
    }

    @Test
    fun deterministicFromSeed() {
        val original = ChatIdentity.generate()
        val restored = ChatIdentity.fromSeedHex(original.seedHex())
        assertEquals("same seed => same public identity", original.publicBundleHex(), restored.publicBundleHex())
        assertEquals("same seed => same fingerprint", original.fingerprint(), restored.fingerprint())
    }

    @Test
    fun bundleShapeAndFingerprint() {
        val id = ChatIdentity.generate()
        assertEquals("64-byte bundle in hex", 128, id.publicBundleHex().length)
        assertEquals("derivable fingerprint", id.fingerprint(), ChatIdentity.fingerprintFromBundle(id.publicBundleHex()))
    }

    @Test
    fun malformedEnvelopeReturnsNull() {
        val bob = ChatIdentity.generate()
        assertNull(bob.open("not json"))
        assertNull(bob.open("{}"))
        assertNull(bob.open("{\"from\":\"zz\",\"nonce\":\"00\",\"ct\":\"00\",\"sig\":\"00\"}"))
    }
}
