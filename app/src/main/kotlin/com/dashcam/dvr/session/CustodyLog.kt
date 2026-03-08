package com.dashcam.dvr.session

import android.util.Log
import com.dashcam.dvr.util.AppConstants
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * CustodyLog
 *
 * Blueprint §11 / §15 — Append-Only Chain-of-Custody Audit Trail
 * ─────────────────────────────────────────────────────────────────────────────
 * Every open, export, integrity-check, or verification action is appended as a
 * single JSONL line to custody.log inside the session folder.
 *
 * Rules (Blueprint §15):
 *   - Append-only: the file is NEVER truncated or overwritten.
 *   - Included in manifest.json hash at export time.
 *   - Read-only in PC viewer; tampering detectable via hash mismatch.
 *   - DROWSY_CRITICAL events cannot be deleted without a custody.log entry.
 *
 * Entry format (one JSON object per line):
 *   {
 *     "ts":         "2026-03-08T16:00:00.123Z",   // NTP-wall UTC
 *     "action":     "SESSION_OPEN",
 *     "operator":   "DEVICE",                     // or analyst ID from PC viewer
 *     "device_id":  "550e8400",                   // first 8 chars of installation UUID
 *     "session_id": "session_20260308_160000",
 *     "detail":     "start",                      // optional free-text detail
 *     "result":     "OK"
 *   }
 *
 * Defined action types:
 *   SESSION_OPEN       — session directory created, recording started
 *   SESSION_CLOSE      — recording stopped, telemetry flushed
 *   SESSION_EXPORT     — case_export.zip generated
 *   SESSION_VERIFY     — integrity check run (from PC viewer or device)
 *   SESSION_ACCESS     — session folder opened for review
 *   DROWSY_CRITICAL_ACK — DROWSY_CRITICAL event acknowledged; required before deletion
 */
object CustodyLog {

    private const val TAG = "CustodyLog"

    /**
     * Create custody.log and write the first SESSION_OPEN entry.
     * Must be called immediately after session directory is created.
     */
    fun init(sessionDir: File, installationUuid: String, sessionId: String) {
        append(
            sessionDir      = sessionDir,
            installationUuid = installationUuid,
            action          = "SESSION_OPEN",
            sessionId       = sessionId,
            detail          = "recording_started",
            result          = "OK"
        )
        Log.i(TAG, "custody.log initialised for ${sessionDir.name}")
    }

    /**
     * Append an audit entry. Thread-safe — synchronized on the log file reference.
     */
    @Synchronized
    fun append(
        sessionDir:       File,
        installationUuid: String,
        action:           String,
        sessionId:        String,
        detail:           String = "",
        result:           String = "OK"
    ) {
        val logFile = File(sessionDir, AppConstants.CUSTODY_LOG_FILENAME)
        try {
            val entry = JSONObject().apply {
                put("ts",         utcNow())
                put("action",     action)
                put("operator",   "DEVICE")
                put("device_id",  installationUuid.take(8))
                put("session_id", sessionId)
                if (detail.isNotBlank()) put("detail", detail)
                put("result",     result)
            }
            // Open in append mode — never truncate
            FileOutputStream(logFile, /* append= */ true).bufferedWriter().use { w ->
                w.write(entry.toString())
                w.newLine()
            }
        } catch (e: Exception) {
            Log.e(TAG, "custody.log write failed [$action]: ${e.message}")
        }
    }

    private fun utcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}