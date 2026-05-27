package com.evolia.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import com.evolia.app.core.BitcoinBridge
import com.evolia.app.core.Dashboard
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
    private lateinit var startButton: Button
    private var pendingStart = false

    // Live dashboard refresh while the activity is in the foreground.
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            updateStatus()
            refreshHandler.postDelayed(this, REFRESH_MS)
        }
    }

    // Permissions are requested only after auth succeeds; then start the service.
    private val startWithPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (pendingStart) {
            pendingStart = false
            ContextCompat.startForegroundService(this, Intent(this, EvoliaService::class.java))
            updateStatus()
            // Value accrues in the background only if the OS doesn't doze the
            // service; ask the user to lift battery restrictions for evolIA.
            requestBatteryExemptionIfNeeded()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this)

        startButton = Button(this).apply {
            text = getString(R.string.btn_start)
            setOnClickListener { authenticateThenStart() }
        }
        val stop = Button(this).apply {
            text = getString(R.string.btn_stop)
            setOnClickListener {
                stopService(Intent(this@MainActivity, EvoliaService::class.java))
                updateStatus()
            }
        }
        val refresh = Button(this).apply {
            text = getString(R.string.btn_refresh)
            setOnClickListener { updateStatus() }
        }
        val chat = Button(this).apply {
            text = getString(R.string.btn_chat)
            setOnClickListener { startActivity(Intent(this@MainActivity, ChatActivity::class.java)) }
        }
        val recordVideo = Button(this).apply {
            text = getString(R.string.btn_actions)
            setOnClickListener {
                ActionQueue.enqueue(EvoliaPaths(File(filesDir, "evolia")), "video_taken")
                toast(getString(R.string.msg_action_recorded))
            }
        }
        val convertBtc = Button(this).apply {
            text = getString(R.string.btn_convert)
            setOnClickListener {
                val paths = EvoliaPaths(File(filesDir, "evolia"))
                val bridge = BitcoinBridge(paths)
                bridge.load()
                val v = Dashboard.collect(paths).personal.totalV
                val conv = bridge.queueConversion(v)
                toast(getString(R.string.msg_conversion).format(v, conv.optLong("sat")))
                updateStatus()
            }
        }

        val copyright = TextView(this).apply {
            text = getString(R.string.copyright)
            textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            alpha = 0.3f
            textSize = 10f
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 80, 40, 40)
                addView(startButton)
                addView(stop)
                addView(recordVideo)
                addView(convertBtc)
                addView(chat)
                addView(refresh)
                addView(status, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { weight = 1f })
                addView(copyright)
            },
        )
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshTick)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshTick)
    }

    private fun updateStatus() {
        status.text = readStatus()
        val isRunning = isEvoliaRunning()
        startButton.visibility = if (isRunning) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun isEvoliaRunning(): Boolean {
        val manager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == EvoliaService::class.java.name }
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
        // Fleet key (password-derived) so the Go mesh layer signs/verifies blocks
        // across this owner's devices.
        val meshKey = Security.deriveFleetKey(password)

        val paths = EvoliaPaths(File(filesDir, "evolia"))
        paths.home.mkdirs()
        paths.sessionState.writeText(
            JSONObject()
                .put("token", token)
                .put("device_id", deviceId)
                .put("mesh_key", meshKey)
                .toString(),
        )

        pendingStart = true
        startWithPermissions.launch(neededPermissions())
    }

    private fun promptSetup(store: AuthStore, onDone: (String) -> Unit) {
        promptSecret(getString(R.string.setup_pin), numeric = true) { pin ->
            if (!AuthStore.isValidPin(pin)) {
                toast(getString(R.string.msg_pin_invalid))
            } else {
                promptSecret(getString(R.string.setup_password), numeric = false) { pw ->
                    if (!AuthStore.isValidPassword(pw)) {
                        toast(getString(R.string.msg_password_invalid))
                    } else {
                        confirm(getString(R.string.setup_biometric)) { bio ->
                            store.setup(pin, pw, bio)
                            toast(getString(R.string.msg_auth_configured))
                            onDone(pw)
                        }
                    }
                }
            }
        }
    }

    private fun promptPin(store: AuthStore, attempts: Int = MAX_ATTEMPTS, onOk: () -> Unit) {
        promptSecret(getString(R.string.auth_pin), numeric = true) { pin ->
            when {
                store.verifyPin(pin) -> onOk()
                attempts > 1 -> {
                    toast(getString(R.string.msg_pin_incorrect).format(attempts - 1))
                    promptPin(store, attempts - 1, onOk)
                }
                else -> toast(getString(R.string.msg_auth_failed))
            }
        }
    }

    private fun promptPassword(store: AuthStore, attempts: Int = MAX_ATTEMPTS, onOk: (String) -> Unit) {
        promptSecret(getString(R.string.auth_password), numeric = false) { pw ->
            when {
                store.verifyPassword(pw) -> onOk(pw)
                attempts > 1 -> {
                    toast(getString(R.string.msg_password_incorrect).format(attempts - 1))
                    promptPassword(store, attempts - 1, onOk)
                }
                else -> toast(getString(R.string.msg_auth_failed))
            }
        }
    }

    private fun promptBiometric(onSuccess: () -> Unit) {
        val canAuth = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            toast(getString(R.string.msg_biometric_unavailable))
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
                .setTitle(getString(R.string.auth_biometric))
                .setSubtitle(getString(R.string.auth_confirm))
                .setNegativeButtonText(getString(R.string.auth_cancel))
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
            .setPositiveButton(getString(R.string.auth_ok)) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(getString(R.string.auth_cancel), null)
            .show()
    }

    private fun confirm(message: String, onChoice: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.auth_yes)) { _, _ -> onChoice(true) }
            .setNegativeButton(getString(R.string.auth_no)) { _, _ -> onChoice(false) }
            .show()
    }

    // evolIA keeps running until the user presses Stop, but Doze/battery
    // optimization can suspend the foreground service in the background. Offer the
    // user the standard exemption so value keeps accruing while the app is closed.
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemptionIfNeeded() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.battery_opt_title))
            .setMessage(getString(R.string.battery_opt_message))
            .setPositiveButton(getString(R.string.battery_opt_allow)) { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName")),
                    )
                } catch (_: ActivityNotFoundException) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: ActivityNotFoundException) {
                        // No battery-optimization screen available — leave as is.
                    }
                }
            }
            .setNegativeButton(getString(R.string.auth_later), null)
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
            perms += Manifest.permission.BLUETOOTH_CONNECT
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
        val paths = EvoliaPaths(File(filesDir, "evolia"))
        val sb = StringBuilder()
        if (paths.walletAddress.exists()) {
            sb.append(getString(R.string.dashboard_wallet))
                .append("\n")
                .append(paths.walletAddress.readText())
                .append("\n\n")
        }
        sb.append(Dashboard.render(Dashboard.collect(paths)))
        return sb.toString()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val SESSION_SECS = 8L * 3600
        const val REFRESH_MS = 5000L
    }
}
