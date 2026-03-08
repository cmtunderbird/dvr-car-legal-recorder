package com.dashcam.dvr.collision.model

/**
 * ImpactEvent — fired by CollisionDetector on threshold confirmation.
 *
 * Carried via RecordingService.collisionEvents StateFlow so the UI and
 * custody.log can react to confirmed events in real-time.
 *
 * direction:
 *   "FRONT_IMPACT"    — dominant ax deceleration (frontal collision)
 *   "REAR_IMPACT"     — dominant ax acceleration  (struck from behind)
 *   "LATERAL_LEFT"    — dominant ay negative
 *   "LATERAL_RIGHT"   — dominant ay positive
 *   "ROAD_IMPACT"     — vertical dominant (pothole / speed bump)
 *                       NOT promoted to a custody event — logged to telemetry only
 *
 * roadState at time of detection:
 *   "SMOOTH" | "ROUGH" | "VERY_ROUGH"
 *
 * confirmed = true  → threshold + duration met → custody event
 * confirmed = false → sub-threshold (logged for audit only, not a custody event)
 */
data class ImpactEvent(
    val tsMonoNs:    Long,
    val tsUtc:       String,
    val direction:   String,
    val peakG:       Float,
    val durationMs:  Long,
    val axG:         Float,
    val ayG:         Float,
    val azNetG:      Float,
    val roadState:   String,
    val confirmed:   Boolean
)

/** Road quality state exposed by RoadQualityMonitor. */
enum class RoadState { SMOOTH, ROUGH, VERY_ROUGH }