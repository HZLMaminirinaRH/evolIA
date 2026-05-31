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
    val walletAddress: File get() = File(home, "evolia_wallet_address.txt")
    val chainConfig: File get() = File(home, "evolia_chain_config.json")
    val deployment: File get() = File(home, "evolia_deployment.json")
    val authConfig: File get() = File(home, ".evolia_auth.json")
    val sessionState: File get() = File(home, ".evolia_session.json")
    val meshVault: File get() = File(home, "evolia_mesh_vault")
    val bitcoinWallet: File get() = File(home, "evolia_bitcoin_wallet.json")
    val conversionHistory: File get() = File(home, "evolia_btc_conversion_history.json")
    val transferHistory: File get() = File(home, "evolia_transfer_history.jsonl")
    val onchainBalance: File get() = File(home, "evolia_onchain_balance.json")
    val chatIdentityKey: File get() = File(home, "evolia_chat_identity.key")
    val chatOutbox: File get() = File(home, "evolia_chat_outbox.jsonl")
    // Bluetooth has its OWN outbox, fanned-out from enqueue alongside chatOutbox.
    // The Go mesh-sync binary drains chatOutbox for UDP; if both transports drained
    // the SAME file, whichever ran first would consume the message and starve the
    // other (the "BT on, queue empty, never delivered" race). Two queues + the
    // receiver's id-dedup let each transport try independently with no double-show.
    val chatOutboxBt: File get() = File(home, "evolia_chat_outbox_bt.jsonl")
    val chatInbox: File get() = File(home, "evolia_chat_inbox.jsonl")
    val chatFingerprint: File get() = File(home, "evolia_chat_fingerprint.txt")
    val chatContacts: File get() = File(home, "evolia_chat_contacts.json")
    val chatBtStats: File get() = File(home, "evolia_chat_bt_stats.json")
    // UDP mesh transport telemetry, written each cycle by the Go mesh-sync
    // binary (go/meshstats package). Same JSON-snapshot pattern as chatBtStats
    // but for the WiFi/UDP side: sends_ok, sends_fail, peers_cold,
    // throttle_events, attacks_by_flow, receives, defense_level — the
    // diagnostic dialog reads this to show "is the UDP arm of the mesh
    // actually moving anything?".
    val meshStats: File get() = File(home, "evolia_mesh_stats.json")

    companion object {
        const val CONVERSION_RATE_V_TO_SAT = 100_000
    }
}
