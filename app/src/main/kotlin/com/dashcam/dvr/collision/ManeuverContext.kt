package com.dashcam.dvr.collision

import android.util.Log
import com.dashcam.dvr.telemetry.model.GpsRecord
import com.dashcam.dvr.telemetry.model.GyroRecord
import com.dashcam.dvr.util.AppConstants
import kotlin.math.abs

/**
 * ManeuverContext — GPS + gyro fusion layer for expected-acceleration estimation
 *
 * Blueprint §8 / §9 — Sensor Fusion / False-Positive Suppression
 * ─────────────────────────────────────────────────────────────────────────────
 * PURPOSE
 * ───────
 * The raw IMU signal contains three superimposed acceleration sources that a
 * naive threshold detector cannot distinguish:
 *
 *   IMU_raw = a_impact (true collision — what we want)
 *           + a_longitudinal  (dV/dt — braking, throttle)
 *           + a_centripetal   (v × ω — cornering at constant speed)
 *           + a_road          (vertical — handled separately by RoadQualityMonitor)
 *
 * ManeuverContext estimates the "expected" acceleration vector from GPS and
 * gyro at every sample. CollisionDetector subtracts this expected vector from
 * the raw IMU signal before threshold evaluation:
 *
 *   residual_g = |IMU_raw − expected| / g
 *
 * Only residual_g > threshold is a genuine unexpected impact.
 *
 * SOURCES
 * ───────
 * 1. Longitudinal (braking / throttle):
 *      a_long = ΔspeedMs / Δt   (from GPS at 5 Hz)
 *      Dead-reckoned between GPS fixes using last known value.
 *      Not used below MIN_SPEED_MS (< ~5 km/h) — GPS speed unreliable at low speed.
 *
 * 2. Centripetal (cornering):
 *      a_cent = speedMs × yawRateRads   (v × ω)
 *      yawRateRads from gyro wz at 100 Hz — preferred over GPS heading diff
 *      because gyro gives 20× better temporal resolution.
 *      GPS heading diff used as sanity check for gyro drift (Module 8 will
 *      implement full gyro drift correction with calibration matrix).
 *
 * COORDINATE CONVENTION
 * ─────────────────────
 * Without Module 8 calibration we assume:
 *   Device ax ≈ vehicle longitudinal axis  (+forward = acceleration, -forward = braking)
 *   Device ay ≈ vehicle lateral axis       (+right = right turn centripetal)
 *
 * Module 8 (Calibration) will replace this with a proper rotation matrix from the
 * measured gravity vector, allowing arbitrary phone mounting orientation.
 *
 * VALIDITY / DEGRADATION
 * ──────────────────────
 * isValid() returns false if:
 *   - No GPS fix yet, OR last fix > GPS_STALE_NS ago (2 seconds)
 *   - Speed < MIN_SPEED_MS (~5 km/h) — GPS speed too noisy for dV/dt
 *
 * When invalid, CollisionDetector falls back to raw IMU mode (conservative).
 * This is the correct posture: better to have a potential false positive than
 * to silently miss a real collision due to bad sensor fusion state.
 *
 * Thread safety:
 *   onGpsRecord() is called from GpsCollector's HandlerThread.
 *   onGyroSample() is called from ImuCollector's HandlerThread.
 *   All exposed fields are @Volatile — safe to read from CollisionDetector's thread.
 */
class ManeuverContext {

    companion object {
        private const val TAG            = "ManeuverContext"
        private const val MIN_SPEED_MS   = 5f / 3.6f     // 5 km/h in m/s — below = GPS noise floor
        private const val GPS_STALE_NS   = 2_000_000_000L // 2 s — stale if no fix received
        private const val MIN_DT_NS      = 100_000_000L   // 100 ms — min GPS interval for dV/dt
        private const val MAX_LONG_G     = 1.5f           // sanity cap: ±1.5g max credible braking/throttle
        private const val MAX_CENT_G     = 1.5f           // sanity cap: ±1.5g max credible cornering
        // Heading delta sanity: if GPS heading diff implies > this rad/s, suspect noise
        private const val MAX_GPS_YAW_RATE_RADS = 1.2f   // ~70 deg/s = very aggressive slalom
    }

    // ── GPS-derived longitudinal ─────────────────────────────────────────────
    @Volatile var longitudinalAccelG:  Float = 0f;  private set  // dV/dt / g
    @Volatile var currentSpeedMs:      Float = 0f;  private set  // latest GPS speed
    @Volatile var currentHeadingDeg:   Float = 0f;  private set

    // ── Gyro-derived centripetal (100 Hz) ────────────────────────────────────
    @Volatile var yawRateRads:         Float = 0f;  private set  // wz from gyro
    @Volatile var centripetalAccelG:   Float = 0f;  private set  // speed × yawRate / g

    // ── Validity tracking ────────────────────────────────────────────────────
    @Volatile private var lastGpsTs:   Long  = 0L
    private var lastSpeedMs:           Float = 0f
    private var lastHeadingDeg:        Float = 0f
    private var lastHeadingTs:         Long  = 0L

    // ── GPS fan-out entry point (5 Hz) ────────────────────────────────────────

    fun onGpsRecord(record: GpsRecord) {
        if (record.fixStatus != "VALID") return   // only use valid fixes

        val nowNs    = record.tsMonoNs
        val speedMs  = record.speedKmh / 3.6f

        // ── Longitudinal: dV/dt ──────────────────────────────────────────────
        if (lastGpsTs > 0L) {
            val dtNs = nowNs - lastGpsTs
            if (dtNs >= MIN_DT_NS && speedMs >= MIN_SPEED_MS) {
                val dtS    = dtNs / 1_000_000_000f
                val dv     = speedMs - lastSpeedMs
                val longG  = (dv / dtS) / AppConstants.GRAVITY_MS2
                // Clamp to physically credible range
                longitudinalAccelG = longG.coerceIn(-MAX_LONG_G, MAX_LONG_G)
            } else if (speedMs < MIN_SPEED_MS) {
                longitudinalAccelG = 0f   // at near-standstill, not meaningful
            }
        }
        lastSpeedMs = speedMs
        lastGpsTs   = nowNs
        currentSpeedMs   = speedMs

        // ── GPS heading sanity cross-check for gyro ──────────────────────────
        if (lastHeadingTs > 0L && speedMs >= MIN_SPEED_MS) {
            val dtNs = nowNs - lastHeadingTs
            if (dtNs >= MIN_DT_NS) {
                val dtS = dtNs / 1_000_000_000f
                var dHead = record.headingDeg - lastHeadingDeg
                // Normalise to [-180, 180]
                if (dHead >  180f) dHead -= 360f
                if (dHead < -180f) dHead += 360f
                val gpsYawRads = Math.toRadians(dHead / dtS.toDouble()).toFloat()
                // If GPS yaw rate is credible AND differs >30% from gyro, log a warning
                // (will be used for drift correction in Module 8)
                if (abs(gpsYawRads) < MAX_GPS_YAW_RATE_RADS) {
                    val gyroDrift = abs(yawRateRads - gpsYawRads)
                    if (gyroDrift > 0.3f) {
                        Log.d(TAG, "Gyro/GPS yaw mismatch: gyro=${yawRateRads}rad/s  " +
                            "gps=${gpsYawRads}rad/s  drift=${gyroDrift}rad/s")
                    }
                }
            }
        }
        lastHeadingDeg = record.headingDeg
        lastHeadingTs  = nowNs
        currentHeadingDeg = record.headingDeg

        // Refresh centripetal with latest speed (gyro wz already current)
        updateCentripetal()
    }

    // ── Gyro fan-out entry point (100 Hz) ────────────────────────────────────

    fun onGyroSample(record: GyroRecord) {
        // wz = rotation rate around device vertical axis (yaw)
        // Positive wz = counter-clockwise when viewed from above (right-hand rule)
        // At 100 Hz this gives 20× better temporal resolution than GPS heading diff
        yawRateRads = record.wz
        updateCentripetal()
    }

    // ── Expected acceleration vector for CollisionDetector ───────────────────

    /**
     * Expected longitudinal acceleration (vehicle forward axis) in m/s².
     * Positive = accelerating, negative = braking.
     * Returns 0 if context is not valid.
     */
    fun expectedLongMs2(): Float =
        if (isSpeedValid()) longitudinalAccelG * AppConstants.GRAVITY_MS2 else 0f

    /**
     * Expected lateral acceleration (centripetal) in m/s².
     * Positive = right turn, negative = left turn.
     * Returns 0 if context is not valid.
     */
    fun expectedLatMs2(): Float =
        if (isSpeedValid()) centripetalAccelG * AppConstants.GRAVITY_MS2 else 0f

    /**
     * True if GPS fix is fresh enough and speed is above noise floor.
     * When false, CollisionDetector uses raw IMU (conservative fallback).
     */
    fun isValid(nowNs: Long): Boolean =
        lastGpsTs > 0L &&
        (nowNs - lastGpsTs) < GPS_STALE_NS &&
        currentSpeedMs >= MIN_SPEED_MS

    // ── Summary for logging / CollisionRecord fields ─────────────────────────
    fun summaryForLog(): String =
        "long=${longitudinalAccelG}g  cent=${centripetalAccelG}g  " +
        "speed=${currentSpeedMs * 3.6f}km/h  yaw=${yawRateRads}rad/s"

    fun reset() {
        longitudinalAccelG = 0f; centripetalAccelG = 0f
        yawRateRads = 0f; currentSpeedMs = 0f
        lastGpsTs = 0L; lastSpeedMs = 0f
        lastHeadingDeg = 0f; lastHeadingTs = 0L
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun isSpeedValid(): Boolean = currentSpeedMs >= MIN_SPEED_MS

    private fun updateCentripetal() {
        val cent = (currentSpeedMs * yawRateRads) / AppConstants.GRAVITY_MS2
        centripetalAccelG = cent.coerceIn(-MAX_CENT_G, MAX_CENT_G)
    }
}