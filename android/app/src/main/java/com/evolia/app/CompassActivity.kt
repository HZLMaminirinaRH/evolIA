package com.evolia.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.evolia.app.sensors.CompassMath
import com.evolia.app.sensors.CompassView
import com.evolia.app.ui.copyrightFooter
import com.evolia.app.ui.setPaddingDp

/**
 * Live sensor panel ("SENSORS"): a visual compass plus the readings that feed V.
 * The heading comes from the rotation-vector sensor (accelerometer + gyroscope +
 * magnetometer fusion); alongside it we surface the magnetic field, step count,
 * gravity and altitude — the same signals the value model consumes, so the user
 * sees what's being measured while their activity accrues value. Each readout is
 * shown only if the device has that sensor (a missing sensor simply feeds 0 into
 * V). No new permission beyond ACTIVITY_RECOGNITION for the step counter.
 */
class CompassActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var magnetometer: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var pressureSensor: Sensor? = null
    private var stepSensor: Sensor? = null

    private lateinit var compass: CompassView
    private lateinit var headingReadout: TextView
    private var magneticReadout: TextView? = null
    private var stepsReadout: TextView? = null
    private var gravityReadout: TextView? = null
    private var altitudeReadout: TextView? = null

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var stepsBaseline = -1.0 // steps shown are counted from when this screen opened

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.compass_title)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        stepSensor = if (hasActivityRecognition()) {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        } else {
            null
        }

        compass = CompassView(this)
        headingReadout = readoutView(textSize = 22f)

        val readings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        readings.addView(headingReadout)
        if (magnetometer != null) magneticReadout = readoutView().also { readings.addView(it) }
        if (stepSensor != null) stepsReadout = readoutView().also { readings.addView(it) }
        if (gravitySensor != null) gravityReadout = readoutView().also { readings.addView(it) }
        if (pressureSensor != null) altitudeReadout = readoutView().also { readings.addView(it) }

        val hint = TextView(this).apply {
            gravity = Gravity.CENTER
            alpha = 0.6f
            text = getString(if (rotationSensor != null) R.string.compass_hint else R.string.compass_unavailable)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingDp(16, 16, 16, 16)
                addView(readings)
                addView(
                    compass,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
                )
                addView(hint)
                addView(copyrightFooter(this@CompassActivity))
            },
        )
    }

    private fun readoutView(textSize: Float = 16f): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        this.textSize = textSize
        alpha = if (textSize > 18f) 1f else 0.85f
    }

    override fun onResume() {
        super.onResume()
        listOfNotNull(rotationSensor, magnetometer, gravitySensor, pressureSensor, stepSensor)
            .forEach { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val deg = CompassMath.azimuthDegrees(orientation[0].toDouble())
                compass.setAzimuth(deg)
                val cardinals = resources.getStringArray(R.array.compass_cardinals)
                headingReadout.text = getString(R.string.compass_readout)
                    .format(deg.toInt(), cardinals[CompassMath.cardinalIndex(deg)])
            }
            Sensor.TYPE_MAGNETIC_FIELD ->
                magneticReadout?.text = getString(R.string.compass_magnetic).format(CompassMath.magnitude(event.values))
            Sensor.TYPE_GRAVITY ->
                gravityReadout?.text = getString(R.string.compass_gravity).format(CompassMath.magnitude(event.values))
            Sensor.TYPE_PRESSURE -> if (event.values.isNotEmpty()) {
                val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, event.values[0])
                altitudeReadout?.text = getString(R.string.compass_altitude).format(alt)
            }
            Sensor.TYPE_STEP_COUNTER -> if (event.values.isNotEmpty()) {
                val cur = event.values[0].toDouble()
                if (stepsBaseline < 0) stepsBaseline = cur
                val session = (cur - stepsBaseline).coerceAtLeast(0.0)
                stepsReadout?.text = getString(R.string.compass_steps).format(session.toInt())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun hasActivityRecognition(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
}
