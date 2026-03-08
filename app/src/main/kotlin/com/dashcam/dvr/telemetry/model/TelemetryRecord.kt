package com.dashcam.dvr.telemetry.model

import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────────────────────────────────────
// TelemetryRecord — sealed family of JSONL entry types
//
// Blueprint §6 — Telemetry Engine / §11 — Session Structure
// Each instance is serialised by Gson to a single JSON line in telemetry.log.
//
// JSONL field conventions:
//   ts_mono_ns  — SystemClock.elapsedRealtimeNanos() for inter-sensor alignment
//   ts_utc      — ISO 8601 UTC string, NTP-corrected for absolute timestamps
//   type        — discriminator, always first field for fast parsing
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GPS/GNSS record — 5 Hz
 *
 * fix_status: "VALID" = HDOP ≤ 2.5 AND ≥ 4 satellites in fix.
 *             "NO_FIX" = below threshold; frames before valid fix are tagged for
 *             analyst awareness (Blueprint §6 — GPS Cold-Start Handling).
 */
data class GpsRecord(
    val type: String = "GPS",
    @SerializedName("ts_mono_ns")  val tsMonoNs:   Long,
    @SerializedName("ts_utc")      val tsUtc:       String,
    val lat:                                          Double,
    val lon:                                          Double,
    @SerializedName("alt_m")       val altM:         Double,
    @SerializedName("speed_kmh")   val speedKmh:     Float,
    @SerializedName("heading_deg") val headingDeg:   Float,
    val hdop:                                         Float,

    // Total satellites contributing to fix
    val satellites:                                   Int,

    // Per-constellation in-fix counts (Dimensity 7300 Ultra: GPS/GLO/GAL/BDS/SBAS/QZSS)
    @SerializedName("sats_gps")    val satsGps:      Int,
    @SerializedName("sats_glo")    val satsGlo:      Int,
    @SerializedName("sats_gal")    val satsGal:      Int,
    @SerializedName("sats_bds")    val satsBds:      Int,
    @SerializedName("sats_qzss")   val satsQzss:     Int,

    // SBAS (EGNOS/WAAS/MSAS/GAGAN) — differential correction pseudoranges in fix
    @SerializedName("sats_sbas")   val satsSbas:     Int,
    @SerializedName("sbas_active") val sbasActive:   Boolean,

    // A-GPS: coarse position seed was available at collector start (speeds cold-start)
    @SerializedName("agps_seeded") val agpsSeeded:   Boolean,

    @SerializedName("fix_status")  val fixStatus:    String   // "VALID" | "NO_FIX"
)

/**
 * Accelerometer record — 100 Hz
 * Values in m/s². Collision spike threshold: |a| > 3g ≈ 29.4 m/s² (Blueprint §8).
 */
data class AccelRecord(
    val type: String = "ACCEL",
    @SerializedName("ts_mono_ns") val tsMonoNs: Long,
    @SerializedName("ts_utc")     val tsUtc:    String,
    val ax: Float,
    val ay: Float,
    val az: Float
)

/**
 * Gyroscope record — 100 Hz
 * Values in rad/s. Used for manoeuvre classification and drowsiness IMU channel.
 */
data class GyroRecord(
    val type: String = "GYRO",
    @SerializedName("ts_mono_ns") val tsMonoNs: Long,
    @SerializedName("ts_utc")     val tsUtc:    String,
    val wx: Float,
    val wy: Float,
    val wz: Float
)

/**
 * Magnetometer record — 10 Hz
 * Values in µT. heading_deg cross-checks GPS bearing (Blueprint §6).
 */
data class MagRecord(
    val type: String = "MAG",
    @SerializedName("ts_mono_ns")  val tsMonoNs:  Long,
    @SerializedName("ts_utc")      val tsUtc:     String,
    val mx: Float,
    val my: Float,
    val mz: Float,
    @SerializedName("heading_deg") val headingDeg: Float
)

/**
 * Barometer record — 5 Hz
 * Improves altitude accuracy vs GPS-only, especially in urban canyons (Blueprint §3).
 */
data class BaroRecord(
    val type: String = "BARO",
    @SerializedName("ts_mono_ns")     val tsMonoNs:    Long,
    @SerializedName("ts_utc")         val tsUtc:       String,
    @SerializedName("pressure_hpa")   val pressureHpa: Float,
    @SerializedName("alt_m")          val altM:        Float
)

/**
 * NTP sync record — written once at session start.
 *
 * Blueprint §6 — Clock Synchronisation:
 *   sync_status: "SYNCED" | "FAILED" | "SKIPPED"
 *   Sessions where sync could not be confirmed are flagged CLOCK_UNVERIFIED
 *   in session.json by SessionManager (Module 4).
 */
data class NtpRecord(
    val type: String = "NTP",
    @SerializedName("ts_utc")       val tsUtc:      String,
    @SerializedName("sync_status")  val syncStatus: String,
    @SerializedName("offset_ms")    val offsetMs:   Long,
    val server:                                      String
)


/**
 * Road quality record — written every 5s OR on state change.
 *
 * state:          "SMOOTH" | "ROUGH" | "VERY_ROUGH"
 * vertical_rms:   RMS of net vertical acceleration over last 2s window (m/s²)
 * sample_window:  actual samples captured in that window (ms)
 * state_changed:  true when this record was triggered by a state transition
 *
 * Used by CollisionDetector to raise thresholds on rough surfaces,
 * eliminating pothole/speed-bump false positives.
 */
data class RoadQualityRecord(
    val type: String = "ROAD_QUALITY",
    @SerializedName("ts_mono_ns")       val tsMonoNs:       Long,
    @SerializedName("ts_utc")           val tsUtc:          String,
    val state:                                               String,
    @SerializedName("vertical_rms_ms2") val verticalRmsMs2: Float,
    @SerializedName("sample_window_ms") val sampleWindowMs: Long,
    @SerializedName("state_changed")    val stateChanged:   Boolean
)

/**
 * Collision / road-impact record — written for every detection outcome.
 *
 * direction:    "FRONT_IMPACT" | "REAR_IMPACT" | "LATERAL_LEFT" | "LATERAL_RIGHT" | "ROAD_IMPACT"
 * peak_g:       highest total-g measured during the spike
 * duration_ms:  how long the threshold was sustained (0 for suppressed road impacts)
 * ax_g/ay_g/az_net_g: peak acceleration per axis in g (gravity removed from az)
 * road_state:   road quality at time of detection
 * confirmed:    true = duration gate passed → real custody event
 * suppressed:   true = classified as road surface feature, NOT a custody event
 */
data class CollisionRecord(
    val type: String = "COLLISION",
    @SerializedName("ts_mono_ns")  val tsMonoNs:   Long,
    @SerializedName("ts_utc")      val tsUtc:      String,
    val direction:                                  String,
    @SerializedName("peak_g")      val peakG:      Float,
    @SerializedName("duration_ms") val durationMs: Long,
    @SerializedName("ax_g")        val axG:        Float,
    @SerializedName("ay_g")        val ayG:        Float,
    @SerializedName("az_net_g")    val azNetG:     Float,
    @SerializedName("road_state")  val roadState:  String,
    val confirmed:                                  Boolean,
    val suppressed:                                 Boolean
)