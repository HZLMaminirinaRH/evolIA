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
import com.evolia.app.chat.ChatIdentity
import com.evolia.app.chat.ChatIdentityStore
import com.evolia.app.chat.ChatManager
import com.evolia.app.chat.ChatStore
import com.evolia.app.core.ActionQueue
import com.evolia.app.core.BitcoinBridge
import com.evolia.app.core.Dashboard
import com.evolia.app.core.EvoliaPaths
import com.evolia.app.core.EvoliaValue
import com.evolia.app.security.AuthStore
import com.evolia.app.security.Security
import com.evolia.app.ui.TransferNotify
import com.evolia.app.ui.copyToClipboard
import com.evolia.app.ui.copyrightFooter
import com.evolia.app.ui.sanitizeForDisplay
import com.evolia.app.ui.setPaddingDp
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
                try {
                    ActionQueue.enqueue(EvoliaPaths(File(filesDir, "evolia")), "video_taken")
                    toast(getString(R.string.msg_action_recorded))
                } catch (e: Exception) {
                    toast("Failed to record action: ${e.message ?: "unknown error"}")
                }
            }
        }
        val convertBtc = Button(this).apply {
            text = getString(R.string.btn_convert)
            setOnClickListener {
                try {
                    val paths = EvoliaPaths(File(filesDir, "evolia"))
                    val bridge = BitcoinBridge(paths)
                    bridge.load()
                    val v = Dashboard.collect(paths).personal.totalV
                    val conv = bridge.queueConversion(v)
                    toast(getString(R.string.msg_conversion).format(v, conv.optLong("sat")))
                    updateStatus()
                } catch (e: Exception) {
                    toast("Failed to queue conversion: ${e.message ?: "unknown error"}")
                }
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
        // Pointing evolIA at a blockchain node is a sensitive owner action (it
        // decides which RPC the app trusts), so it's behind the same 3-layer auth
        // as Start/Transfer, and the inputs are strictly validated before saving.
        val chainConfig = Button(this).apply {
            text = getString(R.string.btn_chain_config)
            setOnClickListener { authenticate(allowSetup = false) { promptChainConfig() } }
        }

        val copyright = copyrightFooter(this)

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingDp(16, 32, 16, 16)
                addView(stop)
                addView(recordVideo)
                addView(convertBtc)
                addView(transfer)
                addView(receive)
                addView(chainConfig)
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
        // Auth-on-launch: opening the app IS the start gesture. If evolIA isn't
        // already running, prompt for PIN + password (and biometric/setup if
        // applicable) and start the service the moment auth succeeds. Cancelling
        // any step closes the activity — there is no in-app "Start" fallback by
        // design, so the user's only path to a running evolIA is sign-in on launch.
        if (!isEvoliaRunning()) {
            authenticate(allowSetup = true, onCancel = { finish() }) { password ->
                onAuthenticated(password)
            }
        }
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
        try {
            status.text = buildStatusText()
            status.movementMethod = LinkMovementMethod.getInstance()
        } catch (e: Exception) {
            status.text = "Failed to load status: ${e.message ?: "unknown error"}"
        }
    }

    private fun isEvoliaRunning(): Boolean {
        val manager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == EvoliaService::class.java.name }
    }

    // --- auth gate -----------------------------------------------------------

    /**
     * Owner auth gate (PIN -> password -> optional biometric) — the Android port
     * of evolia-start's three layers, reused for every owner action. Yields the
     * verified password to [onOk]. When auth isn't configured yet, [allowSetup]
     * decides whether to run first-time setup (the launch flow) or refuse (an action
     * that presupposes an existing owner, e.g. an on-chain transfer). [onCancel]
     * fires if the user dismisses any auth dialog — the launch flow uses it to
     * finish() the activity (auth is mandatory), per-action callers default to a
     * silent no-op (the dashboard stays put).
     */
    private fun authenticate(allowSetup: Boolean, onCancel: () -> Unit = {}, onOk: (String) -> Unit) {
        val store = AuthStore(EvoliaPaths(File(filesDir, "evolia")))
        if (!store.isConfigured()) {
            if (allowSetup) promptSetup(store, onCancel) { password -> onOk(password) }
            else toast(getString(R.string.msg_auth_needed))
            return
        }
        promptPin(store, onCancel = onCancel) {
            promptPassword(store, onCancel = onCancel) { password ->
                val cfg = store.load()
                if (cfg?.biometricEnabled == true) promptBiometric(onCancel) { onOk(password) } else onOk(password)
            }
        }
    }

    private fun onAuthenticated(password: String) {
        try {
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
        } catch (e: Exception) {
            toast("Failed to initialize session: ${e.message ?: "unknown error"}")
        }
    }

    private fun promptSetup(store: AuthStore, onCancel: () -> Unit, onDone: (String) -> Unit) {
        promptSecret(getString(R.string.setup_pin), numeric = true, onCancel = onCancel) { pin ->
            if (!AuthStore.isValidPin(pin)) {
                toast(getString(R.string.msg_pin_invalid))
            } else {
                promptSecret(getString(R.string.setup_password), numeric = false, onCancel = onCancel) { pw ->
                    if (!AuthStore.isValidPassword(pw)) {
                        toast(getString(R.string.msg_password_invalid))
                    } else {
                        confirm(getString(R.string.setup_biometric), onCancel = onCancel) { bio ->
                            try {
                                store.setup(pin, pw, bio)
                                toast(getString(R.string.msg_auth_configured))
                                onDone(pw)
                            } catch (e: Exception) {
                                toast("Auth setup failed: ${e.message ?: "unknown error"}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun promptPin(store: AuthStore, attempts: Int = MAX_ATTEMPTS, onCancel: () -> Unit = {}, onOk: () -> Unit) {
        promptSecret(getString(R.string.auth_pin), numeric = true, onCancel = onCancel) { pin ->
            val ok = try { store.verifyPin(pin) } catch (_: Exception) { false }
            when {
                ok -> onOk()
                attempts > 1 -> {
                    toast(getString(R.string.msg_pin_incorrect).format(attempts - 1))
                    promptPin(store, attempts - 1, onCancel, onOk)
                }
                else -> toast(getString(R.string.msg_auth_failed))
            }
        }
    }

    private fun promptPassword(store: AuthStore, attempts: Int = MAX_ATTEMPTS, onCancel: () -> Unit = {}, onOk: (String) -> Unit) {
        promptSecret(getString(R.string.auth_password), numeric = false, onCancel = onCancel) { pw ->
            val ok = try { store.verifyPassword(pw) } catch (_: Exception) { false }
            when {
                ok -> onOk(pw)
                attempts > 1 -> {
                    toast(getString(R.string.msg_password_incorrect).format(attempts - 1))
                    promptPassword(store, attempts - 1, onCancel, onOk)
                }
                else -> toast(getString(R.string.msg_auth_failed))
            }
        }
    }

    private fun promptBiometric(onCancel: () -> Unit = {}, onSuccess: () -> Unit) {
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
                    // Treat any biometric error (negative-button tap, lockout,
                    // user cancel) as a cancel so the launch flow can finish().
                    onCancel()
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

    private fun promptSecret(title: String, numeric: Boolean, onCancel: () -> Unit = {}, onOk: (String) -> Unit) {
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
            .setNegativeButton(getString(R.string.auth_cancel)) { _, _ -> onCancel() }
            .show()
    }

    private fun confirm(message: String, @Suppress("UNUSED_PARAMETER") onCancel: () -> Unit = {}, onChoice: (Boolean) -> Unit) {
        // The biometric-enrolment confirm has two valid completions (Yes/No, both
        // proceed setup); onCancel is accepted for signature symmetry with the
        // other prompt helpers but is never invoked.
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

    // --- on-chain transfer / receive + P2P local transfer --------------------

    private fun promptTransfer() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.transfer_choice_title))
            .setMessage(getString(R.string.transfer_choice_message))
            .setPositiveButton(getString(R.string.transfer_choice_onchain)) { _, _ ->
                promptOnchainTransfer()
            }
            .setNegativeButton(getString(R.string.transfer_choice_local)) { _, _ ->
                promptLocalTransfer()
            }
            .show()
    }

    private fun promptOnchainTransfer() {
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
            setPaddingDp(20, 10, 20, 0)
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

    private fun promptLocalTransfer() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.transfer_local_title))
            .setMessage(getString(R.string.transfer_local_warning))
            .setPositiveButton(getString(R.string.auth_ok)) { _, _ ->
                promptLocalTransferDetails()
            }
            .setNegativeButton(getString(R.string.auth_cancel), null)
            .show()
    }

    private fun promptLocalTransferDetails() {
        // An OFFLINE promise must travel to the peer over the sealed Bluetooth/UDP
        // mesh, which is addressed by chat contact (not a raw 0x address) — so the
        // recipient is picked from the chat contacts, and the amount is sealed E2E.
        val paths = EvoliaPaths(File(filesDir, "evolia"))
        val store = ChatStore(paths)
        val contacts = store.contacts()
        if (contacts.isEmpty()) {
            toast(getString(R.string.transfer_local_no_contact))
            return
        }
        val labels = contacts.map { c ->
            val fp = ChatIdentity.fingerprintFromBundle(c.bundleHex)?.take(8) ?: "?"
            "${sanitizeForDisplay(c.name)}  ($fp…)"
        }.toTypedArray()
        var chosen = 0
        val amountInput = EditText(this).apply {
            hint = getString(R.string.transfer_amount_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingDp(20, 10, 20, 0)
            addView(amountInput)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.transfer_local_record))
            .setSingleChoiceItems(labels, 0) { _, which -> chosen = which }
            .setView(form)
            .setPositiveButton(getString(R.string.auth_ok)) { _, _ ->
                val amount = amountInput.text.toString().trim().toDoubleOrNull()
                if (amount == null || amount <= 0.0) {
                    toast(getString(R.string.transfer_invalid))
                } else {
                    doOfflineTransfer(contacts[chosen], amount)
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
                // Sender-side "accusé d'envoi": notify only when the chain actually
                // settled it (status success), so an unsettled local fallback never
                // claims a sent receipt it didn't earn.
                if (result.optString("status") == "success") {
                    TransferNotify.notifySent(this, result.optDouble("amount_btce"), result.optString("to"), settled = true)
                }
                updateStatus()
            }
        }.start()
    }

    private fun transferMessage(result: JSONObject): String = when (result.optString("status")) {
        "success" -> getString(R.string.msg_transfer_success).format(result.optDouble("amount_btce"), result.optString("to"))
        "local" -> getString(R.string.msg_transfer_local).format(result.optString("note"))
        else -> getString(R.string.msg_transfer_failed).format(result.optString("error", result.optString("note")))
    }

    private fun doOfflineTransfer(contact: ChatStore.Contact, amountBtce: Double) {
        val paths = EvoliaPaths(File(filesDir, "evolia"))
        // An offline transfer is a peer-to-peer promise: signed by owner auth (just
        // happened), sealed end-to-end to the chosen contact, and carried to the
        // peer over the same dual-transport mesh as chat (Bluetooth offline + UDP).
        // It is NOT on-chain settlement — nothing is minted; the peer simply gets an
        // "accusé de réception". The mesh's adaptive defense catches malicious
        // replays if these promises are shared. The promise is also recorded locally
        // (the existing at-risk ledger) so the sender keeps a trace.
        Thread {
            try {
                val manager = ChatManager(ChatIdentityStore(paths).loadOrCreate(), ChatStore(paths))
                val id = manager.sendTransfer(contact.bundleHex, amountBtce)
                if (id == null) {
                    runOnUiThread { toast(getString(R.string.msg_transfer_local_failed).format("seal failed")) }
                    return@Thread
                }
                // Deduct from local balance immediately (off-chain promise is risky but recorded).
                val valueState = EvoliaValue(paths)
                valueState.load()
                // Safely deduct the amount (clamp to zero to avoid negative balances).
                var currentBalance = Dashboard.collect(paths).personal.totalV
                currentBalance = (currentBalance - amountBtce).coerceAtLeast(0.0)
                // Record the deduction by directly updating the value state.
                val stateJson = JSONObject()
                    .put("total_v", currentBalance)
                    .put("cycle_count", valueState.cycleCount)
                paths.home.mkdirs()
                paths.valueState.writeText(stateJson.toString(2))

                val entry = JSONObject()
                    .put("timestamp", System.currentTimeMillis())
                    .put("to", contact.name)
                    .put("to_fingerprint", ChatIdentity.fingerprintFromBundle(contact.bundleHex) ?: "")
                    .put("amount_btce", amountBtce)
                    .put("status", "local_promise")
                    .put("mode", "offline")
                    .put("envelope_id", id)
                paths.home.mkdirs()
                File(paths.home, "evolia_local_transfers.jsonl").appendText(entry.toString() + "\n")
                runOnUiThread {
                    toast(getString(R.string.msg_transfer_local_recorded).format(amountBtce, contact.name))
                    // Sender-side "accusé d'envoi" for the offline promise.
                    TransferNotify.notifySent(this, amountBtce, contact.name, settled = false)
                    updateStatus()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast(getString(R.string.msg_transfer_local_failed).format(e.message))
                }
            }
        }.start()
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

    // --- on-chain RPC config -------------------------------------------------

    private fun promptChainConfig() {
        val paths = EvoliaPaths(File(filesDir, "evolia"))
        val existing = readChainConfig(paths)
        val rpcInput = EditText(this).apply {
            hint = getString(R.string.chain_config_rpc_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.optString("rpc_url").orEmpty())
        }
        val chainIdInput = EditText(this).apply {
            hint = getString(R.string.chain_config_chainid_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            val cid = existing?.optLong("chain_id", 0L) ?: 0L
            if (cid > 0L) setText(cid.toString())
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingDp(20, 10, 20, 0)
            addView(rpcInput)
            addView(chainIdInput)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.chain_config_title))
            .setView(form)
            .setPositiveButton(getString(R.string.auth_ok)) { _, _ ->
                saveChainConfig(paths, rpcInput.text.toString(), chainIdInput.text.toString())
            }
            .setNegativeButton(getString(R.string.auth_cancel), null)
            .show()
    }

    private fun saveChainConfig(paths: EvoliaPaths, rpcRaw: String, chainIdRaw: String) {
        val rpc = rpcRaw.trim()
        if (!isValidRpcUrl(rpc)) {
            toast(getString(R.string.chain_config_invalid_url))
            return
        }
        val chainId = chainIdRaw.trim().toLongOrNull()
        if (chainId == null || chainId <= 0L) {
            toast(getString(R.string.chain_config_invalid_chainid))
            return
        }
        // Preserve any other keys (e.g. sensory_type) the file may already carry.
        val json = readChainConfig(paths) ?: JSONObject()
        json.put("rpc_url", rpc)
        json.put("chain_id", chainId)
        paths.home.mkdirs()
        // Atomic write (temp + rename), mirroring the rest of the state layer.
        val tmp = File(paths.home, "evolia_chain_config.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(paths.chainConfig)
        toast(getString(R.string.chain_config_saved))
        updateStatus()
    }

    private fun readChainConfig(paths: EvoliaPaths): JSONObject? {
        if (!paths.chainConfig.exists()) return null
        return try {
            JSONObject(paths.chainConfig.readText())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Hardening on the one user-supplied URL evolIA will actually dial. Accept
     * ONLY an http/https endpoint with a real host — no javascript:/file:/data:
     * schemes, no whitespace or control characters, bounded length. http is kept
     * because a local test node (Option C in the guide) is reached over plain
     * http on the LAN; everything else stays out.
     */
    private fun isValidRpcUrl(url: String): Boolean {
        if (url.isEmpty() || url.length > 2048) return false
        if (url.any { it.isWhitespace() || Character.isISOControl(it) }) return false
        val lower = url.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        return try {
            !java.net.URI(url).host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val SESSION_SECS = 8L * 3600
        const val REFRESH_MS = 5000L
    }
}
