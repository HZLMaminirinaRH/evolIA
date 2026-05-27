package com.evolia.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests mirroring evolia-auth's Rust hash tests. */
class Argon2PhcTest {

    @Test
    fun hashRoundtrip() {
        val phc = Argon2Phc.hashSecret("hunter2!!")
        assertTrue(phc.startsWith("\$argon2id\$v=19\$"))
        assertTrue(Argon2Phc.verifySecret("hunter2!!", phc))
        assertFalse(Argon2Phc.verifySecret("wrong", phc))
    }

    @Test
    fun distinctSalts() {
        // Same secret must produce different PHC strings (random salt).
        assertNotEquals(Argon2Phc.hashSecret("same"), Argon2Phc.hashSecret("same"))
    }

    @Test
    fun rejectsMalformedPhc() {
        assertFalse(Argon2Phc.verifySecret("secret", "not-a-phc-string"))
    }
}
