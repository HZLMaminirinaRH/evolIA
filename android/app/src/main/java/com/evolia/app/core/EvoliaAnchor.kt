package com.evolia.app.core

import org.json.JSONObject
import java.time.Instant

/**
 * Sync-log helper (Kotlin port of ganache_db.py logging).
 *
 * Reads total_v from evolia_identity_state.json and appends entries to
 * evolia_blockchain_sync.log — the same file/format the dashboard and the
 * bitcoin bridge consume. ChainAnchor drives the on-chain vs LOCAL decision and
 * uses these helpers to shape and persist each entry.
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

    /** Shape one sync-log entry; status is "success" | "local" | "failed". */
    fun buildLogEntry(vValue: Double, status: String, extra: Map<String, Any?> = emptyMap()): JSONObject {
        val entry = JSONObject()
            .put("timestamp", Instant.now().toString())
            .put("status", status)
            .put("v_value", vValue)
        for ((key, value) in extra) if (value != null) entry.put(key, value)
        return entry
    }

    fun logSync(paths: EvoliaPaths, entry: JSONObject) {
        paths.home.mkdirs()
        paths.blockchainSyncLog.appendText(entry.toString() + "\n")
    }
}
