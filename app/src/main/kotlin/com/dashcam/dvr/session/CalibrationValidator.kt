package com.dashcam.dvr.session

import android.content.Context
import android.util.Log
import com.dashcam.dvr.session.model.CalibrationData
import com.dashcam.dvr.session.model.CalibrationResult
import com.dashcam.dvr.util.AppConstants
import com.google.gson.Gson
import java.io.File
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * CalibrationValidator
 *
 * Blueprint §10 — Calibration Procedure
 * ─────────────────────────────────────────────────────────────────────────────
 * Manages reading and validating calibration.json — the app-level (not per-session)
 * file that stores the IMU calibration parameters from the last calibration run.
 *
 * calibration.json location:  context.filesDir / calibration.json
 *   (Internal storage — survives reboots, cleared on app uninstall/data wipe)
 *
 * What it validates:
 *   1. File exists and is parseable → calibration_valid = false if absent.
 *   2. Gravity vector deviation vs a supplied live reading:
 *      If the stored gravity vector deviates > 5° from the current IMU reading,
 *      the session is flagged and the user is prompted to re-calibrate (Module 8 UI).
 *
 * Note on live-reading deviation:
 *   A true deviation check requires a live accelerometer sample at rest.
 *   In Module 4 the IMU isn't running yet when startSession() is called (it starts
 *   inside TelemetryEngine.start()). The deviation check with a live reading will be
 *   triggered by the Module 8 setup wizard and Module 10 (DDDS pre-check).
 *   For now, this class reads and returns the stored calibration data; the deviation
 *   field in session.json is null until a live check is performed.
 */
object CalibrationValidator {

    private const val TAG = "CalibrationValidator"
    private val gson = Gson()

    /**
     * Read calibration.json from app-level internal storage.
     * Returns a [CalibrationResult] indicating validity and stored parameters.
     */
    fun read(context: Context): CalibrationResult {
        val file = calibrationFile(context)

        if (!file.exists()) {
            Log.w(TAG, "calibration.json not found — session is UNCALIBRATED")
            return CalibrationResult(
                valid        = false,
                deviationDeg = null,
                data         = null,
                reason       = "NO_CALIBRATION_FILE"
            )
        }

        return try {
            val data = gson.fromJson(file.readText(), CalibrationData::class.java)

            // Sanity-check: gravity vector must be non-zero
            val magnitude = vectorMagnitude(data.gravityX, data.gravityY, data.gravityZ)
            if (magnitude < 8.0f || magnitude > 11.5f) {
                Log.w(TAG, "Calibration gravity magnitude out of range: ${magnitude}m/s² — treat as INVALID")
                return CalibrationResult(
                    valid        = false,
                    deviationDeg = null,
                    data         = data,
                    reason       = "GRAVITY_MAGNITUDE_OUT_OF_RANGE"
                )
            }

            Log.i(TAG, "Calibration loaded: gravity=[${data.gravityX},${data.gravityY},${data.gravityZ}]  ts=${data.calibratedTs}")
            CalibrationResult(valid = true, deviationDeg = null, data = data, reason = "OK")

        } catch (e: Exception) {
            Log.e(TAG, "calibration.json parse failed: ${e.message}")
            CalibrationResult(valid = false, deviationDeg = null, data = null, reason = "PARSE_ERROR")
        }
    }

    /**
     * Check deviation between stored gravity vector and a live accelerometer reading.
     * Returns the angle in degrees between the two vectors (0 = no change, >5° = re-calibrate).
     * Blueprint §10: re-calibration prompted if deviation > 5°.
     */
    fun deviationDeg(stored: CalibrationData, liveX: Float, liveY: Float, liveZ: Float): Float {
        val dot = stored.gravityX * liveX + stored.gravityY * liveY + stored.gravityZ * liveZ
        val magStored = vectorMagnitude(stored.gravityX, stored.gravityY, stored.gravityZ)
        val magLive   = vectorMagnitude(liveX, liveY, liveZ)
        if (magStored < 0.001f || magLive < 0.001f) return 0f
        val cosAngle = (dot / (magStored * magLive)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosAngle.toDouble()).toFloat().toDouble()).toFloat()
    }

    /**
     * Write new calibration data to calibration.json.
     * Called by the calibration UI (Module 8) after a successful calibration run.
     */
    fun save(context: Context, data: CalibrationData) {
        try {
            calibrationFile(context).writeText(gson.toJson(data))
            Log.i(TAG, "Calibration saved: gravity=[${data.gravityX},${data.gravityY},${data.gravityZ}]")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save calibration: ${e.message}")
        }
    }

    fun calibrationFile(context: Context): File =
        File(context.filesDir, AppConstants.CALIBRATION_FILENAME)

    private fun vectorMagnitude(x: Float, y: Float, z: Float): Float =
        sqrt((x * x + y * y + z * z).toDouble()).toFloat()
}