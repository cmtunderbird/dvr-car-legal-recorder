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
 *   Adaptive threshold based on road surface vertical RMS.
 *
 * Layer 2 — Maneuver subtraction (ManeuverContext):
 *   ax_residual = ax − a_longitudinal (GPS dV/dt)
 *   ay_residual = ay − a_centripetal  (gyro × speed)
 *   Threshold applied to residual vector, not raw IMU.
 *   Falls back to raw IMU when GPS fix is stale or speed < 5 km/h.
 *
 * Layer 3 — Vertical dominance (axis decomposition):
 *   horiz_frac = lateral_residual / total_residual
 *   Below road-state minimum → ROAD_IMPACT (telemetry only).
 *
 * GRAVITY SOURCE — GravityProvider (Fix for Bug 1 + Bug 3)
 * ──────────────────────────────────────────────────────────
 * Previously: independent gravityEma = -9.807f (hardcoded, wrong sign for
 * face-up phone). Error at t=0 was 19 m/s² = 1.94g. Never fully converged in
 * a 33-second session. Both CollisionDetector and RoadQualityMonitor had their
 * own diverging EMAs.
 *
 * Now: GravityProvider delivers TYPE_GRAVITY (hardware HAL, zero warmup,
 * correct sign) or a first-sample-initialised EMA fallback. Single shared
 * instance — both components read the same gravity vector.
 *
 * STATE MACHINE PRIORITY FIX (Fix for Bug 2)
 * ────────────────────────────────────────────
 * Previous code checked !stillAbove BEFORE the duration gate:
 *   if (!stillAbove) → ROAD_IMPACT
 *   else if (dur ≥ 20ms) → CONFIRMED
 *
 * This caused a 5.27g lateral shake sustained exactly 20ms to be classified
 * ROAD_IMPACT because the magnitude dropped on the same sample the duration
 * gate fired — the gate lost the race.
 *
 * Fixed order:
 *   if (dur ≥ 20ms AND stillAbove) → CONFIRMED   ← duration gate wins
 *   else if (!stillAbove) → ROAD_IMPACT           ← only if gate not yet met
 *
 * ADAPTIVE THRESHOLDS (road state):
 *   SMOOTH:      trigger ≥ 3.0g, horiz_frac ≥ 0.35
 *   ROUGH:       trigger ≥ 3.5g, horiz_frac ≥ 0.45
 *   VERY_ROUGH:  trigger ≥ 4.5g, horiz_frac ≥ 0.65
 *
 * DIRECTION CLASSIFICATION (on confirmed residual vector):
 *   FRONT_IMPACT / REAR_IMPACT / LATERAL_LEFT / LATERAL_RIGHT / ROAD_IMPACT
 *
 * CollisionRecord forensic fields:
 *   expected_ax_g, expected_ay_g  — ManeuverContext prediction
 *   residual_g                    — magnitude that crossed the threshold
 *   speed_kmh, fusion_valid       — GPS context at event time
 */
class CollisionDetector(
    private val ntpManager:      NtpSyncManager,
    private val roadMonitor:     RoadQualityMonitor,
    private val maneuverContext: ManeuverContext,
    private val gravityProvider: GravityProvider,
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
    }

    // ── State machine ─────────────────────────────────────────────────────────
    private enum class State { IDLE, ARMED, COOLDOWN }
    private var state         = State.IDLE
    private var armedTs       = 0L
    private var cooldownEndTs = 0L

    // Peak values captured during ARMED phase
    private var peakG          = 0f
    private var peakAx         = 0f
    private var peakAy         = 0f
    private var peakAzNet      = 0f
    private var peakExpLong    = 0f
    private var peakExpLat     = 0f
    private var peakResidG     = 0f
    private var peakFusionValid = false

    // ── Entry points ──────────────────────────────────────────────────────────

    fun onAccelSample(record: AccelRecord) {
        val nowNs = record.tsMonoNs
        val ax    = record.ax
        val ay    = record.ay
        val az    = record.az

        // ── Gravity from shared GravityProvider (Fix 1+3) ────────────────────
        // No local EMA, no hardcoded constant, no sign assumption.
        val gz    = gravityProvider.gz
        val azNet = az - gz

        // ── Layer 2: Maneuver subtraction ─────────────────────────────────────
        val fusionValid = maneuverContext.isValid(nowNs)
        val expLong     = if (fusionValid) maneuverContext.expectedLongMs2() else 0f
        val expLat      = if (fusionValid) maneuverContext.expectedLatMs2()  else 0f

        val axResidual = ax - expLong
        val ayResidual = ay - expLat

        // ── Layer 1+3: Road quality + axis decomposition ──────────────────────
        val lateralSq  = axResidual * axResidual + ayResidual * ayResidual
        val lateral    = sqrt(lateralSq)
        val totalVec   = sqrt(lateralSq + azNet * azNet)

        val residualG  = totalVec / AppConstants.GRAVITY_MS2
        val horizFrac  = if (totalVec > 0f) lateral / totalVec else 0f

        val roadState  = roadMonitor.currentState
        val threshG    = THRESHOLD_G[roadState]!!
        val minHoriz   = MIN_HORIZ_FRAC[roadState]!!

        when (state) {

            State.COOLDOWN -> {
                if (nowNs >= cooldownEndTs) {
                    state = State.IDLE
                    Log.d(TAG, "Cooldown ended → IDLE")
                }
            }

            State.IDLE -> {
                if (nowNs < cooldownEndTs) { state = State.COOLDOWN; return }
                when {
                    residualG >= threshG && horizFrac >= minHoriz -> {
                        state   = State.ARMED
                        armedTs = nowNs
                        captureArmPeak(residualG, ax, ay, azNet, expLong, expLat, fusionValid)
                        Log.d(TAG, "ARMED  residualG=%.3fg  horizFrac=%.3f  road=$roadState".format(
                            residualG, horizFrac) +
                            "  expLong=%.3fg  expLat=%.3fg  gz=%.3f".format(
                            expLong / AppConstants.GRAVITY_MS2,
                            expLat  / AppConstants.GRAVITY_MS2, gz))
                    }
                    residualG >= threshG -> {
                        // Vertical dominant at threshold — road impact, no gate needed
                        emitRoadImpact(nowNs, residualG, ax, ay, azNet,
                            expLong, expLat, roadState, "horiz_frac_below_min", record.tsUtc)
                    }
                }
            }

            State.ARMED -> {
                if (residualG > peakResidG) {
                    captureArmPeak(residualG, ax, ay, azNet, expLong, expLat, fusionValid)
                }
                val durationNs  = nowNs - armedTs
                val stillAbove  = residualG >= threshG && horizFrac >= minHoriz

                // ── BUG 2 FIX: duration gate checked BEFORE !stillAbove ──────
                // Old: if (!stillAbove) → ROAD_IMPACT  ELSE IF dur≥20ms → CONFIRM
                //   → duration gate lost the race when both fired on same sample
                // New: if (dur≥20ms AND stillAbove) → CONFIRM  (gate wins)
                //      else if (!stillAbove) → ROAD_IMPACT (only if gate not met)
                when {
                    durationNs >= DURATION_NS && stillAbove -> {
                        confirmCollision(nowNs, durationNs / 1_000_000L, roadState, record.tsUtc)
                    }
                    !stillAbove -> {
                        val durMs = durationNs / 1_000_000L
                        Log.d(TAG, "Spike lost at ${durMs}ms < ${AppConstants.IMPACT_DURATION_MS}ms → ROAD_IMPACT")
                        emitRoadImpact(nowNs, peakResidG, peakAx, peakAy, peakAzNet,
                            peakExpLong, peakExpLat, roadState,
                            "duration_gate_${durMs}ms", record.tsUtc)
                        state = State.IDLE
                    }
                }
            }
        }
    }

    fun onGyroSample(record: GyroRecord) {
        // Reserved for rollover / rotation-burst detection (future module)
    }

    fun reset() {
        state = State.IDLE; peakG = 0f; peakResidG = 0f
        armedTs = 0L; cooldownEndTs = 0L
        maneuverContext.reset()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun captureArmPeak(
        residG: Float, ax: Float, ay: Float, azNet: Float,
        expLong: Float, expLat: Float, fusionValid: Boolean
    ) {
        peakResidG      = residG
        peakG           = sqrt(ax*ax + ay*ay + azNet*azNet) / AppConstants.GRAVITY_MS2
        peakAx = ax; peakAy = ay; peakAzNet = azNet
        peakExpLong = expLong; peakExpLat = expLat
        peakFusionValid = fusionValid
    }

    private fun confirmCollision(
        tsMonoNs: Long, durationMs: Long, roadState: RoadState, tsUtc: String
    ) {
        val direction = classifyDirection(
            peakAx - peakExpLong, peakAy - peakExpLat, peakAzNet
        )
        Log.w(TAG, "COLLISION CONFIRMED  dir=$direction  peak=%.3fg  residual=%.3fg  dur=${durationMs}ms"
            .format(peakG, peakResidG) +
            "  road=$roadState  fusion=$peakFusionValid  speed=%.1fkm/h"
            .format(maneuverContext.currentSpeedMs * 3.6f))

        val event = ImpactEvent(
            tsMonoNs   = tsMonoNs,
            tsUtc      = tsUtc,
            direction  = direction,
            peakG      = peakG,
            durationMs = durationMs,
            axG        = peakAx    / AppConstants.GRAVITY_MS2,
            ayG        = peakAy    / AppConstants.GRAVITY_MS2,
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
        Log.d(TAG, "ROAD_IMPACT  residualG=%.3fg  reason=$reason  road=$roadState".format(residG))
        writeCallback?.invoke(
            CollisionRecord(
                tsMonoNs     = tsMonoNs,
                tsUtc        = ntpManager.correctedUtcNow(),
                direction    = "ROAD_IMPACT",
                peakG        = sqrt(ax*ax + ay*ay + azNet*azNet) / AppConstants.GRAVITY_MS2,
                durationMs   = 0L,
                axG          = ax    / AppConstants.GRAVITY_MS2,
                ayG          = ay    / AppConstants.GRAVITY_MS2,
                azNetG       = azNet / AppConstants.GRAVITY_MS2,
                roadState    = roadState.name,
                confirmed    = false,
                suppressed   = true,
                expectedAxG  = expLong / AppConstants.GRAVITY_MS2,
                expectedAyG  = expLat  / AppConstants.GRAVITY_MS2,
                residualG    = residG,
                speedKmh     = maneuverContext.currentSpeedMs * 3.6f,
                fusionValid  = peakFusionValid
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
        val absAx    = abs(axRes)
        val absAy    = abs(ayRes)
        val absAzNet = abs(azNet)
        return when {
            absAzNet > absAx && absAzNet > absAy -> "ROAD_IMPACT"
            absAx   >= absAy                      -> if (axRes < 0f) "FRONT_IMPACT" else "REAR_IMPACT"
            ayRes   >= 0f                         -> "LATERAL_RIGHT"
            else                                  -> "LATERAL_LEFT"
        }
    }
}