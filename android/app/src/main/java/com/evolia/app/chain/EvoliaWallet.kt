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
 *
 * `credentials` is lazy on purpose: generating an EC key pair pulls in web3j /
 * BouncyCastle, which must not run unless on-chain anchoring is actually
 * configured. In the default LOCAL mode the wallet is never touched.
 */
class EvoliaWallet(context: Context, private val paths: EvoliaPaths) {

    private val keyFile = File(paths.home, "evolia_wallet.key")
    val credentials: Credentials by lazy { loadOrCreate() }
    val address: String get() = credentials.address

    private fun loadOrCreate(): Credentials {
        paths.home.mkdirs()
        val creds = if (keyFile.exists()) {
            val hex = String(KeystoreCrypto.decrypt(keyFile.readBytes())).trim()
            Credentials.create(hex)
        } else {
            val pair: ECKeyPair = Keys.createEcKeyPair()
            keyFile.writeBytes(KeystoreCrypto.encrypt(pair.privateKey.toString(16).toByteArray()))
            Credentials.create(pair)
        }
        paths.walletAddress.writeText(creds.address)
        return creds
    }
}
