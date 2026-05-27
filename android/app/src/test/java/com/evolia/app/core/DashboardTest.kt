package com.evolia.app.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Pure-JVM tests for the dashboard aggregation (port of dashboard.py). */
class DashboardTest {

    @Test
    fun aggregatesAllSources() {
        val home = Files.createTempDirectory("evolia-dash").toFile()
        val paths = EvoliaPaths(home)

        paths.identityState.writeText("""{"total_v": 10.0, "cycle_count": 7}""")

        paths.meshVault.mkdirs()
        File(paths.meshVault, "a.json").writeText("""{"v_value": 2.5}""")
        File(paths.meshVault, "b.json").writeText("""{"v_value": 1.5}""")

        // 2 counted (success + local), failed ignored, garbage skipped.
        paths.blockchainSyncLog.writeText(
            listOf(
                """{"status":"success","v_value":3.0}""",
                """{"status":"local","v_value":1.0}""",
                """{"status":"failed","v_value":99.0}""",
                "not-json",
            ).joinToString("\n"),
        )

        paths.bitcoinWallet.writeText("""{"balance_sat": 150000, "addresses": ["a","b"]}""")
        paths.conversionHistory.writeText("""{"conversions":[{},{},{}]}""")

        val s = Dashboard.collect(paths)
        assertEquals(10.0, s.personal.totalV, 1e-9)
        assertEquals(7, s.personal.cycleCount)
        assertEquals(4.0, s.mesh.totalV, 1e-9)
        assertEquals(2, s.mesh.blocks)
        assertEquals(4.0, s.ganache.anchoredV, 1e-9)
        assertEquals(2, s.ganache.tx)
        assertEquals(150000L, s.bitcoin.balanceSat)
        assertEquals(0.0015, s.bitcoin.balanceBtc, 1e-12)
        assertEquals(2, s.bitcoin.addresses)
        assertEquals(3, s.bitcoin.conversions)
        // cognitive power = personal + mesh + ganache = 10 + 4 + 4
        assertEquals(18.0, s.cognitivePower, 1e-9)
    }

    @Test
    fun emptyStateDefaultsToZero() {
        val home = Files.createTempDirectory("evolia-dash-empty").toFile()
        val s = Dashboard.collect(EvoliaPaths(home))
        assertEquals(0.0, s.personal.totalV, 1e-9)
        assertEquals(0, s.personal.cycleCount)
        assertEquals(0, s.mesh.blocks)
        assertEquals(0, s.ganache.tx)
        assertEquals(0L, s.bitcoin.balanceSat)
        assertEquals(0.0, s.cognitivePower, 1e-9)
    }
}
