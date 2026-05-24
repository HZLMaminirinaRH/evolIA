package com.evolia.app.core

import org.json.JSONObject
import java.time.Instant

/**
 * Value anchoring (Kotlin port of ganache_db.py, LOCAL mode).
 *
 * Reads total_v from evolia_identity_state.json and appends one entry per sync
 * to evolia_blockchain_sync.log — the same file/format the dashboard and the
 * bitcoin bridge consume. This is the LOCAL path: it records the value with
 * status "local" so the pipeline keeps flowing. Real on-chain anchoring (web3j,
 * EvoliaCore.anchorValue) is the next 2b step and will emit status "success".
 */
object EvoliaAnchor {

    fun readTotalV(paths: EvoliaPaths): Double {
        val file = paths.identityState
        if (!file.exists()) return 0.0
        return try {
            JSONObject(file.readText()).optDouble("total_v", 0.0)
        } catch (_: Exception) {
            0.0
        }
    }

    fun buildLogEntry(vValue: Double, status: String, note: String? = null): JSONObject {
        val entry = JSONObject()
            .put("timestamp", Instant.now().toString())
            .put("status", status)
            .put("v_value", vValue)
        if (note != null) entry.put("note", note)
        return entry
    }

    fun logSync(paths: EvoliaPaths, entry: JSONObject) {
        paths.home.mkdirs()
        paths.blockchainSyncLog.appendText(entry.toString() + "\n")
    }

    /** Anchor the current value once in LOCAL mode; returns the entry written. */
    fun syncOnce(paths: EvoliaPaths): JSONObject {
        val vValue = readTotalV(paths)
        val note = if (vValue <= 0.0) "no value to anchor" else "local mode (no web3j)"
        val entry = buildLogEntry(vValue, "local", note)
        logSync(paths, entry)
        return entry
    }
}
