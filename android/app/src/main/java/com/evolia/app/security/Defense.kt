package com.evolia.app.security

/** Severity each kind of absorbed hostile input contributes to the level. */
enum class AttackKind(val severity: Double) {
    SQL_INJECTION(1.0),
    BAD_SIGNATURE(0.8),
    UNAUTHORIZED(0.6),
    MALFORMED(0.3),
}

/**
 * Adaptive defense — the Kotlin mirror of evolia-security::evolutive and
 * go/defense: a bounded ring of recent attacks (the "mémoire tampon en son
 * sein") whose level is the accumulated severity it holds and decays as quiet
 * ticks pass. The more hostile input evolIA absorbs, the harder it defends.
 * Strictly reactive: detect, reject, harden, record — never retaliate.
 */
class AdaptiveDefense(capacity: Int = 64) {

    private val cap = capacity.coerceAtLeast(1)
    private val buffer = ArrayDeque<Double>()

    /** Absorb an attack (evicting the oldest when full); returns the new level. */
    fun record(kind: AttackKind): Double {
        if (buffer.size == cap) buffer.removeFirst()
        buffer.addLast(kind.severity)
        return level()
    }

    /** Accumulated severity of buffered attacks. */
    fun level(): Double = buffer.sum()

    /** Relax one notch: forget the oldest absorbed attack on a quiet tick. */
    fun decay() {
        if (buffer.isNotEmpty()) buffer.removeFirst()
    }

    fun size(): Int = buffer.size

    companion object {
        private val NEEDLES = listOf(
            "' or ", "\" or ", " or 1=1", "'='",
            "--", "/*", "*/", "; drop ", " union select", "xp_cmdshell", "';",
        )

        /** Flags SQL-injection-like control inputs; positive => reject + record. */
        fun looksLikeInjection(input: String): Boolean {
            if (input.any { it == Char(0) }) return true
            val lower = input.lowercase()
            return NEEDLES.any { lower.contains(it) }
        }
    }
}
