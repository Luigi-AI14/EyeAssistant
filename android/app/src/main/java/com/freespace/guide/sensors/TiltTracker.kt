package com.freespace.guide.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class TiltTracker(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val needsSmoothing = sensor?.type != Sensor.TYPE_GRAVITY

    @Volatile
    var up: FloatArray = floatArrayOf(0f, 1f, 0f)
        private set

    @Volatile
    var isCalibrated = false
        private set

    private val filtered = floatArrayOf(0f, SensorManager.GRAVITY_EARTH, 0f)

    fun start() {
        val target = sensor ?: return
        manager?.registerListener(this, target, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        if (values.size < 3) return

        if (needsSmoothing) {
            for (axis in 0..2) {
                filtered[axis] += SMOOTHING * (values[axis] - filtered[axis])
            }
        } else {
            for (axis in 0..2) filtered[axis] = values[axis]
        }

        val norm = sqrt(
            filtered[0] * filtered[0] + filtered[1] * filtered[1] + filtered[2] * filtered[2]
        )
        if (norm < 1e-3f) return

        up = floatArrayOf(filtered[0] / norm, filtered[1] / norm, filtered[2] / norm)
        isCalibrated = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SMOOTHING = 0.15f
    }
}
