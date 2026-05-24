package com.evolia.app.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** One instantaneous reading of every tracked sensor (mirror of Python SensorSample). */
data class SensorSample(
    val accelerometer: Double = 0.0, // m/s^2, vector magnitude
    val gyroscope: Double = 0.0,     // rad/s, vector magnitude
    val magnetometer: Double = 0.0,  // microtesla, vector magnitude
    val locationFix: Boolean = false,
    val wifiCount: Int = 0,
    val bleCount: Int = 0,
)

data class EvolveResult(
    val vInstant: Double,
    val vNormalized: Double, // 0..1, the cognitive multiplier
    val components: Map<String, Double>,
)

/**
 * The evolutive formula — the cognitive CORE of evolIA (Kotlin port of
 * evolia_evolve.py). V is an exponential blend of every tracked signal,
 * normalized to 0..1. Pure functions of the inputs; tune ACTION_RATES / COEFF
 * to reshape the economy. Video outranks every action; Bluetooth outranks WiFi.
 */
object Evolve {

    val ACTION_RATES: Map<String, Double> = linkedMapOf(
        "screen_input" to 0.05,
        "sms_sent" to 1.20,
        "photo_taken" to 2.50,
        "video_taken" to 8.00,
    )

    private val COEFF = mapOf(
        "actions" to 0.15,
        "time" to 0.10,
        "complexity" to 0.20,
        "magnetometer" to 0.05,
        "location" to 0.08,
        "ble" to 0.12,
        "wifi" to 0.05,
    )

    private val SCALE = mapOf(
        "actions" to 50.0,
        "time" to 100.0,
        "complexity" to 0.5,
        "location" to 20.0,
        "ble" to 10.0,
        "wifi" to 10.0,
    )

    private const val CAP = 3.0

    private fun expS(x: Double, scale: Double): Double = exp(min(x / scale, CAP))

    fun actionScore(counts: Map<String, Int>): Double =
        ACTION_RATES.entries.sumOf { (k, rate) -> rate * (counts[k] ?: 0) }

    private fun motion(s: SensorSample): Double {
        val accel = min(s.accelerometer / 19.6, 1.0)
        val gyro = min(s.gyroscope / 4.36, 1.0)
        return (accel + gyro) / 2.0
    }

    private fun magnetoNorm(s: SensorSample): Double = min(s.magnetometer / 65.0, 1.0)

    private fun components(
        aScore: Double,
        elapsed: Double,
        motion: Double,
        magneto: Double,
        locCount: Double,
        wifi: Double,
        ble: Double,
    ): Map<String, Double> = mapOf(
        "actions" to COEFF.getValue("actions") * expS(aScore, SCALE.getValue("actions")),
        "time" to COEFF.getValue("time") * expS(elapsed, SCALE.getValue("time")),
        "complexity" to COEFF.getValue("complexity") * expS(motion, SCALE.getValue("complexity")),
        "magnetometer" to COEFF.getValue("magnetometer") * magneto,
        "location" to COEFF.getValue("location") * expS(locCount, SCALE.getValue("location")),
        "wifi" to COEFF.getValue("wifi") * expS(wifi, SCALE.getValue("wifi")),
        "ble" to COEFF.getValue("ble") * expS(ble, SCALE.getValue("ble")),
    )

    // At-rest baseline (all inputs 0) and saturated ceiling (every exponent capped).
    private val V_BASE = components(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0).values.sum()
    private val V_MAX = components(1e9, 1e9, 1e9, 1.0, 1e9, 1e9, 1e9).values.sum()

    fun evolve(
        actionCounts: Map<String, Int>,
        elapsedSeconds: Double,
        sample: SensorSample,
        locationCount: Int,
    ): EvolveResult {
        val comps = components(
            actionScore(actionCounts),
            max(elapsedSeconds, 0.0),
            motion(sample),
            magnetoNorm(sample),
            locationCount.toDouble(),
            sample.wifiCount.toDouble(),
            sample.bleCount.toDouble(),
        )
        val vInstant = comps.values.sum()
        val vNorm = ((vInstant - V_BASE) / (V_MAX - V_BASE)).coerceIn(0.0, 1.0)
        return EvolveResult(vInstant, vNorm, comps)
    }
}
