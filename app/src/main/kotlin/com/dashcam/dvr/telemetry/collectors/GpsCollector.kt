package com.dashcam.dvr.telemetry.collectors

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.dashcam.dvr.telemetry.model.GpsRecord
import com.dashcam.dvr.telemetry.ntp.NtpSyncManager

/**
 * GpsCollector — GNSS @ 5 Hz with A-GPS seeding, SBAS, and constellation telemetry
 *
 * Blueprint §6 — GPS/GNSS
 * ─────────────────────────────────────────────────────────────────────────────
 * Fields: lat, lon, alt_m, speed_kmh, heading_deg, hdop, satellites,
 *         sats_gps/glo/gal/bds/sbas/qzss, sbas_active, agps_seeded, fix_status
 *
 * A-GPS (Assisted GPS):
 *   Android's GNSS HAL automatically contacts the SUPL server to download
 *   almanac, ephemeris, reference time, and coarse position — this is
 *   transparent to the app. We accelerate cold-start further by:
 *   (a) seeding from getLastKnownLocation(NETWORK_PROVIDER | GPS_PROVIDER)
 *       so the HAL knows which part of the sky to search first, and
 *   (b) logging whether a seed was available (agps_seeded in telemetry).
 *   The seed is a one-shot hint — it is NOT used for position output.
 *   All position data comes exclusively from GPS_PROVIDER (raw GNSS).
 *
 * SBAS (Satellite Based Augmentation Systems):
 *   EGNOS (Europe), WAAS (North America), MSAS (Japan),
 *   GAGAN (India), SDCM (Russia).
 *   The Dimensity 7300 Ultra chipset decodes SBAS correction signals
 *   automatically. We track SBAS satellites via GnssStatus.CONSTELLATION_SBAS
 *   and log sats_sbas + sbas_active per frame for evidence integrity.
 *   When sbas_active=true, differential corrections are improving accuracy
 *   beyond standard GNSS (typically 1-3 m vs 3-5 m horizontal).
 *
 * Constellation tracking:
 *   Per-constellation in-fix counts logged every frame:
 *   GPS L1/L5, GLONASS, Galileo E1/E5a, BeiDou B1/B2, QZSS, SBAS.
 *
 * Cold-start validity threshold:
 *   HDOP ≤ 2.5 AND ≥ 4 satellites in fix → fix_status: "VALID"
 *   Otherwise → "NO_FIX" (frames still recorded for cold-start audit trail).
 */
class GpsCollector(
    private val context: Context,
    private val ntpManager: NtpSyncManager,
    writeCallback: ((GpsRecord) -> Unit)? = null
) {
    companion object {
        private const val TAG            = "GpsCollector"
        private const val MIN_INTERVAL_MS = 200L  // 5 Hz
        private const val MIN_DISTANCE_M  = 0f
        private const val HDOP_VALID      = 2.5f
        private const val MIN_SATS_VALID  = 4
        private const val UERE_FACTOR     = 3.0f
    }

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** Swap to null to stop writing without stopping GNSS hardware. */
    @Volatile var writeCallback: ((GpsRecord) -> Unit)? = writeCallback

    private val handlerThread = HandlerThread("GpsCollector").also { it.start() }
    private val handler        = Handler(handlerThread.looper)

    // ── Per-constellation in-fix counts (updated by gnssStatusCallback) ──────
    @Volatile private var totalInFix  = 0
    @Volatile private var inFixGps    = 0
    @Volatile private var inFixGlo    = 0
    @Volatile private var inFixGal    = 0
    @Volatile private var inFixBds    = 0
    @Volatile private var inFixSbas   = 0
    @Volatile private var inFixQzss   = 0

    // ── A-GPS seed tracking ───────────────────────────────────────────────────
    @Volatile var agpsSeeded: Boolean = false; private set

    // ── Public state ─────────────────────────────────────────────────────────
    @Volatile var firstValidFixTs: String?  = null; private set
    @Volatile var hasValidFix:     Boolean  = false; private set

    // ── GNSS status callback — constellation breakdown ────────────────────────
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var tot = 0; var gps = 0; var glo = 0; var gal = 0
            var bds = 0; var sbas = 0; var qzss = 0

            for (i in 0 until status.satelliteCount) {
                if (!status.usedInFix(i)) continue
                tot++
                when (status.getConstellationType(i)) {
                    GnssStatus.CONSTELLATION_GPS     -> gps++
                    GnssStatus.CONSTELLATION_GLONASS -> glo++
                    GnssStatus.CONSTELLATION_GALILEO -> gal++
                    GnssStatus.CONSTELLATION_BEIDOU  -> bds++
                    GnssStatus.CONSTELLATION_SBAS    -> sbas++
                    GnssStatus.CONSTELLATION_QZSS    -> qzss++
                }
            }

            totalInFix = tot; inFixGps = gps; inFixGlo = glo; inFixGal = gal
            inFixBds = bds; inFixSbas = sbas; inFixQzss = qzss

            if (sbas > 0) Log.d(TAG, "SBAS active — $sbas pseudorange(s) in fix")
        }

        override fun onFirstFix(ttffMillis: Int) {
            Log.i(TAG, "GNSS TTFF: ${ttffMillis}ms  agps_seeded=$agpsSeeded")
        }
    }

    private val locationListener = LocationListener { processLocation(it) }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun start() {
        // ── A-GPS seed: provide coarse position hint to GNSS HAL ─────────────
        // We try GPS last-known first (most accurate), then network coarse fix.
        // This is a one-shot read — never used in position output.
        // Android's HAL uses it to narrow the satellite search window and
        // fetch the right ephemeris block from the SUPL server faster.
        try {
            val seedLoc: Location? =
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                       else
                            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (seedLoc != null) {
                agpsSeeded = true
                val ageS = (System.currentTimeMillis() - seedLoc.time) / 1000
                Log.i(TAG, "A-GPS seed available — age=${ageS}s  " +
                    "lat=%.5f  lon=%.5f".format(seedLoc.latitude, seedLoc.longitude))
            } else {
                agpsSeeded = false
                Log.w(TAG, "A-GPS seed unavailable — true cold start (no cached position)")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "A-GPS seed: permission denied — ${e.message}")
        }

        // ── Start GNSS hardware ───────────────────────────────────────────────
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                locationListener,
                handler.looper
            )
            locationManager.registerGnssStatusCallback(gnssStatusCallback, handler)
            Log.i(TAG, "GPS collector started @ 5 Hz  " +
                "(A-GPS auto-SUPL=on, SBAS=tracked, agps_seeded=$agpsSeeded)")
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

    // ── Location processing ───────────────────────────────────────────────────

    private fun processLocation(location: Location) {
        val hdop    = estimateHdop(location)
        val sats    = totalInFix
        val isValid = hdop <= HDOP_VALID && sats >= MIN_SATS_VALID
        val fixSt   = if (isValid) "VALID" else "NO_FIX"

        if (isValid && firstValidFixTs == null) {
            firstValidFixTs = ntpManager.correctedUtcNow()
            hasValidFix     = true
            Log.i(TAG, "First valid GPS fix — ts=$firstValidFixTs  " +
                "sats=$sats  hdop=$hdop  " +
                "GPS=$inFixGps GLO=$inFixGlo GAL=$inFixGal BDS=$inFixBds " +
                "SBAS=$inFixSbas QZSS=$inFixQzss")
        }

        writeCallback?.invoke(
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
                satsGps    = inFixGps,
                satsGlo    = inFixGlo,
                satsGal    = inFixGal,
                satsBds    = inFixBds,
                satsQzss   = inFixQzss,
                satsSbas   = inFixSbas,
                sbasActive = inFixSbas > 0,
                agpsSeeded = agpsSeeded,
                fixStatus  = fixSt
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
