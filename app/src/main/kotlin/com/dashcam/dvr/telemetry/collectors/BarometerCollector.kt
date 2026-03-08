package com.dashcam.dvr.telemetry.collectors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.dashcam.dvr.telemetry.model.BaroRecord
import com.dashcam.dvr.telemetry.ntp.NtpSyncManager

/**
 * BarometerCollector
 *
 * Blueprint §6 — Barometer @ 5 Hz (Recommended)
 * ─────────────────────────────────────────────────
 * Fields: pressure_hpa, alt_m
 *
 * "Strongly recommended; significantly improves altitude accuracy over GPS-only
 * readings, especially in urban canyons." — Blueprint v2.0 §3
 *
 * Altitude derived via SensorManager.getAltitude() — ICAO hypsometric formula:
 *   alt = 44330 × (1 − (pressure / 1013.25) ^ 0.1903)
 *
 * Graceful degradation: if no barometer is present, [start] logs a warning
 * and returns. No BARO records appear in telemetry.log. [isAvailable] is
 * exposed so SessionManager can set barometer_available in session.json.
 */
class BarometerCollector(
    context: Context,
    private val ntpManager: NtpSyncManager,
    private val onRecord: (BaroRecord) -> Unit
) {
    companion object {
        private const val TAG            = "BarometerCollector"
        private const val SAMPLE_RATE_US = 200_000   // 5 Hz
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val isAvailable: Boolean get() = barometer != null

    private val handlerThread = HandlerThread("BaroCollector").also { it.start() }
    private val handler        = Handler(handlerThread.looper)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val pressureHpa = event.values[0]
            val altM = SensorManager.getAltitude(
                SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                pressureHpa
            )
            onRecord(
                BaroRecord(
                    tsMonoNs    = event.timestamp,
                    tsUtc       = ntpManager.correctedUtcNow(),
                    pressureHpa = pressureHpa,
                    altM        = altM
                )
            )
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        if (barometer == null) {
            Log.w(TAG, "No barometer sensor — altitude will be GPS-only for this session")
            return
        }
        val ok = sensorManager.registerListener(listener, barometer, SAMPLE_RATE_US, handler)
        Log.i(TAG, "Barometer started=$ok @ 5 Hz  (${barometer.name} / ${barometer.vendor})")
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        handlerThread.quitSafely()
        Log.i(TAG, "Barometer collector stopped")
    }
}
