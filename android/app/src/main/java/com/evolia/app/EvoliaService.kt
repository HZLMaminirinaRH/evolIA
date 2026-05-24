package com.evolia.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.evolia.app.core.ActionQueue
import com.evolia.app.core.EvoliaAnchor
import com.evolia.app.core.EvoliaPaths
import com.evolia.app.core.EvoliaValue
import com.evolia.app.sensors.AndroidSensors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private val processes = mutableListOf<Process>()

    // Go binaries, packaged as lib*.so so Android extracts them executable.
    private val binaries = listOf(
        "libevolia_net.so",
        "libevolia_mesh_sync.so",
        "libevolia_bridge.so",
    )

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "evolia:supervisor").apply {
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Supervision des services Evolia…"))

        val home = File(filesDir, "evolia").apply { mkdirs() }

        // Native value engine (Phase 2): runs in-process, no Python, no signal 9.
        startValueLoop(home)

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
        val sensors = AndroidSensors(this@EvoliaService).apply { start() }
        val startedAt = System.nanoTime()
        var lastAnchorMs = 0L
        try {
            while (isActive) {
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
                for ((kind, count) in ActionQueue.drain(paths)) value.recordAction(kind, count)
                value.cycle(sensors.sample(), elapsed)
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastAnchorMs >= ANCHOR_MS) {
                    EvoliaAnchor.syncOnce(paths)
                    lastAnchorMs = nowMs
                }
                delay(CYCLE_MS)
            }
        } finally {
            sensors.stop()
        }
    }

    private fun superviseBinary(binary: File, home: File) = scope.launch {
        while (isActive) {
            try {
                val builder = ProcessBuilder(binary.absolutePath)
                    .directory(home)
                    .redirectErrorStream(true)
                builder.environment()["EVOLIA_HOME"] = home.absolutePath
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "evolIA",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        synchronized(processes) {
            processes.forEach { it.destroy() }
            processes.clear()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "evolia"
        private const val NOTIF_ID = 1
        private const val RESTART_BACKOFF_MS = 3000L
        private const val CYCLE_MS = 5000L
        private const val ANCHOR_MS = 30_000L
    }
}
