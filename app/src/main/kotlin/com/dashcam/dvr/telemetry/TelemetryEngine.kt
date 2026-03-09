package com.dashcam.dvr.telemetry

import android.content.Context
import android.util.Log
import com.dashcam.dvr.telemetry.collectors.BarometerCollector
import com.dashcam.dvr.telemetry.collectors.GpsCollector
import com.dashcam.dvr.telemetry.collectors.ImuCollector
import com.dashcam.dvr.telemetry.collectors.MagnetometerCollector
import com.dashcam.dvr.telemetry.model.AccelRecord
import com.dashcam.dvr.telemetry.model.GpsRecord
import com.dashcam.dvr.telemetry.model.GyroRecord
import com.dashcam.dvr.telemetry.model.NtpRecord
import com.dashcam.dvr.telemetry.ntp.NtpSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * TelemetryEngine
 *
 * Blueprint §6 — Telemetry Engine (Enhanced)
 * —————————————————————————————————————————————————————————————
 * Orchestrates all sensor writing for a recording session.
 *
 * GPS LIFECYCLE — IMPORTANT:
 *   GpsCollector is owned by RecordingService and runs from onCreate()
 *   to onDestroy() — completely independent of recording start/stop.
 *   TelemetryEngine.start() just HOOKS the write callback onto the
 *   already-running collector. TelemetryEngine.stop() UNHOOKS it —
 *   the GNSS hardware keeps running, fix stays warm.
 *
 * Called by RecordingService:
 *   start(sessionDir, gpsCollector, onAccelFanOut?, onGyroFanOut?)
 *   stop()
 *
 * State exposed to SessionManager (Module 4) via public properties:
 *   firstValidFixTs   -> session.json "first_valid_fix_ts"
 *   ntpSyncStatus     -> session.json "clock_status"
 *   ntpOffsetMs       -> session.json "ntp_offset_ms"
 *   ntpServerUsed     -> session.json "ntp_server"
 *   barometerPresent  -> session.json "barometer_available"
 */
class TelemetryEngine(private val context: Context) {

    companion object {
        private const val TAG             = "TelemetryEngine"
        const val TELEMETRY_FILE_NAME     = "telemetry.log"
    }

    val ntpManager = NtpSyncManager()

    private lateinit var writer:     TelemetryWriter
    private lateinit var activeGps:  GpsCollector     // reference to service-owned GPS
    private lateinit var imu:        ImuCollector
    private lateinit var mag:        MagnetometerCollector
    private lateinit var baro:       BarometerCollector

    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    @Volatile private var _running = false
    val isRunning: Boolean get() = _running

    // —— Published state for SessionManager (Module 4) ——————————————————————
    val firstValidFixTs:  String?  get() = if (::activeGps.isInitialized) activeGps.firstValidFixTs else null
    val hasValidGpsFix:   Boolean  get() = if (::activeGps.isInitialized) activeGps.hasValidFix      else false
    val ntpSyncStatus:    String   get() = ntpManager.syncStatus
    val ntpOffsetMs:      Long     get() = ntpManager.offsetMs
    val ntpServerUsed:    String   get() = ntpManager.serverUsed
    val barometerPresent: Boolean  get() = if (::baro.isInitialized) baro.isAvailable else false

    // —— Lifecycle ———————————————————————————————————————————————————————————

    /**
     * Start the Telemetry Engine for a new recording session.
     *
     * @param sessionDir     Directory where telemetry.log is created. Must exist.
     * @param gpsCollector   Already-running GpsCollector owned by RecordingService.
     *                       This engine just hooks its write callback — GPS hardware
     *                       is NOT started or stopped here.
     * @param onAccelFanOut  Optional extra consumer for AccelRecord (CollisionDetector, Module 5).
     * @param onGyroFanOut   Optional extra consumer for GyroRecord  (CollisionDetector, Module 5).
     */
    fun start(
        sessionDir:    File,
        gpsCollector:  GpsCollector,
        onNtpSynced:   ((NtpRecord)   -> Unit)? = null,   // -> SessionManager.patchNtpAndSensors()
        onAccelFanOut: ((AccelRecord) -> Unit)? = null,
        onGyroFanOut:  ((GyroRecord)  -> Unit)? = null,
        onGpsFanOut:   ((GpsRecord)   -> Unit)? = null
    ) {
        if (_running) { Log.w(TAG, "Already running — ignoring start()"); return }
        _running  = true
        activeGps = gpsCollector
        Log.i(TAG, "TelemetryEngine starting — session: ${sessionDir.absolutePath}")

        writer = TelemetryWriter(File(sessionDir, TELEMETRY_FILE_NAME))
        writer.open()

        // Hook GPS write callback — GNSS hardware is already running
        activeGps.writeCallback = { writer.write(it) }
        // Module 5: secondary GPS fan-out for ManeuverContext (does not write)
        activeGps.analysisCallback = onGpsFanOut

        imu = ImuCollector(
            context       = context,
            ntpManager    = ntpManager,
            onAccelRecord = { record ->
                writer.write(record)
                onAccelFanOut?.invoke(record)   // -> CollisionDetector (Module 5)
            },
            onGyroRecord  = { record ->
                writer.write(record)
                onGyroFanOut?.invoke(record)    // -> CollisionDetector (Module 5)
            }
        )

        mag  = MagnetometerCollector(context, ntpManager) { writer.write(it) }
        baro = BarometerCollector(context, ntpManager)    { writer.write(it) }

        startJob = scope.launch {
            Log.i(TAG, "Step 1 — NTP sync...")
            val ntpRecord = ntpManager.syncAtSessionStart()
            writer.write(ntpRecord)   // First JSONL entry is always the NTP record
            onNtpSynced?.invoke(ntpRecord)   // Notify SessionManager to patch session.json

            if (!ntpManager.isSynced)
                Log.w(TAG, "⚠ NTP FAILED — SessionManager must set CLOCK_UNVERIFIED in session.json")

            Log.i(TAG, "Step 2 — Starting IMU / Mag / Baro collectors...")
            imu.start()    // Uses its own HandlerThread
            mag.start()
            baro.start()
            Log.i(TAG, "TelemetryEngine fully started — GPS already warm")
        }
    }

    /**
     * Stop the Telemetry Engine. IMU/Mag/Baro stopped, writer flushed and closed.
     * GPS write callback is CLEARED but the GNSS hardware keeps running.
     * Call from any thread; cleanup runs on the IO scope.
     */
    /**
     * Write any telemetry record from Module 5 detectors to the current session writer.
     * Safe to call at 100 Hz; no-op if engine not running.
     */
    fun writeTelemetry(record: Any) {
        if (_running && ::writer.isInitialized) writer.write(record)
    }

    fun stop() {
        if (!_running) { Log.w(TAG, "stop() called while not running — ignored"); return }
        _running = false
        startJob?.cancel()

        // Unhook GPS writer — GNSS hardware stays alive in RecordingService
        if (::activeGps.isInitialized) {
            activeGps.writeCallback = null
            // FIX C: Do NOT null analysisCallback here.  RecordingService owns
            // the GPS analysis lifecycle — it composes _gpsData + ManeuverContext
            // in onGpsFanOut and restores the plain _gpsData callback after stop.
            // Nulling it here killed GPS data for the UI between sessions.
            Log.i(TAG, "GPS write callback cleared — GNSS hardware keeps running")
        }

        if (::imu.isInitialized)  imu.stop()
        if (::mag.isInitialized)  mag.stop()
        if (::baro.isInitialized) baro.stop()

        if (::writer.isInitialized) {
            scope.launch {
                writer.close()
                Log.i(TAG, "TelemetryEngine stopped — writer closed")
            }
        }
    }
}
