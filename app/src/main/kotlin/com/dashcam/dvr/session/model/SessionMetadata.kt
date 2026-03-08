package com.dashcam.dvr.session.model

import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────────────────────────────────────
// SessionMetadata  —  serialised to session.json at session open/close
//
// Blueprint §11 Session Structure + Blueprint §12 Evidence Integrity
// + Blueprint Addon v1.0 §A.6 Navigation Extension Fields
//
// Schema version:  "2.0"  (tracks Blueprint v2.0)
// ─────────────────────────────────────────────────────────────────────────────

data class SessionMetadata(

    // ── Identity ──────────────────────────────────────────────────────────────
    @SerializedName("session_id")        val sessionId:           String,
    @SerializedName("schema_version")    val schemaVersion:       String,

    // ── Device ───────────────────────────────────────────────────────────────
    @SerializedName("device_model")      val deviceModel:         String,
    @SerializedName("android_version")   val androidVersion:      String,
    /** Persisted UUID generated once at app install — survives reboots, not factory reset.
     *  Used to map signing-key certificates to device in PC viewer key store (Blueprint §12). */
    @SerializedName("installation_uuid") val installationUuid:    String,

    // ── Cameras (set by CameraValidator at session start) ────────────────────
    @SerializedName("rear_camera_id")    val rearCameraId:        String,
    @SerializedName("front_camera_id")   val frontCameraId:       String,

    // ── Lifecycle timestamps ──────────────────────────────────────────────────
    @SerializedName("session_start_ts")  val sessionStartTs:      String,
    /** Null until session is closed.  Written by SessionManager.closeSession(). */
    @SerializedName("session_end_ts")    val sessionEndTs:        String?,
    /** "USER_STOP" | "OS_RESTART_RECOVERY" | "CRASH" | "BOOT_RECOVERY" */
    @SerializedName("end_reason")        val endReason:           String?,

    // ── Clock (Blueprint §6 Clock Synchronisation) ────────────────────────────
    /** "VERIFIED" = NTP synced, "CLOCK_UNVERIFIED" = sync failed, "PENDING" = not yet synced */
    @SerializedName("clock_status")      val clockStatus:         String,
    @SerializedName("ntp_sync_status")   val ntpSyncStatus:       String,
    @SerializedName("ntp_offset_ms")     val ntpOffsetMs:         Long,
    @SerializedName("ntp_server")        val ntpServer:           String,

    // ── GPS (Blueprint §6 GPS Cold-Start Handling) ────────────────────────────
    /** ISO-8601 UTC timestamp of first valid GNSS fix (HDOP ≤ 2.5, ≥ 4 sats). Null if never acquired. */
    @SerializedName("first_valid_fix_ts") val firstValidFixTs:    String?,
    /** True if A-GPS seed was available from last-known-location at collector start. */
    @SerializedName("agps_seeded")        val agpsSeeded:         Boolean,

    // ── Sensors ───────────────────────────────────────────────────────────────
    @SerializedName("barometer_available") val barometerAvailable: Boolean,

    // ── Calibration (Blueprint §10) ───────────────────────────────────────────
    /** True if calibration.json was found and parseable. False = uncalibrated session. */
    @SerializedName("calibration_valid")       val calibrationValid:      Boolean,
    /** Deviation in degrees from stored gravity vector (null if no previous calibration). */
    @SerializedName("calibration_deviation_deg") val calibrationDeviationDeg: Float?,

    // ── Storage Health (Blueprint §14 Storage Health Monitoring) ─────────────
    /** Write throughput measured at session open with 10 MB probe file (MB/s). */
    @SerializedName("storage_write_speed_mbps") val storageWriteSpeedMbps: Float,
    /** Free storage at session open in GB. */
    @SerializedName("storage_free_gb")          val storageFreeGb:         Float,
    /** "OK" | "SLOW" (<14 MB/s) | "LOW_SPACE" (<10 GB) | "CRITICAL" (both) */
    @SerializedName("storage_health")           val storageHealth:         String,

    // -- Evidence Integrity (Module 6 - EvidencePackager) --------------------------------
    /** "TIMESTAMPED" | "TSA_UNAVAILABLE" | "TSA_ERROR" | "PENDING" | null = not sealed */
    @SerializedName("tsa_status")        val tsaStatus:           String? = null,
    /** Android Keystore alias used to sign this session */
    @SerializedName("signing_key_id")    val signingKeyId:        String? = null,
    /** SHA-256 hex of the final manifest.json */
    @SerializedName("manifest_hash")     val manifestHash:        String? = null,
    /** ISO-8601 UTC when EvidencePackager.seal() completed */
    @SerializedName("sealed_ts_utc")     val sealedTs:            String? = null,

    // ── Navigation Integration (Addon v1.0 §A.6) ─────────────────────────────
    /** Pre-declared at session open; populated by NavigationEngine (Module 9). */
    @SerializedName("nav_integration")  val navIntegration: NavIntegrationFields = NavIntegrationFields()
)

// ─────────────────────────────────────────────────────────────────────────────
// NavIntegrationFields  —  Addon v1.0 §A.6
// Pre-declared at every session open. Null fields indicate integration inactive.
// Module 9 (NavigationEngine) updates these fields during the session.
// ─────────────────────────────────────────────────────────────────────────────
data class NavIntegrationFields(
    @SerializedName("enabled")                val enabled:              Boolean = false,
    /** "waze" | "google_maps" | null */
    @SerializedName("provider")               val provider:             String? = null,
    @SerializedName("provider_version")       val providerVersion:      String? = null,
    @SerializedName("destination")            val destination:          String? = null,
    @SerializedName("navigation_started_ts")  val navigationStartedTs:  String? = null,
    @SerializedName("navigation_ended_ts")    val navigationEndedTs:    String? = null,
    /** "ARRIVED" | "ABORTED" | "TIMEOUT" */
    @SerializedName("end_reason")             val endReason:            String? = null,
    /** % of journey where GPS track was within 50 m of planned polyline. */
    @SerializedName("route_adherence_pct")    val routeAdherencePct:    Float?  = null,
    @SerializedName("deviations_count")       val deviationsCount:      Int?    = null,
    @SerializedName("reroutes_count")         val reroutesCount:        Int?    = null,
    @SerializedName("speed_violations_count") val speedViolationsCount: Int?    = null,
    @SerializedName("hazard_alerts_received") val hazardAlertsReceived: Int?    = null
)

// ─────────────────────────────────────────────────────────────────────────────
// CalibrationData  —  persisted in calibration.json (app-level, not per-session)
// Blueprint §10 Calibration Procedure
// ─────────────────────────────────────────────────────────────────────────────
data class CalibrationData(
    /** Gravity vector at rest — defines the vertical (Z) axis. */
    @SerializedName("gravity_x")       val gravityX:      Float,
    @SerializedName("gravity_y")       val gravityY:      Float,
    @SerializedName("gravity_z")       val gravityZ:      Float,
    /** Forward axis from 30-second straight-line drive calibration. Null if not yet done. */
    @SerializedName("forward_x")       val forwardX:      Float? = null,
    @SerializedName("forward_y")       val forwardY:      Float? = null,
    @SerializedName("forward_z")       val forwardZ:      Float? = null,
    @SerializedName("calibrated_ts")   val calibratedTs:  String,
    @SerializedName("calibration_ver") val calibrationVer: Int = 1
)

// Internal only — not serialised
data class CalibrationResult(
    val valid:        Boolean,
    val deviationDeg: Float?,
    val data:         CalibrationData?,
    val reason:       String
)

// Internal only — not serialised
data class StorageHealthResult(
    val writeMbps: Float,
    val freeGb:    Float,
    val health:    String   // "OK" | "SLOW" | "LOW_SPACE" | "CRITICAL"
)

// Returned by SessionManager.startSession()
data class SessionStartResult(
    val sessionDir: java.io.File,
    val metadata:   SessionMetadata
)
