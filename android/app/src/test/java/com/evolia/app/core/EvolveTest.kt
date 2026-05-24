package com.evolia.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the evolutive formula (no Android dependencies). */
class EvolveTest {

    @Test
    fun atRestIsZero() {
        val r = Evolve.evolve(emptyMap(), 0.0, SensorSample(), 0)
        assertEquals(0.0, r.vNormalized, 1e-9)
    }

    @Test
    fun normalizedStaysInRange() {
        val r = Evolve.evolve(
            mapOf("video_taken" to 5),
            9999.0,
            SensorSample(20.0, 5.0, 70.0, locationFix = true, wifiCount = 50, bleCount = 30),
            100,
        )
        assertTrue("vNormalized must be within 0..1", r.vNormalized in 0.0..1.0)
    }

    @Test
    fun videoOutranksPhoto() {
        assertTrue(
            Evolve.ACTION_RATES.getValue("video_taken") >
                Evolve.ACTION_RATES.getValue("photo_taken"),
        )
    }

    @Test
    fun bleOutranksWifi() {
        val onlyBle = Evolve.evolve(emptyMap(), 0.0, SensorSample(bleCount = 5), 0)
        val onlyWifi = Evolve.evolve(emptyMap(), 0.0, SensorSample(wifiCount = 5), 0)
        assertTrue("BLE must rank above WiFi", onlyBle.vInstant > onlyWifi.vInstant)
    }

    @Test
    fun moreActionsRaiseValue() {
        val few = Evolve.evolve(mapOf("screen_input" to 1), 0.0, SensorSample(), 0)
        val many = Evolve.evolve(mapOf("video_taken" to 10), 0.0, SensorSample(), 0)
        assertTrue(many.vNormalized > few.vNormalized)
    }
}
