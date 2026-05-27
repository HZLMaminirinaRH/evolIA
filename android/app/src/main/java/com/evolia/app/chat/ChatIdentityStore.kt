package com.evolia.app.chat

import com.evolia.app.chain.KeystoreCrypto
import com.evolia.app.core.EvoliaPaths

/**
 * Persists the chat identity seed encrypted at rest via the Android Keystore
 * (same AES-256/GCM envelope as the wallet key) — the seed never lives in
 * plaintext on disk and is useless without this device. Generated once on first
 * use, like EvoliaWallet.
 */
class ChatIdentityStore(private val paths: EvoliaPaths) {

    private val keyFile get() = paths.chatIdentityKey

    fun loadOrCreate(): ChatIdentity {
        paths.home.mkdirs()
        return if (keyFile.exists()) {
            ChatIdentity.fromSeedHex(String(KeystoreCrypto.decrypt(keyFile.readBytes())).trim())
        } else {
            val identity = ChatIdentity.generate()
            keyFile.writeBytes(KeystoreCrypto.encrypt(identity.seedHex().toByteArray()))
            identity
        }
    }
}
