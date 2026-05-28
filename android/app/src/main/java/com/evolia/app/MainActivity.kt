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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
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
import com.evolia.app.chain.ChainAnchor
import com.evolia.app.core.ActionQueue
import com.evolia.app.core.BitcoinBridge
import com.evolia.app.core.Dashboard
import com.evolia.app.core.EvoliaPaths
import com.evolia.app.security.AuthStore
import com.evolia.app.security.Security
import com.evolia.app.ui.copyToClipboard
import com.evolia.app.ui.copyrightFooter
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
        val compass = Button(this).apply {
            text = getString(R.string.btn_compass)
            setOnClickListener { startActivity(Intent(this@MainActivity, CompassActivity::class.java)) }
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
        // On-chain BTC-e transfer between owners — gated by the strict owner auth
        // (PIN/password/biometric), settled on-chain so it can never double-spend.
        val transfer = Button(this).apply {
            text = getString(R.string.btn_transfer)
            setOnClickListener { authenticate(allowSetup = false) { promptTransfer() } }
        }
        // Receiving is passive on-chain (your balance grows when a peer pays your
        // address); this just shows/shares that address — no auth needed, it's public.
        val receive = Button(this).apply {
            text = getString(R.string.btn_receive)
            setOnClickListener { promptReceive() }
        }

        val copyright = copyrightFooter(this)

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 80, 40, 40)
                addView(startButton)
                addView(stop)
                addView(recordVideo)
                addView(convertBtc)
                addView(transfer)
                addView(receive)
                addView(chat)
                addView(compass)
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
        status.text = buildStatusText()
        status.movementMethod = LinkMovementMethod.getInstance()
        val isRunning = isEvoliaRunning()
        startButton.visibility = if (isRunning) View.GONE else View.VISIBLE
    }

    private fun isEvoliaRunning(): Boolean {
        val manager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == EvoliaService::class.java.name }
    }

    // --- auth gate -----------------------------------------------------------

    private fun authenticateThenStart() = authenticate(allowSetup = true) { password -> onAuthenticated(password) }

    /**
     * Owner auth gate (PIN -> password -> optional biometric) — the Android port
     * of evolia-start's three layers, reused for every owner action. Yields the
     * verified password to [onOk]. When auth isn't configured yet, [allowSetup]
     * decides whether to run first-time setup (the Start flow) or refuse (an action
     * that presupposes an existing owner, e.g. an on-chain transfer).
     */
    private fun authenticate(allowSetup: Boolean, onOk: (String) -> Unit) {
        val store = AuthStore(EvoliaPaths(File(filesDir, "evolia")))
        if (!store.isConfigured()) {
            if (allowSetup) promptSetup(store) { password -> onOk(password) } else toast(getString(R.string.msg_auth_needed))
            return
        }
        promptPin(store) {
            promptPassword(store) { password ->
                val cfg = store.load()
                if (cfg?.biometricEnabled == true) promptBiometric { onOk(password) } else onOk(password)
            }
        }
    }

    private fun onAuthenticated(password: String) {
        val store = AuthStore(EvoliaPaths(File(filesDir, "evolia")))
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms += Manifest.permission.ACTIVITY_RECOGNITION // pedometer
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return perms.toTypedArray()
    }

    private fun buildStatusText(): CharSequence {
        val paths = EvoliaPaths(File(filesDir, "evolia"))
        val sb = SpannableStringBuilder()

        // On-chain transferable balance + a clickable (*) opening the step-by-step
        // guide on exactly what's required to exchange on-chain.
        sb.append(getString(R.string.dashboard_onchain_balance).format(readOnchainBalanceBtce()))
        sb.append(" ")
        val marker = getString(R.string.dashboard_onchain_help)
        val markerStart = sb.length
        sb.append(marker)
        sb.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    startActivity(Intent(this@MainActivity, ExchangeGuideActivity::class.java))
                }
            },
            markerStart,
            markerStart + marker.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.append("\n\n")

        if (paths.walletAddress.exists()) {
            sb.append(getString(R.string.dashboard_wallet))
                .append("\n")
                .append(paths.walletAddress.readText())
                .append("\n\n")
        }
        sb.append(Dashboard.render(Dashboard.collect(paths)))
        return sb
    }

    private fun readOnchainBalanceBtce(): Double {
        val file = EvoliaPaths(File(filesDir, "evolia")).onchainBalance
        if (!file.exists()) return 0.0
        return try {
            JSONObject(file.readText()).optLong("balance_centi", 0L) / 100.0
        } catch (_: Exception) {
            0.0
        }
    }

    // --- on-chain transfer / receive ----------------------------------------

    private fun promptTransfer() {
        val toInput = EditText(this).apply {
            hint = getString(R.string.transfer_to_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val amountInput = EditText(this).apply {
            hint = getString(R.string.transfer_amount_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(toInput)
            addView(amountInput)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.transfer_title))
            .setView(form)
            .setPositiveButton(getString(R.string.auth_ok)) { _, _ ->
                val to = toInput.text.toString().trim()
                val amount = amountInput.text.toString().trim().toDoubleOrNull()
                if (!isValidEthAddress(to) || amount == null || amount <= 0.0) {
                    toast(getString(R.string.transfer_invalid))
                } else {
                    doTransfer(to, Math.round(amount * 100))
                }
            }
            .setNegativeButton(getString(R.string.auth_cancel), null)
            .show()
    }

    private fun doTransfer(to: String, amountCenti: Long) {
        val paths = EvoliaPaths(File(filesDir, "evolia"))
        toast(getString(R.string.msg_transfer_sending))
        // web3j network call — must not run on the main thread.
        Thread {
            val result = ChainAnchor(this, paths).transfer(to, amountCenti)
            runOnUiThread {
                toast(transferMessage(result))
                updateStatus()
            }
        }.start()
    }

    private fun transferMessage(result: JSONObject): String = when (result.optString("status")) {
        "success" -> getString(R.string.msg_transfer_success).format(result.optDouble("amount_btce"), result.optString("to"))
        "local" -> getString(R.string.msg_transfer_local).format(result.optString("note"))
        else -> getString(R.string.msg_transfer_failed).format(result.optString("error", result.optString("note")))
    }

    private fun isValidEthAddress(addr: String): Boolean = addr.matches(Regex("^0x[0-9a-fA-F]{40}$"))

    private fun promptReceive() {
        val paths = EvoliaPaths(File(filesDir, "evolia"))
        toast(getString(R.string.receive_preparing))
        // Resolving the address can create the signing key (crypto) — off the UI thread.
        Thread {
            val address = try {
                ChainAnchor(this, paths).myAddress()
            } catch (_: Exception) {
                ""
            }
            runOnUiThread { showReceiveDialog(address) }
        }.start()
    }

    private fun showReceiveDialog(address: String) {
        if (address.isBlank()) {
            toast(getString(R.string.receive_unavailable))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.receive_title))
            .setMessage(getString(R.string.receive_explain).format(address))
            .setPositiveButton(getString(R.string.auth_ok), null)
            .setNeutralButton(getString(R.string.receive_copy)) { _, _ ->
                copyToClipboard(this, getString(R.string.receive_title), address)
                toast(getString(R.string.msg_copied))
            }
            .show()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val SESSION_SECS = 8L * 3600
        const val REFRESH_MS = 5000L
    }
}
