package com.dashcam.dvr.collision

import android.os.SystemClock
import android.util.Log
import com.dashcam.dvr.collision.model.RoadState
import com.dashcam.dvr.telemetry.model.AccelRecord
import com.dashcam.dvr.telemetry.model.RoadQualityRecord
import com.dashcam.dvr.telemetry.ntp.NtpSyncManager
import com.dashcam.dvr.util.AppConstants
import kotlin.math.sqrt

/**
 * RoadQualityMonitor — continuous vertical-acceleration RMS analyser
 *
 * Blueprint §8 — False-Positive Suppression
 * ──────────────────────────────────────────────────────────────────────────────
 * Purpose:
 *   Distinguish between genuine collision spikes and road-induced vertical
 *   acceleration (potholes, speed bumps, cobblestones, rail crossings).
 *
 * Algorithm:
 *   1. Maintains a circular buffer of net vertical acceleration samples.
 *      az_net = az  −  gravity_z
 *      gravity_z is estimated from the long-term mean of az (when stationary)
 *      or from calibration.json (Module 8 will wire this — for now we use the
 *      rolling mean as a self-calibrating estimate).
 *
 *   2. Every ROAD_QUALITY_UPDATE_MS (500 ms) computes:
 *        verticalRms = √( mean(az_net²) )   over the last WINDOW_MS samples
 *
 *   3. Maps RMS to road state:
 *        < ROUGH_THRESHOLD  (1.5 m/s²)  → SMOOTH
 *        < VERY_ROUGH_THRESHOLD (4.0 m/s²) → ROUGH
 *        ≥ VERY_ROUGH_THRESHOLD            → VERY_ROUGH
 *
 *   4. Writes a ROAD_QUALITY JSONL record every LOG_INTERVAL_MS (5 s)
 *      OR immediately when state changes (for analyst traceability).
 *
 * Thread safety:
 *   onAccelSample() is called from the IMU HandlerThread at 100 Hz.
 *   currentState / currentRmsMs2 are @Volatile reads — safe from any thread.
 *
 * Gravity self-calibration:
 *   Uses an exponential moving average of az to track the gravity component.
 *   α = 0.001 per sample → time constant ≈ 10 seconds of steady-state.
 *   This adapts to device tilt changes without requiring a separate calibration step.
 */
class RoadQualityMonitor(
    private val ntpManager:   NtpSyncManager,
    var writeCallback: ((RoadQualityRecord) -> Unit)? = null
) {
    companion object {
        private const val TAG           = "RoadQualityMonitor"
        private const val WINDOW_SAMPLES = AppConstants.ACCEL_SAMPLE_HZ * 2  // 2s × 100Hz = 200
        private const val UPDATE_SAMPLES = AppConstants.ACCEL_SAMPLE_HZ / 2  // every 50 samples = 0.5s
        private const val LOG_INTERVAL_NS = AppConstants.ROAD_QUALITY_LOG_INTERVAL_MS * 1_000_000L
        private const val GRAVITY_EMA_ALPHA = 0.001f  // slow-tracking EMA for gravity estimation
    }

    // ── Circular buffer for az_net ──────────────────────────────────────────
    private val buffer = FloatArray(WINDOW_SAMPLES)
    private var bufferHead  = 0
    private var bufferCount = 0
    private var samplesSinceUpdate = 0

    // ── Gravity self-calibration (EMA of raw az) ────────────────────────────
    @Volatile private var gravityEma = -AppConstants.GRAVITY_MS2   // initial guess: phone face-up

    // ── Published state ─────────────────────────────────────────────────────
    @Volatile var currentState:   RoadState = RoadState.SMOOTH;  private set
    @Volatile var currentRmsMs2:  Float     = 0f;                private set

    private var lastLogTs: Long = 0L

    // ── Accel fan-out entry point (100 Hz) ───────────────────────────────────

    fun onAccelSample(record: AccelRecord) {
        val az = record.az

        // Update gravity EMA (tracks slow tilt changes)
        gravityEma = gravityEma + GRAVITY_EMA_ALPHA * (az - gravityEma)

        // Net vertical acceleration (gravity removed)
        val azNet = az - gravityEma

        // Write to circular buffer
        buffer[bufferHead] = azNet * azNet   // store squared for RMS
        bufferHead = (bufferHead + 1) % WINDOW_SAMPLES
        if (bufferCount < WINDOW_SAMPLES) bufferCount++

        samplesSinceUpdate++
        if (samplesSinceUpdate >= UPDATE_SAMPLES && bufferCount > 0) {
            samplesSinceUpdate = 0
            evaluate(record.tsMonoNs)
        }
    }

    // ── Evaluation (every 500 ms) ────────────────────────────────────────────

    private fun evaluate(tsMonoNs: Long) {
        // RMS = √( Σ(az_net²) / N )
        var sumSq = 0.0
        for (i in 0 until bufferCount) sumSq += buffer[i]
        val rms = sqrt(sumSq / bufferCount).toFloat()
        currentRmsMs2 = rms

        val newState = when {
            rms < AppConstants.ROAD_ROUGH_RMS_MS2      -> RoadState.SMOOTH
            rms < AppConstants.ROAD_VERY_ROUGH_RMS_MS2 -> RoadState.ROUGH
            else                                        -> RoadState.VERY_ROUGH
        }

        val stateChanged = newState != currentState
        if (stateChanged) {
            Log.i(TAG, "Road state: $currentState → $newState  verticalRms=${rms}m/s²")
            currentState = newState
        }

        // Log to telemetry on state change or every LOG_INTERVAL
        val nowNs = tsMonoNs
        if (stateChanged || (nowNs - lastLogTs) >= LOG_INTERVAL_NS) {
            lastLogTs = nowNs
            writeCallback?.invoke(
                RoadQualityRecord(
                    tsMonoNs      = tsMonoNs,
                    tsUtc         = ntpManager.correctedUtcNow(),
                    state         = newState.name,
                    verticalRmsMs2 = rms,
                    sampleWindowMs = (bufferCount * 10L),   // 100Hz → 10ms/sample
                    stateChanged  = stateChanged
                )
            )
        }
    }

    fun reset() {
        bufferHead = 0; bufferCount = 0; samplesSinceUpdate = 0
        currentState  = RoadState.SMOOTH
        currentRmsMs2 = 0f
        gravityEma    = -AppConstants.GRAVITY_MS2
    }
}