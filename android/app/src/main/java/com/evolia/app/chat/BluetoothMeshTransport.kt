package com.evolia.app.chat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.evolia.app.security.AdaptiveDefense
import com.evolia.app.security.AttackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
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

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var running = false

    /** Usable only with a present, enabled radio and the connect permission. */
    fun isAvailable(): Boolean = adapter?.isEnabled == true && hasConnectPermission()

    /** Launch the RFCOMM accept loop. No-op if Bluetooth is unavailable. */
    fun start(scope: CoroutineScope) {
        if (running || !isAvailable()) return
        running = true
        this.scope = scope
        scope.launch(Dispatchers.IO) { acceptLoop() }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close() // unblocks accept()
        } catch (_: IOException) {
        }
        serverSocket = null
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
                    if (!result.accepted) attacks.incrementAndGet()
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

    /** Drain the outbox and push to in-range bonded peers; re-queue if none
     *  could be reached so the message is not lost. */
    fun relayToPeers() {
        if (!isAvailable()) return
        val devices = try {
            adapter?.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            return
        }
        if (devices.isEmpty()) return // no candidate peer -> leave the outbox for the UDP relay
        val msgs = store.drainOutbox()
        if (msgs.isEmpty()) return

        val frames = msgs.map { it.toJson().toByteArray(Charsets.UTF_8) }
        var deliveredToAny = false
        for (device in devices) {
            if (sendFramesTo(device, frames)) deliveredToAny = true
        }
        if (!deliveredToAny) store.requeueOutbox(msgs)
    }

    /** Relax the defense one notch on a quiet tick (no hostile frame since the
     *  last), so the Bluetooth surface breathes back down like the UDP mesh. */
    fun decayIfQuiet() {
        val cur = attacks.get()
        if (cur == prevAttacks) defense.decay()
        prevAttacks = cur
    }

    private fun sendFramesTo(device: BluetoothDevice, frames: List<ByteArray>): Boolean {
        var socket: BluetoothSocket? = null
        return try {
            // Discovery, if running, sharply slows or aborts an outgoing connect.
            try {
                adapter?.cancelDiscovery()
            } catch (_: SecurityException) {
            }
            socket = device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
            socket.connect()
            val out = socket.outputStream
            frames.forEach { BluetoothFraming.writeFrame(out, it) }
            true
        } catch (_: IOException) {
            false // peer out of range / not running evolIA — try the next device
        } catch (_: SecurityException) {
            false
        } finally {
            try {
                socket?.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val SERVICE_NAME = "evolIA-chat"
        // Fixed SDP service UUID shared by all evolIA nodes — the rendezvous point
        // a peer looks up to open the chat channel.
        private val APP_UUID: UUID = UUID.fromString("e7011a00-c6a7-4b1e-9f2d-000000000001")
    }
}
