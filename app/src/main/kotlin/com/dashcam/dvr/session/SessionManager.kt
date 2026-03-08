package com.dashcam.dvr.session

import com.dashcam.dvr.collision.model.ImpactEvent
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.dashcam.dvr.session.model.NavIntegrationFields
import com.dashcam.dvr.session.model.SessionMetadata
import com.dashcam.dvr.session.model.SessionStartResult
import com.dashcam.dvr.telemetry.TelemetryEngine
import com.dashcam.dvr.telemetry.collectors.GpsCollector
import com.dashcam.dvr.telemetry.model.NtpRecord
import com.dashcam.dvr.util.AppConstants
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import com.dashcam.dvr.evidence.SealResult

/**
 * SessionManager
 *
 * Blueprint §11 Session Structure + §12 Evidence Integrity + §14 Storage Health
 * + Blueprint Addon v1.0 §A.5/§A.6 Navigation Extension Fields
 * ─────────────────────────────────────────────────────────────────────────────
 * Single authority for the evidence session lifecycle:
 *
 *   startSession()        — create directory, storage health check, calibration
 *                           validation, write preliminary session.json, init custody.log
 *
 *   patchNtpAndSensors()  — called by RecordingService after TelemetryEngine NTP
 *                           sync completes; updates clock_status, ntp_*, barometer_available
 *
 *   patchGpsFix()         — called when first valid GNSS fix is acquired mid-session
 *                           (for sessions where fix wasn't ready at open time)
 *
 *   closeSession()        — write end_ts + end_reason, final GPS fix ts, append
 *                           SESSION_CLOSE to custody.log
 *
 *   recordCustodyAction() — convenience wrapper for non-lifecycle custody entries
 *                           (export, verify, access)
 *
 * Installation UUID (Blueprint §12 Key Lifecycle):
 *   Generated once at first launch, stored in SharedPreferences.
 *   Survives app restarts and reboots; cleared only on uninstall / data wipe.
 *   Used in session.json and custody.log to map signing-key certificates to device.
 *
 * Thread safety:
 *   startSession() and closeSession() must be called from an IO thread (they block
 *   on StorageHealthChecker.check()). patchNtpAndSensors() is safe from any thread.
 *   Internal session.json writes are synchronized on the file object.
 */
class SessionManager(private val context: Context) {

    companion object {
        private const val TAG                    = "SessionManager"
        private const val PREFS_NAME             = "dvr_session_prefs"
        private const val KEY_INSTALLATION_UUID  = "installation_uuid"
        private const val KEY_LAST_SESSION_DIR   = "last_session_dir"
        private const val SCHEMA_VERSION         = "2.0"
        private const val DVR_BASE_SUBDIR        = "DVR"
    }

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Installation UUID — lazy, generated once, persisted. */
    val installationUuid: String by lazy { getOrCreateInstallationUuid() }

    // ── Session open ──────────────────────────────────────────────────────────

    /**
     * Open a new recording session.
     *
     * Sequence:
     *   1. Create timestamped session directory under .../DVR/
     *   2. Persist directory path to SharedPreferences (OS-restart recovery)
     *   3. Run storage health check (write probe + free-space read)
     *   4. Read and validate calibration.json
     *   5. Write preliminary session.json (NTP="PENDING" — updated after sync)
     *   6. Initialise custody.log with SESSION_OPEN entry
     *
     * Call from an IO-dispatched coroutine — StorageHealthChecker.check() blocks.
     *
     * @param gpsCollector Already-running GpsCollector — GPS state at session open.
     * @param telemetryEngine Used to read NTP state if already synced (rare).
     */
    /**
     * Pre-allocate the session directory WITHOUT running the full session open.
     * Called by RecordingService Binder so MainActivity can start cameras
     * into the correct folder before startSession() is called on the IO thread.
     */
    fun prepareSessionDir(): java.io.File {
        val dir = createSessionDir()
        dir.mkdirs()
        prefs.edit().putString(KEY_LAST_SESSION_DIR, dir.absolutePath).apply()
        Log.i(TAG, "Session dir pre-allocated: ${dir.absolutePath}")
        return dir
    }

    @Suppress("UNUSED_PARAMETER")  // telemetryEngine: forward hook for Module 5 CollisionDetector
    fun startSession(
        gpsCollector:   GpsCollector,
        telemetryEngine: TelemetryEngine,
        existingDir:     java.io.File? = null   // pre-allocated by prepareSessionDir()
    ): SessionStartResult {

        val sessionDir = existingDir ?: createSessionDir()
        sessionDir.mkdirs()

        // Persist for OS-restart recovery
        prefs.edit().putString(KEY_LAST_SESSION_DIR, sessionDir.absolutePath).apply()

        Log.i(TAG, "Session opening: ${sessionDir.absolutePath}")

        // ── Storage health ────────────────────────────────────────────────────
        val storage = StorageHealthChecker.check(sessionDir)

        // ── Calibration ───────────────────────────────────────────────────────
        val calibration = CalibrationValidator.read(context)

        // ── Preliminary session.json ──────────────────────────────────────────
        // NTP fields are "PENDING" here — patchNtpAndSensors() updates them
        // ~2 seconds later once TelemetryEngine completes its NTP sync.
        val metadata = SessionMetadata(
            sessionId            = sessionDir.name,
            schemaVersion        = SCHEMA_VERSION,
            deviceModel          = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion       = Build.VERSION.RELEASE,
            installationUuid     = installationUuid,
            rearCameraId         = "0",      // CameraValidator (Module 2) — already confirmed
            frontCameraId        = "1",      // same
            sessionStartTs       = utcNow(),
            sessionEndTs         = null,
            endReason            = null,
            clockStatus          = "PENDING",
            ntpSyncStatus        = "PENDING",
            ntpOffsetMs          = 0L,
            ntpServer            = "",
            firstValidFixTs      = gpsCollector.firstValidFixTs,   // may be null at open
            agpsSeeded           = gpsCollector.agpsSeeded,
            barometerAvailable   = false,    // updated in patchNtpAndSensors()
            calibrationValid     = calibration.valid,
            calibrationDeviationDeg = calibration.deviationDeg,
            storageWriteSpeedMbps   = storage.writeMbps,
            storageFreeGb           = storage.freeGb,
            storageHealth           = storage.health,
            navIntegration          = NavIntegrationFields()   // Addon v1 — Module 9 fills this
        )

        writeSessionJson(sessionDir, metadata)

        // ── custody.log ───────────────────────────────────────────────────────
        CustodyLog.init(sessionDir, installationUuid, sessionDir.name)

        Log.i(TAG, "Session opened: ${sessionDir.name}  " +
            "storage=${storage.health}(${storage.writeMbps}MB/s, ${storage.freeGb}GB)  " +
            "calibration=${calibration.valid}  agps=${gpsCollector.agpsSeeded}  " +
            "fix=${gpsCollector.firstValidFixTs ?: "NO_FIX_YET"}")

        return SessionStartResult(sessionDir, metadata)
    }

    // ── NTP + sensor patch (called ~2s after open, once TelemetryEngine syncs) ──

    /**
     * Update session.json with NTP sync results and sensor availability.
     * Called by RecordingService via the onNtpSynced callback from TelemetryEngine.
     * Safe to call from any thread.
     */
    fun patchNtpAndSensors(
        sessionDir:        File,
        ntpRecord:         NtpRecord,
        barometerPresent:  Boolean
    ) {
        mutateSessionJson(sessionDir) { existing ->
            existing.copy(
                clockStatus        = if (ntpRecord.syncStatus == "SYNCED") "VERIFIED" else "CLOCK_UNVERIFIED",
                ntpSyncStatus      = ntpRecord.syncStatus,
                ntpOffsetMs        = ntpRecord.offsetMs,
                ntpServer          = ntpRecord.server,
                barometerAvailable = barometerPresent
            )
        }
        Log.i(TAG, "session.json NTP patched: ${ntpRecord.syncStatus}  offset=${ntpRecord.offsetMs}ms  baro=$barometerPresent")

        if (ntpRecord.syncStatus != "SYNCED")
            Log.w(TAG, "CLOCK_UNVERIFIED — session evidence timestamps cannot be fully corroborated")
    }

    // ── GPS fix patch (if fix wasn't ready at session open) ──────────────────

    /**
     * Update first_valid_fix_ts once GPS lock is acquired during the session.
     * Only updates the field if it was null at session open.
     */
    fun patchFirstGpsFix(sessionDir: File, firstFixTs: String) {
        mutateSessionJson(sessionDir) { existing ->
            if (existing.firstValidFixTs == null) existing.copy(firstValidFixTs = firstFixTs)
            else existing
        }
        Log.i(TAG, "session.json first_valid_fix_ts patched: $firstFixTs")
    }

    // ── Session close ─────────────────────────────────────────────────────────

    /**
     * Finalise session.json with end timestamp and reason.
     * Append SESSION_CLOSE to custody.log.
     *
     * @param endReason "USER_STOP" | "OS_RESTART_RECOVERY" | "CRASH"
     */
    fun closeSession(sessionDir: File, endReason: String = "USER_STOP") {
        mutateSessionJson(sessionDir) { existing ->
            existing.copy(
                sessionEndTs = utcNow(),
                endReason    = endReason
            )
        }
        CustodyLog.append(
            sessionDir       = sessionDir,
            installationUuid = installationUuid,
            action           = "SESSION_CLOSE",
            sessionId        = sessionDir.name,
            detail           = endReason,
            result           = "OK"
        )
        // Clear last-session pointer
        prefs.edit().remove(KEY_LAST_SESSION_DIR).apply()
        Log.i(TAG, "Session closed: ${sessionDir.name}  reason=$endReason")
    }

    // ── Custody convenience ───────────────────────────────────────────────────

    fun recordCustodyAction(sessionDir: File, action: String, detail: String = "", result: String = "OK") {
        CustodyLog.append(sessionDir, installationUuid, action, sessionDir.name, detail, result)
    }

    // ── OS-restart recovery ───────────────────────────────────────────────────

    /**
     * Returns the last open session directory if the service was killed mid-recording.
     * Used by RecordingService when onStartCommand receives a null intent (OS restart).
     */
    fun recoverLastSession(): File? {
        val path = prefs.getString(KEY_LAST_SESSION_DIR, null) ?: return null
        val dir  = File(path)
        return if (dir.exists() && dir.isDirectory) {
            Log.i(TAG, "OS-restart recovery: reopening ${dir.name}")
            CustodyLog.append(dir, installationUuid, "SESSION_ACCESS", dir.name, "os_restart_recovery")
            dir
        } else {
            prefs.edit().remove(KEY_LAST_SESSION_DIR).apply()
            null
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @Synchronized
    private fun writeSessionJson(dir: File, metadata: SessionMetadata) {
        try {
            File(dir, AppConstants.SESSION_META_FILENAME).writeText(gson.toJson(metadata))
        } catch (e: Exception) {
            Log.e(TAG, "session.json write failed: ${e.message}")
        }
    }

    @Synchronized
    private fun mutateSessionJson(dir: File, transform: (SessionMetadata) -> SessionMetadata) {
        val file = File(dir, AppConstants.SESSION_META_FILENAME)
        if (!file.exists()) { Log.w(TAG, "mutateSessionJson: file missing in ${dir.name}"); return }
        try {
            val current = gson.fromJson(file.readText(), SessionMetadata::class.java)
            writeSessionJson(dir, transform(current))
        } catch (e: Exception) {
            Log.e(TAG, "session.json mutate failed: ${e.message}")
        }
    }

    private fun createSessionDir(): File {
        val ts      = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(baseDir, "$DVR_BASE_SUBDIR/${AppConstants.SESSION_DIR_PREFIX}$ts")
    }

    private fun getOrCreateInstallationUuid(): String {
        return prefs.getString(KEY_INSTALLATION_UUID, null) ?: run {
            val uuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALLATION_UUID, uuid).apply()
            Log.i(TAG, "Installation UUID generated: $uuid")
            uuid
        }
    }

    private fun utcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    /**
     * Appends a confirmed impact event to custody.log.
     * ROAD_IMPACT events (confirmed=false) are NOT written to custody —
     * they appear in telemetry.log only.
     */

    /**
     * Updates session.json with seal results from EvidencePackager.
     * Called after EvidencePackager.seal() completes successfully.
     * These fields are informational only; the authoritative integrity proof
     * is manifest.json + signature + tsa_response.tsr.
     */
    fun patchSealResult(sessionDir: File, result: SealResult) {
        mutateSessionJson(sessionDir) { meta ->
            meta.copy(
                tsaStatus    = result.tsaStatus,
                signingKeyId = AppConstants.KEYSTORE_ALIAS,
                manifestHash = result.manifestHash,
                sealedTs     = result.sealedTs
            )
        }
        Log.i(TAG, "session.json patched with seal result  tsa=${result.tsaStatus}  hash=${result.manifestHash}")
    }

    fun appendCustodyEvent(sessionDir: File, event: ImpactEvent) {
        if (!event.confirmed) return
        try {
            CustodyLog.append(
                sessionDir       = sessionDir,
                installationUuid = installationUuid,
                action           = "IMPACT_EVENT",
                sessionId        = sessionDir.name,
                detail           = "${event.direction}  peak=${event.peakG}g  road=${event.roadState}",
                result           = "RECORDED"
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "appendCustodyEvent failed: ${e.message}")
        }
    }
}
