package com.evolia.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.evolia.app.core.ActionQueue
import com.evolia.app.core.EvoliaPaths
import java.io.File

/** Minimal control panel: start/stop the supervisor and show the shared state. */
class MainActivity : AppCompatActivity() {

    // Ask for the radio/notification permissions, then start regardless — the
    // sensor layer degrades gracefully if any are denied.
    private val startWithPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, EvoliaService::class.java),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this)

        val start = Button(this).apply {
            text = "Démarrer Evolia"
            setOnClickListener {
                startWithPermissions.launch(neededPermissions())
                status.text = readStatus()
            }
        }

        val stop = Button(this).apply {
            text = "Arrêter"
            setOnClickListener {
                stopService(Intent(this@MainActivity, EvoliaService::class.java))
                status.text = "Arrêté."
            }
        }

        val refresh = Button(this).apply {
            text = "Rafraîchir l'état"
            setOnClickListener { status.text = readStatus() }
        }

        val recordVideo = Button(this).apply {
            text = "Action: vidéo (+8 BTC-e)"
            setOnClickListener {
                ActionQueue.enqueue(EvoliaPaths(File(filesDir, "evolia")), "video_taken")
                status.text = "Action enregistrée (sera prise au prochain cycle)."
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 80, 40, 40)
                addView(start)
                addView(stop)
                addView(recordVideo)
                addView(refresh)
                addView(status)
            },
        )
        status.text = readStatus()
    }

    private fun neededPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return perms.toTypedArray()
    }

    private fun readStatus(): String {
        val file = File(File(filesDir, "evolia"), "evolia_identity_state.json")
        return if (file.exists()) "État partagé:\n" + file.readText() else "Pas encore d'état."
    }
}
