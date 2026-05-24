package com.evolia.app.core

import java.io.File

/**
 * The shared EVOLIA_HOME layout (Kotlin mirror of evolia_paths.py / evolia-core).
 * On Android, home is the app's private files dir, but the file names match
 * every other language so the state stays interoperable.
 */
class EvoliaPaths(val home: File) {
    val valueState: File get() = File(home, "evolia_value_state.json")
    val identityState: File get() = File(home, "evolia_identity_state.json")
    val actionQueue: File get() = File(home, "evolia_action_queue.jsonl")
    val blockchainSyncLog: File get() = File(home, "evolia_blockchain_sync.log")

    companion object {
        const val CONVERSION_RATE_V_TO_SAT = 100_000
    }
}
