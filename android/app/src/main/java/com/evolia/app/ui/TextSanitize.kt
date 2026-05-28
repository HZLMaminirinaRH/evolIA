package com.evolia.app.ui

/**
 * Strip characters that could spoof or corrupt the on-screen log:
 *  - C0/C1 control codes — including newlines and tabs, so a received message can
 *    never inject extra lines into the conversation (a UI-spoofing trick);
 *  - Unicode bidirectional overrides (U+202A..202E, U+2066..2069, U+200E/200F):
 *    the "Trojan Source" / bidi spoofing set that can reorder displayed text.
 *
 * Pure (no Android types) so it unit-tests on the JVM. Normal scripts and emoji
 * are left untouched. Bidi code points are written as \u escapes on purpose, so
 * this source file contains no invisible characters itself.
 */
fun sanitizeForDisplay(text: String): String = buildString(text.length) {
    for (c in text) {
        val code = c.code
        val isBidiOverride = code in 0x202A..0x202E ||
            code in 0x2066..0x2069 ||
            code == 0x200E ||
            code == 0x200F
        if (Character.isISOControl(c) || isBidiOverride) continue
        append(c)
    }
}
