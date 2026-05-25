package com.evolia.app.chain

import android.content.Context
import com.evolia.app.core.EvoliaAnchor
import com.evolia.app.core.EvoliaPaths
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import java.math.BigInteger

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
