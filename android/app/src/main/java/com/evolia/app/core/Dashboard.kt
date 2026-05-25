package com.evolia.app.core

import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Read-only aggregation of the shared evolIA state — a Kotlin port of
 * dashboard.py. Reads exactly the files the other layers write (identity state,
 * the Go mesh vault, the blockchain sync log, the bitcoin wallet/history) and
 * folds them into one snapshot. `collect` is pure and unit-tested; the UI just
 * renders it.
 */
object Dashboard {

    data class Personal(val totalV: Double, val cycleCount: Int)
    data class Mesh(val totalV: Double, val blocks: Int)
    data class Ganache(val anchoredV: Double, val tx: Int)
    data class Bitcoin(
        val addresses: Int,
        val balanceSat: Long,
        val balanceBtc: Double,
        val balanceUsd: Double,
        val conversions: Int,
    )

    data class Snapshot(
        val personal: Personal,
        val mesh: Mesh,
        val ganache: Ganache,
        val bitcoin: Bitcoin,
        val cognitivePower: Double,
    )

    private const val USD_PER_BTC = 70_000.0

    fun satToBtc(sat: Long): Double = sat / 100_000_000.0

    fun satToUsd(sat: Long): Double = satToBtc(sat) * USD_PER_BTC

    fun collect(paths: EvoliaPaths): Snapshot {
        val identity = readJson(paths.identityState)
        val personalV = identity?.optDouble("total_v", 0.0) ?: 0.0
        val cycles = identity?.optInt("cycle_count", 0) ?: 0

        val (meshV, meshCount) = meshTotals(paths)
        val (ganacheV, ganacheTx) = ganacheTotals(paths)

        val wallet = readJson(paths.bitcoinWallet)
        val balanceSat = wallet?.optLong("balance_sat", 0L) ?: 0L
        val addresses = wallet?.optJSONArray("addresses")?.length() ?: 0
        val conversions = readJson(paths.conversionHistory)?.optJSONArray("conversions")?.length() ?: 0

        return Snapshot(
            personal = Personal(personalV, cycles),
            mesh = Mesh(meshV, meshCount),
            ganache = Ganache(ganacheV, ganacheTx),
            bitcoin = Bitcoin(
                addresses = addresses,
                balanceSat = balanceSat,
                balanceBtc = satToBtc(balanceSat),
                balanceUsd = satToUsd(balanceSat),
                conversions = conversions,
            ),
            cognitivePower = personalV + meshV + ganacheV,
        )
    }

    private fun meshTotals(paths: EvoliaPaths): Pair<Double, Int> {
        val vault = paths.meshVault
        if (!vault.isDirectory) return 0.0 to 0
        var total = 0.0
        var count = 0
        vault.listFiles { f -> f.extension == "json" }?.forEach { f ->
            readJson(f)?.let {
                total += it.optDouble("v_value", 0.0)
                count++
            }
        }
        return total to count
    }

    private fun ganacheTotals(paths: EvoliaPaths): Pair<Double, Int> {
        val log = paths.blockchainSyncLog
        if (!log.exists()) return 0.0 to 0
        var total = 0.0
        var count = 0
        log.readText().lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val entry = try {
                JSONObject(line)
            } catch (_: Exception) {
                return@forEach
            }
            if (entry.optString("status") in setOf("success", "local")) {
                count++
                total += entry.optDouble("v_value", 0.0)
            }
        }
        return total to count
    }

    private fun readJson(file: File): JSONObject? = try {
        if (file.exists()) JSONObject(file.readText()) else null
    } catch (_: Exception) {
        null
    }

    fun render(s: Snapshot): String {
        val l = Locale.US
        return buildString {
            appendLine("================ EVOLIA — DASHBOARD ================")
            appendLine(String.format(l, "[PERSONNEL] V=%.2f BTC-e  cycles=%d", s.personal.totalV, s.personal.cycleCount))
            appendLine(String.format(l, "[MAILLAGE]  V=%.2f BTC-e  blocs=%d", s.mesh.totalV, s.mesh.blocks))
            appendLine(String.format(l, "[GANACHE]   V=%.2f BTC-e  tx=%d", s.ganache.anchoredV, s.ganache.tx))
            appendLine(
                String.format(
                    l,
                    "[BITCOIN]   %,d SAT (%.8f BTC ~ $%.2f)  addr=%d  conv=%d",
                    s.bitcoin.balanceSat,
                    s.bitcoin.balanceBtc,
                    s.bitcoin.balanceUsd,
                    s.bitcoin.addresses,
                    s.bitcoin.conversions,
                ),
            )
            appendLine("----------------------------------------------------")
            append(String.format(l, "[PUISSANCE COGNITIVE] %.2f BTC-e", s.cognitivePower))
        }
    }
}
