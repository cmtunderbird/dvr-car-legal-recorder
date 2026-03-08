package com.dashcam.dvr.telemetry

import android.content.Context
import android.util.Log
import com.dashcam.dvr.telemetry.collectors.BarometerCollector
import com.dashcam.dvr.telemetry.collectors.GpsCollector
import com.dashcam.dvr.telemetry.collectors.ImuCollector
import com.dashcam.dvr.telemetry.collectors.MagnetometerCollector
import com.dashcam.dvr.telemetry.model.AccelRecord
import com.dashcam.dvr.telemetry.model.GyroRecord
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
 * ────────────────────────────────────────────
 * Single entry-point for all sensor collection. Co-ordinates:
 *   NTP sync        — once at session start
 *   GPS/GNSS        — 5 Hz, with cold-start flagging
 *   Accelerometer   — 100 Hz
 *   Gyroscope       — 100 Hz
 *   Magnetometer    — 10 Hz
 *   Barometer       — 5 Hz (graceful no-op if absent)
 *
 * Called by RecordingService:
 *   start(sessionDir, onAccelFanOut?, onGyroFanOut?)
 *   stop()
 *
 * State exposed to SessionManager (Module 4) via public properties:
 *   firstValidFixTs   → session.json "first_valid_fix_ts"
 *   ntpSyncStatus     → session.json "clock_status"
 *   ntpOffsetMs       → session.json "ntp_offset_ms"
 *   ntpServerUsed     → session.json "ntp_server"
 *   barometerPresent  → session.json "barometer_available"
 *
 * Module 5 hook:
 *   Pass CollisionDetector callbacks via onAccelFanOut / onGyroFanOut in start().
 *   No changes to this file are needed when Module 5 is added.
 */
class TelemetryEngine(private val context: Context) {

    companion object {
        private const val TAG             = "TelemetryEngine"
        const val TELEMETRY_FILE_NAME     = "telemetry.log"
    }

    val ntpManager = NtpSyncManager()

    private lateinit var writer: TelemetryWriter
    private lateinit var gps:    GpsCollector
    private lateinit var imu:    ImuCollector
    private lateinit var mag:    MagnetometerCollector
    private lateinit var baro:   BarometerCollector

    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    @Volatile private var _running = false
    val isRunning: Boolean get() = _running

    // ── Published state for SessionManager (Module 4) ─────────────────────────
    val firstValidFixTs:  String?  get() = if (::gps.isInitialized)  gps.firstValidFixTs  else null
    val hasValidGpsFix:   Boolean  get() = if (::gps.isInitialized)  gps.hasValidFix       else false
    val ntpSyncStatus:    String   get() = ntpManager.syncStatus
    val ntpOffsetMs:      Long     get() = ntpManager.offsetMs
    val ntpServerUsed:    String   get() = ntpManager.serverUsed
    val barometerPresent: Boolean  get() = if (::baro.isInitialized) baro.isAvailable      else false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start the Telemetry Engine for a new recording session.
     *
     * @param sessionDir     Directory where telemetry.log is created. Must exist.
     * @param onAccelFanOut  Optional extra consumer for AccelRecord (CollisionDetector, Module 5).
     * @param onGyroFanOut   Optional extra consumer for GyroRecord  (CollisionDetector, Module 5).
     */
    fun start(
        sessionDir:    File,
        onAccelFanOut: ((AccelRecord) -> Unit)? = null,
        onGyroFanOut:  ((GyroRecord)  -> Unit)? = null
    ) {
        if (_running) { Log.w(TAG, "Already running — ignoring start()"); return }
        _running = true
        Log.i(TAG, "TelemetryEngine starting — session: ${sessionDir.absolutePath}")

        writer = TelemetryWriter(File(sessionDir, TELEMETRY_FILE_NAME))
        writer.open()

        gps = GpsCollector(context, ntpManager) { writer.write(it) }

        imu = ImuCollector(
            context       = context,
            ntpManager    = ntpManager,
            onAccelRecord = { record ->
                writer.write(record)
                onAccelFanOut?.invoke(record)   // → CollisionDetector (Module 5)
            },
            onGyroRecord  = { record ->
                writer.write(record)
                onGyroFanOut?.invoke(record)    // → CollisionDetector (Module 5)
            }
        )

        mag  = MagnetometerCollector(context, ntpManager) { writer.write(it) }
        baro = BarometerCollector(context, ntpManager)    { writer.write(it) }

        startJob = scope.launch {
            Log.i(TAG, "Step 1 — NTP sync...")
            val ntpRecord = ntpManager.syncAtSessionStart()
            writer.write(ntpRecord)   // First JSONL entry is always the NTP record

            if (!ntpManager.isSynced)
                Log.w(TAG, "⚠ NTP FAILED — SessionManager must set CLOCK_UNVERIFIED in session.json")

            Log.i(TAG, "Step 2 — Starting sensor collectors...")
            launch(Dispatchers.Main) { gps.start() }   // GPS needs a Looper thread
            imu.start()    // Uses its own HandlerThread
            mag.start()
            baro.start()
            Log.i(TAG, "TelemetryEngine fully started")
        }
    }

    /**
     * Stop the Telemetry Engine. Sensors stopped, writer flushed and closed.
     * Call from any thread; cleanup runs on the IO scope.
     */
    fun stop() {
        if (!_running) { Log.w(TAG, "stop() called while not running — ignored"); return }
        _running = false
        startJob?.cancel()
        if (::gps.isInitialized)  gps.stop()
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
