package com.evolia.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Copy text to the system clipboard. Keeps "sharing" an address or identity
 * entirely inside the app — no outward Intent (ACTION_SEND) to another app — so
 * there is no link or hand-off pointing outside evolIA. The user pastes wherever
 * they choose.
 */
fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
