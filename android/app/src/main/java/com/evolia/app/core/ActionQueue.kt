package com.evolia.app.core

import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Append-only digital-action queue (Kotlin mirror of evolia_actions.py).
 * Producers (the UI, future watchers) only ever append; the value loop is the
 * single owner that drains it each cycle — keeping exactly one writer of state.
 */
object ActionQueue {

    fun enqueue(paths: EvoliaPaths, kind: String, count: Int = 1) {
        if (kind !in Evolve.ACTION_RATES || count <= 0) return
        paths.home.mkdirs()
        val line = JSONObject()
            .put("kind", kind)
            .put("count", count)
            .put("ts", Instant.now().toString())
            .toString()
        paths.actionQueue.appendText(line + "\n")
    }

    /** Atomically take all queued events; new events go to a fresh file. */
    fun drain(paths: EvoliaPaths): List<Pair<String, Int>> {
        val queue = paths.actionQueue
        if (!queue.exists()) return emptyList()
        val tmp = File(queue.parentFile, queue.name + ".draining")
        if (!queue.renameTo(tmp)) return emptyList()

        val events = mutableListOf<Pair<String, Int>>()
        tmp.readLines().forEach { line ->
            try {
                val ev = JSONObject(line)
                val kind = ev.optString("kind")
                if (kind in Evolve.ACTION_RATES) events.add(kind to ev.optInt("count", 1))
            } catch (_: Exception) {
                // skip malformed lines
            }
        }
        tmp.delete()
        return events
    }
}
