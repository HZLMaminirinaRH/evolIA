package com.evolia.app.chain

import android.content.Context
import com.evolia.app.core.EvoliaPaths
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import java.io.File

/**
 * The device-held Ethereum signing identity. The private key is generated once
 * (web3j Keys.createEcKeyPair) and stored encrypted at rest via the Android
 * Keystore (see KeystoreCrypto) — never in plaintext, never exported. The
 * address is mirrored to a plain file so the UI can show it for funding.
 */
class EvoliaWallet(context: Context, private val paths: EvoliaPaths) {

    private val keyFile = File(paths.home, "evolia_wallet.key")
    val credentials: Credentials = loadOrCreate()
    val address: String get() = credentials.address

    init {
        paths.home.mkdirs()
        paths.walletAddress.writeText(address)
    }

    private fun loadOrCreate(): Credentials {
        if (keyFile.exists()) {
            val hex = String(KeystoreCrypto.decrypt(keyFile.readBytes())).trim()
            return Credentials.create(hex)
        }
        val pair: ECKeyPair = Keys.createEcKeyPair()
        paths.home.mkdirs()
        keyFile.writeBytes(KeystoreCrypto.encrypt(pair.privateKey.toString(16).toByteArray()))
        return Credentials.create(pair)
    }
}
