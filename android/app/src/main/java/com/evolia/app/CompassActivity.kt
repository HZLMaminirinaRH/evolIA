package com.evolia.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.evolia.app.sensors.CompassMath
import com.evolia.app.sensors.CompassView

/**
 * Visual compass so the user keeps their bearings while using evolIA. The heading
 * comes from the rotation-vector sensor (a fusion of accelerometer + gyroscope +
 * magnetometer); the gyroscope's motion also keeps feeding the value loop while
 * evolIA runs, so orienting yourself accrues V. No new permission is needed —
 * motion sensors are not permission-gated — and it degrades to an "unavailable"
 * message on a device without the sensor.
 */
class CompassActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var compass: CompassView
    private lateinit var readout: TextView
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.compass_title)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

        compass = CompassView(this)
        readout = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 22f
        }
        val hint = TextView(this).apply {
            gravity = Gravity.CENTER
            alpha = 0.6f
            text = getString(if (rotationSensor != null) R.string.compass_hint else R.string.compass_unavailable)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 40, 40, 40)
                addView(readout)
                addView(
                    compass,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
                )
                addView(hint)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
        ) {
            return
        }
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val deg = CompassMath.azimuthDegrees(orientation[0].toDouble())
        compass.setAzimuth(deg)
        val cardinals = resources.getStringArray(R.array.compass_cardinals)
        readout.text = getString(R.string.compass_readout)
            .format(deg.toInt(), cardinals[CompassMath.cardinalIndex(deg)])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
