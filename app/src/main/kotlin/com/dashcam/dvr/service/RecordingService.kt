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
import com.dashcam.dvr.DVRApplication.Companion.CHANNEL_ALERT
import com.dashcam.dvr.DVRApplication.Companion.CHANNEL_RECORDING
import com.dashcam.dvr.R
import com.dashcam.dvr.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * RecordingService — Foreground Service
 *
 * Blueprint §14: Process Survival
 * ────────────────────────────────
 * Runs as a Foreground Service (foregroundServiceType = camera|microphone|location).
 * This prevents the Android OS from killing the recording pipeline under memory pressure,
 * Doze mode, or when the screen is off.
 *
 * A PARTIAL_WAKE_LOCK is held for the entire recording session to keep the CPU active
 * when the display sleeps (Blueprint §14 — WakeLock & Screen-Off Recording).
 *
 * Modules wired in later iterations:
 *  - CameraManager       (Module 2)
 *  - TelemetryEngine     (Module 3)
 *  - SessionManager      (Module 4)
 *  - CollisionDetector   (Module 5)
 *  - EvidencePackager    (Module 6)
 *  - LoopRecorder        (Module 7)
 *
 * Lifecycle:
 *  START_RECORDING  → acquires WakeLock, starts sub-systems
 *  STOP_RECORDING   → stops sub-systems, releases WakeLock, stops foreground
 *  TRIGGER_EVENT    → instructs LoopRecorder to protect current segment
 */
class RecordingService : LifecycleService() {

    // ── Inner Binder for Activity binding ──────────────────────────────────
    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = RecordingBinder()

    // ── State ──────────────────────────────────────────────────────────────
    sealed class ServiceState {
        object Idle       : ServiceState()
        object Starting   : ServiceState()
        object Recording  : ServiceState()
        object Stopping   : ServiceState()
        data class Error(val message: String) : ServiceState()
    }

    private val _state = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val state: StateFlow<ServiceState> = _state

    // ── WakeLock ───────────────────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Coroutine Jobs ─────────────────────────────────────────────────────
    private var statusUpdateJob: Job? = null

    // ── Notification ID ────────────────────────────────────────────────────
    private val NOTIFICATION_ID = 1001

    // ── Service command actions ────────────────────────────────────────────
    companion object {
        const val ACTION_START_RECORDING  = "com.dashcam.dvr.START_RECORDING"
        const val ACTION_STOP_RECORDING   = "com.dashcam.dvr.STOP_RECORDING"
        const val ACTION_TRIGGER_EVENT    = "com.dashcam.dvr.TRIGGER_EVENT"
        const val ACTION_MUTE_AUDIO       = "com.dashcam.dvr.MUTE_AUDIO"
        private const val TAG             = "RecordingService"

        fun startRecording(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START_RECORDING)
            context.startForegroundService(intent)
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_STOP_RECORDING)
            context.startService(intent)
        }

        fun triggerManualEvent(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_TRIGGER_EVENT)
            context.startService(intent)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_RECORDING -> handleStartRecording()
            ACTION_STOP_RECORDING  -> handleStopRecording()
            ACTION_TRIGGER_EVENT   -> handleManualEventTrigger()
            else                   -> {
                // Service restarted by OS after kill — resume recording
                if (_state.value == ServiceState.Idle) {
                    Log.w(TAG, "Service restarted by OS — resuming recording")
                    handleStartRecording()
                }
            }
        }
        // START_STICKY: if the OS kills this service, restart it automatically
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        Log.w(TAG, "Service destroyed — releasing resources")
        releaseWakeLock()
        statusUpdateJob?.cancel()
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Command Handlers
    // ══════════════════════════════════════════════════════════════════════

    private fun handleStartRecording() {
        if (_state.value is ServiceState.Recording) {
            Log.d(TAG, "Already recording — ignoring start command")
            return
        }
        Log.i(TAG, "Starting recording session")
        _state.value = ServiceState.Starting

        // Promote to foreground immediately — must be called within 5 seconds of startForegroundService()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))

        lifecycleScope.launch {
            try {
                // ── Module wiring (added in subsequent modules) ────────────
                // sessionManager.startSession()
                // telemetryEngine.start()
                // cameraManager.startDualCapture()
                // loopRecorder.start()
                // collisionDetector.start()
                // ──────────────────────────────────────────────────────────

                _state.value = ServiceState.Recording
                updateNotification("● REC  |  00:00:00")
                startStatusUpdates()
                Log.i(TAG, "Recording started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording: ${e.message}", e)
                _state.value = ServiceState.Error(e.message ?: "Unknown error")
                showErrorNotification("Failed to start recording: ${e.message}")
            }
        }
    }

    private fun handleStopRecording() {
        if (_state.value !is ServiceState.Recording) {
            Log.d(TAG, "Not recording — ignoring stop command")
            return
        }
        Log.i(TAG, "Stopping recording session")
        _state.value = ServiceState.Stopping

        lifecycleScope.launch {
            try {
                statusUpdateJob?.cancel()

                // ── Module teardown (added in subsequent modules) ──────────
                // collisionDetector.stop()
                // loopRecorder.stop()
                // cameraManager.stopCapture()
                // telemetryEngine.stop()
                // sessionManager.closeSession()
                // ──────────────────────────────────────────────────────────

                _state.value = ServiceState.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "Recording stopped cleanly")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording: ${e.message}", e)
            }
        }
    }

    private fun handleManualEventTrigger() {
        Log.i(TAG, "Manual event trigger received")
        lifecycleScope.launch {
            // loopRecorder.protectCurrentSegment(EventType.MANUAL)
            updateNotification("● REC  |  ⚡ Event saved")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // WakeLock
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Acquires a PARTIAL_WAKE_LOCK.
     * This keeps the CPU running when the screen turns off, ensuring
     * the camera pipeline and telemetry engine continue uninterrupted.
     * (Blueprint §14 — WakeLock & Screen-Off Recording)
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DVR:RecordingWakeLock"
        ).also {
            it.setReferenceCounted(false)
            it.acquire(/* max 12h timeout */ 12 * 60 * 60 * 1000L)
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // Notification helpers
    // ══════════════════════════════════════════════════════════════════════

    private var sessionSeconds = 0L

    private fun startStatusUpdates() {
        sessionSeconds = 0L
        statusUpdateJob = lifecycleScope.launch {
            while (true) {
                delay(1_000)
                sessionSeconds++
                val h = sessionSeconds / 3600
                val m = (sessionSeconds % 3600) / 60
                val s = sessionSeconds % 60
                updateNotification("● REC  |  %02d:%02d:%02d".format(h, m, s))
            }
        }
    }

    private fun buildNotification(statusText: String) =
        NotificationCompat.Builder(this, CHANNEL_RECORDING)
            .setContentTitle("DVR Active")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_rec)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openMainActivityIntent())
            .addAction(
                R.drawable.ic_stop,
                "Stop",
                stopPendingIntent()
            )
            .addAction(
                R.drawable.ic_event,
                "Save Event",
                triggerEventPendingIntent()
            )
            .build()

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun showErrorNotification(message: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(
            NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setContentTitle("DVR Error")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    // ── PendingIntents ─────────────────────────────────────────────────────

    private fun openMainActivityIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
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
