package com.dashcam.dvr.collision

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
 * ─────────────────────────────────────────────────────────────────────────────
 * Classifies road surface quality from the net vertical acceleration (road
 * vibration transmitted through tyres → chassis → phone mount).
 *
 *   az_net = az_measured  −  gravity_z
 *
 * gravity_z is now supplied by GravityProvider (TYPE_GRAVITY sensor or
 * first-sample-initialised EMA fallback) — NOT a hardcoded constant.
 * This eliminates the convergence lag that previously caused the phone to
 * read as VERY_ROUGH for 30+ seconds after recording started on a quiet desk.
 *
 * Algorithm:
 *   200-sample circular buffer (2 s at 100 Hz) of az_net².
 *   RMS evaluated every 50 samples (500 ms):
 *     < 1.5 m/s²  → SMOOTH
 *     < 4.0 m/s²  → ROUGH
 *     ≥ 4.0 m/s²  → VERY_ROUGH
 *   ROAD_QUALITY JSONL record written on state change or every 5 s.
 */
class RoadQualityMonitor(
    private val ntpManager:      NtpSyncManager,
    private val gravityProvider: GravityProvider,
    var writeCallback: ((RoadQualityRecord) -> Unit)? = null
) {
    companion object {
        private const val TAG            = "RoadQualityMonitor"
        private const val WINDOW_SAMPLES = AppConstants.ACCEL_SAMPLE_HZ * 2   // 200 @ 100 Hz
        private const val UPDATE_SAMPLES = AppConstants.ACCEL_SAMPLE_HZ / 2   // every 50 = 0.5 s
        private const val LOG_INTERVAL_NS = AppConstants.ROAD_QUALITY_LOG_INTERVAL_MS * 1_000_000L
    }

    // ── Circular buffer (stores az_net² for RMS) ──────────────────────────────
    private val buffer = FloatArray(WINDOW_SAMPLES)
    private var bufferHead         = 0
    private var bufferCount        = 0
    private var samplesSinceUpdate = 0

    // ── Published state ───────────────────────────────────────────────────────
    @Volatile var currentState:  RoadState = RoadState.SMOOTH; private set
    @Volatile var currentRmsMs2: Float     = 0f;               private set

    private var lastLogTs: Long = 0L

    // ── Accel fan-out entry point (100 Hz) ────────────────────────────────────

    fun onAccelSample(record: AccelRecord) {
        // Net vertical acceleration — gravity from shared GravityProvider
        // No local EMA; no hardcoded constant; correct sign from first sample.
        val azNet = record.az - gravityProvider.gz

        buffer[bufferHead] = azNet * azNet
        bufferHead = (bufferHead + 1) % WINDOW_SAMPLES
        if (bufferCount < WINDOW_SAMPLES) bufferCount++

        samplesSinceUpdate++
        if (samplesSinceUpdate >= UPDATE_SAMPLES && bufferCount > 0) {
            samplesSinceUpdate = 0
            evaluate(record.tsMonoNs)
        }
    }

    // ── Evaluation (every 500 ms) ─────────────────────────────────────────────

    private fun evaluate(tsMonoNs: Long) {
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
            Log.i(TAG, "Road state: $currentState → $newState  verticalRms=${rms}m/s²" +
                "  gravity_z=${gravityProvider.gz}")
            currentState = newState
        }

        if (stateChanged || (tsMonoNs - lastLogTs) >= LOG_INTERVAL_NS) {
            lastLogTs = tsMonoNs
            writeCallback?.invoke(
                RoadQualityRecord(
                    tsMonoNs       = tsMonoNs,
                    tsUtc          = ntpManager.correctedUtcNow(),
                    state          = newState.name,
                    verticalRmsMs2 = rms,
                    sampleWindowMs = (bufferCount * 10L),
                    stateChanged   = stateChanged
                )
            )
        }
    }

    fun reset() {
        bufferHead = 0; bufferCount = 0; samplesSinceUpdate = 0
        currentState = RoadState.SMOOTH; currentRmsMs2 = 0f; lastLogTs = 0L
    }
}