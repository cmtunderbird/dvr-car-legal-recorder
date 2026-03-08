package com.dashcam.dvr.telemetry.collectors

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.dashcam.dvr.telemetry.model.GpsRecord
import com.dashcam.dvr.telemetry.ntp.NtpSyncManager

/**
 * GpsCollector
 *
 * Blueprint §6 — GPS/GNSS @ 5 Hz
 * ─────────────────────────────────
 * Fields: lat, lon, alt_m, speed_kmh, heading_deg, hdop, satellites, fix_status
 *
 * Cold-Start Handling (Enhanced):
 *   Frames before a valid fix are tagged fix_status: "NO_FIX".
 *   Valid fix = HDOP ≤ 2.5 AND ≥ 4 satellites used-in-fix.
 *   [firstValidFixTs] is written to session.json as first_valid_fix_ts (Module 4).
 *
 * HDOP estimation:
 *   Reads Location.extras["hdop"] first (available on Dimensity 7300 Ultra / most
 *   Qualcomm & MediaTek chipsets). Falls back to: HDOP ≈ accuracy(m) / 3.0.
 */
class GpsCollector(
    private val context: Context,
    private val ntpManager: NtpSyncManager,
    private val onRecord: (GpsRecord) -> Unit
) {
    companion object {
        private const val TAG             = "GpsCollector"
        private const val MIN_INTERVAL_MS = 200L   // 5 Hz
        private const val MIN_DISTANCE_M  = 0f
        private const val HDOP_VALID      = 2.5f
        private const val MIN_SATS_VALID  = 4
        private const val UERE_FACTOR     = 3.0f
    }

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val handlerThread = HandlerThread("GpsCollector").also { it.start() }
    private val handler        = Handler(handlerThread.looper)

    @Volatile private var satellitesInFix = 0

    @Volatile var firstValidFixTs: String?  = null; private set
    @Volatile var hasValidFix:     Boolean  = false; private set

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellitesInFix = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
        override fun onFirstFix(ttffMillis: Int) {
            Log.i(TAG, "GNSS TTFF: ${ttffMillis}ms")
        }
    }

    private val locationListener = LocationListener { processLocation(it) }

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                locationListener,
                handler.looper
            )
            locationManager.registerGnssStatusCallback(gnssStatusCallback, handler)
            Log.i(TAG, "GPS collector started @ 5 Hz")
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS permission missing: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "GPS start failed: ${e.message}")
        }
    }

    fun stop() {
        try {
            locationManager.removeUpdates(locationListener)
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        } finally {
            handlerThread.quitSafely()
        }
        Log.i(TAG, "GPS collector stopped")
    }

    private fun processLocation(location: Location) {
        val hdop      = estimateHdop(location)
        val sats      = satellitesInFix
        val isValid   = hdop <= HDOP_VALID && sats >= MIN_SATS_VALID
        val fixStatus = if (isValid) "VALID" else "NO_FIX"

        if (isValid && firstValidFixTs == null) {
            firstValidFixTs = ntpManager.correctedUtcNow()
            hasValidFix     = true
            Log.i(TAG, "First valid GPS fix — ts=$firstValidFixTs sats=$sats hdop=$hdop")
        }

        onRecord(
            GpsRecord(
                tsMonoNs   = SystemClock.elapsedRealtimeNanos(),
                tsUtc      = ntpManager.correctedUtcNow(),
                lat        = location.latitude,
                lon        = location.longitude,
                altM       = location.altitude,
                speedKmh   = location.speed * 3.6f,
                headingDeg = if (location.hasBearing()) location.bearing else -1f,
                hdop       = hdop,
                satellites = sats,
                fixStatus  = fixStatus
            )
        )
    }

    private fun estimateHdop(location: Location): Float {
        val chipsetHdop = location.extras?.getFloat("hdop", -1f) ?: -1f
        return if (chipsetHdop > 0f) chipsetHdop
        else if (location.hasAccuracy()) location.accuracy / UERE_FACTOR
        else 99f
    }
}
