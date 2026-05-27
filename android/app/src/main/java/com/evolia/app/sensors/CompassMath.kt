package com.evolia.app.sensors

/**
 * Pure compass math, split out so the angle handling unit-tests on the JVM (the
 * SensorManager sensor-fusion that produces the azimuth is Android-only). A real
 * compass needs absolute heading, which the gyroscope ALONE cannot give (it
 * measures angular velocity only); the heading comes from the rotation-vector
 * sensor, a fusion of accelerometer + gyroscope + magnetometer — so the
 * gyroscope genuinely contributes, and its motion keeps feeding V meanwhile.
 */
object CompassMath {

    /**
     * Normalize an azimuth in radians (SensorManager.getOrientation()[0], range
     * -PI..PI) to compass degrees in [0, 360): 0 = North, 90 = East, 180 = South,
     * 270 = West.
     */
    fun azimuthDegrees(azimuthRadians: Double): Double {
        val deg = Math.toDegrees(azimuthRadians)
        return ((deg % 360.0) + 360.0) % 360.0
    }

    /** Cardinal index 0..7 (N, NE, E, SE, S, SW, W, NW) for a heading in degrees. */
    fun cardinalIndex(degrees: Double): Int {
        val d = ((degrees % 360.0) + 360.0) % 360.0
        return ((d + 22.5) / 45.0).toInt() % 8
    }

    /**
     * Euclidean magnitude of a sensor vector's first up-to-3 components — for
     * TYPE_MAGNETIC_FIELD this is the magnetic field strength in microtesla,
     * computed exactly like AndroidSensors (the same value that feeds V).
     */
    fun magnitude(values: FloatArray): Double {
        var sumSq = 0.0
        val n = minOf(3, values.size)
        for (i in 0 until n) sumSq += values[i].toDouble() * values[i]
        return kotlin.math.sqrt(sumSq)
    }
}
