package com.evolia.app.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic core — a Kotlin port of evolia-security:
 *  - master key = Argon2id(password, salt = SHA-256(device_id)[..16]) -> 32 bytes
 *  - ChaCha20-Poly1305 AEAD: base64(nonce[12] || ciphertext+tag)
 *  - session tokens: encrypted JSON, device-bound, with an expiry
 *  - HMAC-SHA256 detached signatures, verified in constant time
 *
 * Self-contained (no Android APIs) so it is unit-tested on the JVM.
 */
class Security(val deviceId: String, masterPassword: String) {

    data class SessionToken(val token: String, val tokenId: String, val expiresAt: String)

    private val masterKey: ByteArray = deriveKey(deviceId, masterPassword)

    fun encrypt(plaintext: String): String {
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = ChaCha20Poly1305().apply {
            init(true, AEADParameters(KeyParameter(masterKey), TAG_BITS, nonce))
        }
        val input = plaintext.toByteArray(Charsets.UTF_8)
        val out = ByteArray(cipher.getOutputSize(input.size))
        val written = cipher.processBytes(input, 0, input.size, out, 0)
        cipher.doFinal(out, written)
        return Base64.getEncoder().encodeToString(nonce + out)
    }

    fun decrypt(token: String): String {
        val blob = Base64.getDecoder().decode(token)
        require(blob.size > NONCE_LEN) { "ciphertext too short" }
        val nonce = blob.copyOfRange(0, NONCE_LEN)
        val ciphertext = blob.copyOfRange(NONCE_LEN, blob.size)
        val cipher = ChaCha20Poly1305().apply {
            init(false, AEADParameters(KeyParameter(masterKey), TAG_BITS, nonce))
        }
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        val written = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        val total = written + cipher.doFinal(out, written)
        return String(out, 0, total, Charsets.UTF_8)
    }

    fun generateSessionToken(userId: String, durationSecs: Long): SessionToken {
        val created = OffsetDateTime.now(ZoneOffset.UTC)
        val expires = created.plusSeconds(durationSecs)
        val createdStr = created.toString()
        val expiresStr = expires.toString()

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(userId.toByteArray(Charsets.UTF_8))
        digest.update(createdStr.toByteArray(Charsets.UTF_8))
        digest.update(deviceId.toByteArray(Charsets.UTF_8))
        val tokenId = digest.digest().toHex()

        val data = JSONObject()
            .put("token_id", tokenId)
            .put("user_id", userId)
            .put("created_at", createdStr)
            .put("expires_at", expiresStr)
            .put("device_id", deviceId)
        return SessionToken(encrypt(data.toString()), tokenId, expiresStr)
    }

    /** User id if the token decrypts, matches this device, and has not expired. */
    fun validateSessionToken(token: String): String? {
        val json = try {
            decrypt(token)
        } catch (_: Exception) {
            return null
        }
        val data = JSONObject(json)
        if (data.optString("device_id") != deviceId) return null
        val expires = try {
            OffsetDateTime.parse(data.optString("expires_at"))
        } catch (_: Exception) {
            return null
        }
        if (OffsetDateTime.now(ZoneOffset.UTC).isAfter(expires)) return null
        return data.optString("user_id")
    }

    fun sign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(masterKey, "HmacSHA256"))
        }
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun verify(data: String, signatureHex: String): Boolean {
        val expected = hexToBytes(signatureHex) ?: return false
        val actual = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(masterKey, "HmacSHA256"))
        }.doFinal(data.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(expected, actual)
    }

    private fun deriveKey(deviceId: String, password: String): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 16)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(2)
            .withMemoryAsKB(19456)
            .withParallelism(1)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator().apply { init(params) }
        val key = ByteArray(32)
        generator.generateBytes(password.toByteArray(Charsets.UTF_8), key)
        return key
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        return try {
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val NONCE_LEN = 12
        const val TAG_BITS = 128
    }
}
