package com.evolia.app.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import com.evolia.app.R

/**
 * The copyright line shown at the bottom of every screen. Centralised so every
 * page (dashboard, chat, sensors, exchange guide) renders it identically and a
 * single edit changes them all.
 */
fun copyrightFooter(context: Context): TextView = TextView(context).apply {
    text = context.getString(R.string.copyright)
    textAlignment = View.TEXT_ALIGNMENT_CENTER
    alpha = 0.3f
    textSize = 10f
}
