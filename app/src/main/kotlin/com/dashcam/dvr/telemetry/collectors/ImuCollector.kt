package com.dashcam.dvr.telemetry.collectors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.dashcam.dvr.telemetry.model.AccelRecord
import com.dashcam.dvr.telemetry.model.GyroRecord
import com.dashcam.dvr.telemetry.ntp.NtpSyncManager

/**
 * ImuCollector
 *
 * Blueprint §6 — Accelerometer + Gyroscope @ 100 Hz
 * ────────────────────────────────────────────────────
 * Accelerometer: ax, ay, az  (m/s²)  — SENSOR_TYPE_ACCELEROMETER
 * Gyroscope:     wx, wy, wz  (rad/s) — SENSOR_TYPE_GYROSCOPE
 *
 * Both run at 100 Hz (SAMPLE_RATE_US = 10,000 µs) on a single dedicated
 * HandlerThread. At 200 callbacks/second, keeping this off the main thread
 * is critical for Camera2 capture session stability.
 *
 * event.timestamp is elapsedRealtimeNanos() — matches the PTS base used by
 * the Camera pipeline for frame-accurate sensor-video alignment (Blueprint §5).
 *
 * Module 5 hook: onAccelRecord / onGyroRecord lambdas are fanned out by
 * TelemetryEngine to CollisionDetector when it is integrated.
 */
class ImuCollector(
    context: Context,
    private val ntpManager: NtpSyncManager,
    private val onAccelRecord: (AccelRecord) -> Unit,
    private val onGyroRecord:  (GyroRecord)  -> Unit
) {
    companion object {
        private const val TAG            = "ImuCollector"
        private const val SAMPLE_RATE_US = 10_000   // 100 Hz
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope     = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val handlerThread = HandlerThread("ImuCollector").also { it.start() }
    private val handler        = Handler(handlerThread.looper)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val tsUtc = ntpManager.correctedUtcNow()
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> onAccelRecord(
                    AccelRecord(
                        tsMonoNs = event.timestamp,
                        tsUtc    = tsUtc,
                        ax       = event.values[0],
                        ay       = event.values[1],
                        az       = event.values[2]
                    )
                )
                Sensor.TYPE_GYROSCOPE -> onGyroRecord(
                    GyroRecord(
                        tsMonoNs = event.timestamp,
                        tsUtc    = tsUtc,
                        wx       = event.values[0],
                        wy       = event.values[1],
                        wz       = event.values[2]
                    )
                )
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d(TAG, "Accuracy changed — sensor=${sensor?.name} accuracy=$accuracy")
        }
    }

    fun start() {
        val accelOk = accelerometer?.let {
            sensorManager.registerListener(listener, it, SAMPLE_RATE_US, handler)
        } ?: run { Log.e(TAG, "No accelerometer found!"); false }

        val gyroOk = gyroscope?.let {
            sensorManager.registerListener(listener, it, SAMPLE_RATE_US, handler)
        } ?: run { Log.w(TAG, "No gyroscope — gyro records will be absent"); false }

        Log.i(TAG, "IMU collector started — accel=$accelOk  gyro=$gyroOk  @ 100 Hz")
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        handlerThread.quitSafely()
        Log.i(TAG, "IMU collector stopped")
    }
}
