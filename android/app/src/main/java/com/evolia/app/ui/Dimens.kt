package com.evolia.app.ui

import android.content.Context
import android.view.View

/**
 * Density-independent sizing helpers. The app builds its UI in code (no XML
 * layouts), and several screens passed raw pixel values to setPadding(...),
 * which renders physically larger or smaller depending on each peer's screen
 * density — the "ajuster l'affichage selon les dimensions des écrans" the user
 * asked for. These convert dp to px against the device's real density so a
 * padding looks the same on every phone.
 *
 * (textSize is NOT here: View.textSize already interprets its argument as SP,
 * so those values were density-independent all along — only raw-pixel paddings
 * needed fixing.)
 */
fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

fun View.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

/** Set padding given in dp (converted to px against the live density). */
fun View.setPaddingDp(left: Int, top: Int, right: Int, bottom: Int) =
    setPadding(dp(left), dp(top), dp(right), dp(bottom))
