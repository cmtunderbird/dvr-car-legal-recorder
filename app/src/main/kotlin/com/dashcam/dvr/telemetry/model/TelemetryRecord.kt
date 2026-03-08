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
    val satellites:                                   Int,
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
