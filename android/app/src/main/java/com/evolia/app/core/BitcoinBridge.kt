package com.evolia.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Bitcoin bridge — Kotlin port of evolia_bitcoin.py. Converts accumulated value
 * (BTC-e) into satoshis and persists wallet/conversion state to the same files
 * (evolia_bitcoin_wallet.json / evolia_btc_conversion_history.json) the Python
 * service and the dashboard use. The conversion math is pure; the real
 * BIP44/network parts (bitcoinlib) have no analog here, so has_bitcoinlib is
 * always false — the rest works, exactly like the degraded Python path.
 */
class BitcoinBridge(private val paths: EvoliaPaths) {

    var addresses: List<String> = emptyList()
        private set
    var balanceSat: Long = 0
        private set

    private val conversions = mutableListOf<JSONObject>()

    fun load(): Boolean {
        if (!paths.bitcoinWallet.exists()) return false
        val wallet = try {
            JSONObject(paths.bitcoinWallet.readText())
        } catch (_: Exception) {
            return false
        }
        addresses = wallet.optJSONArray("addresses")
            ?.let { arr -> List(arr.length()) { arr.optString(it) } }
            ?: emptyList()
        balanceSat = wallet.optLong("balance_sat", 0L)
        conversions.clear()
        if (paths.conversionHistory.exists()) {
            try {
                JSONObject(paths.conversionHistory.readText())
                    .optJSONArray("conversions")?.let { arr ->
                        for (i in 0 until arr.length()) conversions.add(arr.getJSONObject(i))
                    }
            } catch (_: Exception) {
                // keep an empty history
            }
        }
        return true
    }

    fun queueConversion(vValue: Double, source: String = "value"): JSONObject {
        val conv = JSONObject()
            .put("timestamp", nowIso())
            .put("v_value", vValue)
            .put("sat", vToSat(vValue))
            .put("source", source)
            .put("status", "pending")
            .put("address", addresses.firstOrNull() ?: JSONObject.NULL)
        conversions.add(conv)
        save()
        return conv
    }

    fun pendingCount(): Int = conversions.count { it.optString("status") == "pending" }

    fun toJson(): JSONObject = JSONObject()
        .put("timestamp", nowIso())
        .put("addresses", JSONArray(addresses))
        .put("balance_sat", balanceSat)
        .put("pending_conversions", pendingCount())
        .put("has_bitcoinlib", false)

    fun save() {
        paths.home.mkdirs()
        paths.bitcoinWallet.writeText(toJson().toString())
        paths.conversionHistory.writeText(
            JSONObject()
                .put("timestamp", nowIso())
                .put("conversions", JSONArray(conversions))
                .toString(),
        )
    }

    private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

    companion object {
        const val RATE = EvoliaPaths.CONVERSION_RATE_V_TO_SAT
        const val MIN_TX_SAT = 1_000L
        const val MAX_TX_SAT = 1_000_000L
        private const val USD_PER_BTC = 70_000.0

        /** BTC-e value to satoshis, clamped to the per-tx bounds (0 if non-positive). */
        fun vToSat(vValue: Double): Long {
            val sat = (vValue * RATE).toLong()
            return if (sat > 0) sat.coerceIn(MIN_TX_SAT, MAX_TX_SAT) else 0L
        }

        fun satToBtc(sat: Long): Double = sat / 100_000_000.0

        fun satToUsd(sat: Long): Double = satToBtc(sat) * USD_PER_BTC
    }
}
