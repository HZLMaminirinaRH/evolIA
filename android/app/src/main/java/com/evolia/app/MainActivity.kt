package com.evolia.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.evolia.app.core.ActionQueue
import com.evolia.app.core.EvoliaPaths
import com.evolia.app.security.AuthStore
import com.evolia.app.security.Security
import org.json.JSONObject
import java.io.File

/**
 * Control panel + owner auth gate. Starting the supervisor is gated behind the
 * three-layer auth (PIN, password, optional biometric) — the Android port of
 * evolia-start: on success it derives the security key from the verified
 * password, mints a device-bound session token and persists it so the service
 * can pass EVOLIA_SESSION_TOKEN / EVOLIA_DEVICE_ID to the Go children.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private var pendingStart = false

    // Permissions are requested only after auth succeeds; then start the service.
    private val startWithPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (pendingStart) {
            pendingStart = false
            ContextCompat.startForegroundService(this, Intent(this, EvoliaService::class.java))
            status.text = readStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this)

        val start = Button(this).apply {
            text = "Démarrer Evolia"
            setOnClickListener { authenticateThenStart() }
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

    // --- auth gate -----------------------------------------------------------

    private fun authenticateThenStart() {
        val store = AuthStore(EvoliaPaths(File(filesDir, "evolia")))
        if (!store.isConfigured()) {
            promptSetup(store) { password -> onAuthenticated(store, password) }
        } else {
            promptPin(store) {
                promptPassword(store) { password ->
                    val cfg = store.load()
                    if (cfg?.biometricEnabled == true) {
                        promptBiometric { onAuthenticated(store, password) }
                    } else {
                        onAuthenticated(store, password)
                    }
                }
            }
        }
    }

    private fun onAuthenticated(store: AuthStore, password: String) {
        store.markAuthed()
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "android-device"
        val token = Security(deviceId, password).generateSessionToken("owner", SESSION_SECS).token

        val paths = EvoliaPaths(File(filesDir, "evolia"))
        paths.home.mkdirs()
        paths.sessionState.writeText(
            JSONObject().put("token", token).put("device_id", deviceId).toString(),
        )

        pendingStart = true
        startWithPermissions.launch(neededPermissions())
    }

    private fun promptSetup(store: AuthStore, onDone: (String) -> Unit) {
        promptSecret("Créer un PIN (4-6 chiffres)", numeric = true) { pin ->
            if (!AuthStore.isValidPin(pin)) {
                toast("PIN invalide (4-6 chiffres).")
            } else {
                promptSecret("Créer un mot de passe (min 8)", numeric = false) { pw ->
                    if (!AuthStore.isValidPassword(pw)) {
                        toast("Mot de passe trop court (min 8).")
                    } else {
                        confirm("Activer la biométrie (empreinte) ?") { bio ->
                            store.setup(pin, pw, bio)
                            toast("Authentification configurée.")
                            onDone(pw)
                        }
                    }
                }
            }
        }
    }

    private fun promptPin(store: AuthStore, attempts: Int = MAX_ATTEMPTS, onOk: () -> Unit) {
        promptSecret("PIN", numeric = true) { pin ->
            when {
                store.verifyPin(pin) -> onOk()
                attempts > 1 -> {
                    toast("PIN incorrect (${attempts - 1} essai(s) restant(s)).")
                    promptPin(store, attempts - 1, onOk)
                }
                else -> toast("Authentification échouée.")
            }
        }
    }

    private fun promptPassword(store: AuthStore, attempts: Int = MAX_ATTEMPTS, onOk: (String) -> Unit) {
        promptSecret("Mot de passe", numeric = false) { pw ->
            when {
                store.verifyPassword(pw) -> onOk(pw)
                attempts > 1 -> {
                    toast("Mot de passe incorrect (${attempts - 1} essai(s) restant(s)).")
                    promptPassword(store, attempts - 1, onOk)
                }
                else -> toast("Authentification échouée.")
            }
        }
    }

    private fun promptBiometric(onSuccess: () -> Unit) {
        val canAuth = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            toast("Biométrie indisponible — étape ignorée.")
            onSuccess()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    toast("Biométrie: $errString")
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Authentification biométrique")
                .setSubtitle("Confirmez votre identité")
                .setNegativeButtonText("Annuler")
                .build(),
        )
    }

    private fun promptSecret(title: String, numeric: Boolean, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = if (numeric) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirm(message: String, onChoice: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Oui") { _, _ -> onChoice(true) }
            .setNegativeButton("Non") { _, _ -> onChoice(false) }
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

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
        val home = File(filesDir, "evolia")
        val state = File(home, "evolia_identity_state.json")
        val wallet = File(home, "evolia_wallet_address.txt")
        val sb = StringBuilder()
        if (wallet.exists()) sb.append("Wallet (à financer en gas):\n").append(wallet.readText()).append("\n\n")
        sb.append(if (state.exists()) "État partagé:\n" + state.readText() else "Pas encore d'état.")
        return sb.toString()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val SESSION_SECS = 8L * 3600
    }
}
