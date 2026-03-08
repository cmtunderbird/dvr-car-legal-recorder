package com.dashcam.dvr.collision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.dashcam.dvr.util.AppConstants

/**
 * GravityProvider — single shared source of the device gravity vector
 *
 * Blueprint §8 — Sensor Fusion / Gravity Reference
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY THIS EXISTS
 * ───────────────
 * The old approach had two independent gravity EMAs:
 *   1. CollisionDetector.gravityEma   initialised to -9.807 (wrong sign/value)
 *   2. RoadQualityMonitor.gravityEma  same init, diverges independently
 *
 * Problems that caused:
 *   - Wrong sign: if phone is face-up, az ≈ +9.8, EMA starts at -9.8 → 19.6 m/s²
 *     error at t=0, takes >30s to converge even with α=0.001
 *   - While unconverged: az_net = az − gravityEma is massively inflated
 *     → RMS is inflated → phone stuck at VERY_ROUGH on a quiet desk
 *     → adaptive threshold raised to 4.5g / 65% horiz → real impacts missed
 *   - Two EMAs converge at different rates; during a spike one updates faster
 *     than the other, creating asymmetric az_net between the two consumers
 *
 * THE FIX: Android TYPE_GRAVITY
 * ──────────────────────────────
 * Android's sensor fusion HAL provides TYPE_GRAVITY — a hardware-accelerated
 * complementary filter combining accelerometer + gyroscope that:
 *   ✓ Is correctly signed for the actual phone orientation (no hardcoded sign)
 *   ✓ Converges immediately at first sample (zero warmup)
 *   ✓ Is already low-pass filtered by the HAL — no separate EMA needed
 *   ✓ Is stable during high-g events (gyroscope rate integration bridges spikes)
 *   ✓ Single source shared by all consumers
 *
 * FALLBACK: If TYPE_GRAVITY is unavailable (very rare on Android ≥ 9), we fall
 * back to a low-pass EMA on TYPE_ACCELEROMETER, but initialised from the FIRST
 * valid sample — not a hardcoded constant — so the sign and magnitude are always
 * correct from the very first sample.
 *
 * THREAD SAFETY
 * ─────────────
 * gx, gy, gz are @Volatile — safe to read from any thread at any rate.
 * SensorEvent delivery runs on a dedicated HandlerThread.
 *
 * LIFECYCLE
 * ─────────
 * start() — called from RecordingService.startGpsMonitorLoop() (always-on)
 * stop()  — called from RecordingService.onDestroy()
 */
class GravityProvider(private val context: Context) {

    companion object {
        private const val TAG   = "GravityProvider"
        private const val RATE  = SensorManager.SENSOR_DELAY_GAME  // ~50 Hz for gravity
        // Fallback EMA alpha — only used if TYPE_GRAVITY unavailable
        // Lower than before: gravity changes slowly, high alpha = more smoothing
        private const val FALLBACK_ALPHA = 0.005f
    }

    // ── Published gravity vector (m/s²) ──────────────────────────────────────
    @Volatile var gx: Float = 0f; private set
    @Volatile var gy: Float = 0f; private set
    @Volatile var gz: Float = AppConstants.GRAVITY_MS2; private set  // safe default face-up
    @Volatile var isReady: Boolean = false; private set              // true once first sample received

    val gravityMagnitude: Float get() = Math.sqrt((gx*gx + gy*gy + gz*gz).toDouble()).toFloat()

    // ── Internals ─────────────────────────────────────────────────────────────
    private val sensorManager  = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor  = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor    = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val handlerThread  = HandlerThread("GravityProvider").also { it.start() }
    private val handler        = Handler(handlerThread.looper)

    // Fallback EMA state — first sample initialises the filter (no hardcoded constant)
    private var fallbackInitialised = false
    private var fallbackGx = 0f
    private var fallbackGy = 0f
    private var fallbackGz = AppConstants.GRAVITY_MS2

    private val usingHardwareFusion: Boolean get() = gravitySensor != null

    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {

                Sensor.TYPE_GRAVITY -> {
                    // Hardware-fused: already correct orientation + low-pass filtered
                    gx = event.values[0]
                    gy = event.values[1]
                    gz = event.values[2]
                    isReady = true
                }

                Sensor.TYPE_ACCELEROMETER -> {
                    // Fallback path: EMA initialised from first sample (correct sign/magnitude)
                    val ax = event.values[0]
                    val ay = event.values[1]
                    val az = event.values[2]
                    if (!fallbackInitialised) {
                        fallbackGx = ax; fallbackGy = ay; fallbackGz = az
                        fallbackInitialised = true
                        Log.i(TAG, "Fallback gravity init from first sample: " +
                            "gx=%.3f gy=%.3f gz=%.3f".format(ax, ay, az))
                    } else {
                        fallbackGx = fallbackGx + FALLBACK_ALPHA * (ax - fallbackGx)
                        fallbackGy = fallbackGy + FALLBACK_ALPHA * (ay - fallbackGy)
                        fallbackGz = fallbackGz + FALLBACK_ALPHA * (az - fallbackGz)
                    }
                    gx = fallbackGx; gy = fallbackGy; gz = fallbackGz
                    isReady = true
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        if (usingHardwareFusion) {
            sensorManager.registerListener(listener, gravitySensor, RATE, handler)
            Log.i(TAG, "TYPE_GRAVITY registered (hardware fusion) — zero warmup")
        } else {
            sensorManager.registerListener(listener, accelSensor, RATE, handler)
            Log.w(TAG, "TYPE_GRAVITY unavailable — using accelerometer EMA fallback")
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        handlerThread.quitSafely()
        Log.i(TAG, "GravityProvider stopped  magnitude=${gravityMagnitude}g")
    }

    /**
     * Diagnostic summary for session.json / logcat.
     */
    fun summary(): String =
        "gx=%.3f gy=%.3f gz=%.3f mag=%.4fg source=%s ready=%s".format(
            gx, gy, gz, gravityMagnitude / AppConstants.GRAVITY_MS2,
            if (usingHardwareFusion) "TYPE_GRAVITY" else "ACCEL_EMA_FALLBACK",
            isReady
        )
}