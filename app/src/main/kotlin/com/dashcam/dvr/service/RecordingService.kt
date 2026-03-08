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
import com.dashcam.dvr.ui.MainActivity
import com.dashcam.dvr.util.AppConstants
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecordingService — Foreground Service
 *
 * Blueprint §14: Process Survival
 * ────────────────────────────────
 * Module 1:  Foreground Service skeleton — WakeLock, notifications, lifecycle
 * Module 3:  TelemetryEngine wired in — GPS + IMU + NTP started/stopped in sync
 * Module 4:  SessionManager will replace createSessionDirStub()
 * Module 5:  CollisionDetector fan-out hooks pre-plumbed via TelemetryEngine.start()
 *
 * NOTE — foregroundServiceType = "location" only (not camera/microphone).
 * Adding camera/microphone types triggers HyperOS full-screen privacy overlay
 * which steals window focus and minimises the app. Camera sessions are owned by
 * DVRCameraManager in MainActivity (CameraX lifecycle). Location is legitimate
 * for continuous GPS telemetry. See _manifest_service_patch.xml for context.
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


    // ── Module 3 ───────────────────────────────────────────────────────────
    private lateinit var telemetryEngine: TelemetryEngine

    // ── WakeLock ───────────────────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Jobs ───────────────────────────────────────────────────────────────
    private var timerJob:        Job? = null
    private var statusUpdateJob: Job? = null

    // ── Session dir (stub until Module 4 — SessionManager) ────────────────
    private var currentSessionDir: File? = null

    // ── Companion ──────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "RecordingService"

        const val ACTION_START_RECORDING = "com.dashcam.dvr.START_RECORDING"
        const val ACTION_STOP_RECORDING  = "com.dashcam.dvr.STOP_RECORDING"
        const val ACTION_TRIGGER_EVENT   = "com.dashcam.dvr.TRIGGER_EVENT"

        private const val NOTIFICATION_ID  = 1001
        private const val STATUS_UPDATE_MS = 2_000L
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        telemetryEngine = TelemetryEngine(applicationContext)
        Log.i(TAG, "RecordingService created")
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_RECORDING -> handleStartRecording()
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
        Log.i(TAG, "RecordingService destroyed")
    }

    // ── Instance methods called by MainActivity (via bound service) ────────

    /** Called by MainActivity when user taps Record. */
    fun startRecording() {
        startService(Intent(this, RecordingService::class.java)
            .setAction(ACTION_START_RECORDING))
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
        updateNotification("DVR Ready")
    }

    // ── Internal start/stop handlers ───────────────────────────────────────

    private fun handleStartRecording() {
        if (_state.value is ServiceState.Recording) {
            Log.w(TAG, "Already recording — ignoring start"); return
        }
        _state.value = ServiceState.Starting
        startForegroundNotification()
        acquireWakeLock()

        lifecycleScope.launch {
            try {
                val sessionDir = createSessionDirStub()
                currentSessionDir = sessionDir

                // ── Module 3: start telemetry ──────────────────────────────
                // onAccelFanOut / onGyroFanOut → CollisionDetector (Module 5)
                telemetryEngine.start(
                    sessionDir    = sessionDir,
                    onAccelFanOut = null,
                    onGyroFanOut  = null
                )

                // ── Module 2 placeholder ───────────────────────────────────
                // cameraManager.start(sessionDir)  ← wired in Module 2

                _state.value = ServiceState.Recording
                startTimerLoop()
                startStatusUpdateLoop()
                Log.i(TAG, "Recording started — session: ${sessionDir.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording: ${e.message}")
                _state.value = ServiceState.Error(e.message ?: "Unknown error")
                telemetryEngine.stop()
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

        lifecycleScope.launch {
            // ── Module 2 placeholder ───────────────────────────────────────
            // cameraManager.stop()

            // ── Module 3: flush and close telemetry.log ────────────────────
            telemetryEngine.stop()

            // ── Module 6 placeholder ───────────────────────────────────────
            // evidencePackager.seal(currentSessionDir)

            if (telemetryEngine.ntpSyncStatus != "SYNCED")
                Log.w(TAG, "Session CLOCK_UNVERIFIED — ${telemetryEngine.ntpSyncStatus}")

            Log.i(TAG, "Session ended — " +
                "first_fix=${telemetryEngine.firstValidFixTs ?: "NONE"}  " +
                "ntp=${telemetryEngine.ntpSyncStatus}  " +
                "offset=${telemetryEngine.ntpOffsetMs}ms"
            )

            _elapsedSeconds.value = 0L
            releaseWakeLock()
            _state.value = ServiceState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleManualEvent() {
        Log.i(TAG, "Manual event triggered — segment protection queued (Module 7)")
        // Module 7 (LoopRecorder) will protect current segment here
    }

    // ── Session dir stub (replaced by SessionManager — Module 4) ──────────

    private fun createSessionDirStub(): File {
        val ts         = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseDir    = getExternalFilesDir("sessions") ?: filesDir.resolve("sessions")
        val sessionDir = File(baseDir, "session_$ts")
        sessionDir.mkdirs()
        Log.i(TAG, "Session dir created: ${sessionDir.absolutePath}")
        return sessionDir
    }

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

    private fun startStatusUpdateLoop() {
        statusUpdateJob = lifecycleScope.launch {
            while (true) {
                delay(STATUS_UPDATE_MS)
                val gps = if (telemetryEngine.hasValidGpsFix) "GPS ✓" else "GPS acquiring…"
                val ntp = if (telemetryEngine.ntpSyncStatus == "SYNCED") "NTP ✓" else "NTP ✗"
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
}
