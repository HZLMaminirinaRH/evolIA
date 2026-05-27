package com.evolia.app.sensors

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A self-drawn compass dial — no image assets, everything is painted on the
 * Canvas so it scales to any screen. setAzimuth() rotates the whole card (ticks,
 * cardinal letters and the needle) opposite to the device heading, so North
 * always points to real north while the phone's top edge is your heading.
 */
class CompassView(context: Context) : View(context) {

    private var azimuth = 0.0 // device heading, degrees

    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.LTGRAY
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        strokeWidth = 3f
    }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val southPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    fun setAzimuth(deg: Double) {
        azimuth = deg
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.8f
        labelPaint.textSize = radius * 0.18f

        canvas.drawCircle(cx, cy, radius, dialPaint)

        // Rotate the dial opposite to the heading so North tracks reality.
        canvas.save()
        canvas.rotate(-azimuth.toFloat(), cx, cy)

        var a = 0
        while (a < 360) {
            val rad = Math.toRadians(a.toDouble())
            val inner = radius * (if (a % 90 == 0) 0.82f else 0.9f)
            canvas.drawLine(
                cx + (radius * sin(rad)).toFloat(),
                cy - (radius * cos(rad)).toFloat(),
                cx + (inner * sin(rad)).toFloat(),
                cy - (inner * cos(rad)).toFloat(),
                tickPaint,
            )
            a += 30
        }

        // Needle: red half points North (up), white half points South (down).
        val needle = radius * 0.7f
        val baseW = radius * 0.12f
        canvas.drawPath(triangle(cx, cy, needle, baseW, up = true), northPaint)
        canvas.drawPath(triangle(cx, cy, needle, baseW, up = false), southPaint)

        drawCardinal(canvas, "N", cx, cy, radius, 0.0)
        drawCardinal(canvas, "E", cx, cy, radius, 90.0)
        drawCardinal(canvas, "S", cx, cy, radius, 180.0)
        drawCardinal(canvas, "W", cx, cy, radius, 270.0)

        canvas.restore()
    }

    private fun triangle(cx: Float, cy: Float, len: Float, baseW: Float, up: Boolean): Path {
        val tip = if (up) cy - len else cy + len
        return Path().apply {
            moveTo(cx, tip)
            lineTo(cx - baseW, cy)
            lineTo(cx + baseW, cy)
            close()
        }
    }

    private fun drawCardinal(canvas: Canvas, label: String, cx: Float, cy: Float, radius: Float, angleDeg: Double) {
        val rad = Math.toRadians(angleDeg)
        val r = radius * 0.6f
        canvas.drawText(
            label,
            cx + (r * sin(rad)).toFloat(),
            cy - (r * cos(rad)).toFloat() + labelPaint.textSize / 3f,
            labelPaint,
        )
    }
}
