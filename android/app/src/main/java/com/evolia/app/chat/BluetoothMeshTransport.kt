package com.evolia.app.chat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.evolia.app.core.EvoliaPaths
import com.evolia.app.security.AdaptiveDefense
import com.evolia.app.security.AttackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 2 chat transport: Bluetooth Classic RFCOMM, so evolIA messages move
 * peer-to-peer with NO internet and NO shared WiFi — the offline mesh use case,
 * the same one Briar serves. It carries the SAME opaque sealed envelopes as the
 * UDP relay and NEVER decrypts a body. An *insecure* RFCOMM socket (no OS
 * pairing dialog) is deliberate and safe: end-to-end confidentiality and
 * authenticity already live in ChatIdentity (static-static X25519 ECDH ->
 * HKDF -> ChaCha20-Poly1305 + an Ed25519 signature). That is exactly Briar's
 * model — treat the Bluetooth link as untrusted and do our own crypto over it —
 * so we neither depend on nor trust Bluetooth's link-layer security.
 *
 *  - Receive (server): an RFCOMM service socket accepts connections and streams
 *    length-prefixed envelopes through ChatIntake, so hostile Bluetooth input
 *    (malformed / SQL-injection-like routing fields) hardens the same adaptive
 *    defense as block input.
 *  - Send (client): drains the app outbox and pushes envelopes to in-range
 *    bonded peers; anything undelivered (no peer in range) is re-queued so the
 *    UDP relay or the next tick can still carry it — nothing is lost.
 *
 * Degrades gracefully: with no Bluetooth adapter, a disabled radio, or the
 * runtime BLUETOOTH_CONNECT permission withheld, every entry point is a no-op,
 * so the app still builds and runs off Bluetooth (the project's off-device
 * principle). Multi-hop store-and-forward and simultaneous cross-transport
 * fan-out (one message offered to UDP AND Bluetooth at once) are Phase 3; Phase 2
 * is direct delivery to peers in radio range, mirroring the UDP relay.
 */
class BluetoothMeshTransport(
    private val context: Context,
    private val store: ChatStore,
    private val defense: AdaptiveDefense,
    private val myFingerprint: String,
    private val paths: EvoliaPaths? = null,
) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // Ids already delivered, so a re-sent envelope is stored once. Intake is
    // serialized through intakeLock, so a plain set is safe across connections.
    private val seen: MutableSet<String> = store.inboxIds().toMutableSet()
    private val intakeLock = Any()

    // Hostile frames absorbed since start; the relay tick decays the defense on a
    // quiet tick (none since the last), so the Bluetooth surface breathes back
    // down like the UDP mesh. attacks is atomic (many connection coroutines);
    // prevAttacks is touched only by the single relay tick.
    private val attacks = AtomicLong(0)
    private var prevAttacks = 0L

    // Runtime counters surfaced to the in-app diagnostic so a user reporting
    // "BT on, paired, no delivery" can see exactly which arm is silent: did the
    // sender even try to connect? did any connection succeed? did the receiver's
    // accept loop ever see a peer? Persisted to chatBtStats on each change so
    // ChatActivity can read them without an IBinder hop.
    private val framesSent = AtomicLong(0)
    private val framesReceived = AtomicLong(0)
    private val connectAttempts = AtomicLong(0)
    private val connectSuccesses = AtomicLong(0)
    private val acceptCount = AtomicLong(0)
    private val intakeRejections = AtomicLong(0)

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var running = false

    // Peers found by active discovery (BluetoothDevice keyed by address), in
    // addition to the bonded set. The insecure RFCOMM transport does not actually
    // need an OS bond — confidentiality/authenticity is our own E2E crypto — so a
    // device seen in a scan is a perfectly valid relay target even if Android has
    // dropped (or never created) the pairing. This is what makes delivery survive
    // the "pairing keeps getting lost between two phones" problem. Concurrent: the
    // discovery receiver writes, the relay tick reads.
    private val discovered = ConcurrentHashMap<String, BluetoothDevice>()
    @Volatile private var discoveryReceiver: BroadcastReceiver? = null

    /** Usable only with a present, enabled radio and the connect permission. */
    fun isAvailable(): Boolean = adapter?.isEnabled == true && hasConnectPermission()

    /** Launch the RFCOMM accept loop. No-op if Bluetooth is unavailable. */
    fun start(scope: CoroutineScope) {
        if (running || !isAvailable()) return
        running = true
        this.scope = scope
        registerDiscovery()
        scope.launch(Dispatchers.IO) { acceptLoop() }
    }

    fun stop() {
        running = false
        unregisterDiscovery()
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        try {
            serverSocket?.close() // unblocks accept()
        } catch (_: IOException) {
        }
        serverSocket = null
    }

    /** Register a receiver for discovery results and bond-state changes, then
     *  kick off a scan. A device FOUND during a scan is remembered as a relay
     *  candidate (no OS bond required — the link is insecure-by-design and our
     *  crypto is end-to-end). A bond that drops to NONE is re-requested so the
     *  user's manual pairing, when they do use it, is kept alive instead of
     *  silently evaporating. Degrades to a no-op without the scan permission. */
    private fun registerDiscovery() {
        if (discoveryReceiver != null) return
        if (!hasScanPermission()) return
        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev: BluetoothDevice? =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (dev != null && canRunEvolia(dev)) discovered[dev.address] = dev
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        // Keep scanning periodically while we're running, so a peer
                        // that powers on later is still found.
                        if (running) startScan()
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val state = intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE,
                        )
                        val dev: BluetoothDevice? =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        // A bond we had has dropped: re-request it for a real peer so
                        // a manual pairing the user made does not keep evaporating.
                        if (state == BluetoothDevice.BOND_NONE && dev != null && canRunEvolia(dev)) {
                            try {
                                dev.createBond()
                            } catch (_: SecurityException) {
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        try {
            context.registerReceiver(rx, filter)
            discoveryReceiver = rx
            startScan()
        } catch (_: SecurityException) {
        }
    }

    private fun unregisterDiscovery() {
        val rx = discoveryReceiver ?: return
        try {
            context.unregisterReceiver(rx)
        } catch (_: IllegalArgumentException) {
            // already unregistered — safe to ignore
        }
        discoveryReceiver = null
    }

    private fun startScan() {
        if (!hasScanPermission()) return
        try {
            val a = adapter ?: return
            if (a.isDiscovering) return
            a.startDiscovery()
        } catch (_: SecurityException) {
        }
    }

    private fun acceptLoop() {
        val a = adapter ?: return
        while (running) {
            val server = try {
                a.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
            } catch (_: SecurityException) {
                return
            } catch (_: IOException) {
                return
            }
            serverSocket = server
            try {
                while (running) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        break // closed by stop(), or transient — fall out to re-listen
                    }
                    acceptCount.incrementAndGet()
                    persistStats()
                    scope?.launch(Dispatchers.IO) { handleConnection(socket) }
                }
            } finally {
                try {
                    server.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun handleConnection(socket: BluetoothSocket) {
        try {
            socket.inputStream.use { input ->
                while (running) {
                    val frame = try {
                        BluetoothFraming.readFrame(input) ?: break // clean EOF
                    } catch (_: IOException) {
                        // A garbled/oversized frame is hostile input: score it and
                        // drop the link (the same defense as a malformed datagram).
                        defense.record(AttackKind.MALFORMED)
                        attacks.incrementAndGet()
                        break
                    }
                    val result = synchronized(intakeLock) {
                        ChatIntake.accept(frame, myFingerprint, store, defense, seen)
                    }
                    framesReceived.incrementAndGet()
                    if (!result.accepted) {
                        attacks.incrementAndGet()
                        intakeRejections.incrementAndGet()
                    }
                    persistStats()
                }
            }
        } catch (_: IOException) {
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    /** Drain the Bluetooth outbox and push to in-range peers (bonded ∪ discovered);
     *  re-queue if none could be reached so the message is not lost. Returns the
     *  number of peers a frame batch actually reached (a successful connect+write),
     *  so the caller can log delivery rather than have it silently swallowed — the
     *  Kotlin mirror of the Go relayChat's Int return. 0 means nothing left the
     *  radio this tick (no candidate, empty queue, or every peer out of range).
     *  This drains the dedicated BT queue (chatOutboxBt), not the shared one — the
     *  Go binary owns the UDP outbox, so the two transports never race. */
    fun relayToPeers(): Int {
        // Persist a fresh stats snapshot every tick — the discovery/targets
        // metrics change without any counter being incremented (scan permission
        // grant, BOND_STATE flips, a device appearing in or leaving range), so
        // they need a heartbeat write the diagnostic can read.
        persistStats()
        if (!isAvailable()) return 0
        val bonded = try {
            adapter?.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        // Candidates = bonded peers + peers seen by active discovery, de-duplicated
        // by address. Including discovered (non-bonded) peers is what lets delivery
        // survive Android dropping the pairing between two phones: the insecure
        // RFCOMM link never needed the bond in the first place.
        val byAddr = LinkedHashMap<String, BluetoothDevice>()
        for (d in bonded) if (canRunEvolia(d)) byAddr[d.address] = d
        for (d in discovered.values) if (canRunEvolia(d)) byAddr.putIfAbsent(d.address, d)
        val candidates = byAddr.values.toList()
        // No candidate yet — kick a scan so one appears on a later tick, and leave
        // the queue intact (nothing is dropped).
        if (candidates.isEmpty()) {
            startScan()
            return 0
        }
        val msgs = store.drainBtOutbox()
        if (msgs.isEmpty()) return 0

        // A scan in progress sharply slows an outgoing connect; pause it while we
        // push, then it resumes on the next DISCOVERY_FINISHED.
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        val frames = msgs.map { it.toJson().toByteArray(Charsets.UTF_8) }
        var reached = 0
        for (device in candidates) {
            if (sendFramesTo(device, frames)) reached++
        }
        if (reached == 0) store.requeueBtOutbox(msgs)
        return reached
    }

    /** True if a bonded device could plausibly be another evolIA phone, so we
     *  only spend an RFCOMM connect on a real candidate. Two cheap, offline
     *  signals (no SDP round-trip needed):
     *   - the SDP UUID cache (device.uuids) already advertises our APP_UUID, OR
     *   - the device's major class is not a known non-phone peripheral
     *     (peripheral=keyboard/mouse, audio/video=headset/speaker, imaging,
     *     health, toy). A phone/computer/uncategorized device stays a candidate.
     *  Erring toward "candidate" is safe: a wrong guess just costs one connect
     *  that fails exactly as before, while a paired keyboard/mouse/headset —
     *  the actual stack-congestion culprits — are skipped. */
    private fun canRunEvolia(device: BluetoothDevice): Boolean {
        try {
            val uuids = device.uuids
            if (uuids != null) {
                for (u in uuids) {
                    if (u.uuid == APP_UUID) return true // advertises evolIA — definite peer
                }
            }
        } catch (_: SecurityException) {
            // fall through to the device-class heuristic
        }
        return try {
            when (device.bluetoothClass?.majorDeviceClass) {
                android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL,
                android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO,
                android.bluetooth.BluetoothClass.Device.Major.IMAGING,
                android.bluetooth.BluetoothClass.Device.Major.HEALTH,
                android.bluetooth.BluetoothClass.Device.Major.TOY,
                -> false
                else -> true // PHONE, COMPUTER, UNCATEGORIZED, or unknown -> try it
            }
        } catch (_: SecurityException) {
            true // can't read the class -> don't exclude a possible peer
        }
    }

    /** Relax the defense one notch on a quiet tick (no hostile frame since the
     *  last), so the Bluetooth surface breathes back down like the UDP mesh. */
    fun decayIfQuiet() {
        val cur = attacks.get()
        if (cur == prevAttacks) defense.decay()
        prevAttacks = cur
    }

    private fun sendFramesTo(device: BluetoothDevice, frames: List<ByteArray>): Boolean {
        connectAttempts.incrementAndGet()
        // Discovery, if running, sharply slows or aborts an outgoing connect.
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        // Primary path: connect via the SDP service record for APP_UUID. On some
        // Android builds / adapters the SDP lookup is flaky and connect() throws
        // even though the peer's RFCOMM server is up; the channel-1 fallback below
        // is the well-known reflection workaround for exactly that case.
        if (writeFramesOver({ device.createInsecureRfcommSocketToServiceRecord(APP_UUID) }, frames)) {
            return true
        }
        return writeFramesOver({ fallbackChannelSocket(device) }, frames)
    }

    /** Open a socket via [open], connect, stream every frame, close. Returns true
     *  only if the connect succeeded AND all frames were written. Any failure is
     *  swallowed (returns false) so the caller can try the next path/peer. */
    private fun writeFramesOver(open: () -> BluetoothSocket?, frames: List<ByteArray>): Boolean {
        var socket: BluetoothSocket? = null
        return try {
            socket = open() ?: return false
            socket.connect()
            connectSuccesses.incrementAndGet()
            val out = socket.outputStream
            frames.forEach {
                BluetoothFraming.writeFrame(out, it)
                framesSent.incrementAndGet()
            }
            persistStats()
            true
        } catch (_: IOException) {
            persistStats()
            false // peer out of range / not running evolIA — try the next path/device
        } catch (_: SecurityException) {
            persistStats()
            false
        } catch (_: ReflectiveOperationException) {
            false // fallback reflection not available on this build — give up cleanly
        } finally {
            try {
                socket?.close()
            } catch (_: IOException) {
            }
        }
    }

    /** Reflection fallback: open an insecure RFCOMM socket on channel 1 directly,
     *  bypassing the SDP UUID lookup. createInsecureRfcommSocket(int) is hidden
     *  API but stable across AOSP; if reflection is blocked the caller treats the
     *  resulting exception as a clean failure. */
    private fun fallbackChannelSocket(device: BluetoothDevice): BluetoothSocket? {
        val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
        return m.invoke(device, 1) as? BluetoothSocket
    }

    /** Snapshot of every paired Bluetooth device's name + address, so the
     *  diagnostic dialog can show WHO is paired (often the user paired a car or
     *  headset, not the other phone). Empty on no permission / no adapter. */
    fun bondedDeviceNames(): List<String> {
        if (!isAvailable()) return emptyList()
        return try {
            adapter?.bondedDevices?.map { dev ->
                val name = try { dev.name ?: "?" } catch (_: SecurityException) { "?" }
                "$name (${dev.address})"
            }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** Devices found by active discovery this session that the relay considers a
     *  plausible evolIA peer (post canRunEvolia filter). What the diagnostic shows
     *  as "scan candidates" — non-empty = the scan path is working even if no
     *  bonded phone is present. */
    fun discoveredEvoliaCandidates(): List<String> = discovered.values
        .filter { canRunEvolia(it) }
        .map {
            val name = try { it.name ?: "?" } catch (_: SecurityException) { "?" }
            "$name (${it.address})"
        }

    /** Devices the relay would actually try to connect to right now: bonded peers
     *  that pass canRunEvolia ∪ discovered candidates. What the diagnostic shows
     *  as "Targets" — the single number that explains "Tentatives connect = 0":
     *  a 0 here means relayToPeers() has nothing to dial, end of story. */
    fun connectionTargetsCount(): Int {
        if (!isAvailable()) return 0
        val byAddr = HashSet<String>()
        try {
            adapter?.bondedDevices?.forEach { if (canRunEvolia(it)) byAddr.add(it.address) }
        } catch (_: SecurityException) {
        }
        for (d in discovered.values) if (canRunEvolia(d)) byAddr.add(d.address)
        return byAddr.size
    }

    /** True if the adapter is mid-scan right now (a one-shot snapshot — the user
     *  may have caught it between two passes). Useful to tell "scan never ran"
     *  apart from "scan ran but found nothing yet". */
    fun isDiscovering(): Boolean = try {
        adapter?.isDiscovering == true
    } catch (_: SecurityException) {
        false
    }

    /** True if the relay holds the BLUETOOTH_SCAN runtime permission needed for
     *  active discovery (API 31+). Withheld -> discovery silently no-ops, which
     *  the diagnostic must surface so the user knows what to grant. */
    fun canScan(): Boolean = hasScanPermission()

    private fun persistStats() {
        val p = paths ?: return
        try {
            val j = JSONObject()
                .put("frames_sent", framesSent.get())
                .put("frames_received", framesReceived.get())
                .put("connect_attempts", connectAttempts.get())
                .put("connect_successes", connectSuccesses.get())
                .put("accept_count", acceptCount.get())
                .put("intake_rejections", intakeRejections.get())
                // Discovery + targeting snapshot, so the diagnostic can answer
                // "why is connect_attempts = 0 ?" without guessing: scan permission,
                // is a scan running, how many devices have been discovered, how
                // many are actual relay targets (bonded ∪ discovered, post filter).
                .put("scan_permission", canScan())
                .put("is_discovering", isDiscovering())
                .put("discovered_count", discovered.size)
                .put("discovered_evolia_candidates", discoveredEvoliaCandidates().size)
                .put("connection_targets", connectionTargetsCount())
            p.home.mkdirs()
            p.chatBtStats.writeText(j.toString())
        } catch (_: Exception) {
            // Best-effort; a stats write failure must not break the relay.
        }
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Active discovery needs BLUETOOTH_SCAN on API 31+ (and location pre-31, which
     *  the manifest already requests). Absent it, discovery is a no-op and we fall
     *  back to the bonded set only. */
    private fun hasScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val SERVICE_NAME = "evolIA-chat"
        // Fixed SDP service UUID shared by all evolIA nodes — the rendezvous point
        // a peer looks up to open the chat channel.
        private val APP_UUID: UUID = UUID.fromString("e7011a00-c6a7-4b1e-9f2d-000000000001")
    }
}
