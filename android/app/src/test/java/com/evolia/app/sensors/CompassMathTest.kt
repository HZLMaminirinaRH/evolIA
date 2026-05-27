package com.evolia.app.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for the compass angle handling (no Android sensor needed). */
class CompassMathTest {

    @Test
    fun azimuthDegreesNormalizesRadians() {
        assertEquals(0.0, CompassMath.azimuthDegrees(0.0), 1e-6)
        assertEquals(90.0, CompassMath.azimuthDegrees(Math.PI / 2), 1e-6)
        assertEquals(180.0, CompassMath.azimuthDegrees(Math.PI), 1e-6)
        // getOrientation returns negative radians for the western half.
        assertEquals(270.0, CompassMath.azimuthDegrees(-Math.PI / 2), 1e-6)
    }

    @Test
    fun cardinalIndexMapsEightSectors() {
        assertEquals(0, CompassMath.cardinalIndex(0.0))   // N
        assertEquals(1, CompassMath.cardinalIndex(45.0))  // NE
        assertEquals(2, CompassMath.cardinalIndex(90.0))  // E
        assertEquals(3, CompassMath.cardinalIndex(135.0)) // SE
        assertEquals(4, CompassMath.cardinalIndex(180.0)) // S
        assertEquals(5, CompassMath.cardinalIndex(225.0)) // SW
        assertEquals(6, CompassMath.cardinalIndex(270.0)) // W
        assertEquals(7, CompassMath.cardinalIndex(315.0)) // NW
    }

    @Test
    fun cardinalIndexWrapsAroundNorth() {
        assertEquals(0, CompassMath.cardinalIndex(350.0)) // back to N
        assertEquals(0, CompassMath.cardinalIndex(359.9))
        assertEquals(0, CompassMath.cardinalIndex(11.0))
        assertEquals(1, CompassMath.cardinalIndex(23.0))  // crosses into NE at 22.5
    }
}
