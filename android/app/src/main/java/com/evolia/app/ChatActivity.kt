package com.evolia.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.evolia.app.chat.ChatIdentityStore
import com.evolia.app.chat.ChatManager
import com.evolia.app.chat.ChatStore
import com.evolia.app.core.EvoliaPaths
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
    private lateinit var contactSpinner: Spinner

    // Sent messages are not persisted in plaintext (the outbox holds only sealed
    // bodies), so keep a session-local view for the conversation display.
    private val sent = mutableListOf<String>()

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
        manager = ChatManager(ChatIdentityStore(paths).loadOrCreate(), store)

        val myId = TextView(this).apply {
            text = getString(R.string.chat_my_identity).format(manager.myFingerprint)
            setTextIsSelectable(true)
        }
        val shareId = Button(this).apply {
            text = getString(R.string.chat_share_identity)
            setOnClickListener { shareBundle() }
        }
        val addContact = Button(this).apply {
            text = getString(R.string.chat_add_contact)
            setOnClickListener { promptAddContact() }
        }
        contactSpinner = Spinner(this)
        refreshContacts()

        log = TextView(this)
        val scroll = ScrollView(this).apply { addView(log) }

        val input = EditText(this).apply { hint = getString(R.string.chat_message_hint) }
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
                addView(contactSpinner)
                addView(
                    scroll,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
                )
                addView(input)
                addView(send)
            },
        )
        renderLog()
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
        val contact = selectedContact()
        if (contact == null) {
            toast(getString(R.string.chat_no_contact))
            return
        }
        if (manager.send(contact.bundleHex, text)) {
            sent.add(getString(R.string.chat_me_prefix).format(contact.name, text))
            input.text.clear()
            renderLog()
        } else {
            toast(getString(R.string.chat_send_failed))
        }
    }

    private fun selectedContact(): ChatStore.Contact? =
        store.contacts().getOrNull(contactSpinner.selectedItemPosition)

    private fun refreshContacts() {
        val names = store.contacts().map { it.name }
        contactSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            names.ifEmpty { listOf(getString(R.string.chat_no_contacts)) },
        )
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
        manager.inbox().forEach { sb.append(it.senderFingerprint.take(8)).append(" > ").append(it.text).append("\n") }
        sent.forEach { sb.append(it).append("\n") }
        log.text = if (sb.isEmpty()) getString(R.string.chat_empty) else sb.toString()
    }

    private fun isEvoliaRunning(): Boolean {
        val am = getSystemService(android.app.ActivityManager::class.java)
        return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == EvoliaService::class.java.name }
    }

    private fun shareBundle() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, manager.myBundleHex)
                },
                getString(R.string.chat_share_identity),
            ),
        )
    }

    private fun promptAddContact() {
        val name = EditText(this).apply { hint = getString(R.string.chat_contact_name) }
        val bundle = EditText(this).apply { hint = getString(R.string.chat_contact_bundle) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(bundle)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.chat_add_contact))
            .setView(box)
            .setPositiveButton(getString(R.string.auth_ok)) { _, _ ->
                val n = name.text.toString().trim()
                val b = bundle.text.toString().trim()
                if (n.isNotEmpty() && b.isNotEmpty()) {
                    store.addContact(n, b)
                    refreshContacts()
                    toast(getString(R.string.chat_contact_added))
                }
            }
            .setNegativeButton(getString(R.string.auth_cancel), null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REFRESH_MS = 4000L
    }
}
