package com.evolia.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import com.evolia.app.core.SensorSample
import kotlin.math.sqrt

/**
 * Android replacement for the termux-api sensor readers. Motion sensors stream
 * via SensorManager (we keep the latest vector magnitude); WiFi count comes from
 * WifiManager. Everything degrades gracefully to a neutral value, exactly like
 * the Python readers do off-device.
 *
 * Note: WiFi scan results and BLE/location need runtime permissions on modern
 * Android; without them these return 0/false (Phase 2b wires the prompts).
 */
class AndroidSensors(private val context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Volatile private var accelerometer = 0.0
    @Volatile private var gyroscope = 0.0
    @Volatile private var magnetometer = 0.0

    fun start() {
        register(Sensor.TYPE_ACCELEROMETER)
        register(Sensor.TYPE_GYROSCOPE)
        register(Sensor.TYPE_MAGNETIC_FIELD)
    }

    fun stop() = sensorManager.unregisterListener(this)

    private fun register(type: Int) {
        sensorManager.getDefaultSensor(type)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val n = minOf(3, v.size)
        var sumSq = 0.0
        for (i in 0 until n) sumSq += v[i].toDouble() * v[i]
        val magnitude = sqrt(sumSq)
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelerometer = magnitude
            Sensor.TYPE_GYROSCOPE -> gyroscope = magnitude
            Sensor.TYPE_MAGNETIC_FIELD -> magnetometer = magnitude
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun wifiCount(): Int = try {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wm.scanResults?.size ?: 0
    } catch (_: Exception) {
        0
    }

    fun sample(): SensorSample = SensorSample(
        accelerometer = accelerometer,
        gyroscope = gyroscope,
        magnetometer = magnetometer,
        locationFix = false,
        wifiCount = wifiCount(),
        bleCount = 0,
    )
}
