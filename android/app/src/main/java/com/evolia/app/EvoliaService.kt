package com.evolia.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.evolia.app.chain.ChainAnchor
import com.evolia.app.chat.BluetoothMeshTransport
import com.evolia.app.chat.ChatIdentityStore
import com.evolia.app.chat.ChatStore
import com.evolia.app.core.ActionQueue
import com.evolia.app.core.EvoliaPaths
import com.evolia.app.core.EvoliaValue
import com.evolia.app.security.AdaptiveDefense
import com.evolia.app.sensors.AndroidSensors
import com.evolia.app.sensors.MediaActionCapture
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.Security

/**
 * Foreground service that supervises the prebuilt Go networking binaries.
 *
 * Android only allows executing binaries from the app's nativeLibraryDir, so
 * the binaries are shipped inside the APK as lib*.so (see
 * scripts/build-android-binaries.sh). Each is run with EVOLIA_HOME pointing at
 * the app's private files dir and restarted if it exits — the foreground
 * notification is what stops Android from killing them (the signal-9 fix).
 *
 * Phase 1 supervises the Go layer (net / mesh-sync / bridge). The value model,
 * web3 anchoring and auth follow as Kotlin ports (see android/README.md).
 */
class EvoliaService : Service() {

    // The exception handler keeps the service (and the app) alive if any
    // coroutine throws — e.g. an optional web3j/sensor path failing.
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, _ -> },
    )
    private var wakeLock: PowerManager.WakeLock? = null
    private val processes = mutableListOf<Process>()
    private var bluetoothChat: BluetoothMeshTransport? = null

    // Owner session passed to the Go children (minted by the auth gate).
    private var sessionToken: String? = null
    private var deviceId: String? = null
    private var meshKey: String? = null

    // Go binaries, packaged as lib*.so so Android extracts them executable.
    private val binaries = listOf(
        "libevolia_net.so",
        "libevolia_mesh_sync.so",
        "libevolia_bridge.so",
    )

    override fun onCreate() {
        super.onCreate()
        setupSecurityProvider()
        createChannel()
        // Ephemeral chat: start every session from a clean slate, so messages from
        // a prior run (or one the OS killed without onDestroy) never linger.
        purgeChatMessages()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "evolia:supervisor").apply {
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Supervision des services Evolia…"))

        val home = File(filesDir, "evolia").apply { mkdirs() }
        loadSession(home)

        // Native value engine (Phase 2): runs in-process, no Python, no signal 9.
        startValueLoop(home)

        // Bluetooth mesh chat (Phase 2): peer-to-peer messaging with no internet
        // or WiFi. No-ops gracefully when Bluetooth is off/unpermitted.
        startBluetoothChat(home)

        // Watch the chat inbox for new incoming envelopes and post a system
        // notification on arrival, so the recipient sees a message even when
        // the chat screen isn't open.
        startChatNotifier(home)

        // Supervise the prebuilt Go binaries if they were packaged (Phase 1).
        val nativeDir = applicationInfo.nativeLibraryDir
        for (name in binaries) {
            superviseBinary(File(nativeDir, name), home)
        }
        return START_STICKY
    }

    private fun startValueLoop(home: File) = scope.launch {
        val paths = EvoliaPaths(home)
        val value = EvoliaValue(paths)
        value.load()
        // Drives on-chain (or LOCAL) anchoring each interval. The signing wallet
        // is created lazily, only if anchoring is actually configured.
        val chain = ChainAnchor(this@EvoliaService, paths)
        val sensors = AndroidSensors(this@EvoliaService).apply { start() }
        val media = MediaActionCapture(this@EvoliaService, paths).apply { start() }
        val startedAt = System.nanoTime()
        var lastAnchorMs = 0L
        try {
            while (isActive) {
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
                for ((kind, count) in ActionQueue.drain(paths)) value.recordAction(kind, count)
                value.cycle(sensors.sample(), elapsed)
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastAnchorMs >= ANCHOR_MS) {
                    chain.syncOnce()
                    // Cache the on-chain proven balance so the dashboard can show
                    // what's transferable without a main-thread network call.
                    chain.refreshBalance()
                    lastAnchorMs = nowMs
                }
                delay(CYCLE_MS)
            }
        } finally {
            sensors.stop()
            media.stop()
        }
    }

    private fun startBluetoothChat(home: File) = scope.launch {
        val paths = EvoliaPaths(home)
        val store = ChatStore(paths)
        val identity = ChatIdentityStore(paths).loadOrCreate()
        val transport = BluetoothMeshTransport(
            this@EvoliaService, store, AdaptiveDefense(), identity.fingerprint(),
        )
        bluetoothChat = transport
        transport.start(scope)
        // Carry queued envelopes to in-range bonded peers each cycle, in step with
        // the Go relay's cadence (the receive side runs continuously).
        while (isActive) {
            transport.relayToPeers()
            transport.decayIfQuiet()
            delay(CYCLE_MS)
        }
    }

    private fun loadSession(home: File) {
        val file = File(home, ".evolia_session.json")
        if (!file.exists()) return
        try {
            val j = JSONObject(file.readText())
            sessionToken = j.optString("token").ifBlank { null }
            deviceId = j.optString("device_id").ifBlank { null }
            meshKey = j.optString("mesh_key").ifBlank { null }
        } catch (_: Exception) {
            // No usable session — children still run with EVOLIA_HOME only.
        }
    }

    private fun superviseBinary(binary: File, home: File) = scope.launch {
        while (isActive) {
            try {
                val builder = ProcessBuilder(binary.absolutePath)
                    .directory(home)
                    .redirectErrorStream(true)
                builder.environment()["EVOLIA_HOME"] = home.absolutePath
                sessionToken?.let { builder.environment()["EVOLIA_SESSION_TOKEN"] = it }
                deviceId?.let { builder.environment()["EVOLIA_DEVICE_ID"] = it }
                meshKey?.let { builder.environment()["EVOLIA_MESH_KEY"] = it }
                val process = builder.start()
                synchronized(processes) { processes.add(process) }
                process.waitFor()
                synchronized(processes) { processes.remove(process) }
            } catch (_: Exception) {
                // fall through to the restart backoff
            }
            if (isActive) delay(RESTART_BACKOFF_MS)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("evolIA")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()

    /**
     * Periodically scan the chat inbox for new envelopes and pop a system
     * notification per new sender. The relay (UDP or Bluetooth) fills the inbox
     * file; the service is the only thing that runs continuously, so it's the
     * right place to surface arrival. We only read the relay-visible "from"
     * field (already in plaintext as a routing fingerprint), never decrypt — the
     * notification just nudges the user to open the chat.
     */
    private fun startChatNotifier(home: File) = scope.launch {
        val paths = EvoliaPaths(home)
        val store = ChatStore(paths)
        // Pre-seed with any envelopes already on disk so the user isn't spammed
        // for messages they have already seen in an earlier session.
        val seen = store.inboxIds().toMutableSet()
        while (isActive) {
            delay(CHAT_NOTIF_POLL_MS)
            try {
                val newFrom = mutableListOf<String>()
                for (wire in store.readInbox()) {
                    if (seen.add(wire.id)) newFrom.add(wire.from.take(8))
                }
                if (newFrom.isNotEmpty()) postChatNotification(newFrom)
            } catch (_: Exception) {
                // Best-effort — a transient IO error must not break the loop.
            }
        }
    }

    private fun postChatNotification(senders: List<String>) {
        val openChat = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val from = senders.distinct().joinToString(", ")
        val notif = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
            .setContentTitle(getString(R.string.chat_notif_title))
            .setContentText(getString(R.string.chat_notif_content).format(from))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .setContentIntent(openChat)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(CHAT_NOTIF_ID, notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted on API 33+ — silently skip.
        }
    }

    // Android ships a stripped-down BouncyCastle as the default "BC" provider,
    // which web3j's secp256k1 key generation chokes on. Replace it with the
    // bundled full provider before any on-chain crypto runs.
    private fun setupSecurityProvider() {
        if (Security.getProvider("BC")?.javaClass == BouncyCastleProvider::class.java) return
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            // Low-priority channel for the always-on supervisor notification: it
            // must show but never make noise.
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "evolIA", NotificationManager.IMPORTANCE_LOW),
            )
            // Default-priority channel for incoming chat messages: pops/vibrates
            // so the user notices a peer message even when the app is closed.
            nm.createNotificationChannel(
                NotificationChannel(
                    CHAT_CHANNEL_ID,
                    getString(R.string.chat_channel_messages),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    override fun onDestroy() {
        // Close the Bluetooth sockets before cancelling the scope (a blocking
        // accept() won't unwind on coroutine cancellation alone).
        bluetoothChat?.stop()
        bluetoothChat = null
        scope.cancel()
        synchronized(processes) {
            processes.forEach { it.destroy() }
            processes.clear()
        }
        // Children (incl. the chat relay that fills the inbox) are now stopped, so
        // wipe all messages: stopping evolIA leaves nothing confidential on disk.
        purgeChatMessages()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun purgeChatMessages() {
        try {
            ChatStore(EvoliaPaths(File(filesDir, "evolia"))).purgeMessages()
        } catch (_: Exception) {
            // Best-effort: a missing dir or transient IO error must not block teardown.
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "evolia"
        private const val CHAT_CHANNEL_ID = "evolia_chat"
        private const val NOTIF_ID = 1
        private const val CHAT_NOTIF_ID = 2
        private const val RESTART_BACKOFF_MS = 3000L
        private const val CYCLE_MS = 5000L
        private const val ANCHOR_MS = 30_000L
        // Poll the chat inbox a bit more often than the value cycle so a peer
        // message surfaces within a few seconds of arriving on the file system.
        private const val CHAT_NOTIF_POLL_MS = 3000L
    }
}
