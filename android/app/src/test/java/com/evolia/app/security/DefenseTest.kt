package com.evolia.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the adaptive defense (mirror of go/defense + Rust). */
class DefenseTest {

    @Test
    fun growsWithAttacksAndIsBounded() {
        val d = AdaptiveDefense(3)
        assertEquals(0.0, d.level(), 1e-9)
        val l1 = d.record(AttackKind.MALFORMED)
        val l2 = d.record(AttackKind.SQL_INJECTION)
        assertTrue("absorbing more attacks must raise the level", l2 > l1)
        d.record(AttackKind.BAD_SIGNATURE)
        d.record(AttackKind.UNAUTHORIZED)
        assertEquals("buffer must stay bounded", 3, d.size())
    }

    @Test
    fun relaxesOnDecay() {
        val d = AdaptiveDefense(8)
        d.record(AttackKind.SQL_INJECTION)
        d.record(AttackKind.SQL_INJECTION)
        val before = d.level()
        d.decay()
        assertTrue("a quiet tick must relax the defense", d.level() < before)
    }

    @Test
    fun injectionDetector() {
        assertTrue(AdaptiveDefense.looksLikeInjection("' OR '1'='1"))
        assertTrue(AdaptiveDefense.looksLikeInjection("'; DROP TABLE peers;--"))
        assertTrue(AdaptiveDefense.looksLikeInjection("a UNION SELECT secret FROM users"))
        assertFalse(AdaptiveDefense.looksLikeInjection("phone-galaxy-a52"))
        assertFalse(AdaptiveDefense.looksLikeInjection("owner"))
    }
}
