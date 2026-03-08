package com.dashcam.dvr.telemetry.collectors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.dashcam.dvr.telemetry.model.MagRecord
import com.dashcam.dvr.telemetry.ntp.NtpSyncManager
import kotlin.math.atan2

/**
 * MagnetometerCollector
 *
 * Blueprint §6 — Magnetometer @ 10 Hz
 * ─────────────────────────────────────
 * Fields: mx, my, mz (µT), heading_deg
 *
 * heading_deg is derived from the X/Y magnetic field components and cross-checks
 * GPS bearing (Blueprint §6 — "Heading cross-check with GPS").
 *
 * Note: heading assumes the phone is roughly horizontal. Tilt-compensated heading
 * fusing the gravity vector from the accelerometer can be added in Module 4/5.
 */
class MagnetometerCollector(
    context: Context,
    private val ntpManager: NtpSyncManager,
    private val onRecord: (MagRecord) -> Unit
) {
    companion object {
        private const val TAG            = "MagnetometerCollector"
        private const val SAMPLE_RATE_US = 100_000   // 10 Hz
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val handlerThread = HandlerThread("MagCollector").also { it.start() }
    private val handler        = Handler(handlerThread.looper)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val mx = event.values[0]
            val my = event.values[1]
            val mz = event.values[2]
            val headingRad = atan2(my.toDouble(), mx.toDouble())
            val headingDeg = ((Math.toDegrees(headingRad).toFloat() + 360f) % 360f)
            onRecord(
                MagRecord(
                    tsMonoNs   = event.timestamp,
                    tsUtc      = ntpManager.correctedUtcNow(),
                    mx         = mx,
                    my         = my,
                    mz         = mz,
                    headingDeg = headingDeg
                )
            )
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW)
                Log.w(TAG, "Magnetometer accuracy low ($accuracy) — possible magnetic interference")
        }
    }

    fun start() {
        val ok = magnetometer?.let {
            sensorManager.registerListener(listener, it, SAMPLE_RATE_US, handler)
        } ?: run { Log.w(TAG, "No magnetometer — heading cross-check unavailable"); false }
        Log.i(TAG, "Magnetometer collector started=$ok @ 10 Hz")
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        handlerThread.quitSafely()
        Log.i(TAG, "Magnetometer collector stopped")
    }
}
