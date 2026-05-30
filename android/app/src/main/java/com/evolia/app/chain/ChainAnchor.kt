package com.evolia.app.chain

import android.content.Context
import com.evolia.app.core.EvoliaAnchor
import com.evolia.app.core.EvoliaPaths
import com.evolia.app.ui.TransferNotify
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import java.math.BigInteger
import java.time.Instant

/**
 * On-chain anchoring (Kotlin/web3j port of ganache_db.py's real path).
 *
 * Mirrors sync_once: read total_v, then anchor it on EvoliaCore.anchorValue
 * (value scaled x100, like Python) and log status "success" with tx_hash/block.
 * Every off-nominal case degrades to a logged LOCAL entry exactly as Python
 * does — no value (v<=0), no RPC configured, or an unreachable node — and only a
 * thrown transaction error yields "failed". The contract is deployed once from
 * the bundled bytecode (assets/EvoliaCore.json) and its address cached in
 * evolia_deployment.json (same format/file the Python side uses).
 *
 * Config lives in evolia_chain_config.json: {"rpc_url": "...", "chain_id": N}.
 * Absent or empty rpc_url keeps everything in LOCAL mode.
 */
class ChainAnchor(context: Context, private val paths: EvoliaPaths) {

    private val app = context.applicationContext
    private val wallet = EvoliaWallet(app, paths)

    private data class Config(val rpcUrl: String, val chainId: Long, val sensoryType: String)

    fun syncOnce(): JSONObject {
        val v = EvoliaAnchor.readTotalV(paths)
        if (v <= 0.0) return logLocal(v, "no value to anchor")
        val cfg = readConfig() ?: return logLocal(v, "no RPC configured")

        val web3 = Web3j.build(HttpService(cfg.rpcUrl))
        try {
            val reachable = try {
                web3.web3ClientVersion().send(); true
            } catch (_: Exception) {
                false
            }
            if (!reachable) return logLocal(v, "node unreachable")
            return try {
                anchorOnChain(web3, cfg, v).also { EvoliaAnchor.logSync(paths, it) }
            } catch (e: Exception) {
                logFailed(v, e.message ?: e.toString())
            }
        } finally {
            web3.shutdown()
        }
    }

    /** This device's on-chain signing address (creates the wallet on first use). */
    fun myAddress(): String {
        ensureSecurityProvider()
        return wallet.address
    }

    /**
     * Move proven BTC-e (centi) from this device to `toAddress` via
     * EvoliaCore.transfer. The chain orders the transaction and checks the
     * balance, so a transfer can never overspend — the structural
     * anti-double-spend. Mirrors syncOnce's status discipline: LOCAL when nothing
     * is settled (no RPC / node unreachable / contract not yet deployed), "failed"
     * only on a thrown transaction error, otherwise "success" with tx_hash/block.
     * Records the result to the dedicated transfer history (never the sync log, so
     * anchored totals stay clean) and refreshes the cached balance. Must run off
     * the main thread (network call).
     */
    fun transfer(toAddress: String, amountCenti: Long): JSONObject {
        if (amountCenti <= 0L) return logTransferLocal(toAddress, amountCenti, "amount must be positive")
        val cfg = readConfig() ?: return logTransferLocal(toAddress, amountCenti, "no RPC configured")
        val contractAddress = deployedAddress()
            ?: return logTransferLocal(toAddress, amountCenti, "contract not yet deployed — wait for the first value anchor cycle")

        val web3 = Web3j.build(HttpService(cfg.rpcUrl))
        try {
            val reachable = try {
                web3.web3ClientVersion().send(); true
            } catch (_: Exception) {
                false
            }
            if (!reachable) return logTransferLocal(toAddress, amountCenti, "node unreachable")
            ensureSecurityProvider()
            return try {
                logTransfer(transferOnChain(web3, cfg, contractAddress, toAddress, amountCenti))
            } catch (e: Exception) {
                logTransfer(transferEntry(toAddress, amountCenti, "failed", mapOf("error" to (e.message ?: e.toString()))))
            } finally {
                runCatching { refreshBalance() }
            }
        } finally {
            web3.shutdown()
        }
    }

    /**
     * Refresh and cache this node's on-chain proven balance (centi-BTC-e) so the
     * dashboard can show what is transferable without a main-thread network call.
     * Self-contained and best-effort: silently keeps the previous cached value
     * when no RPC is configured or the node/contract is unreachable.
     */
    fun refreshBalance() {
        val cfg = readConfig() ?: return
        val contractAddress = deployedAddress() ?: return
        val web3 = Web3j.build(HttpService(cfg.rpcUrl))
        try {
            ensureSecurityProvider()
            val centi = queryBalance(web3, contractAddress, wallet.address) ?: return
            // Compare with the previous cached value so an INCREASE since the last
            // poll is surfaced as a receiver "accusé de réception" — an on-chain
            // transfer landed in our account. Decreases (we just sent) are silent
            // here; the sender side posts its own "accusé d'envoi" via doTransfer.
            val prevCenti = readCachedBalanceCenti()
            paths.home.mkdirs()
            paths.onchainBalance.writeText(
                JSONObject()
                    .put("balance_centi", centi.toLong())
                    .put("address", wallet.address)
                    .put("timestamp", Instant.now().toString())
                    .toString(),
            )
            if (prevCenti != null && centi.toLong() > prevCenti) {
                val deltaBtce = (centi.toLong() - prevCenti) / 100.0
                TransferNotify.notifyReceived(app, deltaBtce, "on-chain", settled = true)
            }
        } catch (_: Exception) {
            // Best-effort cache — a transient failure just keeps the last value.
        } finally {
            web3.shutdown()
        }
    }

    /** Read the previously-cached centi-balance, or null if no prior cache. Used
     *  to detect an INCREASE between refresh ticks (= incoming transfer). */
    private fun readCachedBalanceCenti(): Long? {
        val f = paths.onchainBalance
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText()).optLong("balance_centi", -1L).takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryBalance(web3: Web3j, contractAddress: String, owner: String): BigInteger? {
        val function = Function(
            "provenOf",
            listOf<Type<*>>(Address(owner)),
            listOf<TypeReference<*>>(object : TypeReference<Uint256>() {}),
        )
        val response = web3.ethCall(
            Transaction.createEthCallTransaction(owner, contractAddress, FunctionEncoder.encode(function)),
            DefaultBlockParameterName.LATEST,
        ).send()
        if (response.hasError()) return null
        val decoded = FunctionReturnDecoder.decode(response.value, function.outputParameters)
        return (decoded.firstOrNull() as? Uint256)?.value
    }

    private fun transferOnChain(
        web3: Web3j,
        cfg: Config,
        contractAddress: String,
        toAddress: String,
        amountCenti: Long,
    ): JSONObject {
        val txManager = RawTransactionManager(web3, wallet.credentials, cfg.chainId)
        val function = Function(
            "transfer",
            listOf<Type<*>>(Address(toAddress), Uint256(BigInteger.valueOf(amountCenti))),
            emptyList<TypeReference<*>>(),
        )
        val ethSend = txManager.sendTransaction(
            DefaultGasProvider.GAS_PRICE,
            DefaultGasProvider.GAS_LIMIT,
            contractAddress,
            FunctionEncoder.encode(function),
            BigInteger.ZERO,
        )
        if (ethSend.hasError()) throw RuntimeException("transfer: ${ethSend.error.message}")
        val receipt = receiptProcessor(web3).waitForTransactionReceipt(ethSend.transactionHash)
        return transferEntry(
            toAddress,
            amountCenti,
            "success",
            mapOf("tx_hash" to receipt.transactionHash, "block" to receipt.blockNumber.toLong()),
        )
    }

    private fun transferEntry(
        to: String,
        amountCenti: Long,
        status: String,
        extra: Map<String, Any?> = emptyMap(),
    ): JSONObject {
        val entry = JSONObject()
            .put("timestamp", Instant.now().toString())
            .put("status", status)
            .put("mode", "transfer")
            .put("to", to)
            .put("amount_centi", amountCenti)
            .put("amount_btce", amountCenti / 100.0)
        for ((key, value) in extra) if (value != null) entry.put(key, value)
        return entry
    }

    private fun logTransfer(entry: JSONObject): JSONObject {
        paths.home.mkdirs()
        paths.transferHistory.appendText(entry.toString() + "\n")
        return entry
    }

    private fun logTransferLocal(to: String, amountCenti: Long, note: String): JSONObject =
        logTransfer(transferEntry(to, amountCenti, "local", mapOf("note" to note)))

    // web3j's secp256k1 needs the full BouncyCastle provider; the service installs
    // it at startup, but a transfer/receive can be initiated from the UI before the
    // service runs, so make ChainAnchor self-sufficient (idempotent).
    private fun ensureSecurityProvider() {
        if (java.security.Security.getProvider("BC")?.javaClass == BouncyCastleProvider::class.java) return
        java.security.Security.removeProvider("BC")
        java.security.Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    private fun anchorOnChain(web3: Web3j, cfg: Config, v: Double): JSONObject {
        val txManager = RawTransactionManager(web3, wallet.credentials, cfg.chainId)
        val contractAddress = deployedAddress() ?: deploy(web3, txManager)

        val function = Function(
            "anchorValue",
            listOf<Type<*>>(
                Uint256(BigInteger.valueOf((v * 100).toLong())),
                Utf8String(cfg.sensoryType),
            ),
            emptyList<TypeReference<*>>(),
        )
        val ethSend = txManager.sendTransaction(
            DefaultGasProvider.GAS_PRICE,
            DefaultGasProvider.GAS_LIMIT,
            contractAddress,
            FunctionEncoder.encode(function),
            BigInteger.ZERO,
        )
        if (ethSend.hasError()) throw RuntimeException("anchorValue: ${ethSend.error.message}")
        val receipt = receiptProcessor(web3).waitForTransactionReceipt(ethSend.transactionHash)
        return EvoliaAnchor.buildLogEntry(
            v,
            "success",
            mapOf("tx_hash" to receipt.transactionHash, "block" to receipt.blockNumber.toLong()),
        )
    }

    private fun deploy(web3: Web3j, txManager: RawTransactionManager): String {
        val ethSend = txManager.sendTransaction(
            DefaultGasProvider.GAS_PRICE,
            DefaultGasProvider.GAS_LIMIT,
            null,
            loadBytecode(),
            BigInteger.ZERO,
        )
        if (ethSend.hasError()) throw RuntimeException("deploy: ${ethSend.error.message}")
        val receipt = receiptProcessor(web3).waitForTransactionReceipt(ethSend.transactionHash)
        val address = receipt.contractAddress ?: throw RuntimeException("deploy: no contract address")
        paths.home.mkdirs()
        paths.deployment.writeText(JSONObject().put("contract_address", address).toString())
        return address
    }

    private fun receiptProcessor(web3: Web3j) = PollingTransactionReceiptProcessor(web3, 1500L, 60)

    private fun deployedAddress(): String? {
        if (!paths.deployment.exists()) return null
        return try {
            JSONObject(paths.deployment.readText()).optString("contract_address").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun readConfig(): Config? {
        if (!paths.chainConfig.exists()) return null
        return try {
            val json = JSONObject(paths.chainConfig.readText())
            val rpc = json.optString("rpc_url").trim()
            if (rpc.isBlank()) return null
            Config(
                rpcUrl = rpc,
                chainId = json.optLong("chain_id", 1337L),
                sensoryType = json.optString("sensory_type", "Physique+Ondes"),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun loadBytecode(): String =
        app.assets.open("EvoliaCore.json").bufferedReader().use {
            JSONObject(it.readText()).getString("bytecode")
        }

    private fun logLocal(v: Double, note: String): JSONObject =
        EvoliaAnchor.buildLogEntry(v, "local", mapOf("note" to note)).also { EvoliaAnchor.logSync(paths, it) }

    private fun logFailed(v: Double, error: String): JSONObject =
        EvoliaAnchor.buildLogEntry(v, "failed", mapOf("error" to error)).also { EvoliaAnchor.logSync(paths, it) }
}
