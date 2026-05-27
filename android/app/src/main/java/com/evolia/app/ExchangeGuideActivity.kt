package com.evolia.app

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.evolia.app.core.EvoliaPaths
import java.io.File

/**
 * "What you must do" page for on-chain BTC-e exchange, opened from the clickable
 * (*) next to the transferable balance on the dashboard. evolIA's users aren't all
 * crypto-literate, so this is a plain-language, beginner-oriented walkthrough: what
 * an RPC is and how to point evolIA at one, what "gas" is and how to obtain it
 * (free on a test network, or via an exchange topped up with mobile money / card on
 * a real network), and how sending/receiving works — including the key clarification
 * that BTC-e moves between evolIA users and is NOT a coin traded on Binance/OKX.
 * No crypto runs here: the device address is read from its cached file.
 */
class ExchangeGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.guide_title)

        val paths = EvoliaPaths(File(filesDir, "evolia"))
        val address = if (paths.walletAddress.exists()) {
            paths.walletAddress.readText().trim()
        } else {
            getString(R.string.guide_address_pending)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(48, 48, 48, 48)
        }
        column.addView(paragraph(getString(R.string.guide_body)))
        section(column, R.string.guide_s1_title, R.string.guide_s1_body)
        section(column, R.string.guide_s2_title, R.string.guide_s2_body)
        section(column, R.string.guide_s3_title, R.string.guide_s3_body)
        section(column, R.string.guide_s4_title, R.string.guide_s4_body)
        column.addView(
            TextView(this).apply {
                textSize = 13f
                alpha = 0.6f
                setPadding(0, 40, 0, 4)
                text = getString(R.string.guide_address_label)
            },
        )
        column.addView(
            TextView(this).apply {
                textSize = 14f
                setTextIsSelectable(true)
                text = address
            },
        )

        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun section(parent: LinearLayout, titleRes: Int, bodyRes: Int) {
        parent.addView(
            TextView(this).apply {
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 36, 0, 8)
                text = getString(titleRes)
            },
        )
        parent.addView(paragraph(getString(bodyRes)))
    }

    private fun paragraph(content: String): TextView = TextView(this).apply {
        textSize = 15f
        setLineSpacing(8f, 1f)
        text = content
    }
}
