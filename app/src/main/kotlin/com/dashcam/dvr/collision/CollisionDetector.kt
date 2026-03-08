package com.dashcam.dvr.collision

import android.os.SystemClock
import android.util.Log
import com.dashcam.dvr.collision.model.ImpactEvent
import com.dashcam.dvr.collision.model.RoadState
import com.dashcam.dvr.telemetry.model.AccelRecord
import com.dashcam.dvr.telemetry.model.CollisionRecord
import com.dashcam.dvr.telemetry.model.GyroRecord
import com.dashcam.dvr.telemetry.ntp.NtpSyncManager
import com.dashcam.dvr.util.AppConstants
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * CollisionDetector — road-quality-aware impact detection and direction classification
 *
 * Blueprint §8 — Collision Detection (3g / 20ms threshold)
 * Blueprint §9 — Impact Direction Classification
 * ──────────────────────────────────────────────────────────────────────────────
 * Core design:
 *   A naive 3g threshold fires on EVERY pothole, speed bump, or rough road.
 *   This detector separates GENUINE COLLISIONS from ROAD IMPACTS by:
 *
 *   1. Axis decomposition (per sample):
 *        lateral_g    = √(ax² + ay²) / g        ← horizontal plane
 *        az_net_g     = |az − gravity_ema| / g   ← net vertical (road inputs)
 *        total_g      = √(ax² + ay² + az_net²) / g
 *        horiz_frac   = lateral_g / total_g       ← fraction that is horizontal
 *
 *   2. Adaptive threshold (road-quality-aware):
 *        SMOOTH:      trigger ≥ 3.0g, horiz_frac ≥ 0.35
 *        ROUGH:       trigger ≥ 3.5g, horiz_frac ≥ 0.45  (raised to reduce potholes)
 *        VERY_ROUGH:  trigger ≥ 4.5g, horiz_frac ≥ 0.65  (heavy suppression)
 *
 *   3. Duration gate (Blueprint §8):
 *        Spike must sustain the effective threshold for ≥ IMPACT_DURATION_MS (20 ms)
 *        Sub-20ms spikes are road impacts — logged as ROAD_IMPACT, not COLLISION.
 *
 *   4. Vertical-dominant spike → always classified ROAD_IMPACT regardless of g-level.
 *        Logged to telemetry for analyst review, NOT promoted to a custody event.
 *
 * State machine:
 *   IDLE → ARMED (threshold crossed) → TRIGGERED (≥20ms) → COOLDOWN (2s) → IDLE
 *   IDLE → ARMED → IDLE (spike < 20ms = road impact)
 *
 * Direction classification (after removing gravity from az):
 *   Find which of (ax, ay, az_net) has the largest absolute value:
 *   ax dominant, ax < 0 → FRONT_IMPACT  (deceleration = frontal collision)
 *   ax dominant, ax > 0 → REAR_IMPACT   (acceleration  = struck from behind)
 *   ay dominant, ay > 0 → LATERAL_RIGHT
 *   ay dominant, ay < 0 → LATERAL_LEFT
 *   az_net dominant      → ROAD_IMPACT   (vertical = road surface feature)
 *
 * Gravity estimation:
 *   RoadQualityMonitor provides gravityEma (its own internal EMA). The detector
 *   uses AppConstants.GRAVITY_MS2 for its own az_net computation to avoid
 *   coupling — the two components are independent.
 *   This is accurate enough for axis classification; full calibration is Module 8.
 *
 * Thread safety:
 *   onAccelSample() and onGyroSample() are called from the IMU HandlerThread.
 *   All mutable state is accessed from that thread only — no locking needed.
 */
class CollisionDetector(
    private val ntpManager:      NtpSyncManager,
    private val roadMonitor:     RoadQualityMonitor,
    private val onEvent:         (ImpactEvent) -> Unit,
    var writeCallback: ((CollisionRecord) -> Unit)? = null
) {
    companion object {
        private const val TAG = "CollisionDetector"

        // Adaptive threshold table
        private val THRESHOLD_G   = mapOf(
            RoadState.SMOOTH     to AppConstants.IMPACT_G_THRESHOLD,
            RoadState.ROUGH      to AppConstants.IMPACT_G_ROUGH,
            RoadState.VERY_ROUGH to AppConstants.IMPACT_G_VERY_ROUGH
        )
        private val MIN_HORIZ_FRAC = mapOf(
            RoadState.SMOOTH     to AppConstants.IMPACT_MIN_HORIZ_FRAC,
            RoadState.ROUGH      to AppConstants.IMPACT_MIN_HORIZ_ROUGH,
            RoadState.VERY_ROUGH to AppConstants.IMPACT_MIN_HORIZ_V_ROUGH
        )
        private val COOLDOWN_NS  = AppConstants.IMPACT_COOLDOWN_MS * 1_000_000L
        private val DURATION_NS  = AppConstants.IMPACT_DURATION_MS * 1_000_000L
        private const val GRAVITY_EMA_ALPHA = 0.001f
    }

    // ── Gravity self-calibration (independent EMA — see class doc) ──────────
    private var gravityEma = -AppConstants.GRAVITY_MS2

    // ── State machine ────────────────────────────────────────────────────────
    private enum class State { IDLE, ARMED, COOLDOWN }
    private var state = State.IDLE

    private var armedTs:       Long  = 0L   // monotonic ns when ARMED
    private var cooldownEndTs: Long  = 0L   // monotonic ns when COOLDOWN ends
    private var peakG:         Float = 0f
    private var peakAx:        Float = 0f
    private var peakAy:        Float = 0f
    private var peakAzNet:     Float = 0f

    // ── Gyro — stored for multi-axis context (Module 10 drowsiness can also read) ─
    @Volatile var lastGyroRecord: GyroRecord? = null; private set

    // ── Entry points ─────────────────────────────────────────────────────────

    fun onAccelSample(record: AccelRecord) {
        val nowNs = record.tsMonoNs
        val ax    = record.ax
        val ay    = record.ay
        val az    = record.az

        // Update local gravity EMA
        gravityEma = gravityEma + GRAVITY_EMA_ALPHA * (az - gravityEma)

        // Decompose
        val azNet     = az - gravityEma
        val lateralSq = ax * ax + ay * ay
        val lateral   = sqrt(lateralSq)
        val totalVec  = sqrt(lateralSq + azNet * azNet)

        val totalG    = totalVec  / AppConstants.GRAVITY_MS2
        val horizFrac = if (totalVec > 0f) lateral / totalVec else 0f

        val roadState  = roadMonitor.currentState
        val threshG    = THRESHOLD_G[roadState]!!
        val minHoriz   = MIN_HORIZ_FRAC[roadState]!!

        when (state) {
            State.COOLDOWN -> {
                if (nowNs >= cooldownEndTs) {
                    state = State.IDLE
                    Log.d(TAG, "Cooldown ended — detector IDLE")
                }
                // During cooldown: still track for post-event analysis but don't trigger
            }

            State.IDLE -> {
                if (totalG >= threshG && horizFrac >= minHoriz) {
                    // Threshold crossed — arm the duration gate
                    state    = State.ARMED
                    armedTs  = nowNs
                    peakG    = totalG
                    peakAx   = ax; peakAy = ay; peakAzNet = azNet
                    Log.d(TAG, "ARMED  totalG=${totalG}g  horizFrac=${horizFrac}  road=$roadState")
                } else if (totalG >= threshG && horizFrac < minHoriz) {
                    // Spike but vertical dominant — classify immediately as ROAD_IMPACT
                    logRoadImpact(nowNs, totalG, ax, ay, azNet, roadState, "horiz_frac_below_min")
                }
            }

            State.ARMED -> {
                // Track peak while spike sustains
                if (totalG > peakG) {
                    peakG = totalG; peakAx = ax; peakAy = ay; peakAzNet = azNet
                }

                val still_above = totalG >= threshG && horizFrac >= minHoriz
                val duration_ns = nowNs - armedTs

                if (!still_above) {
                    // Spike dropped before duration gate — road impact
                    val durMs = duration_ns / 1_000_000L
                    Log.d(TAG, "Spike dropped at ${durMs}ms < ${AppConstants.IMPACT_DURATION_MS}ms → ROAD_IMPACT")
                    logRoadImpact(nowNs, peakG, peakAx, peakAy, peakAzNet, roadState, "duration_below_gate_${durMs}ms")
                    state = State.IDLE
                } else if (duration_ns >= DURATION_NS) {
                    // Duration gate passed — confirmed collision
                    val durMs = duration_ns / 1_000_000L
                    triggerCollision(nowNs, durMs, roadState, record.tsUtc)
                }
            }
        }
    }

    fun onGyroSample(record: GyroRecord) {
        lastGyroRecord = record   // Available for Module 10 drowsiness lateral-sway check
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun triggerCollision(
        tsMonoNs: Long, durationMs: Long, roadState: RoadState, tsUtc: String
    ) {
        val direction = classifyDirection(peakAx, peakAy, peakAzNet)
        Log.w(TAG, "COLLISION CONFIRMED  direction=$direction  peakG=${peakG}g  " +
            "duration=${durationMs}ms  road=$roadState")

        val event = ImpactEvent(
            tsMonoNs   = tsMonoNs,
            tsUtc      = tsUtc,
            direction  = direction,
            peakG      = peakG,
            durationMs = durationMs,
            axG        = peakAx / AppConstants.GRAVITY_MS2,
            ayG        = peakAy / AppConstants.GRAVITY_MS2,
            azNetG     = peakAzNet / AppConstants.GRAVITY_MS2,
            roadState  = roadState.name,
            confirmed  = true
        )

        writeCallback?.invoke(
            CollisionRecord(
                tsMonoNs   = tsMonoNs,
                tsUtc      = tsUtc,
                direction  = direction,
                peakG      = peakG,
                durationMs = durationMs,
                axG        = event.axG,
                ayG        = event.ayG,
                azNetG     = event.azNetG,
                roadState  = roadState.name,
                confirmed  = true,
                suppressed = false
            )
        )

        onEvent(event)

        state         = State.COOLDOWN
        cooldownEndTs = tsMonoNs + COOLDOWN_NS
        peakG         = 0f
    }

    private fun logRoadImpact(
        tsMonoNs: Long, totalG: Float,
        ax: Float, ay: Float, azNet: Float,
        roadState: RoadState, reason: String
    ) {
        Log.d(TAG, "ROAD_IMPACT  totalG=${totalG}g  reason=$reason  road=$roadState")
        writeCallback?.invoke(
            CollisionRecord(
                tsMonoNs   = tsMonoNs,
                tsUtc      = ntpManager.correctedUtcNow(),
                direction  = "ROAD_IMPACT",
                peakG      = totalG,
                durationMs = 0L,
                axG        = ax / AppConstants.GRAVITY_MS2,
                ayG        = ay / AppConstants.GRAVITY_MS2,
                azNetG     = azNet / AppConstants.GRAVITY_MS2,
                roadState  = roadState.name,
                confirmed  = false,
                suppressed = true
            )
        )
        // ROAD_IMPACT events do NOT fire onEvent() — they are telemetry-only
    }

    private fun classifyDirection(ax: Float, ay: Float, azNet: Float): String {
        val absAx    = abs(ax)
        val absAy    = abs(ay)
        val absAzNet = abs(azNet)

        return when {
            absAzNet > absAx && absAzNet > absAy -> "ROAD_IMPACT"
            absAx   >= absAy                      -> if (ax < 0f) "FRONT_IMPACT" else "REAR_IMPACT"
            ay      >= 0f                         -> "LATERAL_RIGHT"
            else                                  -> "LATERAL_LEFT"
        }
    }

    fun reset() {
        state = State.IDLE; peakG = 0f
        gravityEma = -AppConstants.GRAVITY_MS2
    }
}