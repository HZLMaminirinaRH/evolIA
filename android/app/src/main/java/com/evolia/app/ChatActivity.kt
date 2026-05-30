package com.evolia.app

import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.evolia.app.chat.ChatIdentity
import com.evolia.app.chat.ChatIdentityStore
import com.evolia.app.chat.ChatManager
import com.evolia.app.chat.ChatStore
import com.evolia.app.core.ActionQueue
import com.evolia.app.core.EvoliaPaths
import com.evolia.app.ui.copyToClipboard
import com.evolia.app.ui.copyrightFooter
import com.evolia.app.ui.sanitizeForDisplay
import java.io.File

/**
 * End-to-end peer chat UI. The crypto identity (ChatIdentity) is loaded from the
 * Keystore-backed store; messages are sealed here and queued to the outbox the
 * Go relay drains, while inbound envelopes (delivered to the inbox) are decrypted
 * for display. The relay never sees plaintext — encryption is end-to-end.
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var manager: ChatManager
    private lateinit var store: ChatStore
    private lateinit var log: TextView
    private lateinit var recipientsButton: Button

    // Multi-recipient: the sender picks one OR many contacts; the same plaintext
    // is sealed once per recipient (each ECDH is per-pair) and queued to each.
    private val selectedContacts = mutableListOf<ChatStore.Contact>()

    // Sent messages: track message ID + recipient fingerprint for delivery status.
    // The display string is paired with metadata so we can mark messages delivered
    // when ACKs arrive. Ephemeral (purged when evolIA stops).
    data class SentMessage(val display: String, val msgId: String, val recipientFp: String)
    private val sent = mutableListOf<SentMessage>()

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            renderLog()
            refreshHandler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.chat_title)

        val paths = EvoliaPaths(File(filesDir, "evolia"))
        store = ChatStore(paths)
        // Each sent message is a valued digital action (sms_sent), drained by the
        // running value loop into V -> BTC-e — chat engagement is rewarded too.
        manager = ChatManager(ChatIdentityStore(paths).loadOrCreate(), store) {
            ActionQueue.enqueue(paths, "sms_sent")
        }

        val myId = TextView(this).apply {
            text = getString(R.string.chat_my_identity).format(manager.myFingerprint)
            setTextIsSelectable(true)
        }
        val shareId = Button(this).apply {
            text = getString(R.string.chat_copy_identity)
            setOnClickListener { shareBundle() }
        }
        val addContact = Button(this).apply {
            text = getString(R.string.chat_add_contact)
            setOnClickListener { promptAddContact() }
        }
        val removeContact = Button(this).apply {
            text = getString(R.string.chat_remove_contact)
            setOnClickListener { promptRemoveContact() }
        }
        // Diagnostic: shows transport state (BT/Wi-Fi on/off + bonded peer count)
        // so a user reporting "Bluetooth is on but message lost" can see immediately
        // that no peer is paired at the OS level — the real common failure mode.
        val diagnostic = Button(this).apply {
            text = getString(R.string.chat_diag_button)
            setOnClickListener { showTransportDiagnostic() }
        }
        // Multi-recipient picker: tap to open a checkbox list of contacts; the
        // button label shows who's currently selected, so the user can see who
        // a message will go to before pressing Send.
        recipientsButton = Button(this).apply {
            setOnClickListener { promptRecipients() }
        }
        updateRecipientsLabel()

        log = TextView(this).apply {
            // Defence-in-depth: never auto-linkify received text, and never let a
            // future change make any link tappable — received content stays inert.
            autoLinkMask = 0
            linksClickable = false
            typeface = Typeface.create("cursive", Typeface.ITALIC)
        }
        val scroll = ScrollView(this).apply { addView(log) }

        val input = EditText(this).apply {
            hint = getString(R.string.chat_message_hint)
            filters = arrayOf(android.text.InputFilter.LengthFilter(ChatManager.MAX_MESSAGE_CHARS))
        }
        val send = Button(this).apply {
            text = getString(R.string.chat_send)
            setOnClickListener { sendMessage(input) }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 60, 40, 40)
                addView(myId)
                addView(shareId)
                addView(addContact)
                addView(removeContact)
                addView(diagnostic)
                addView(recipientsButton)
                addView(
                    scroll,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
                )
                addView(input)
                addView(send)
                addView(copyrightFooter(this@ChatActivity))
            },
        )
        renderLog()
        // Messaging rides Bluetooth (offline) and Wi-Fi/LAN (UDP relay), so nudge
        // the user to switch on whichever radio is off — apps can't toggle them
        // silently on modern Android, so we point at the right settings screen.
        promptEnableRadiosIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshTick)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshTick)
    }

    private fun sendMessage(input: EditText) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!isEvoliaRunning()) {
            toast(getString(R.string.chat_inactive))
            return
        }
        if (selectedContacts.isEmpty()) {
            toast(getString(R.string.chat_no_contact))
            return
        }
        // Seal once per recipient (each pair has its own ECDH key). Track who
        // succeeded so the user sees partial progress when one bundle is bad.
        val failed = mutableListOf<String>()
        for (c in selectedContacts) {
            val msgId = manager.send(c.bundleHex, text)
            if (msgId != null) {
                // Resolve the recipient's fingerprint from the bundle.
                val recipientFp = ChatIdentity.fingerprintFromBundle(c.bundleHex) ?: "?"
                sent.add(
                    SentMessage(
                        display = getString(R.string.chat_me_prefix)
                            .format(sanitizeForDisplay(c.name), sanitizeForDisplay(text)),
                        msgId = msgId,
                        recipientFp = recipientFp
                    )
                )
            } else {
                failed.add(sanitizeForDisplay(c.name))
            }
        }
        if (failed.size < selectedContacts.size) {
            // At least one recipient took the message — clear the composer.
            input.text.clear()
            renderLog()
        }
        if (failed.isNotEmpty()) {
            toast(getString(R.string.chat_send_failed_some).format(failed.joinToString(", ")))
        }
    }

    private fun updateRecipientsLabel() {
        val names = selectedContacts.joinToString(", ") { sanitizeForDisplay(it.name) }
        recipientsButton.text = if (names.isEmpty()) {
            getString(R.string.chat_recipients_none)
        } else {
            getString(R.string.chat_recipients_label).format(names)
        }
    }

    private fun promptRecipients() {
        val all = store.contacts()
        if (all.isEmpty()) {
            toast(getString(R.string.chat_no_contacts))
            return
        }
        // Build the checked-list dialog: each entry shows the name + the 8-char
        // fingerprint prefix so the user can disambiguate two contacts with the
        // same chosen name (since name is just a local label).
        val labels = all.map { c ->
            val fp = ChatIdentity.fingerprintFromBundle(c.bundleHex)?.take(8) ?: "?"
            "${sanitizeForDisplay(c.name)}  ($fp…)"
        }.toTypedArray()
        val checked = BooleanArray(all.size) { i ->
            selectedContacts.any { it.bundleHex == all[i].bundleHex }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.chat_recipients_pick))
            .setMultiChoiceItems(labels, checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton(getString(R.string.auth_ok)) { _, _ ->
                selectedContacts.clear()
                for (i in all.indices) if (checked[i]) selectedContacts.add(all[i])
                updateRecipientsLabel()
            }
            .setNegativeButton(getString(R.string.auth_cancel), null)
            .show()
    }

    private fun refreshContacts() {
        // A removed contact must drop out of the active recipient set so the next
        // send doesn't try to seal to a vanished bundle.
        val live = store.contacts().map { it.bundleHex }.toSet()
        selectedContacts.removeAll { it.bundleHex !in live }
        updateRecipientsLabel()
    }

    private fun renderLog() {
        // Ephemeral: while evolIA is stopped there is no live session — messages
        // have been purged from disk, so drop the in-memory view too. Nothing
        // confidential survives a stop, on disk or in memory.
        if (!isEvoliaRunning()) {
            sent.clear()
            log.text = getString(R.string.chat_inactive)
            return
        }
        val sb = StringBuilder()
        val delivered = manager.getDeliveredMessageIds()
        // Sanitize per field (before the separators) so a received message can't
        // inject control chars, fake newlines, or bidi-spoof the conversation.
        manager.inbox().forEach { received ->
            sb.append(sanitizeForDisplay(received.senderFingerprint.take(8)))
                .append(" > ")
                .append(sanitizeForDisplay(received.text))
                .append("\n")
            // Auto-send ACK back to sender to confirm delivery.
            // The ACK envelope carries the original message ID, and the sender
            // decrypts + marks it delivered. Idempotent: re-queueing a duplicate
            // ACK is harmless (relay dedups by id).
            manager.sendAck(received.senderFingerprint, received.messageId)
        }
        sent.forEach { msg ->
            sb.append(msg.display)
            if (msg.msgId in delivered) {
                sb.append(" ✓")
            }
            sb.append("\n")
        }
        log.text = if (sb.isEmpty()) getString(R.string.chat_empty) else sb.toString()
    }

    private fun isEvoliaRunning(): Boolean {
        val am = getSystemService(android.app.ActivityManager::class.java)
        return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == EvoliaService::class.java.name }
    }

    private fun shareBundle() {
        copyToClipboard(this, getString(R.string.chat_copy_identity), manager.myBundleHex)
        toast(getString(R.string.msg_copied))
    }

    private fun promptAddContact() {
        val name = EditText(this).apply { hint = getString(R.string.chat_contact_name) }
        val bundle = EditText(this).apply { hint = getString(R.string.chat_contact_bundle) }
        // Live fingerprint preview: as the user pastes the 128-char public key,
        // derive its 16-char fingerprint and show it. The name above is just a
        // local label — what actually routes a message is THIS fingerprint, so
        // surfacing it stops users from confusing the two.
        val fingerprintLabel = TextView(this).apply {
            textSize = 13f
            alpha = 0.7f
            setPadding(0, 16, 0, 0)
            text = getString(R.string.chat_contact_fingerprint_pending)
        }
        bundle.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val b = s?.toString()?.trim().orEmpty()
                val fp = if (b.isEmpty()) null else ChatIdentity.fingerprintFromBundle(b)
                fingerprintLabel.text = if (fp != null) {
                    getString(R.string.chat_contact_fingerprint_label).format(fp)
                } else {
                    getString(R.string.chat_contact_fingerprint_pending)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(bundle)
            addView(fingerprintLabel)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.chat_add_contact))
            .setView(box)
            .setPositiveButton(getString(R.string.auth_ok)) { _, _ ->
                val n = name.text.toString().trim()
                val b = bundle.text.toString().trim()
                when {
                    n.isEmpty() || b.isEmpty() -> toast(getString(R.string.chat_contact_invalid))
                    // Reject a malformed bundle at add time (rather than at the
                    // first send attempt) so the user gets the error immediately.
                    !manager.isValidBundle(b) -> toast(getString(R.string.chat_contact_invalid_bundle))
                    else -> {
                        store.addContact(n, b)
                        refreshContacts()
                        toast(getString(R.string.chat_contact_added))
                    }
                }
            }
            .setNegativeButton(getString(R.string.auth_cancel), null)
            .show()
    }

    private fun promptRemoveContact() {
        val all = store.contacts()
        if (all.isEmpty()) {
            toast(getString(R.string.chat_no_contacts))
            return
        }
        // No longer driven by a single "selected" contact — let the user pick
        // which entry to drop from a list, then confirm.
        val labels = all.map { c ->
            val fp = ChatIdentity.fingerprintFromBundle(c.bundleHex)?.take(8) ?: "?"
            "${sanitizeForDisplay(c.name)}  ($fp…)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.chat_remove_contact))
            .setItems(labels) { _, i ->
                val target = all[i]
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.chat_remove_contact))
                    .setMessage(
                        getString(R.string.chat_remove_contact_confirm)
                            .format(sanitizeForDisplay(target.name)),
                    )
                    .setPositiveButton(getString(R.string.auth_yes)) { _, _ ->
                        manager.removeContact(target.bundleHex)
                        refreshContacts()
                        toast(getString(R.string.chat_contact_removed))
                    }
                    .setNegativeButton(getString(R.string.auth_no), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.auth_cancel), null)
            .show()
    }

    private fun promptEnableRadiosIfNeeded() {
        // Only nag if BOTH transports are off — once either radio is on, peer
        // messaging can flow (BT alone is the offline mesh; Wi-Fi alone is the
        // LAN/UDP relay). Earlier we re-asked for Wi-Fi even when BT was already
        // up and a peer link was established; this stops that.
        val btOff = !isBluetoothOn()
        val wifiOff = !isWifiOn()
        if (!btOff || !wifiOff) return
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_radios_title)
            .setMessage(R.string.chat_enable_both)
            .setPositiveButton(R.string.chat_open_bluetooth_settings) { _, _ ->
                openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            }
            .setNeutralButton(R.string.chat_open_wifi_settings) { _, _ ->
                openSettings(Settings.ACTION_WIFI_SETTINGS)
            }
            .setNegativeButton(R.string.auth_later, null)
            .show()
    }

    /** Show a diagnostic snapshot of peer-to-peer reach: radio state, paired
     *  device names (to spot a "paired a car, not the other phone" mistake),
     *  permission state, and runtime counters (frames sent/received, connect
     *  attempts/successes) so a user reporting "BT on, paired, no delivery" can
     *  see exactly which arm is silent. */
    private fun showTransportDiagnostic() {
        val btOn = isBluetoothOn()
        val wifiOn = isWifiOn()
        val perm = hasBluetoothConnectPermission()
        val bondedNames = try {
            if (btOn && perm) {
                (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                    ?.adapter?.bondedDevices?.map { dev ->
                        val name = try { dev.name ?: "?" } catch (_: SecurityException) { "?" }
                        "  • $name (${dev.address})"
                    }.orEmpty()
            } else emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        val bondedList = if (bondedNames.isEmpty()) {
            "  (none)"
        } else {
            bondedNames.joinToString("\n")
        }
        val stats = readBtStats()
        val paths = EvoliaPaths(File(filesDir, "evolia"))
        val outboxPending = if (paths.chatOutbox.exists()) paths.chatOutbox.readLines().count { it.isNotBlank() } else 0
        val message = getString(R.string.chat_diag_message).format(
            if (btOn) getString(R.string.chat_diag_on) else getString(R.string.chat_diag_off),
            if (perm) getString(R.string.chat_diag_on) else getString(R.string.chat_diag_off),
            bondedNames.size,
            bondedList,
            if (wifiOn) getString(R.string.chat_diag_on) else getString(R.string.chat_diag_off),
            stats.optLong("frames_sent"),
            stats.optLong("connect_attempts"),
            stats.optLong("connect_successes"),
            stats.optLong("accept_count"),
            stats.optLong("frames_received"),
            stats.optLong("intake_rejections"),
            outboxPending,
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_diag_title)
            .setMessage(message)
            .setPositiveButton(R.string.chat_diag_pair_device) { _, _ ->
                openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            }
            .setNegativeButton(R.string.auth_ok, null)
            .show()
    }

    private fun readBtStats(): org.json.JSONObject {
        val paths = EvoliaPaths(File(filesDir, "evolia"))
        if (!paths.chatBtStats.exists()) return org.json.JSONObject()
        return try {
            org.json.JSONObject(paths.chatBtStats.readText())
        } catch (_: Exception) {
            org.json.JSONObject()
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.BLUETOOTH_CONNECT,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isBluetoothOn(): Boolean =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    private fun isWifiOn(): Boolean =
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // No settings activity to handle this — nothing more we can do.
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REFRESH_MS = 4000L
    }
}
