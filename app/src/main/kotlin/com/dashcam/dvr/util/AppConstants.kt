package com.dashcam.dvr.util

/**
 * AppConstants
 *
 * Single source of truth for all configuration constants.
 * Values map directly to parameters defined in the Blueprint v2.
 */
object AppConstants {

    // ── Video ──────────────────────────────────────────────────────────────
    const val REAR_CAM_WIDTH        = 1920
    const val REAR_CAM_HEIGHT       = 1080
    const val REAR_CAM_FPS          = 30
    const val REAR_CAM_BITRATE_BPS  = 8_000_000   // 8 Mbps

    const val FRONT_CAM_WIDTH       = 1280
    const val FRONT_CAM_HEIGHT      = 720
    const val FRONT_CAM_FPS         = 30
    const val FRONT_CAM_BITRATE_BPS = 4_000_000   // 4 Mbps

    const val VIDEO_CODEC            = "video/avc"  // H.264
    const val VIDEO_I_FRAME_INTERVAL = 1            // seconds between keyframes

    // ── Audio ──────────────────────────────────────────────────────────────
    const val AUDIO_CODEC        = "audio/mp4a-latm"  // AAC-LC
    const val AUDIO_BITRATE_BPS  = 128_000
    const val AUDIO_SAMPLE_RATE  = 44100
    const val AUDIO_CHANNELS     = 1

    // ── Loop Recording ─────────────────────────────────────────────────────
    const val SEGMENT_DURATION_MS    = 3 * 60 * 1000L  // 3 minutes
    const val SEGMENT_OVERLAP_MS     = 2_000L           // 2s overlap
    const val PRE_EVENT_BUFFER_MS    = 30_000L          // 30s in-memory buffer
    const val MAX_PROTECTED_EVENTS   = 25               // Blueprint §4
    const val MIN_FREE_STORAGE_BYTES = 10L * 1024 * 1024 * 1024  // 10 GB

    // ── Telemetry Sampling ─────────────────────────────────────────────────
    const val GPS_SAMPLE_HZ     = 5
    const val ACCEL_SAMPLE_HZ   = 100
    const val GYRO_SAMPLE_HZ    = 100
    const val MAGNETO_SAMPLE_HZ = 10
    const val BARO_SAMPLE_HZ    = 5

    // ── GPS Quality ────────────────────────────────────────────────────────
    const val GPS_MIN_SATELLITES = 4
    const val GPS_MAX_HDOP       = 2.5f

    // ── Calibration ────────────────────────────────────────────────────────
    const val CALIBRATION_MIN_SPEED_KMH         = 20.0f
    const val CALIBRATION_DRIVE_SEC             = 30
    const val CALIBRATION_GRAVITY_TOLERANCE_DEG = 5.0f

    // ── Evidence Integrity ─────────────────────────────────────────────────
    const val HASH_ALGORITHM      = "SHA-256"
    const val KEYSTORE_ALIAS      = "dvr_signing_key_v1"
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    // ── Storage & Session ──────────────────────────────────────────────────
    const val SESSION_DIR_PREFIX    = "session_"
    const val REAR_VIDEO_FILENAME   = "rear_camera.mp4"
    const val FRONT_VIDEO_FILENAME  = "front_camera.mp4"
    const val AUDIO_FILENAME        = "audio.aac"
    const val TELEMETRY_FILENAME    = "telemetry.log"
    const val CALIBRATION_FILENAME  = "calibration.json"
    const val SESSION_META_FILENAME = "session.json"
    const val MANIFEST_FILENAME     = "manifest.json"
    const val SIGNATURE_FILENAME    = "signature"
    const val CUSTODY_LOG_FILENAME  = "custody.log"

    // ── Addon v1.0 §A.5 — Navigation Integration session files ──────────────────
    const val FILE_ROUTE_PLAN     = "route_plan.geojson"    // planned route polyline
    const val FILE_ROUTE_ACTUAL   = "route_actual.geojson"  // actual GNSS track
    const val FILE_NAV_EVENTS     = "navigation_events.log" // Waze alerts, re-routes, deviations
    const val FILE_SPEED_LIMITS   = "speed_limits.log"      // speed limit per segment x actual speed
    const val FILE_NAV_STATE      = "navigation_state.log"  // state machine transitions

    // ── Addon v1.0 §B.8 — Driver Drowsiness Detection session files ─────────────
    const val FILE_DROWSINESS     = "drowsiness.log"         // per-alert JSONL with all sensor readings
    const val FILE_FATIGUE_SCORE  = "fatigue_score.json"     // 0-100 score sampled every 30s
    const val EXPORT_FILENAME_PREFIX = "case_export_"

    // ── Storage health ─────────────────────────────────────────────────────
    const val MIN_WRITE_SPEED_BYTES_SEC = 14 * 1024 * 1024L  // 14 MB/s

    // ── NTP ────────────────────────────────────────────────────────────────
    const val NTP_SERVER     = "pool.ntp.org"
    const val NTP_TIMEOUT_MS = 5_000L

    // ── Event Window ───────────────────────────────────────────────────────
    const val EVENT_WINDOW_BEFORE_MS = 10_000L
    const val EVENT_WINDOW_AFTER_MS  = 10_000L

    // ── WakeLock ───────────────────────────────────────────────────────────
    /** Safety ceiling — 12 hours covers any realistic driving session (Blueprint §14) */
    const val MAX_SESSION_WAKELOCK_MS = 12 * 60 * 60 * 1000L

    // ── Collision Detection — adaptive thresholds ─────────────────────────────
    // Base threshold (Blueprint §8): 3g / 20ms
    // Raised on rough road to suppress pothole/speed-bump false positives.
    const val IMPACT_G_THRESHOLD    = 3.0f   // SMOOTH road: trigger ≥ 3.0g
    const val IMPACT_G_ROUGH        = 3.5f   // ROUGH road:  trigger ≥ 3.5g
    const val IMPACT_G_VERY_ROUGH   = 4.5f   // VERY_ROUGH:  trigger ≥ 4.5g

    // Minimum horizontal fraction of total-g vector required for COLLISION
    // (below this → vertical dominant → classified as ROAD_IMPACT, not collision)
    const val IMPACT_MIN_HORIZ_FRAC   = 0.35f  // SMOOTH
    const val IMPACT_MIN_HORIZ_ROUGH  = 0.45f  // ROUGH
    const val IMPACT_MIN_HORIZ_V_ROUGH = 0.65f  // VERY_ROUGH

    const val IMPACT_DURATION_MS   = 20L          // Blueprint §8: must sustain ≥ 20ms
    const val IMPACT_COOLDOWN_MS   = 2_000L       // 2s dead-zone after confirmed collision
    const val GRAVITY_MS2          = 9.80665f

    // ── Road Quality Monitor ──────────────────────────────────────────────────
    // RMS thresholds for vertical net acceleration (m/s²)
    const val ROAD_ROUGH_RMS_MS2       = 1.5f   // > 1.5 m/s² RMS → ROUGH
    const val ROAD_VERY_ROUGH_RMS_MS2  = 4.0f   // > 4.0 m/s² RMS → VERY_ROUGH
    const val ROAD_QUALITY_LOG_INTERVAL_MS = 5_000L  // write record every 5s (or on state change)

    // ── Events log ────────────────────────────────────────────────────────────
    const val EVENTS_FILENAME = "events.log"

    // ── Module 6 — Evidence Packager ──────────────────────────────────────────
    /** RFC 3161 TSA response token stored alongside the signature */
    const val TSA_TOKEN_FILENAME       = "tsa_response.tsr"
    /** Public key export — written once on first seal; send to key server */
    const val KEY_CERTIFICATE_FILENAME = "key_certificate.json"
    /** Primary TSA endpoint (DigiCert public free TSA) */
    const val TSA_URL_PRIMARY          = "http://timestamp.digicert.com"
}
