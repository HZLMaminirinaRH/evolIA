package com.evolia.app.core

import org.json.JSONObject
import java.time.Instant

/**
 * Value accumulation driven by the evolutive formula (Kotlin port of
 * evolia_value.py). Per cycle:
 *
 *     base = BTC-e of the actions recorded since the last cycle
 *     V    = evolve(...).vNormalized              // cognitive multiplier 0..1
 *     gain = base * (1 + V) + SENSOR_FLOOR * V     // actions amplified by V,
 *                                                  // plus a floor so movement
 *                                                  // alone still accrues value
 *     totalV += gain
 *
 * State persists to evolia_value_state.json and the headline figures mirror to
 * evolia_identity_state.json — the same files the dashboard and on-chain anchor
 * read, so this is a drop-in replacement for the Python loop.
 */
class EvoliaValue(private val paths: EvoliaPaths) {

    var totalV: Double = 0.0
        private set
    var cycleCount: Int = 0
        private set
    var locationCount: Int = 0
        private set
    val actionCounts: MutableMap<String, Int> =
        Evolve.ACTION_RATES.keys.associateWith { 0 }.toMutableMap()

    private var pendingBase: Double = 0.0 // BTC-e of actions not yet folded into a cycle

    fun recordAction(kind: String, count: Int = 1): Double {
        require(kind in Evolve.ACTION_RATES) { "unknown action: $kind" }
        require(count >= 0) { "count must be >= 0" }
        val base = Evolve.ACTION_RATES.getValue(kind) * count
        actionCounts[kind] = (actionCounts[kind] ?: 0) + count
        pendingBase += base
        return base
    }

    fun cycle(sample: SensorSample, elapsedSeconds: Double = 0.0): JSONObject {
        cycleCount++
        if (sample.locationFix) locationCount++

        val result = Evolve.evolve(actionCounts, elapsedSeconds, sample, locationCount)
        val base = pendingBase
        pendingBase = 0.0

        val gain = base * (1.0 + result.vNormalized) + SENSOR_FLOOR * result.vNormalized
        totalV += gain
        save()

        return JSONObject()
            .put("cycle", cycleCount)
            .put("base_btc_e", round4(base))
            .put("v_normalized", round4(result.vNormalized))
            .put("gain", round4(gain))
            .put("total_v", round4(totalV))
    }

    fun save() {
        paths.home.mkdirs()
        val timestamp = Instant.now().toString()
        val state = JSONObject()
            .put("timestamp", timestamp)
            .put("total_v", totalV)
            .put("cycle_count", cycleCount)
            .put("location_count", locationCount)
            .put("action_counts", JSONObject(actionCounts.toMap()))
        paths.valueState.writeText(state.toString(2))

        val identity = JSONObject()
            .put("total_v", totalV)
            .put("cycle_count", cycleCount)
            .put("timestamp", timestamp)
        paths.identityState.writeText(identity.toString(2))
    }

    fun load(): Boolean {
        val file = paths.valueState
        if (!file.exists()) return false
        return try {
            val data = JSONObject(file.readText())
            totalV = data.optDouble("total_v", 0.0)
            cycleCount = data.optInt("cycle_count", 0)
            locationCount = data.optInt("location_count", 0)
            data.optJSONObject("action_counts")?.let { ac ->
                for (k in actionCounts.keys) actionCounts[k] = ac.optInt(k, actionCounts.getValue(k))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun round4(x: Double): Double = Math.round(x * 10_000.0) / 10_000.0

    companion object {
        // Max value (BTC-e) a full-activity cycle can accrue from sensors alone.
        const val SENSOR_FLOOR = 1.0
    }
}
