package com.evolia.app.sensors

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.evolia.app.core.SensorSample
import java.util.Collections
import kotlin.math.sqrt

/**
 * Android replacement for the termux-api sensor readers. Motion streams via
 * SensorManager; WiFi via WifiManager; BLE via a continuous low-power scan whose
 * distinct devices are counted per cycle; location via the last known fix.
 *
 * Every reader is permission- and null-guarded and degrades to a neutral value
 * (0 / false), exactly like the Python readers off-device — so a denied
 * permission or absent radio never breaks the value loop.
 */
class AndroidSensors(context: Context) : SensorEventListener {

    private val app = context.applicationContext
    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Volatile private var accelerometer = 0.0
    @Volatile private var gyroscope = 0.0
    @Volatile private var magnetometer = 0.0

    private val bleDevices = Collections.synchronizedSet(mutableSetOf<String>())
    private var bleScanner: BluetoothLeScanner? = null
    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Only nearby devices count as a real interaction (mirror of the
            // RSSI filter in evolia_sensors.py); a far, weak signal is noise.
            if (result.rssi >= NEAR_RSSI_DBM) bleDevices.add(result.device.address)
        }
    }

    fun start() {
        // Linear acceleration (gravity removed) is the movement signal, matching
        // evolia_sensors.read_motion — ~0 at rest, rising with real motion.
        register(Sensor.TYPE_LINEAR_ACCELERATION)
        register(Sensor.TYPE_GYROSCOPE)
        register(Sensor.TYPE_MAGNETIC_FIELD)
        startBle()
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        stopBle()
    }

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
            Sensor.TYPE_LINEAR_ACCELERATION -> accelerometer = magnitude
            Sensor.TYPE_GYROSCOPE -> gyroscope = magnitude
            Sensor.TYPE_MAGNETIC_FIELD -> magnetometer = magnitude
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- radios --------------------------------------------------------------

    private fun startBle() {
        if (!hasPermission(bleScanPermission())) return
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return
        bleScanner = adapter.bluetoothLeScanner
        try {
            bleScanner?.startScan(bleCallback)
        } catch (_: SecurityException) {
        }
    }

    private fun stopBle() {
        try {
            bleScanner?.stopScan(bleCallback)
        } catch (_: Exception) {
        }
        bleScanner = null
    }

    private fun bleScanPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun wifiCount(): Int = try {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wm.scanResults?.count { it.level >= NEAR_RSSI_DBM } ?: 0
    } catch (_: Exception) {
        0
    }

    private fun hasLocationFix(): Boolean {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return false
        }
        return try {
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).any { p ->
                lm.isProviderEnabled(p) && lm.getLastKnownLocation(p) != null
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    fun sample(): SensorSample {
        // Distinct BLE devices seen since the last cycle, then reset the window.
        val ble = synchronized(bleDevices) {
            val count = bleDevices.size
            bleDevices.clear()
            count
        }
        return SensorSample(
            accelerometer = accelerometer,
            gyroscope = gyroscope,
            magnetometer = magnetometer,
            locationFix = hasLocationFix(),
            wifiCount = wifiCount(),
            bleCount = ble,
        )
    }

    companion object {
        // Minimum signal strength (dBm) to count an AP/device as a nearby
        // interaction. Must match NEAR_RSSI_DBM in evolia_sensors.py.
        private const val NEAR_RSSI_DBM = -70
    }
}
