package com.evolia.app.security

import com.evolia.app.core.EvoliaPaths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Pure-JVM tests for the owner auth store (Argon2id-backed). */
class AuthStoreTest {

    private fun store() = AuthStore(EvoliaPaths(Files.createTempDirectory("evolia-auth").toFile()))

    @Test
    fun setupThenVerify() {
        val store = store()
        assertFalse(store.isConfigured())
        assertNull(store.load())

        store.setup("1234", "password123", biometricEnabled = false)
        assertTrue(store.isConfigured())
        assertTrue(store.verifyPin("1234"))
        assertFalse(store.verifyPin("0000"))
        assertTrue(store.verifyPassword("password123"))
        assertFalse(store.verifyPassword("wrong-pass"))
    }

    @Test
    fun validationRules() {
        assertTrue(AuthStore.isValidPin("1234"))
        assertTrue(AuthStore.isValidPin("123456"))
        assertFalse(AuthStore.isValidPin("123"))
        assertFalse(AuthStore.isValidPin("1234567"))
        assertFalse(AuthStore.isValidPin("12ab"))
        assertTrue(AuthStore.isValidPassword("abcdefgh"))
        assertFalse(AuthStore.isValidPassword("short"))
    }
}
