package com.evolia.app.security

import com.evolia.app.core.EvoliaPaths
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Owner auth configuration, mirroring evolia-auth's AuthConfig. */
data class AuthConfig(
    val owner: Boolean,
    val pinHash: String,
    val passwordHash: String,
    val biometricEnabled: Boolean,
    val created: String,
    val lastAuth: String?,
)

/**
 * On-disk owner authentication — the Android port of evolia-auth's storage and
 * verification. Credentials are Argon2id PHC hashes in `.evolia_auth.json`
 * (app-private storage, already owner-only, so no chmod needed). The Rust TTY
 * prompts are replaced by the UI gate in MainActivity, but the layered scheme
 * (PIN, password, optional biometric) and the config format are identical.
 */
class AuthStore(private val paths: EvoliaPaths) {

    fun isConfigured(): Boolean = paths.authConfig.exists()

    fun load(): AuthConfig? {
        if (!paths.authConfig.exists()) return null
        return try {
            val j = JSONObject(paths.authConfig.readText())
            AuthConfig(
                owner = j.optBoolean("owner", true),
                pinHash = j.getString("pin_hash"),
                passwordHash = j.getString("password_hash"),
                biometricEnabled = j.optBoolean("biometric_enabled", false),
                created = j.optString("created"),
                lastAuth = if (j.isNull("last_auth")) null else j.optString("last_auth"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun setup(pin: String, password: String, biometricEnabled: Boolean): AuthConfig {
        val cfg = AuthConfig(
            owner = true,
            pinHash = Argon2Phc.hashSecret(pin),
            passwordHash = Argon2Phc.hashSecret(password),
            biometricEnabled = biometricEnabled,
            created = nowIso(),
            lastAuth = null,
        )
        save(cfg)
        return cfg
    }

    fun verifyPin(pin: String): Boolean {
        val cfg = load() ?: return false
        return Argon2Phc.verifySecret(pin, cfg.pinHash)
    }

    fun verifyPassword(password: String): Boolean {
        val cfg = load() ?: return false
        return Argon2Phc.verifySecret(password, cfg.passwordHash)
    }

    fun markAuthed() {
        val cfg = load() ?: return
        save(cfg.copy(lastAuth = nowIso()))
    }

    private fun save(cfg: AuthConfig) {
        paths.home.mkdirs()
        val j = JSONObject()
            .put("owner", cfg.owner)
            .put("pin_hash", cfg.pinHash)
            .put("password_hash", cfg.passwordHash)
            .put("biometric_enabled", cfg.biometricEnabled)
            .put("created", cfg.created)
            .put("last_auth", cfg.lastAuth ?: JSONObject.NULL)
        paths.authConfig.writeText(j.toString())
    }

    private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

    companion object {
        fun isValidPin(pin: String): Boolean = pin.length in 4..6 && pin.all { it.isDigit() }

        fun isValidPassword(password: String): Boolean = password.length >= 8
    }
}
