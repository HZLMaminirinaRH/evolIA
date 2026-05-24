package com.evolia.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

/** Minimal control panel: start/stop the supervisor and show the shared state. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this)

        val start = Button(this).apply {
            text = "Démarrer Evolia"
            setOnClickListener {
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, EvoliaService::class.java),
                )
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

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 80, 40, 40)
                addView(start)
                addView(stop)
                addView(refresh)
                addView(status)
            },
        )
        status.text = readStatus()
    }

    private fun readStatus(): String {
        val file = File(File(filesDir, "evolia"), "evolia_identity_state.json")
        return if (file.exists()) "État partagé:\n" + file.readText() else "Pas encore d'état."
    }
}
