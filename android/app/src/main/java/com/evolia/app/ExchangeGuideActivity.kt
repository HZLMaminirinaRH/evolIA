package com.evolia.app

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
 * (*) next to the transferable balance on the dashboard. An on-chain transfer is
 * only settled once a node has ordered and verified it, so it has concrete
 * prerequisites the user controls — this screen states them explicitly, in order,
 * and shows the device's own funding address. No crypto runs here: the address is
 * read from its cached file (it appears after the first on-chain activity).
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

        val body = TextView(this).apply {
            textSize = 15f
            setLineSpacing(8f, 1f)
            text = getString(R.string.guide_body)
        }
        val addressLabel = TextView(this).apply {
            textSize = 13f
            alpha = 0.6f
            setPadding(0, 40, 0, 4)
            text = getString(R.string.guide_address_label)
        }
        val addressValue = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            text = address
        }

        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@ExchangeGuideActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.START
                        setPadding(48, 48, 48, 48)
                        addView(body)
                        addView(addressLabel)
                        addView(addressValue)
                    },
                )
            },
        )
    }
}
