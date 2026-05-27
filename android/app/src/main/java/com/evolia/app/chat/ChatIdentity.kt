package com.evolia.app.chat

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The device's end-to-end chat identity — distinct from the on-chain wallet so
 * messaging works even in LOCAL mode and never touches the financial key.
 *
 * A single 32-byte seed deterministically derives two keypairs:
 *  - Ed25519 (signing): authenticity + the challenge-response that proves a peer
 *    owns its advertised public key.
 *  - X25519 (ECDH): a static-static shared secret -> HKDF-SHA256 -> a 32-byte
 *    key feeding ChaCha20-Poly1305 (the same AEAD the security spine uses).
 *
 * The public identity is the 64-byte bundle (Ed25519 pub || X25519 pub), shared
 * as hex. Self-contained (no Android APIs) so it is unit-tested on the JVM;
 * persistence lives in ChatIdentityStore.
 *
 * Phase 1 uses long-term (static) ECDH keys — no forward secrecy yet; an
 * ephemeral/ratchet layer can slot in later without changing the envelope shape.
 */
class ChatIdentity private constructor(private val seed: ByteArray) {

    private val edPriv = Ed25519PrivateKeyParameters(seed, 0)
    private val edPub: Ed25519PublicKeyParameters = edPriv.generatePublicKey()

    private val xPriv = X25519PrivateKeyParameters(hkdf(seed, X_LABEL), 0)
    private val xPub: X25519PublicKeyParameters = xPriv.generatePublicKey()

    /** Ed25519 pub (32) || X25519 pub (32) as 128 hex chars — shareable identity. */
    fun publicBundleHex(): String = (edPub.encoded + xPub.encoded).toHex()

    /** Short, human-comparable id (first 8 bytes of SHA-256 of the signing key). */
    fun fingerprint(): String = fingerprintOf(edPub.encoded)

    fun seedHex(): String = seed.toHex()

    fun sign(data: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, edPriv)
        update(data, 0, data.size)
        generateSignature()
    }

    /** Sign a handshake challenge, proving ownership of this identity. */
    fun respondChallenge(challenge: ByteArray): String = sign(challenge).toHex()

    data class Opened(val senderFingerprint: String, val senderBundleHex: String, val plaintext: ByteArray)

    /** Encrypt+authenticate [plaintext] for the holder of [recipientBundleHex]. */
    fun seal(recipientBundleHex: String, plaintext: ByteArray): String {
        val recipientX = splitBundle(recipientBundleHex).second
        val key = sharedKey(recipientX)
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = ChaCha20Poly1305().apply {
            init(true, AEADParameters(KeyParameter(key), TAG_BITS, nonce))
        }
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val written = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, written)
        val sig = sign(nonce + out)
        return JSONObject()
            .put("v", 1)
            .put("from", publicBundleHex())
            .put("nonce", nonce.toHex())
            .put("ct", out.toHex())
            .put("sig", sig.toHex())
            .toString()
    }

    /** Verify the sender's signature, then decrypt. Null on any failure. */
    fun open(envelope: String): Opened? {
        val o = try {
            JSONObject(envelope)
        } catch (_: Exception) {
            return null
        }
        val fromBundle = o.optString("from")
        val nonce = hexToBytes(o.optString("nonce")) ?: return null
        val ct = hexToBytes(o.optString("ct")) ?: return null
        val sig = hexToBytes(o.optString("sig")) ?: return null
        if (nonce.size != NONCE_LEN || ct.size <= TAG_BITS / 8) return null
        val (senderEd, senderX) = try {
            splitBundle(fromBundle)
        } catch (_: Exception) {
            return null
        }
        if (!verifyEd(senderEd, nonce + ct, sig)) return null
        return try {
            val key = sharedKey(senderX)
            val cipher = ChaCha20Poly1305().apply {
                init(false, AEADParameters(KeyParameter(key), TAG_BITS, nonce))
            }
            val out = ByteArray(cipher.getOutputSize(ct.size))
            val written = cipher.processBytes(ct, 0, ct.size, out, 0)
            val total = written + cipher.doFinal(out, written)
            Opened(fingerprintOf(senderEd), fromBundle, out.copyOfRange(0, total))
        } catch (_: Exception) {
            null
        }
    }

    private fun sharedKey(peerXPub: ByteArray): ByteArray {
        val agreement = X25519Agreement().apply { init(xPriv) }
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerXPub, 0), shared, 0)
        return hkdf(shared, MSG_LABEL)
    }

    companion object {
        private const val NONCE_LEN = 12
        private const val TAG_BITS = 128
        private const val BUNDLE_LEN = 64
        private val X_LABEL = "evolia-chat-x25519".toByteArray()
        private val MSG_LABEL = "evolia-chat-msg-v1".toByteArray()

        fun generate(): ChatIdentity =
            ChatIdentity(ByteArray(32).also { SecureRandom().nextBytes(it) })

        fun fromSeedHex(hex: String): ChatIdentity =
            ChatIdentity(requireNotNull(hexToBytes(hex)) { "bad seed hex" })

        fun newChallenge(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

        /** True if [responseHex] is a valid signature of [challenge] by [bundleHex]'s owner. */
        fun verifyChallenge(bundleHex: String, challenge: ByteArray, responseHex: String): Boolean {
            val ed = try {
                splitBundle(bundleHex).first
            } catch (_: Exception) {
                return false
            }
            val sig = hexToBytes(responseHex) ?: return false
            return verifyEd(ed, challenge, sig)
        }

        fun fingerprintFromBundle(bundleHex: String): String? = try {
            fingerprintOf(splitBundle(bundleHex).first)
        } catch (_: Exception) {
            null
        }

        private fun splitBundle(hex: String): Pair<ByteArray, ByteArray> {
            val b = requireNotNull(hexToBytes(hex)) { "bad bundle hex" }
            require(b.size == BUNDLE_LEN) { "bundle must be $BUNDLE_LEN bytes" }
            return b.copyOfRange(0, 32) to b.copyOfRange(32, 64)
        }

        private fun verifyEd(edPub: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
            Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(edPub, 0))
                update(data, 0, data.size)
                verifySignature(sig)
            }
        } catch (_: Exception) {
            false
        }

        private fun fingerprintOf(edPub: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(edPub).copyOfRange(0, 8).toHex()

        private fun hkdf(ikm: ByteArray, info: ByteArray, len: Int = 32): ByteArray {
            val gen = HKDFBytesGenerator(SHA256Digest()).apply {
                init(HKDFParameters(ikm, null, info))
            }
            return ByteArray(len).also { gen.generateBytes(it, 0, len) }
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun hexToBytes(s: String): ByteArray? {
            if (s.isEmpty() || s.length % 2 != 0) return null
            return try {
                ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
