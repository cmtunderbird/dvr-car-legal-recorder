package com.dashcam.dvr.collision

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
 * CollisionDetector — maneuver-aware, road-quality-adaptive impact detection
 *
 * Blueprint §8 — Collision Detection  |  §9 — Direction Classification
 * ─────────────────────────────────────────────────────────────────────────────
 * THREE-LAYER FALSE-POSITIVE SUPPRESSION
 * ───────────────────────────────────────
 *
 * Layer 1 — Road quality (RoadQualityMonitor):
 *   Raises the trigger threshold on rough roads so potholes and speed bumps
 *   do not fire events. SMOOTH / ROUGH / VERY_ROUGH adaptive thresholds.
 *
 * Layer 2 — Maneuver subtraction (ManeuverContext):
 *   Subtracts expected vehicle dynamics from the raw IMU signal:
 *
 *     ax_residual = ax_measured − a_longitudinal   (GPS dV/dt braking/throttle)
 *     ay_residual = ay_measured − a_centripetal    (v × ω cornering)
 *
 *   Without this layer:
 *     Hard braking at 0.8g → IMU shows 0.8g → below 3g threshold (lucky)
 *     But threshold is 3g, and emergency stop at 1.2g with a pothole adds up
 *   With this layer:
 *     Emergency stop at 1.2g → GPS explains 1.2g → residual ≈ 0 → no trigger
 *     Cornering at 0.4g → gyro explains 0.4g → residual ≈ 0 → no trigger
 *     Real rear-end at 3g → GPS shows simultaneous deceleration BUT the delta
 *     between expected and actual is still > threshold → confirmed collision
 *
 *   ManeuverContext.isValid() returns false when:
 *     - No GPS fix, or fix is stale (> 2s)
 *     - Speed < 5 km/h (GPS speed noise floor)
 *   When invalid → falls back to raw IMU mode (conservative, keeps sensitivity).
 *
 * Layer 3 — Vertical dominance test (axis decomposition):
 *   horiz_frac = lateral_residual / total_residual
 *   If below road-state minimum → ROAD_IMPACT (suppressed, telemetry only)
 *
 * DURATION GATE (Blueprint §8):
 *   Spike must sustain threshold for ≥ IMPACT_DURATION_MS (20 ms).
 *   Sub-20ms spikes → ROAD_IMPACT regardless of magnitude.
 *
 * DIRECTION CLASSIFICATION (Blueprint §9 — uses residual vector):
 *   FRONT_IMPACT   — ax_residual dominant, ax < 0  (frontal collision)
 *   REAR_IMPACT    — ax_residual dominant, ax > 0  (struck from behind)
 *   LATERAL_LEFT   — ay_residual dominant, ay < 0
 *   LATERAL_RIGHT  — ay_residual dominant, ay > 0
 *   ROAD_IMPACT    — az_net dominant OR horiz_frac too low
 *
 * COLLISION RECORD (forensic evidence fields):
 *   peak_g, ax_g, ay_g, az_net_g — raw IMU peak values
 *   expected_ax_g, expected_ay_g — ManeuverContext prediction at moment of event
 *   residual_g                   — net residual that crossed the threshold
 *   speed_kmh                    — vehicle speed from GPS at moment of event
 *   road_state                   — road quality classification
 *   fusion_valid                 — true = maneuver subtraction was applied
 *
 * STATE MACHINE:
 *   IDLE → ARMED → TRIGGERED (≥20ms) → COOLDOWN (2s) → IDLE
 *   IDLE → ARMED → IDLE  (sub-duration = road impact)
 */
class CollisionDetector(
    private val ntpManager:      NtpSyncManager,
    private val roadMonitor:     RoadQualityMonitor,
    private val maneuverContext: ManeuverContext,
    private val onEvent:         (ImpactEvent) -> Unit,
    var writeCallback: ((CollisionRecord) -> Unit)? = null
) {
    companion object {
        private const val TAG = "CollisionDetector"

        private val THRESHOLD_G = mapOf(
            RoadState.SMOOTH     to AppConstants.IMPACT_G_THRESHOLD,
            RoadState.ROUGH      to AppConstants.IMPACT_G_ROUGH,
            RoadState.VERY_ROUGH to AppConstants.IMPACT_G_VERY_ROUGH
        )
        private val MIN_HORIZ_FRAC = mapOf(
            RoadState.SMOOTH     to AppConstants.IMPACT_MIN_HORIZ_FRAC,
            RoadState.ROUGH      to AppConstants.IMPACT_MIN_HORIZ_ROUGH,
            RoadState.VERY_ROUGH to AppConstants.IMPACT_MIN_HORIZ_V_ROUGH
        )
        private val COOLDOWN_NS = AppConstants.IMPACT_COOLDOWN_MS * 1_000_000L
        private val DURATION_NS = AppConstants.IMPACT_DURATION_MS * 1_000_000L
        private const val GRAVITY_EMA_ALPHA = 0.001f
    }

    // ── Gravity EMA (independent; for az_net computation) ────────────────────
    private var gravityEma = -AppConstants.GRAVITY_MS2

    // ── State machine ─────────────────────────────────────────────────────────
    private enum class State { IDLE, ARMED, COOLDOWN }
    private var state         = State.IDLE
    private var armedTs       = 0L
    private var cooldownEndTs = 0L

    // Peak values captured during ARMED phase (raw IMU)
    private var peakG       = 0f
    private var peakAx      = 0f
    private var peakAy      = 0f
    private var peakAzNet   = 0f
    // Expected at peak moment (for CollisionRecord evidence)
    private var peakExpLong = 0f
    private var peakExpLat  = 0f
    private var peakResidG  = 0f
    private var peakFusionValid = false

    // ── Entry points ──────────────────────────────────────────────────────────

    fun onAccelSample(record: AccelRecord) {
        val nowNs = record.tsMonoNs
        val ax    = record.ax
        val ay    = record.ay
        val az    = record.az

        // Update gravity EMA
        gravityEma = gravityEma + GRAVITY_EMA_ALPHA * (az - gravityEma)
        val azNet  = az - gravityEma

        // ── Layer 2: Maneuver subtraction ─────────────────────────────────────
        val fusionValid = maneuverContext.isValid(nowNs)
        val expLong     = if (fusionValid) maneuverContext.expectedLongMs2() else 0f
        val expLat      = if (fusionValid) maneuverContext.expectedLatMs2()  else 0f

        val axResidual  = ax - expLong
        val ayResidual  = ay - expLat
        // az residual: road monitor already handles vertical; no centripetal component

        // ── Layer 1+3: Road quality + axis decomposition ──────────────────────
        val lateralSqR  = axResidual * axResidual + ayResidual * ayResidual
        val lateralR    = sqrt(lateralSqR)
        val totalVecR   = sqrt(lateralSqR + azNet * azNet)

        val residualG   = totalVecR / AppConstants.GRAVITY_MS2
        val horizFrac   = if (totalVecR > 0f) lateralR / totalVecR else 0f

        val roadState   = roadMonitor.currentState
        val threshG     = THRESHOLD_G[roadState]!!
        val minHoriz    = MIN_HORIZ_FRAC[roadState]!!

        when (state) {
            State.COOLDOWN -> {
                if (nowNs >= cooldownEndTs) {
                    state = State.IDLE
                    Log.d(TAG, "Cooldown ended — IDLE  fusion=${fusionValid}")
                }
            }

            State.IDLE -> {
                if (residualG >= threshG && horizFrac >= minHoriz) {
                    state       = State.ARMED
                    armedTs     = nowNs
                    captureArmPeak(residualG, ax, ay, azNet, expLong, expLat, fusionValid)
                    Log.d(TAG, "ARMED  residualG=${residualG}g  horizFrac=${horizFrac}" +
                        "  road=$roadState  fusion=$fusionValid" +
                        "  expLong=${expLong/AppConstants.GRAVITY_MS2}g" +
                        "  expLat=${expLat/AppConstants.GRAVITY_MS2}g")
                } else if (residualG >= threshG && horizFrac < minHoriz) {
                    // Vertical dominant at threshold → road impact, no duration gate needed
                    emitRoadImpact(nowNs, residualG, ax, ay, azNet,
                        expLong, expLat, roadState, "horiz_frac_below_min", record.tsUtc)
                }
            }

            State.ARMED -> {
                if (residualG > peakResidG) {
                    captureArmPeak(residualG, ax, ay, azNet, expLong, expLat, fusionValid)
                }
                val stillAbove  = residualG >= threshG && horizFrac >= minHoriz
                val durationNs  = nowNs - armedTs

                if (!stillAbove) {
                    val durMs = durationNs / 1_000_000L
                    Log.d(TAG, "Spike dropped at ${durMs}ms < ${AppConstants.IMPACT_DURATION_MS}ms → ROAD_IMPACT")
                    emitRoadImpact(nowNs, peakResidG, peakAx, peakAy, peakAzNet,
                        peakExpLong, peakExpLat, roadState, "duration_gate_${durMs}ms", record.tsUtc)
                    state = State.IDLE
                } else if (durationNs >= DURATION_NS) {
                    confirmCollision(nowNs, durationNs / 1_000_000L, roadState, record.tsUtc)
                }
            }
        }
    }

    fun onGyroSample(record: GyroRecord) {
        // ManeuverContext already has its own gyro fan-out — but we keep this
        // hook here so CollisionDetector can optionally use gyro in the future
        // (e.g. rotation-rate burst detection for rollover — Module 8+)
    }

    fun reset() {
        state = State.IDLE; peakG = 0f; peakResidG = 0f
        gravityEma = -AppConstants.GRAVITY_MS2
        maneuverContext.reset()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun captureArmPeak(
        residG: Float, ax: Float, ay: Float, azNet: Float,
        expLong: Float, expLat: Float, fusionValid: Boolean
    ) {
        peakResidG      = residG
        peakG           = sqrt(ax*ax + ay*ay + azNet*azNet) / AppConstants.GRAVITY_MS2
        peakAx          = ax; peakAy = ay; peakAzNet = azNet
        peakExpLong     = expLong; peakExpLat = expLat
        peakFusionValid = fusionValid
    }

    private fun confirmCollision(
        tsMonoNs: Long, durationMs: Long, roadState: RoadState, tsUtc: String
    ) {
        val direction = classifyDirection(
            peakAx - peakExpLong, peakAy - peakExpLat, peakAzNet
        )
        Log.w(TAG, "COLLISION CONFIRMED  dir=$direction  peak=${peakG}g" +
            "  residual=${peakResidG}g  dur=${durationMs}ms  road=$roadState" +
            "  fusion=$peakFusionValid  speed=${maneuverContext.currentSpeedMs * 3.6f}km/h")

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
        writeCallback?.invoke(buildRecord(tsMonoNs, tsUtc, direction, durationMs, roadState, true))
        onEvent(event)
        state         = State.COOLDOWN
        cooldownEndTs = tsMonoNs + COOLDOWN_NS
        peakG = 0f; peakResidG = 0f
    }

    private fun emitRoadImpact(
        tsMonoNs: Long, residG: Float,
        ax: Float, ay: Float, azNet: Float,
        expLong: Float, expLat: Float,
        roadState: RoadState, reason: String, tsUtc: String
    ) {
        Log.d(TAG, "ROAD_IMPACT  residualG=${residG}g  reason=$reason  road=$roadState" +
            "  fusion=$peakFusionValid")
        // ROAD_IMPACT: telemetry only, does NOT fire onEvent()
        writeCallback?.invoke(
            CollisionRecord(
                tsMonoNs      = tsMonoNs,
                tsUtc         = ntpManager.correctedUtcNow(),
                direction     = "ROAD_IMPACT",
                peakG         = sqrt(ax*ax + ay*ay + azNet*azNet) / AppConstants.GRAVITY_MS2,
                durationMs    = 0L,
                axG           = ax    / AppConstants.GRAVITY_MS2,
                ayG           = ay    / AppConstants.GRAVITY_MS2,
                azNetG        = azNet / AppConstants.GRAVITY_MS2,
                roadState     = roadState.name,
                confirmed     = false,
                suppressed    = true,
                expectedAxG   = expLong / AppConstants.GRAVITY_MS2,
                expectedAyG   = expLat  / AppConstants.GRAVITY_MS2,
                residualG     = residG,
                speedKmh      = maneuverContext.currentSpeedMs * 3.6f,
                fusionValid   = peakFusionValid
            )
        )
    }

    private fun buildRecord(
        tsMonoNs: Long, tsUtc: String, direction: String,
        durationMs: Long, roadState: RoadState, confirmed: Boolean
    ) = CollisionRecord(
        tsMonoNs    = tsMonoNs,
        tsUtc       = tsUtc,
        direction   = direction,
        peakG       = peakG,
        durationMs  = durationMs,
        axG         = peakAx    / AppConstants.GRAVITY_MS2,
        ayG         = peakAy    / AppConstants.GRAVITY_MS2,
        azNetG      = peakAzNet / AppConstants.GRAVITY_MS2,
        roadState   = roadState.name,
        confirmed   = confirmed,
        suppressed  = !confirmed,
        expectedAxG = peakExpLong / AppConstants.GRAVITY_MS2,
        expectedAyG = peakExpLat  / AppConstants.GRAVITY_MS2,
        residualG   = peakResidG,
        speedKmh    = maneuverContext.currentSpeedMs * 3.6f,
        fusionValid = peakFusionValid
    )

    private fun classifyDirection(axRes: Float, ayRes: Float, azNet: Float): String {
        val absAx    = kotlin.math.abs(axRes)
        val absAy    = kotlin.math.abs(ayRes)
        val absAzNet = kotlin.math.abs(azNet)
        return when {
            absAzNet > absAx && absAzNet > absAy -> "ROAD_IMPACT"
            absAx   >= absAy                      -> if (axRes < 0f) "FRONT_IMPACT" else "REAR_IMPACT"
            ayRes   >= 0f                         -> "LATERAL_RIGHT"
            else                                  -> "LATERAL_LEFT"
        }
    }
}