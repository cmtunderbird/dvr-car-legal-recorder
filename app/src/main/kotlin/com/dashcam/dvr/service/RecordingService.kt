package com.dashcam.dvr.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dashcam.dvr.DVRApplication.Companion.CHANNEL_RECORDING
import com.dashcam.dvr.R
import com.dashcam.dvr.telemetry.TelemetryEngine
import com.dashcam.dvr.session.SessionManager
import com.dashcam.dvr.telemetry.collectors.GpsCollector
import com.dashcam.dvr.ui.MainActivity
import com.dashcam.dvr.util.AppConstants
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.dashcam.dvr.collision.CollisionDetector
import com.dashcam.dvr.collision.EventsLog
import com.dashcam.dvr.collision.GravityProvider
import com.dashcam.dvr.collision.ManeuverContext
import com.dashcam.dvr.collision.RoadQualityMonitor
import com.dashcam.dvr.collision.model.ImpactEvent
import com.dashcam.dvr.telemetry.model.CollisionRecord
import com.dashcam.dvr.telemetry.model.RoadQualityRecord
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * RecordingService — Foreground Service
 *
 * Blueprint §14: Process Survival
 * ────────────────────────────────
 * Module 1:  Foreground Service skeleton — WakeLock, notifications, lifecycle
 * Module 3:  TelemetryEngine wired in — GPS + IMU + NTP started/stopped in sync
 * Module 4:  SessionManager — session.json, custody.log, storage health, calibration
 * Module 5:  CollisionDetector fan-out hooks pre-plumbed via TelemetryEngine.start()
 *
 * NOTE — foregroundServiceType = "location" only (not camera/microphone).
 * Adding camera/microphone types triggers HyperOS full-screen privacy overlay
 * which steals window focus and minimises the app. Camera sessions are owned by
 * DVRCameraManager in MainActivity (CameraX lifecycle). Location is legitimate
 * for continuous GPS telemetry. See AndroidManifest.xml foregroundServiceType declaration.
 */
class RecordingService : LifecycleService() {

    // ── Binder ─────────────────────────────────────────────────────────────
    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }
    private val binder = RecordingBinder()

    // ── State ──────────────────────────────────────────────────────────────
    sealed class ServiceState {
        object Idle      : ServiceState()
        object Starting  : ServiceState()
        object Recording : ServiceState()
        object Stopping  : ServiceState()
        data class Error(val message: String) : ServiceState()
    }

    private val _state          = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val state: StateFlow<ServiceState> = _state

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds

    // GPS fix state — observed by MainActivity to update tvGpsStatus
    private val _hasGpsFix = MutableStateFlow(false)
    val hasGpsFix: StateFlow<Boolean> = _hasGpsFix


    // ── Module 3 ───────────────────────────────────────────────────────────
    private lateinit var telemetryEngine: TelemetryEngine
    private lateinit var sessionManager:  SessionManager

    // GPS collector — always-on, lifecycle: onCreate() → onDestroy()
    // Independent of recording. TelemetryEngine just hooks/unhooks the write callback.
    private lateinit var gpsCollector: GpsCollector

    // ── WakeLock ───────────────────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Jobs ───────────────────────────────────────────────────────────────
    private var timerJob:        Job? = null
    private var statusUpdateJob: Job? = null
    private var gpsMonitorJob:    Job? = null

        // Current session dir - set by handleStartRecording(), cleared on stop
        // ── Module 5: Collision + Road Quality ───────────────────────────────────
    private val _collisionEvents = MutableSharedFlow<ImpactEvent>(extraBufferCapacity = 8)
    val collisionEvents = _collisionEvents.asSharedFlow()
    private lateinit var gravityProvider:   GravityProvider
    private lateinit var maneuverContext:   ManeuverContext
    private lateinit var roadMonitor:       RoadQualityMonitor
    private lateinit var collisionDetector: CollisionDetector
    private var eventsLog: EventsLog? = null
    private var currentSessionDir: File? = null

    // ── Companion ──────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "RecordingService"

        const val ACTION_START_RECORDING = "com.dashcam.dvr.START_RECORDING"
        const val ACTION_STOP_RECORDING  = "com.dashcam.dvr.STOP_RECORDING"
        const val ACTION_TRIGGER_EVENT   = "com.dashcam.dvr.TRIGGER_EVENT"

        const val EXTRA_SESSION_DIR          = "com.dashcam.dvr.EXTRA_SESSION_DIR"
        private const val NOTIFICATION_ID  = 1001
        private const val STATUS_UPDATE_MS = 2_000L
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        telemetryEngine = TelemetryEngine(applicationContext)
        sessionManager  = SessionManager(applicationContext)
        gpsCollector = GpsCollector(applicationContext, telemetryEngine.ntpManager)
        gpsCollector.start()   // GNSS warm-up — always on regardless of recording
        startGpsMonitorLoop()   // keeps _hasGpsFix live at all times
        Log.i(TAG, "RecordingService created")
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_RECORDING -> { handleStartRecording(intent); return START_NOT_STICKY }
            ACTION_STOP_RECORDING  -> handleStopRecording()
            ACTION_TRIGGER_EVENT   -> handleManualEvent()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped from recents — reset state so next launch starts clean.
        Log.i(TAG, "onTaskRemoved — resetting state to Idle")
        timerJob?.cancel(); timerJob = null
        statusUpdateJob?.cancel(); statusUpdateJob = null
        if (telemetryEngine.isRunning) telemetryEngine.stop()
        releaseWakeLock()
        _state.value          = ServiceState.Idle
        _elapsedSeconds.value = 0L
        // _hasGpsFix driven by gpsMonitorLoop — not reset on idle (GPS stays warm)
        updateNotification("DVR Ready")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (telemetryEngine.isRunning) {
            Log.w(TAG, "Service destroyed while recording — emergency telemetry stop")
            telemetryEngine.stop()
        }
        releaseWakeLock()
        gpsMonitorJob?.cancel(); gpsMonitorJob = null
        if (::gpsCollector.isInitialized) gpsCollector.stop()
        Log.i(TAG, "RecordingService destroyed")
    }

    // ── Instance methods called by MainActivity (via bound service) ────────

        /** Pre-allocate the session dir for cameras, then pass path to startRecording(). */
    fun prepareSessionDir(): File = sessionManager.prepareSessionDir()

    /** Called by MainActivity when user taps Record. Pass the pre-allocated dir path. */
    fun startRecording(sessionDirPath: String) {
        startService(Intent(this, RecordingService::class.java)
            .setAction(ACTION_START_RECORDING)
            .putExtra(EXTRA_SESSION_DIR, sessionDirPath))
    }

    /** Called by MainActivity when user taps Stop. */
    fun stopRecording() {
        startService(Intent(this, RecordingService::class.java)
            .setAction(ACTION_STOP_RECORDING))
    }

    /** Called by MainActivity when user taps Event button. */
    fun triggerEvent() {
        startService(Intent(this, RecordingService::class.java)
            .setAction(ACTION_TRIGGER_EVENT))
    }

    /**
     * Reset orphaned state to Idle. Called by MainActivity.onServiceConnected
     * when it detects the service survived an app kill in Recording/Starting state
     * but the Activity has no camera session (HyperOS keeps services alive).
     */
    fun resetToIdle() {
        Log.i(TAG, "resetToIdle — cancelling timers, stopping telemetry")
        timerJob?.cancel(); timerJob = null
        statusUpdateJob?.cancel(); statusUpdateJob = null
        if (telemetryEngine.isRunning) telemetryEngine.stop()
        releaseWakeLock()
        _state.value          = ServiceState.Idle
        _elapsedSeconds.value = 0L
        // _hasGpsFix driven by gpsMonitorLoop — not reset on idle (GPS stays warm)
        updateNotification("DVR Ready")
    }

    // ── Internal start/stop handlers ───────────────────────────────────────

    private fun handleStartRecording(intent: Intent) {
        if (_state.value is ServiceState.Recording) {
            Log.w(TAG, "Already recording — ignoring start"); return
        }
        _state.value = ServiceState.Starting
        startForegroundNotification()
        acquireWakeLock()


        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // ── Module 4: SessionManager opens the evidence session ─────────────────
                // SessionManager is now the single authority for session directory creation.
                // It runs storage health check, reads calibration.json, writes preliminary
                // session.json, and initialises custody.log — all before recording begins.
                // Use the pre-allocated dir from prepareSessionDir() if provided (normal start),
                // or create a new one on OS-restart recovery (no intent extra).
                val preallocatedDir = intent.getStringExtra(EXTRA_SESSION_DIR)
                    ?.let { java.io.File(it) }
                val sessionResult = sessionManager.startSession(
                    gpsCollector    = gpsCollector,
                    telemetryEngine = telemetryEngine,
                    existingDir     = preallocatedDir   // null = OS restart, new dir created
                )
                currentSessionDir = sessionResult.sessionDir
                val sessionDir = currentSessionDir!!
                // sessionDir is non-null from this point — captured as val

                // Module 5: open EventsLog + hook detector write callbacks
                eventsLog = EventsLog(sessionDir)
                roadMonitor.writeCallback       = { rec -> telemetryEngine.writeTelemetry(rec) }
                collisionDetector.writeCallback = { rec -> telemetryEngine.writeTelemetry(rec) }

                // ── Module 3: TelemetryEngine wires GPS write callback + starts IMU/Mag/Baro
                // onNtpSynced fires ~2s later after NTP sync; SessionManager patches session.json
                telemetryEngine.start(
                    sessionDir    = sessionDir,
                    gpsCollector  = gpsCollector,
                    onNtpSynced   = { ntpRecord: com.dashcam.dvr.telemetry.model.NtpRecord ->
                        sessionManager.patchNtpAndSensors(
                            sessionDir,
                            ntpRecord,
                            telemetryEngine.barometerPresent
                        )
                        // If GPS fix arrived while NTP was syncing, capture it now
                        gpsCollector.firstValidFixTs?.let { fixTs ->
                            sessionManager.patchFirstGpsFix(sessionDir, fixTs)
                        }
                    },
                    onAccelFanOut = { rec ->
                        roadMonitor.onAccelSample(rec)
                        collisionDetector.onAccelSample(rec)
                    },
                    onGyroFanOut  = { rec ->
                        collisionDetector.onGyroSample(rec)
                        maneuverContext.onGyroSample(rec)
                    },
                    onGpsFanOut   = { rec ->
                        maneuverContext.onGpsRecord(rec)
                    }   //  ManeuverContext GPS+gyro fusion (Module 5)
                )

                // ── Module 2 placeholder ─────────────────────────────────────────────────
                // cameraManager.start(sessionDir)   wired in Module 2

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _state.value = ServiceState.Recording
                    startTimerLoop()
                    startStatusUpdateLoop()
                }
                Log.i(TAG, "Recording started — session: ${sessionDir.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording: ${e.message}")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _state.value = ServiceState.Error(e.message ?: "Unknown error")
                }
                telemetryEngine.stop()
            gravityProvider.stop()                     // unregister TYPE_GRAVITY sensor
                            // Module 5: detach write callbacks (detectors stay alive, just stop writing)
            roadMonitor.writeCallback       = null
            collisionDetector.writeCallback = null
            roadMonitor.reset()
            collisionDetector.reset()
            maneuverContext.reset()
            eventsLog = null
            // Module 4: close session record on startup failure
                currentSessionDir?.let { sessionManager.closeSession(it, "CRASH") }
                releaseWakeLock()
                stopSelf()
            }
        }
    }

    private fun handleStopRecording() {
        if (_state.value !is ServiceState.Recording) {
            Log.w(TAG, "Not recording — ignoring stop"); return
        }
        _state.value = ServiceState.Stopping
        timerJob?.cancel(); timerJob = null
        statusUpdateJob?.cancel(); statusUpdateJob = null


        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // ── Module 2 placeholder ──────────────────────────────────────────────────
            // cameraManager.stop()    wired in Module 2

            // ── Module 3: flush and close telemetry.log ───────────────────────────────
            telemetryEngine.stop()


            gravityProvider.stop()                     // unregister TYPE_GRAVITY sensor
            // Module 5: detach write callbacks and reset detectors
            roadMonitor.writeCallback       = null
            collisionDetector.writeCallback = null
            roadMonitor.reset()
            collisionDetector.reset()
            maneuverContext.reset()
            eventsLog = null
            // ── Module 4: close session — write end_ts, final custody.log entry ───────
            currentSessionDir?.let { dir ->
                sessionManager.closeSession(dir, "USER_STOP")
            }

            // ── Module 6 placeholder ──────────────────────────────────────────────────
            // evidencePackager.seal(currentSessionDir)   wired in Module 6

            if (telemetryEngine.ntpSyncStatus != "SYNCED")
                Log.w(TAG, "Session CLOCK_UNVERIFIED — ${telemetryEngine.ntpSyncStatus}")

            Log.i(TAG, "Session closed — " +
                "session=${currentSessionDir?.name ?: "?"}  " +
                "first_fix=${gpsCollector.firstValidFixTs ?: "NONE"}  " +
                "ntp=${telemetryEngine.ntpSyncStatus}  " +
                "offset=${telemetryEngine.ntpOffsetMs}ms"
            )

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                _elapsedSeconds.value = 0L
                releaseWakeLock()
                _state.value = ServiceState.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleManualEvent() {
        Log.i(TAG, "Manual event triggered — segment protection queued (Module 7)")
        // Module 7 (LoopRecorder) will protect current segment here
    }

    // ── createSessionDirStub() removed — Module 4 SessionManager owns session directory creation ──

    // ── WakeLock ───────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DVR:RecordingWakeLock")
            .also { it.acquire(AppConstants.MAX_SESSION_WAKELOCK_MS) }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ── Loops ──────────────────────────────────────────────────────────────

    private fun startTimerLoop() {
        timerJob = lifecycleScope.launch {
            while (true) {
                delay(1_000L)
                _elapsedSeconds.value++
            }
        }
    }

    // ── GPS monitor — always-on from onCreate() to onDestroy() ─────────────
    // Polls gpsCollector.hasValidFix every second and pushes into _hasGpsFix.
    // Completely independent of recording state — the UI label reflects the
    // live GNSS fix status at all times, not just during a recording session.
    private fun startGpsMonitorLoop() {
        gpsMonitorJob?.cancel()
                // Module 5: create detectors - GravityProvider is shared source of truth
                // (Fix 1+3: replaces two independent wrong-sign gravity EMAs)
        gravityProvider   = GravityProvider(this)
        gravityProvider.start()                        // registers TYPE_GRAVITY sensor
        maneuverContext   = ManeuverContext()
        roadMonitor       = RoadQualityMonitor(
            ntpManager      = telemetryEngine.ntpManager,
            gravityProvider = gravityProvider
        )
        collisionDetector = CollisionDetector(
            ntpManager      = telemetryEngine.ntpManager,
            roadMonitor     = roadMonitor,
            maneuverContext = maneuverContext,
            gravityProvider = gravityProvider,
            onEvent         = { event -> handleImpactEvent(event) }
        )
        gpsMonitorJob = lifecycleScope.launch {
            while (true) {
                _hasGpsFix.value = gpsCollector.hasValidFix
                delay(1_000L)
            }
        }
    }

    private fun startStatusUpdateLoop() {
        statusUpdateJob = lifecycleScope.launch {
            while (true) {
                delay(STATUS_UPDATE_MS)
                val hasfix = gpsCollector.hasValidFix
                val gps = if (hasfix) "GPS \u2705" else "GPS acquiring\u2026"
                val ntp = if (telemetryEngine.ntpSyncStatus == "SYNCED") "NTP \u2705" else "NTP \u274c"
                updateNotification("Recording  $gps  $ntp")
            }
        }
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun startForegroundNotification() =
        startForeground(NOTIFICATION_ID, buildNotification("Starting recording…"))

    private fun updateNotification(text: String) =
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))

    private fun buildNotification(contentText: String) =
        NotificationCompat.Builder(this, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_dashcam_notification)
            .setContentTitle("DVR Recording")
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openMainActivityIntent())
            .addAction(0, "Stop",  stopPendingIntent())
            .addAction(0, "Event", triggerEventPendingIntent())
            .build()

    // ── PendingIntents ─────────────────────────────────────────────────────

    private fun openMainActivityIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopPendingIntent() = PendingIntent.getService(
        this, 1,
        Intent(this, RecordingService::class.java).setAction(ACTION_STOP_RECORDING),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun triggerEventPendingIntent() = PendingIntent.getService(
        this, 2,
        Intent(this, RecordingService::class.java).setAction(ACTION_TRIGGER_EVENT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ── Module 5: impact event handler ───────────────────────────────────────
    private fun handleImpactEvent(event: ImpactEvent) {
        Log.w(TAG, "Impact event: ${event.direction}  peakG=${event.peakG}g  road=${event.roadState}")
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                eventsLog?.append(event)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write events.log: ${e.message}")
            }
            currentSessionDir?.let { dir ->
                sessionManager.appendCustodyEvent(dir, event)
            }
            _collisionEvents.emit(event)
        }
    }
}
