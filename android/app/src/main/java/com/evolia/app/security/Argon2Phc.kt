package com.evolia.app.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id password hashing in the PHC string format — a Kotlin port of
 * evolia-auth's hash_secret/verify_secret. Uses the same defaults as the Rust
 * `argon2` crate (Argon2id, v=19, m=19456, t=2, p=1, 32-byte output, per-secret
 * 16-byte random salt), so the `$argon2id$...` strings interoperate.
 */
object Argon2Phc {

    private const val M_COST = 19456
    private const val T_COST = 2
    private const val P_COST = 1
    private const val OUT_LEN = 32
    private const val SALT_LEN = 16

    private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun hashSecret(secret: String): String {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val hash = raw(secret, salt, M_COST, T_COST, P_COST, OUT_LEN)
        return "\$argon2id\$v=19\$m=$M_COST,t=$T_COST,p=$P_COST\$" +
            encoder.encodeToString(salt) + "\$" + encoder.encodeToString(hash)
    }

    fun verifySecret(secret: String, phc: String): Boolean {
        val parsed = parse(phc) ?: return false
        val computed = raw(secret, parsed.salt, parsed.m, parsed.t, parsed.p, parsed.hash.size)
        return MessageDigest.isEqual(computed, parsed.hash)
    }

    private class Parsed(val m: Int, val t: Int, val p: Int, val salt: ByteArray, val hash: ByteArray)

    private fun parse(phc: String): Parsed? = try {
        // $argon2id$v=19$m=..,t=..,p=..$<salt>$<hash>
        val parts = phc.split("$")
        if (parts.size != 6 || parts[1] != "argon2id") {
            null
        } else {
            val params = parts[3].split(",").associate {
                val kv = it.split("=")
                kv[0] to kv[1].toInt()
            }
            Parsed(
                m = params.getValue("m"),
                t = params.getValue("t"),
                p = params.getValue("p"),
                salt = decoder.decode(parts[4]),
                hash = decoder.decode(parts[5]),
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun raw(secret: String, salt: ByteArray, m: Int, t: Int, p: Int, outLen: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(t)
            .withMemoryAsKB(m)
            .withParallelism(p)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator().apply { init(params) }
        val out = ByteArray(outLen)
        generator.generateBytes(secret.toByteArray(Charsets.UTF_8), out)
        return out
    }
}
