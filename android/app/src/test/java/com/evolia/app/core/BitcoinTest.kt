package com.evolia.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Pure-JVM tests for the bitcoin bridge (port of evolia_bitcoin.py). */
class BitcoinTest {

    @Test
    fun vToSatClamps() {
        assertEquals(0L, BitcoinBridge.vToSat(0.0))
        assertEquals(0L, BitcoinBridge.vToSat(-5.0))
        // 0.005 * 100_000 = 500 -> clamped up to the min.
        assertEquals(BitcoinBridge.MIN_TX_SAT, BitcoinBridge.vToSat(0.005))
        // 0.5 * 100_000 = 50_000 (within bounds).
        assertEquals(50_000L, BitcoinBridge.vToSat(0.5))
        // 1000 * 100_000 way over -> clamped to the max.
        assertEquals(BitcoinBridge.MAX_TX_SAT, BitcoinBridge.vToSat(1000.0))
    }

    @Test
    fun satConversions() {
        assertEquals(0.0015, BitcoinBridge.satToBtc(150_000L), 1e-12)
        assertEquals(0.0015 * 70_000.0, BitcoinBridge.satToUsd(150_000L), 1e-9)
    }

    @Test
    fun queueConversionPersistsAndReloads() {
        val home = Files.createTempDirectory("evolia-btc").toFile()
        val paths = EvoliaPaths(home)

        val bridge = BitcoinBridge(paths)
        assertFalse(bridge.load())

        val conv = bridge.queueConversion(0.5, source = "test")
        assertEquals(50_000L, conv.optLong("sat"))
        assertEquals("pending", conv.optString("status"))
        assertTrue(paths.bitcoinWallet.exists())
        assertTrue(paths.conversionHistory.exists())

        val reloaded = BitcoinBridge(paths)
        assertTrue(reloaded.load())
        assertEquals(1, reloaded.pendingCount())
        assertEquals(0L, reloaded.balanceSat)

        // The dashboard sees the conversion count.
        val snapshot = Dashboard.collect(paths)
        assertEquals(1, snapshot.bitcoin.conversions)
    }
}
