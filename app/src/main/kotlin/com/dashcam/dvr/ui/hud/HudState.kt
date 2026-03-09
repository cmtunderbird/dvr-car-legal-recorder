package com.dashcam.dvr.ui.hud

import com.dashcam.dvr.telemetry.model.GpsRecord

/**
 * HudState — immutable snapshot of all data rendered by the military-style HUD.
 *
 * Built by MainActivity from RecordingService flows and pushed to
 * HudOverlayView.update(state) on every frame-relevant change.
 */
data class HudState(
    // ── Recording ────────────────────────────────────────────
    val isRecording: Boolean   = false,
    val elapsedSeconds: Long   = 0L,
    val blinkVisible: Boolean  = true,

    // ── GPS ──────────────────────────────────────────────────
    val hasGpsFix: Boolean     = false,
    val gps: GpsRecord?        = null,

    // ── Acceleration (in g, gravity-subtracted) ──────────────
    val accelAxG: Float        = 0f,   // longitudinal (+ = forward)
    val accelAyG: Float        = 0f,   // lateral      (+ = right)
    val accelTotalG: Float     = 0f,   // √(ax²+ay²+az²) net
    // ── Clinometer (derived from gravity vector) ─────────────
    val pitchDeg: Float        = 0f,   // nose-up positive
    val rollDeg: Float         = 0f,   // right-down positive

    // ── Road quality ─────────────────────────────────────────
    val roadState: String      = "---",  // SMOOTH / ROUGH / VERY_ROUGH
    val roadRmsMs2: Float      = 0f,

    // ── Events ───────────────────────────────────────────────
    val lastEventDirection: String? = null,
    val lastEventPeakG: Float      = 0f,
    val lastEventTimeMs: Long      = 0L,
    val protectedCount: Int        = 0
)
